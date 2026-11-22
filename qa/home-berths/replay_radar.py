"""Replay the additional traffic-scope frames in a hosted home-port artifact."""

import argparse
import hashlib
import io
import json
import math
from pathlib import Path
from zipfile import ZipFile

A = "a115ea91-4230-418c-90a4-f7a43949fbb1"
B = "b224fb02-06e7-4800-88d3-461c21593f12"
STATES = {
    "radar-first": ([A, B], A),
    "radar-second": ([A, B], B),
    "radar-reordered": ([B, A], B),
    "radar-picked": ([B, A], A),
    "radar-cycled": ([B, A], B),
    "radar-lost": ([A], B),
    "radar-recovered": ([A], A),
}


def check(value, reason):
    if not value:
        raise ValueError(reason)


def point(row, contact):
    cx, cy, r = row["scope"]
    ship = row["ship"]
    other = contact["position"]
    dx = other["x"] - ship["x"]
    dz = other["z"] - ship["z"]
    yaw = math.radians(row["yaw"])
    side = dx * math.cos(yaw) + dz * math.sin(yaw)
    depth = -dx * math.sin(yaw) + dz * math.cos(yaw)
    horizontal = math.hypot(side, depth)
    scale = r * math.sqrt(min(1, horizontal / 5000)) / horizontal
    return [cx + math.floor(0.5 + side * scale), cy + math.floor(0.5 - depth * scale)]


def verify(client, ledger, landing):
    frames = client["frames"]
    actions = client["actions"]
    core = landing["core"]
    check(client["language"] == "zh_cn" and set(frames) == set(STATES), "Seven Chinese radar frames")
    phases = ledger["phases"]
    check(
        ledger["syntheticLedgerTraffic"] is True and [p["phase"] for p in phases] == [0, 1, 2, 3],
        "Fixture phases and final cleanup",
    )
    keys = [{A, B}, {A, B}, {A}, set()]
    offsets = [
        {A: (1000, 120, 220), B: (-2050, -180, -300)},
        {A: (2200, 120, 220), B: (-800, -180, -300)},
        {A: (2200, 120, 220)},
        {},
    ]
    for i, phase in enumerate(phases):
        check(
            phase["target"] == "home" and phase["core"] == core and set(phase["contacts"]) == keys[i],
            "Expected server ledger contacts and returning core",
        )
        if i:
            check(phases[i - 1]["tick"] < phase["tick"], "Ordered server ticks")
        for ident, pos in phase["contacts"].items():
            delta = tuple(pos[k] - phase["ship"][k] for k in "xyz")
            check(math.dist(delta, offsets[i][ident]) < 0.01, "Known formation coordinates in server ledger")
    for title, (ids, selected) in STATES.items():
        row = frames[title]
        contacts = row["contacts"]
        lost = title == "radar-lost"
        check(
            row["target"] == "home" and row["core"] == core and [c["id"] for c in contacts] == ids,
            "Unchanged returning vessel and range order: " + title,
        )
        check(
            row["selected"] == selected and row["callsign"] == "飞船 " + selected[:6],
            "Same call sign after resort or disappearance: " + title,
        )
        check(row["label"] == ("信号已丢失" if lost else row["callsign"]), "Displayed signal state: " + title)
        phase = 0 if title in ("radar-first", "radar-second") else 2 if len(ids) == 1 else 1
        for contact in contacts:
            delta = tuple(contact["position"][k] - row["ship"][k] for k in "xyz")
            check(
                math.dist(delta, offsets[phase][contact["id"]]) < 40
                and contact["dot"] == point(row, contact),
                "Server and client agree on the plotted echo: " + title,
            )
        if len(contacts) == 2:
            check(math.dist(contacts[0]["dot"], contacts[1]["dot"]) > 12, "Disjoint mouse hit targets")
    check(
        [a["action"] for a in actions] == ["toggle", "echo", "echo", "next", "next", "toggle"],
        "Pilot click sequence",
    )
    check(
        [a["label"] for a in actions if a["action"] != "echo"]
        == ["舰船雷达 · 2", "下一艘", "下一艘", "舰船雷达 · 1"],
        "Actual Chinese buttons",
    )
    for action, title, ident in zip(actions[1:3], ("radar-first", "radar-reordered"), (B, A)):
        row = frames[title]
        dot = next(c["dot"] for c in row["contacts"] if c["id"] == ident)
        expected = [
            math.floor((v + 0.5) * fb / gui) for v, fb, gui in zip(dot, row["framebuffer"], row["gui"])
        ]
        check(
            action["fromFrame"] == title and action["id"] == ident and action["pixel"] == expected,
            "Mouse click hit the requested visible signal",
        )
    return {
        "syntheticLedgerTraffic": True,
        "target": "home",
        "contacts": [A, B],
        "core": core,
        "frames": list(STATES),
        "clicks": actions,
    }


