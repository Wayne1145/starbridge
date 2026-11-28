"""The cloud evidence must show a carried schematic chosen from the calibration screen."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from smoke import validate


class TestNavigationEvidence(unittest.TestCase):
    result = {'pass': True, 'keptSchematic': True, 'returnedToSameChart': True,
              'clicked': ['Mars', 'calibration', 'absent rocket', 'carried tier 2 schematic', 'back']}
    inputs = ('Clicked route.starbridge.mars', 'Clicked nav.starbridge.proof.title',
              'Clicked navigation rocket (absent)', 'Clicked navigation schematic (apply)',
              'Clicked nav.starbridge.proof.back')

    def test_receipt_requires_the_original_item_and_same_chart(self):
        validate('calibration', self.result, '\n'.join(self.inputs))
        for name in ('keptSchematic', 'returnedToSameChart'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                validate('calibration', {**self.result, name: False}, '\n'.join(self.inputs))

    def test_opening_the_chart_twice_does_not_prove_calibration(self):
        with self.assertRaises(ValueError):
            validate('calibration', self.result, '\n'.join((*self.inputs[:3], self.inputs[0], self.inputs[-1])))
