"""Verify an archived two-client crew display mission without launching Minecraft."""

import argparse
import gzip
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import uuid
from zipfile import ZipFile

from host import CAPTAIN, ENGINEER, NAMES, ROLES, require, verify
from upstream_runtime_region import read_compound  # imported by the hosted pair save reader


def panel_pixels(image, frame):
    left, top, width = frame["panel"]
    fw, fh = frame["framebuffer"]
    gw, gh = frame["gui"]
    reading = frame["reading"]
    require(image.size == (fw, fh), "Frame dimensions differ")
    x = left + 13
    right = left + width - 12
    half = (right - x) // 2

    def pixel(a, b):
        return image.getpixel((int((a + 0.5) * fw / gw), int((b + 0.5) * fh / gh)))[:3]

    gold = (255, 201, 130)
    aqua = (128, 223, 202)
    text = (236, 241, 237)
    dim = (178, 197, 204)
    progress = max(0, min(1, 1 - reading["remaining"] / max(1, reading["span"])))
    mark = x + math.floor((right - x) * progress + 0.5)
    require(
        all(pixel(mark + d, top + 44) == gold for d in range(-2, 3)), "Progress arrow at the measured range"
    )
    reserve = math.floor((right - x) * min(1, reading["reserve"] / max(1, reading["energy"])) + 0.5)
    require(
        all(pixel(a, top + 109) == (gold if a < x + reserve else aqua) for a in range(x, right + 1)),
        "Protected and available FE match the meter",
    )
    for start, end, row, color in [
        (left + 9, left + width // 2, 8, text),
        (left + width // 2, right, 8, aqua),
        (x, x + half, 28, dim),
        (x + half, right, 28, text),
        (x, x + half, 56, text),
        (x + half, right, 56, text),
        (x, x + half, 75, dim),
        (x + half, right, 75, dim),
        (x, x + half, 94, text),
        (x + half, right, 94, gold),
    ]:
        require(
            any(pixel(xx, top + yy) == color for xx in range(start, end) for yy in range(row, row + 11)),
            "Missing rendered instruments at " + str((start, row)),
        )


def replay(path, digest=None):
    from PIL import Image

    if digest:
        with path.open("rb") as f:
            require(
                hashlib.file_digest(f, "sha256").hexdigest() == digest, "Unexpected Actions artifact digest"
            )
    with ZipFile(path) as z:

        def raw(name):
            members = [m for m in z.infolist() if m.filename == name]
            require(
                len(members) == 1 and 0 < members[0].file_size <= 8 * 1024**2,
                "Missing or oversized artifact " + name,
            )
            return z.read(members[0])

        def data(name):
            return json.loads(raw(name))

        report = data("crew-result.json")
        verify(report)
        require(
            data("server-crew-server.json") == report["server"]
            and data(CAPTAIN + "-crew-controls.json") == report["captain"]
            and data(ENGINEER + "-crew-machine.json") == report["engineer"],
            "Raw participants disagree with report",
        )
        for role, key in ((CAPTAIN, "captain"), (ENGINEER, "engineer")):
            require(
                raw(role + "-crew-inputs.log").decode().splitlines() == report[key]["inputs"],
                "Missing bound game input",
            )
            model = read_compound(gzip.decompress(raw(role + ".dat")))
            require(
                str(uuid.UUID(bytes=struct.pack(">4i", *model["UUID"]))) == ROLES[role]
                and model["playerGameType"] == 0
                and model["Health"] == report["save"]["players"][role]["health"]
                and model["Pos"] == report["save"]["players"][role]["position"],
                "Wrong saved survival player",
            )
            if role == ENGINEER:
                require(
                    sum(r["Count"] for r in model["Inventory"] if r["id"] == "minecraft:iron_ingot") == 1,
                    "The engineer did not save the smelted iron",
                )
            require(
                "Stopping!" in raw(role + "-latest.log").decode(errors="replace"),
                "Client did not stop normally",
            )
        world = read_compound(gzip.decompress(raw("level.dat")))["Data"]
        require(
            data("ops.json") == []
            and not world["allowCommands"]
            and world["GameType"] == 0
            and "All dimensions are saved" in raw("server-latest.log").decode(errors="replace"),
            "Stopped non-OP survival world",
        )
        for name in (*NAMES, "crew-furnace"):
            content = raw(ENGINEER + "-" + name + ".png")
            require(
                hashlib.sha256(content).hexdigest() == report["screenshots"][name]["sha256"],
                "Screenshot digest " + name,
            )
            if name in NAMES:
                frame = data(ENGINEER + "-" + name + ".json")
                require(frame == report["frames"][name], "Raw frame differs")
                with Image.open(io.BytesIO(content)) as image:
                    panel_pixels(image.convert("RGB"), frame)
        return {
            "source": report["source"],
            "ship": report["server"]["ship"],
            "screenshots": report["screenshots"],
            "saved": report["save"],
            "motion": report["server"]["motion"],
            "fare": report["server"]["fare"],
            "reserve": report["server"]["reserve"],
        }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--sha256")
    args = parser.parse_args()
    print(json.dumps(replay(args.artifact, args.sha256), ensure_ascii=False, indent=2))
