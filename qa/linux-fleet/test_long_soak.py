import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import long_soak as soak
import runtime


def minutes():
    elapsed = 0
    for count, seconds in soak.STAGES.items():
        expected = list(soak.NAMES)[:count]
        for index in range(seconds // 60 + (count != 10)):
            ramp = index == 0 and count != 10
            elapsed += 60
            members = expected[:-1] if ramp else expected
            yield {
                "pid": 73,
                "target": count,
                "online": len(members),
                "observedMoving": len(members),
                "minimumOnline": len(members),
                "maximumOnline": len(members),
                "members": members,
                "movingMembers": members,
                "windowWallSeconds": 60.0,
                "windowTicks": 1200,
                "serverTick": 20 * elapsed,
                "quorumWallSeconds": elapsed,
                "ships": [
                    {
                        "active": True,
                        "approach": False,
                        "source": "home",
                        "target": "moon",
                        "distance": 6e7 - elapsed,
                    }
                    for _ in range(3)
                ],
            }


class LongFleetTest(unittest.TestCase):
    def test_only_full_identity_minutes_count_over_two_six_and_forty_hours(self):
        self.assertEqual(soak.verify_stages(minutes(), 73), soak.STAGES)
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            soak.verify_stages(list(minutes())[:-1], 73)

    def test_rejects_missing_tick_changed_pid_stale_cabin_and_replaced_member(self):
        first, second, *_ = list(minutes())[:2]
        for override, message in [
            ({"pid": 74}, "process"),
            ({"serverTick": 2399}, "tick interval"),
            ({"quorumWallSeconds": 130}, "monotonic"),
            ({"ships": first["ships"]}, "no progress"),
            ({"members": first["members"][:-1] + ["FleetQA27"]}, "membership"),
        ]:
            with self.subTest(override=override), self.assertRaisesRegex(ValueError, message):
                soak.verify_stages([first, dict(second, **override)], 73)
        lost = copy.deepcopy(second)
        lost["movingMembers"].pop()
        lost["observedMoving"] -= 1
        with self.assertRaises(ValueError):
            soak.verify_stages([first, lost], 73)

    def test_workstation_gate_precedes_game_files_subprocesses_and_downloads(self):
        for platform, release, host, env in [
            ("win32", "Windows", "lab", {}),
            ("linux", "6.1-microsoft-standard-WSL2", "lab", {}),
            ("linux", "6.2", "wrong", {}),
            ("linux", "6.2", "lab", {"DISPLAY": ":0"}),
            ("linux", "6.2", "lab", {"RUNNER_ENVIRONMENT": "github-hosted"}),
        ]:
            with (
                self.subTest(platform=platform, env=env),
                tempfile.TemporaryDirectory() as tmp,
                patch.object(runtime.sys, "platform", platform),
                patch("runtime.platform.release", return_value=release),
                patch("runtime.socket.gethostname", return_value="lab"),
                patch.dict("os.environ", env, clear=True),
                patch("subprocess.Popen") as spawn,
                patch("long_soak.fetch_package") as fetch,
            ):
                root = Path(tmp) / "never-created"
                with self.assertRaisesRegex(RuntimeError, "dedicated Linux"):
                    soak.run_long(root, host, Path(tmp) / "fixture", "")
                spawn.assert_not_called()
                fetch.assert_not_called()
                self.assertFalse(root.exists())

    def test_native_linux_and_marker_on_parent_worktree(self):
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.object(runtime.sys, "platform", "linux"),
            patch("runtime.platform.release", return_value="6.2"),
            patch("runtime.socket.gethostname", return_value="lab"),
            patch.dict("os.environ", {}, clear=True),
            patch("subprocess.Popen") as spawn,
        ):
            root = Path(temp)
            project = root / "source"
            checkout = root / "nested"
            meta = project / ".git/worktrees/nested"
            meta.mkdir(parents=True)
            checkout.mkdir()
            (checkout / ".git").write_text("gitdir: " + str(meta))
            (meta / "commondir").write_text("../..")
            with patch.object(runtime, "ROOT", checkout):
                runtime.require_runtime_host("lab")
                marker = project / ".tools/no-local-game-clients"
                marker.parent.mkdir()
                marker.touch()
                with self.assertRaisesRegex(RuntimeError, "disabled on this workstation"):
                    runtime.require_runtime_host("lab")
            spawn.assert_not_called()

    def test_stop_marks_interruption_and_never_starts_a_peer(self):
        with (
            tempfile.TemporaryDirectory() as temp,
            patch("long_soak.require_runtime_host"),
            patch("long_soak.start_child") as start,
            patch("long_soak.stop_server") as stop,
            patch("long_soak.stop_own_process"),
        ):
            root = Path(temp)
            (root / "server").mkdir()
            (root / "Peers").mkdir()
            (root / "STOP").touch()
            with self.assertRaisesRegex(ValueError, "Stop requested"):
                soak.supervise(root, "lab")
            start.assert_not_called()
            stop.assert_called_once_with(None)
            state = soak.read(root / "supervisor.json")
            self.assertEqual(state["state"], "interrupted")
            self.assertNotIn("Peers", state["processes"])


if __name__ == "__main__":
    unittest.main()
