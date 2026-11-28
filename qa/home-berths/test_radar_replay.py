"""A retained signal may disappear without moving the selection ring to its neighbour."""

import unittest
from replay_radar import A, B, pixels, point


class RadarMarkerTest(unittest.TestCase):
    def test_lost_callsign_needs_two_lines_and_no_border_around_remaining_signal(self):
        class Image:
            size = (200, 140)

            def __init__(self):
                self.colors = {}

            def getpixel(self, pos):
                return self.colors.get(pos, (0, 0, 0))

        image = Image()
        row = {
            "scope": [40, 60, 31],
            "gui": image.size,
            "framebuffer": image.size,
            "contacts": [{"id": A, "dot": [50, 52]}],
            "selected": B,
            "label": "信号已丢失",
        }
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                image.colors[50 + dx, 52 + dy] = (155, 190, 247)
        image.colors[80, 38] = (155, 177, 191)
        with self.assertRaisesRegex(ValueError, "retains its callsign"):
            pixels(image, row)
        image.colors[80, 48] = (155, 177, 191)
        pixels(image, row)
        image.colors[46, 48] = (255, 200, 130)
        with self.assertRaisesRegex(ValueError, "amber border"):
            pixels(image, row)

    def test_forward_echo_turns_left_on_a_quarter_turn_to_its_right(self):
        row = {"ship": dict(x=0, y=7, z=0), "scope": [40, 60, 30], "yaw": 0}
        other = {"position": dict(x=0, y=8, z=1250)}
        self.assertEqual(point(row, other), [40, 45])
        row["yaw"] = -90
        self.assertEqual(point(row, other), [25, 60])


if __name__ == "__main__":
    unittest.main()
