import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import garden
import smoke


class GardenTest(unittest.TestCase):
    def test_checkpoint_and_growth_witness(self):
        start = {
            "inputs": garden.original_inputs(),
            "initialInventoryEmpty": True,
            "upperSpacesEmpty": True,
            "plantedTick": 150,
        }
        growth = {
            "east": True,
            "west": False,
            "plantedTick": 150,
            "grewAtTick": 3108,
            "randomTickSpeed": 3,
        }
        server = {
            "eastCut": True,
            "westCut": True,
            "craftedPaper": 3,
            "craftedTerminal": 1,
            "stored": 1,
        }
        self.assertEqual(
            garden.verify_crop(start, growth, server)["grewAfterWorldTicks"], 2958
        )
        for changes in (
            {"grewAtTick": 150},
            {"east": False},
            {"randomTickSpeed": 300},
            {"plantedTick": 0},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                garden.verify_crop(start, growth | changes, server)
        self.assertRaises(
            ValueError, garden.verify_crop, start, growth, server | {"stored": 2}
        )

    def test_no_extra_fields_can_be_exported_from_a_local_save(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / "checkpoint.json"
            for extra in (
                {"Player": {"name": "unused"}},
                {"rawRegion": "not permitted"},
                {"eastAge": 15},
            ):
                file.write_text(json.dumps(garden.INPUTS | extra))
                with (
                    self.subTest(extra=extra),
                    patch.object(garden, "CHECKPOINT", file),
                    self.assertRaises(ValueError),
                ):
                    garden.original_inputs()

    def test_workbench_and_both_views_are_required_in_the_action_log(self):
        result = {
            "pass": True,
            "collectedCane": 3,
            "views": ["plan", "elevation", "plan"],
            "mode": "survival",
            "useKey": "U",
        }
        steps = smoke.JOURNEY["garden"]
        smoke.validate("garden", result, "\n".join(steps))
        for ix in range(len(steps)):
            with (
                self.subTest(ix=ix),
                self.assertRaisesRegex(ValueError, "missing or out of order"),
            ):
                smoke.validate(
                    "garden", result, "\n".join(steps[:ix] + steps[ix + 1 :])
                )
        self.assertRaises(
            ValueError,
            smoke.validate,
            "garden",
            result | {"collectedCane": 2},
            "\n".join(steps),
        )
