from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from host import run
from replay_crew import panel_pixels


class CrewDisplayTest(unittest.TestCase):
    def test_workstation_is_rejected_before_creating_instance_or_starting_either_client(self):
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.dict("os.environ", {"GITHUB_ACTIONS": ""}),
            patch("host.configure") as install,
            patch("host.launch") as launch,
        ):
            root = Path(temp) / "never-created"
            with self.assertRaises(RuntimeError):
                run(root, root / "evidence", Path(temp) / "fixture")
            self.assertFalse(root.exists())
            install.assert_not_called()
            launch.assert_not_called()

    def test_readback_requires_the_reserve_to_fill_exactly_its_share_of_the_fuel_instrument(self):
        class Frame:
            size = (330, 190)

            def __init__(self):
                self.p = {}

            def getpixel(self, pos):
                return self.p.get(pos, (0, 0, 0))

        image = Frame()
        frame = {
            "panel": [10, 20, 310],
            "gui": image.size,
            "framebuffer": image.size,
            "reading": {"remaining": 250, "span": 1000, "reserve": 25, "energy": 100},
        }
        x, right = 23, 308
        gold = (255, 201, 130)
        aqua = (128, 223, 202)
        text = (236, 241, 237)
        dim = (178, 197, 204)
        for dx in range(-2, 3):
            image.p[237 + dx, 64] = gold
        for pos in range(x, right + 1):
            image.p[pos, 129] = gold if pos < x + 71 else aqua
        for a, b, c in [
            (25, 28, text),
            (280, 28, aqua),
            (25, 48, dim),
            (280, 48, text),
            (25, 76, text),
            (280, 76, text),
            (25, 95, dim),
            (280, 95, dim),
            (25, 114, text),
            (280, 114, gold),
        ]:
            image.p[a, b] = c
        panel_pixels(image, frame)
        frame["reading"]["reserve"] = 42
        with self.assertRaisesRegex(ValueError, "FE match"):
            panel_pixels(image, frame)


if __name__ == "__main__":
    unittest.main()
