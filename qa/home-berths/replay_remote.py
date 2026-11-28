"""Replay a published home-port evidence ZIP without Minecraft or a display (requires Pillow)."""

import argparse
import hashlib
import io
import json
import math
from pathlib import Path
from zipfile import ZipFile

from PIL import Image

from remote import FRAMES, PACK, PORT, check, verify_clicks


def marker(image, layout, offset, clear):
    gui_w, gui_h = layout["gui"]
    left, top, side = layout["map"]
    fx, fy = layout["framebuffer"]
    check(image.size == (fx, fy), "Screenshot resolution agrees with mouse coordinates")
    # Count the painted shape in the grid coordinates, independently of the five fixture probes.
    mx, mz = [
        start + math.floor((value + 512) * (side - 1) / 1024 + 0.5)
        for start, value in zip((left, top), offset)
    ]
    rgb = (131, 224, 203) if clear else (255, 200, 130)
    opposite = (255, 200, 130) if clear else (131, 224, 203)

    def pixel(dx, dz):
        return image.getpixel(
            (int((mx + dx + 0.5) * fx / gui_w), int((mz + dz + 0.5) * fy / gui_h))
        )[:3]

    mask = {
        (dx, dz) for dx in range(-6, 7) for dz in range(-6, 7) if pixel(dx, dz) == rgb
    }
    check(
        not any(
            pixel(dx, dz) == opposite for dx in range(-6, 7) for dz in range(-6, 7)
        ),
        "Incorrect opposite-color marker in selection",
    )
    if clear:
        expected = {
            (x, z)
            for x in range(-5, 6)
            for z in range(-5, 6)
            if (abs(z) == 5 and abs(x) >= 3) or (abs(x) == 5 and abs(z) == 4)
        }
    else:
        expected = {
            (delta + dx, sign * delta + dz)
            for delta in range(-4, 5)
            for sign in (-1, 1)
            for dx in (0, 1)
            for dz in (0, 1)
        }
    check(
        mask == expected,
        f"Unexpected painted marker at offset {offset}: {len(mask)} / {len(expected)} GUI pixels",
    )
    return {
        "shape": "open brackets" if clear else "cross",
        "rgb": list(rgb),
        "paintedGuiPixels": len(mask),
        "centerColored": (0, 0) in mask,
    }


def replay(path, expected_digest=None):
    raw = path.read_bytes()
    archive_hash = hashlib.sha256(raw).hexdigest()
    if expected_digest:
        check(
            archive_hash == expected_digest,
            "Evidence archive digest differs from expected",
        )
    with ZipFile(io.BytesIO(raw)) as archive:

        def member(name):
            entries = [i for i in archive.infolist() if i.filename == name]
            check(
                len(entries) == 1 and 0 < entries[0].file_size <= 5 * 1024**2,
                "Missing or unbounded evidence member " + name,
            )
            return archive.read(entries[0])

        def data(name):
            return json.loads(member(name))

        client = data("home-client.json")
        server = data("home-server.json")
        evidence = data("evidence.json")
        loaded = data("home-loaded.json")
        actions = member("home-actions.txt").decode("utf8")
        verify_clicks(client, server, actions)
        source = evidence["source"]
        saved = evidence["save"]
        check(
            evidence["cleanExit"] is True
            and saved["settled"] is True
            and saved["cheats"] is False
            and saved["survival"] is True,
            "Clean survival save",
        )
        check(
            saved["recordedHome"] == PORT
            and saved["pilot"] == server["pilot"]
            and saved["paidReceipt"] == server["receipt"]
            and saved["remainingFE"] == server["afterFE"]
            and saved["diamonds"] == server["diamonds"],
            "Live and cold save audit disagree",
        )
        check(
            source["mainJarSha256"] == loaded["starbridge"], "Actual loaded public JAR"
        )
        lock = {row["name"]: row for row in PACK["dependencies"]}
        for name in (
            "Retrofit",
            "Galacticraft Legacy",
            "Valkyrien Skies",
            "Kotlin for Forge",
        ):
            row = lock[name]
            check(
                evidence["originals"][row["file"]] == row["sha256"],
                "Pinned original dependency " + name,
            )
        for mod, name in [
            ("retrofit", "Retrofit"),
            ("valkyrienskies", "Valkyrien Skies"),
        ]:
            check(loaded[mod] == lock[name]["sha256"], "Runtime container " + mod)
        checks = {}
        for name, (_, clear) in FRAMES.items():
            image_bytes = member(name + ".png")
            info = evidence["screenshots"][name]
            check(
                hashlib.sha256(image_bytes).hexdigest() == info["sha256"],
                "PNG digest " + name,
            )
            with Image.open(io.BytesIO(image_bytes)) as image:
                check(
                    image.format == "PNG"
                    and [image.width, image.height] == [info["width"], info["height"]],
                    "PNG type and dimensions",
                )
                checks[name] = marker(
                    image.convert("RGB"),
                    client["mapClicks"][0],
                    client["frames"][name]["offset"],
                    clear,
                )
        return {
            "evidenceSha256": archive_hash,
            "source": source,
            "ship": server,
            "saved": saved,
            "map": client["mapClicks"],
            "steps": actions.splitlines(),
            "markerPixels": checks,
            "screenshots": evidence["screenshots"],
        }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--sha256")
    args = parser.parse_args()
    print(json.dumps(replay(args.archive, args.sha256), indent=2, ensure_ascii=False))
