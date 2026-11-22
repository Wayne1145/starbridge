"""Offline regression checks. Win32 desktop/process entry points may never be called in these tests."""

import ast
from contextlib import contextmanager
import ctypes
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import local_client_policy as policy

RUNNERS = {
    "bridge-controls/run_windows.py": ("run_game", (None, None)),
    "concurrent-orbits/soak_host.py": ("supervise", (None, None)),
    "connected-fleet/run_wide.py": ("run_case", (None, None, None)),
    "habitat-ui/run_flight_setup.py": ("main", ()),
    "habitat-ui/run_propellant.py": ("main", ()),
    "integrated-fleet/run.py": ("run_game", (None, None)),
    "native-fuel/run_windows.py": ("supervise", (None, None, None)),
    "original-flight/continue_surface.py": ("play", (None, None, None, None, None)),
    "survival-expedition/run_windows.py": ("run", (None, None, None, None)),
    "wide-fleet/flight.py": ("run", (None, None, None, None)),
}


def mark(root):
    marker = root / policy.MARKER
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.touch()
    return marker


def linked_checkout(base):
    main, checkout = base / "main repository", base / "linked checkout"
    metadata = main / ".git/worktrees/fixture"
    metadata.mkdir(parents=True)
    (metadata / "commondir").write_text("../..\n", "utf8")
    checkout.mkdir()
    (checkout / ".git").write_text(
        "gitdir: " + os.path.relpath(metadata, checkout) + "\n", "utf8"
    )
    return main, checkout


@contextmanager
def runner(name):
    f = ROOT / "qa" / name
    with patch.object(sys, "path", [str(f.parent), *sys.path]), patch.dict(sys.modules):
        for other in ("run_windows", "soak_host", "controls_host"):
            sys.modules.pop(other, None)
        if os.name != "nt":
            sys.modules["msvcrt"] = types.ModuleType("msvcrt")
            sys.modules["_winapi"] = types.ModuleType("_winapi")
        spec = importlib.util.spec_from_file_location("observed_runner", f)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        yield module


