"""Use the actual stopped world to check continuation after the bucket and canopy failures."""

import io
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

import trial
from continuation import ARCHIVES, CAMP_RUN, restore_world
from replay import replay
from yard import REGION, inspect_yard


class YardContinuation(unittest.TestCase):
    def test_recovered_orders_preserve_every_completed_action_in_both_real_client_logs(self):
        archive, _ = ARCHIVES[CAMP_RUN]
        report = replay(archive)
        self.assertFalse(report["completed"])
        self.assertEqual([p["cursor"] for p in report["players"].values()], [35, 14])
        with ZipFile(archive) as outer, ZipFile(io.BytesIO(outer.read("cloud-world.zip"))) as world:
            for role, goal in [("OrbitCaptain", 35), ("OrbitEngineer", 14)]:
                previous = [
                    s.strip()
                    for s in world.read(role + "/expedition-orders.txt").decode().splitlines()
                    if s.strip() and not s.lstrip().startswith("#")
                ]
                repaired = [
                    s.strip()
                    for s in trial.orders_for("yard")[role]
                    if s.strip() and not s.lstrip().startswith("#")
                ]
                self.assertEqual(repaired[:goal], previous[:goal])
                self.assertNotEqual(repaired[goal], previous[goal])

            class Region:
                def read_bytes(self):
                    return world.read(REGION)

            self.assertFalse(any(cell["expected"] == cell["found"] for cell in inspect_yard(Region(), False)))
            with self.assertRaisesRegex(ValueError, "Original launch pad or seat"):
                inspect_yard(Region(), True)

    def test_edited_passed_action_is_refused_before_any_world_members_are_copied(self):
        archive, _ = ARCHIVES[CAMP_RUN]
        with ZipFile(archive) as outer, tempfile.TemporaryDirectory() as tmp:
            started = json.loads(outer.read("pair-result.json"))
            orders = trial.orders_for("yard")
            orders["OrbitEngineer"][0] = "different executed order"
            with self.assertRaisesRegex(ValueError, "executed orders"):
                restore_world(outer.read("cloud-world.zip"), Path(tmp), started["checkpoint"], orders)
            self.assertFalse((Path(tmp) / "server").exists())