def pixels(image, row):
    panel = row["scope"]
    fw, fh = row["framebuffer"]
    gw, gh = row["gui"]
    check((fw, fh) == image.size, "Screenshot dimensions")

    def color(x, y):
        return image.getpixel((int((x + 0.5) * fw / gw), int((y + 0.5) * fh / gh)))[:3]

    amber = (255, 200, 130)
    blue = (155, 190, 247)
    gray = (155, 177, 191)
    for contact in row["contacts"]:
        x, y = contact["dot"]
        check(
            all(color(x + i, y + j) == blue for i in (-1, 0, 1) for j in (-1, 0, 1)),
            "Visible 3×3 blue contact",
        )
        mask = {(i, j) for i in range(-5, 6) for j in range(-5, 6) if color(x + i, y + j) == amber}
        expected = (
            {(i, j) for i in range(-4, 5) for j in range(-4, 5) if max(abs(i), abs(j)) == 4}
            if contact["id"] == row["selected"]
            else set()
        )
        check(mask == expected, "Only the tracked visible signal has the amber border")
    lost = row["label"] == "信号已丢失"
    begin = panel[0] + panel[2] + 7
    wanted = gray if lost else amber
    check(
        any(color(x, y) == wanted for x in range(begin, min(begin + 58, gw)) for y in range(33, 45)),
        "Painted Chinese heading",
    )
    if lost:
        check(
            any(color(x, y) == gray for x in range(begin, min(begin + 58, gw)) for y in range(45, 56)),
            "Lost signal retains its callsign",
        )


def replay(path, expected_digest=None):
    from PIL import Image

    if expected_digest:
        with path.open("rb") as stream:
            check(
                hashlib.file_digest(stream, "sha256").hexdigest() == expected_digest, "GitHub artifact digest"
            )
    with ZipFile(path) as archive:

        def read(name):
            entries = [e for e in archive.infolist() if e.filename == name]
            check(
                len(entries) == 1 and 0 < entries[0].file_size < 8 * 1024**2,
                "Missing or oversized artifact member " + name,
            )
            return archive.read(entries[0])

        client = json.loads(read("radar-client.json"))
        server = json.loads(read("radar-server.json"))
        landing = json.loads(read("home-server.json"))
        receipt = json.loads(read("evidence.json"))
        report = verify(client, server, landing)
        check(
            landing["pass"]
            and receipt["cleanExit"]
            and receipt["save"]["settled"]
            and not receipt["save"]["cheats"]
            and receipt["save"]["survival"]
            and receipt["save"]["diamonds"] == 7,
            "Non-operator survival captain returned with cargo",
        )
        for title, row in client["frames"].items():
            raw = read(title + ".png")
            check(
                hashlib.sha256(raw).hexdigest() == receipt["radarScreenshots"][title]["sha256"],
                "Captured screenshot hash",
            )
            with Image.open(io.BytesIO(raw)) as image:
                pixels(image.convert("RGB"), row)
        return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--sha256")
    args = parser.parse_args()
    print(json.dumps(replay(args.archive, args.sha256), ensure_ascii=False, indent=2))
