"""Offline checks for the evidence and launch policy. No test starts a game or contacts GitHub."""
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import package
import runtime
import smoke


class ClickEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        records = json.loads((runtime.ROOT / 'docs/flight-setup-verification.json').read_bytes())['scenarios']
        cls.slate = records['slate-a']
        cls.title = records['runtime-a']
        cls.main_components = records['main-a']['observed']

    def test_recorded_survival_visit_and_return(self):
        smoke.validate('slate-only', self.slate['observed'], '\n'.join(self.slate['actions']))

    def test_each_install_entry_and_its_return_page_are_required(self):
        actions = self.slate['actions']
        for index, action in enumerate(actions):
            if action in ('Clicked gui.starbridge.install.title', 'Clicked gui.done', 'FlightInstallScreen'):
                with self.subTest(index=index), self.assertRaisesRegex(ValueError, 'missing or out of order'):
                    smoke.validate('slate-only', self.slate['observed'], '\n'.join(actions[:index] + actions[index + 1:]))

    def test_survival_requires_a_boolean_cheat_setting_and_held_crafted_item(self):
        for override in ({'commands': True}, {'commands': 0}, {'heldCraftedSlate': False}, {'gameType': 'creative'}):
            with self.subTest(override=override), self.assertRaises(ValueError):
                smoke.validate('slate-only', {**self.slate['observed'], **override}, '\n'.join(self.slate['actions']))

    def test_runtime_detour_and_component_states_are_both_checked(self):
        actions = self.title['actions']
        # These two historical recordings cover different installations. This combination is test data only.
        synthetic = {**self.main_components, 'detailOpened': 'galacticraft', 'followedRuntimeAndReturned': True}
        smoke.validate('main-only', synthetic, '\n'.join(actions))
        with self.assertRaisesRegex(ValueError, 'missing or out of order'):
            smoke.validate('main-only', synthetic, '\n'.join(actions[:2] + actions[2:4][::-1] + actions[4:]))
        with self.assertRaisesRegex(ValueError, 'required'):
            smoke.validate('main-only', {**synthetic, 'required': self.title['observed']['required']}, '\n'.join(actions))

    def test_recorded_screenshot_and_truncated_frame(self):
        reference = self.slate['images'][0]
        path = runtime.ROOT / reference['file']
        self.assertEqual(reference['sha256'], smoke.png(path)['sha256'])
        with tempfile.TemporaryDirectory() as temp:
            partial = Path(temp) / 'truncated.png'
            partial.write_bytes(path.read_bytes()[:-24])
            with self.assertRaisesRegex(ValueError, 'Incomplete PNG'):
                smoke.png(partial)


def zipped(entries):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return data.getvalue()


class PublishedPackageTest(unittest.TestCase):
    def test_wrong_checksum_or_embedded_manifest_cannot_install(self):
        lock = (runtime.ROOT / 'pack/original-spaceflight.lock.json').read_bytes()
        member = 'mods/' + package.MAIN
        # A correct inner JAR hash alone cannot establish which flight manifest it contains.
        for embedded, override, error in [(lock, '0' * 64, 'checksum mismatch'), (b'{}', None, 'published manifest'),
                                         (lock, None, None)]:
            with self.subTest(error=error), tempfile.TemporaryDirectory() as temp:
                jar = zipped({'META-INF/starbridge/original-spaceflight.lock.json': embedded})
                checksum = override or hashlib.sha256(jar).hexdigest()
                bundle = zipped({'original-spaceflight.lock.json': lock, 'SHA256SUMS.txt': f'{checksum}  {member}\n', member: jar})
                output = Path(temp) / package.MAIN
                if error:
                    with self.assertRaisesRegex(ValueError, error):
                        package.extract(bundle, output)
                    self.assertFalse(output.exists())
                else:
                    self.assertEqual(hashlib.sha256(bundle).hexdigest(), package.extract(bundle, output))
                    self.assertEqual(jar, output.read_bytes())

    def test_build_must_match_revision_workflow_and_pr_base(self):
        repo, head, base = 'owner/flight', 'a' * 40, 'b' * 40
        run = {'id': 14, 'head_sha': head, 'head_repository': {'full_name': repo}, 'path': '.github/workflows/ci.yml',
               'event': 'pull_request', 'conclusion': 'success', 'pull_requests': [{'number': 2, 'base': {'sha': base}}]}
        for change in ({'head_sha': 'c' * 40}, {'path': '.github/workflows/remote-gui.yml'}, {'head_repository': None},
                       {'head_repository': {'full_name': 'other/flight'}}, {'conclusion': 'failure'}, {'event': 'push'},
                       {'pull_requests': [{'number': 3, 'base': {'sha': base}}]},
                       {'pull_requests': [{'number': 2, 'base': {'sha': 'd' * 40}}]}, {}):
            with self.subTest(change=change), patch.object(package, 'api', return_value=run | change) as api:
                args = (repo, head, '14', repo, 'pull_request', '2', base)
                if change:
                    with self.assertRaisesRegex(ValueError, 'successful Build and test'):
                        package.select_run(*args)
                else:
                    self.assertEqual(14, package.select_run(*args)['id'])
                api.assert_called_once_with(repo, 'actions/runs/14')


