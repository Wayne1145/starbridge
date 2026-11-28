import unittest

from trial import ROLES
from continuation import BASELINE
from replay import evidence_folder, replay, replay_folder


class HostedArtifactsTest(unittest.TestCase):
    def test_original_two_crafts_and_disjoint_players_from_closed_world(self):
        report = replay(BASELINE)
        self.assertTrue(report["completed"] and report["serverSaved"])
        counts = []
        for name in ROLES:
            saved = report["players"][name]
            self.assertEqual(
                (saved["startedCursor"], saved["cursor"], saved["actionsInThisSession"]), (0, 10, 10)
            )
            self.assertEqual(
                (
                    saved["craftedEvents"]["wooden_pickaxe"],
                    saved["savedCraftedStats"]["minecraft:wooden_pickaxe"],
                ),
                (1, 1),
            )
            self.assertEqual(saved["health"], 20)
            counts.append(saved["inventory"]["minecraft:cobblestone"])
        self.assertEqual(counts, [19, 14])

    def test_inventory_and_checkpoint_cannot_replace_missing_per_player_craft(self):
        with evidence_folder(BASELINE) as root:
            log = root / "server-survival-events.log"
            log.write_text(
                log.read_text().replace(
                    "OrbitEngineer crafted 1 wooden_pickaxe", "OrbitCaptain crafted 1 wooden_pickaxe"
                )
            )
            with self.assertRaisesRegex(ValueError, "Missing genuine craft event.*OrbitEngineer"):
                replay_folder(root, None)

    def test_result_cannot_replace_client_action_or_exit(self):
        with evidence_folder(BASELINE) as root:
            log = root / "OrbitCaptain-survival-events.log"
            original = log.read_text()
            log.write_text("\n".join(row for row in original.splitlines() if " complete 7 " not in row))
            with self.assertRaisesRegex(ValueError, "action log"):
                replay_folder(root, None)
            log.write_text(original)
            last = root / "OrbitEngineer-latest.log"
            last.write_bytes(last.read_bytes().replace(b"Stopping!", b"cut short"))
            with self.assertRaisesRegex(ValueError, "client exit for OrbitEngineer"):
                replay_folder(root, None)
