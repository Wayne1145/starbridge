import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile
import trial


class PairCheckpointTests(unittest.TestCase):
    def test_public_checkpoint_contains_generated_world_and_two_orders_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            world = root / 'server/world'
            world.mkdir(parents=True)
            (world / 'level.dat').write_bytes(b'closed-world')
            (world / 'session.lock').write_bytes(b'lock')
            (root / 'server/private-config.txt').write_text('not a game save')
            for name in trial.ROLES:
                (root / name).mkdir()
                (root / name / 'expedition-orders.txt').write_text('test order')
                (root / name / 'unrelated.txt').write_text('unrelated')
            output = root / 'evidence'
            output.mkdir()
            receipt = trial.checkpoint(root, output)
            with ZipFile(output / receipt['archive']) as z:
                self.assertEqual(set(z.namelist()), {'world/level.dat', *(n + '/expedition-orders.txt' for n in trial.ROLES)})

    def test_local_machine_is_rejected_before_installation_or_process_creation(self):
        with patch.object(trial, 'configure') as install, patch.object(trial, 'launch') as launch:
            with patch.dict('os.environ', {'RUNNER_ENVIRONMENT': 'self-hosted'}):
                with self.assertRaises(RuntimeError): trial.run(Path('.'), Path('.'), Path('unused'), Path('unused'))
            install.assert_not_called()
            launch.assert_not_called()


class RestoreTests(unittest.TestCase):
    def test_closed_profiles_and_both_cursors_continue_at_the_new_roles_orders(self):
        from continuation import restore_world
        import io
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old_orders = trial.orders_for('workbench')
            updated_orders = trial.orders_for('materials')
            stream = io.BytesIO()
            with ZipFile(stream, 'w') as archive:
                archive.writestr('world/level.dat', b'closed')
                for role, uid in trial.ROLES.items():
                    archive.writestr('world/playerdata/' + uid + '.dat', role)
                    archive.writestr(role + '/expedition-orders.txt', '\n'.join(old_orders[role]))
                    archive.writestr(role + '/expedition-cursor.txt', '7')
            raw = stream.getvalue()
            expected = {'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
            with self.assertRaisesRegex(ValueError, 'prefix'):
                restore_world(raw, root, expected, {name:['different'] for name in trial.ROLES})
            self.assertFalse((root / 'server').exists())
            restore_world(raw, root, expected, updated_orders)
            for role in trial.ROLES:
                self.assertEqual((root/role/'expedition-cursor.txt').read_text(), '7')
                self.assertEqual((root/'server/world/playerdata'/(trial.ROLES[role] + '.dat')).read_text(), role)
            with self.assertRaisesRegex(ValueError, 'already contains'):
                restore_world(raw, root, expected, updated_orders)