class RunnerTest(unittest.TestCase):
    def test_only_linux_natives_and_forge_override_are_on_classpath(self):
        def lib(name, file, rules=()):
            return {'name': name, 'rules': rules, 'downloads': {'artifact': {'path': file}}}
        native = 'org.lwjgl:lwjgl-glfw:3.3.1:natives-'
        vanilla = {'libraries': [lib('org.ow2.asm:asm:9.4', 'asm-old.jar'), lib('org.lwjgl:lwjgl-glfw:3.3.1', 'glfw.jar'),
                                 lib(native + 'linux', 'linux.jar', [{'action': 'allow', 'os': {'name': 'linux'}}]),
                                 lib(native + 'windows', 'windows.jar', [{'action': 'allow', 'os': {'name': 'windows'}}])]}
        forge = {'libraries': [lib('org.ow2.asm:asm:9.9.1', 'asm-new.jar')]}
        self.assertCountEqual(['asm-new.jar', 'glfw.jar', 'linux.jar'], [a['path'] for a in runtime.libraries(vanilla, forge)])
        self.assertFalse(runtime.linux_rules([{'action': 'allow', 'os': {'arch': 'x86'}}], {}))
        rule = [{'action': 'allow', 'features': {'has_custom_resolution': True}}]
        self.assertFalse(runtime.linux_rules(rule, {}))
        self.assertTrue(runtime.linux_rules(rule, {'has_custom_resolution': True}))

    def test_starts_are_refused_on_workstations_self_hosted_and_with_local_marker(self):
        hosted = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux'}
        cases = [('win32', hosted, False), ('linux', {}, False), ('linux', hosted | {'RUNNER_ENVIRONMENT': 'self-hosted'}, False),
                 ('linux', hosted | {'RUNNER_OS': 'Windows'}, False), ('linux', hosted, True)]
        for platform, env, marked in cases:
            with self.subTest(platform=platform, env=env, marked=marked), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                if marked:
                    (root / '.tools').mkdir()
                    (root / '.tools/no-local-game-clients').touch()
                with patch.object(runtime, 'ROOT', root), patch.object(runtime.sys, 'platform', platform), \
                        patch.dict(os.environ, env, clear=True), patch('subprocess.Popen') as start:
                    with self.assertRaisesRegex(RuntimeError, 'GitHub-hosted Linux'):
                        runtime.prepare(root / 'launcher')
                    with self.assertRaisesRegex(RuntimeError, 'GitHub-hosted Linux'):
                        smoke.run_scenario(root / 'game', root / 'evidence', root / 'fixture.jar', 'slate-only')
                    start.assert_not_called()
                self.assertFalse((root / 'launcher').exists())
                self.assertFalse((root / 'game').exists())


if __name__ == '__main__':
    unittest.main()