class WorkstationPolicyTest(unittest.TestCase):
    def test_linked_worktrees_and_nested_snapshots_inherit_the_main_marker(self):
        with tempfile.TemporaryDirectory() as temporary:
            main, worktree = linked_checkout(Path(temporary))
            marker = mark(main)
            for root in (
                main,
                main / "build/snapshot",
                worktree,
                worktree / "build/snapshot",
            ):
                with (
                    self.subTest(root=root),
                    patch.object(policy, "_desktop_api") as user32,
                ):
                    self.assertEqual(policy.blocking_marker(root), marker)
                    with self.assertRaisesRegex(RuntimeError, policy.DISABLED):
                        policy.create_private_desktop(root, "not-created")
                    user32.assert_not_called()
            self.assertIsNone(policy.blocking_marker(Path(temporary) / "independent"))

    def test_allowed_desktop_uses_and_closes_only_the_mocked_handle(self):
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch.object(policy, "_desktop_api") as backend,
        ):
            api = backend.return_value
            api.CreateDesktopW.return_value = 17
            desktop = policy.create_private_desktop(Path(temporary), "fake-name")
            desktop.close()
            desktop.close()
            api.CreateDesktopW.assert_called_once_with(
                "fake-name", None, None, 0, 0x01FF, None
            )
            api.CloseDesktop.assert_called_once_with(17)

    def test_every_graphical_runner_stops_before_native_queries_files_and_processes(
        self,
    ):
        with tempfile.TemporaryDirectory() as temporary:
            main, worktree = linked_checkout(Path(temporary))
            mark(main)
            for name, (entry, arguments) in RUNNERS.items():
                with (
                    self.subTest(name=name),
                    patch.object(ctypes, "WinDLL", create=True) as dll,
                    patch.object(ctypes, "windll", create=True) as windll,
                    patch("subprocess.Popen") as process,
                    patch.object(policy, "_desktop_api") as desktop,
                    runner(name) as module,
                    patch.object(module, "ROOT", worktree),
                ):
                    with self.assertRaisesRegex(RuntimeError, policy.DISABLED):
                        getattr(module, entry)(*arguments)
                    if name == "concurrent-orbits/soak_host.py":
                        with self.assertRaisesRegex(RuntimeError, policy.DISABLED):
                            module.Child(
                                None, worktree / "server", "old-waiting-job", []
                            )
                    self.assertFalse(
                        dll.mock_calls
                        or windll.mock_calls
                        or process.mock_calls
                        or desktop.mock_calls
                    )

    def test_one_native_desktop_creation_site_for_all_tracked_launchers(self):
        files = sorted((ROOT / "qa").glob("*/*.py")) + sorted(
            (ROOT / "tools").glob("*.py")
        )
        actual = []
        for file in files:
            if "CreateDesktop" not in file.read_text("utf8"):
                continue
            tree = ast.parse(file.read_text("utf8"))
            calls = [
                n
                for n in ast.walk(tree)
                if isinstance(n, ast.Call)
                and isinstance(n.func, ast.Attribute)
                and n.func.attr
                in (
                    "CreateDesktopW",
                    "CreateDesktopA",
                    "CreateDesktopExW",
                    "CreateDesktopExA",
                )
            ]
            actual.extend([file.relative_to(ROOT).as_posix()] * len(calls))
        self.assertEqual(actual, ["tools/local_client_policy.py"])

    def test_recipe_and_power_diagnostics_prepare_and_spawn_only_a_dedicated_server(
        self,
    ):
        for power in (False, True):
            with (
                self.subTest(power=power),
                tempfile.TemporaryDirectory() as tmp,
                runner("survival-expedition/run_windows.py") as module,
            ):
                root = Path(tmp)
                mark(root)
                server = root / "template/server"
                target = root / "run-survival-expedition-power-test"
                (root / "pack/config").mkdir(parents=True)
                (root / "pack/config/ExtraPlanets.cfg").write_text("")
                for d in ("mods", "retrofit/mods", "retrofit/.retrofit"):
                    (server / d).mkdir(parents=True)
                (server / "launch.args").write_text(
                    "cpw.mods.bootstraplauncher.BootstrapLauncher\n--launchTarget forgeserver\nnogui\n"
                )
                fixture = (
                    root
                    / "build/qa/survival-expedition/starbridge-survival-expedition-1.0.0.jar"
                )
                fixture.parent.mkdir(parents=True)
                fixture.write_bytes(b"inert QA placeholder")
                outname = (
                    "generator-power-probe.json" if power else "blast-brick-probe.json"
                )
                handle = Mock()
                handle.poll.return_value = None

                def started(argv, **kwargs):
                    self.assertEqual(argv, ["not-a-java-process", "@launch.args"])
                    self.assertEqual(kwargs["cwd"], target / "server")
                    self.assertEqual(
                        kwargs["creationflags"],
                        getattr(subprocess, "CREATE_NO_WINDOW", 0),
                    )
                    (target / "server" / outname).write_text("{}")
                    return handle

                with (
                    patch.object(module, "ROOT", root),
                    patch.object(module, "java17", return_value="not-a-java-process"),
                    patch("socket.socket"),
                    patch.dict(os.environ),
                    patch.object(ctypes, "WinDLL", create=True) as dll,
                    patch.object(policy, "_desktop_api") as desktop,
                    patch("subprocess.Popen", side_effect=started) as spawned,
                ):
                    module.run(
                        target,
                        root / "template",
                        0,
                        1,
                        recipe_only=not power,
                        power_only=power,
                    )
                    self.assertFalse(dll.mock_calls or desktop.mock_calls)
                    spawned.assert_called_once()
                    handle.stdin.write.assert_called_once_with(b"stop\n")
                (episode,) = (target / "recordings").iterdir()
                self.assertEqual(
                    set(json.loads((episode / "installed.json").read_bytes())),
                    {"server"},
                )
                self.assertEqual(
                    json.loads((episode / "end.json").read_bytes())["reason"],
                    "generator energy observed" if power else "recipe audit",
                )
                self.assertTrue((episode / "server" / outname).is_file())
                self.assertFalse((target / "OrbitCaptain").exists())
                self.assertFalse((target / "OrbitEngineer").exists())

    def test_dedicated_only_rejects_client_second_target_and_nested_argfiles(self):
        with (
            tempfile.TemporaryDirectory() as tmp,
            runner("survival-expedition/run_windows.py") as module,
        ):
            file = Path(tmp) / "launch.args"
            for args in (
                "--launchTarget forgeclient nogui",
                "--launchTarget forgeserver",
                "--launchTarget forgeserver --launchTarget=forgeclient nogui",
                "--launchTarget forgeserver nogui @more.args",
            ):
                with self.subTest(args=args):
                    file.write_text(args)
                    self.assertRaisesRegex(
                        ValueError,
                        "dedicated Forge server",
                        module.verify_server_args,
                        file,
                    )


if __name__ == "__main__":
    unittest.main()
