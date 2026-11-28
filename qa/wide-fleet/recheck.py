"""Rebuild the wide-ship checks from a frozen ZIP, without Minecraft or an existing test save."""

import argparse
import csv
import gzip
import io
import json
import math
import re
import sys
import tempfile
from pathlib import Path
from zipfile import ZipFile
from verify import bpos, minus, plus, uuid_words
from yard import ROOT

sys.path.insert(0, str(ROOT / "tools"))
from upstream_runtime_region import read_compound, chunks
from inspect_deck import SavedColumns


def compound(raw):
    return read_compound(gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw)


def audit_archive(archive):
    with (
        ZipFile(archive) as z,
        tempfile.TemporaryDirectory(prefix="wide-fleet-recheck-") as td,
    ):
        assert z.testzip() is None

        def read(name):
            return json.loads(z.read(name))

        server = read("integrated-server.json")
        n = server["length"]
        f = server["flight"]
        yard = read("native-shipyard/loaded-dock-result.json")
        native = compound(z.read(f"inputs/{n}.nbt"))
        h = native["Hull"]
        logical = uuid_words(native["Rocket"])
        core = uuid_words(h["Core"])
        assert (
            logical == yard["rocket"] == f["lunar"]["original"] == f["home"]["original"]
        )
        assert core == yard["core"] == f["moonHull"]["core"] == f["homeHull"]["core"]
        assert (
            n == yard["length"]
            and server["hostCount"] == 2
            and server["publishedLan"]
            and not server["allowCommands"]
        )
        metadata = compound(z.read("world/level.dat"))["Data"]
        assert metadata["GameType"] == 0 and not metadata["allowCommands"]
        con = read("lan-connection.json")
        assert con["gamemode"] == "survival" and not con["op"] and not con["memory"]
        assert z.read("visitor/guest-worlds.txt").decode().splitlines() == [
            "minecraft:overworld",
            "retrofit:legacy_dim_-28",
            "minecraft:overworld",
        ]
        inputs = [
            row[2] for row in csv.reader(io.StringIO(z.read("input.csv").decode()))
        ]
        expected_actions = iter(inputs)
        for action in [
            "used-original-bridge",
            "click-route.starbridge.moon",
            "click-nav.starbridge.cruise",
            "click-orbit.starbridge.landing",
            "click-orbit.starbridge.touchdown",
            "lunar-deck",
            "used-paired-terminal",
            "click-route.starbridge.home",
            "click-nav.starbridge.cruise",
            "click-orbit.starbridge.landing",
            "click-orbit.starbridge.touchdown",
            "home-deck",
        ]:
            assert action in expected_actions, action
        live = read("wide-job-verified.json")
        assert live["rocket"] == logical and live["core"] == core
        journal = compound(z.read("world/data/starbridge_passages.dat"))
        assert journal["Legs"] == []
        vs = json.loads(
            compound(z.read("world/data/vs_ship_data.dat"))["data"]["vs_pipeline"]
        )
        ship = next(s for s in vs["ships"] if s["id"] == f["home"]["shipId"])
        box = ship["shipAABB"]
        base = Path(td)
        home = base / "home"
        home.mkdir()
        target = {"home": [], "moon": []}
        lunar_slots = {f[key]["leg"]: set() for key in ("moonFare", "homeFare")}
        for name in z.namelist():
            m = re.fullmatch(
                r"world/(dimensions/retrofit/legacy_dim_-28/)?region/(r\.-?\d+\.-?\d+\.mca)",
                name,
            )
            if not m:
                continue
            side = "moon" if m.group(1) else "home"
            out = base / side / m.group(2)
            out.parent.mkdir(exist_ok=True)
            out.write_bytes(z.read(name))
            for chunk in chunks(out):
                stamp = chunk.get("starbridge:passage_checkpoint")
                if side == "moon" and stamp and uuid_words(stamp) in lunar_slots:
                    lunar_slots[uuid_words(stamp)].add((chunk["xPos"], chunk["zPos"]))
                    assert not chunk.get("block_entities"), (
                        name,
                        "stored machines on Moon",
                    )
                    for section in chunk.get("sections", []):
                        states = section.get("block_states", {})
                        if all(
                            b["Name"] == "minecraft:air"
                            for b in states.get("palette", [])
                        ):
                            continue
                        # A stale, unused palette entry alone does not mean any voxel survived.
                        columns = SavedColumns(out.parent)
                        assert all(
                            columns.block(
                                chunk["xPos"] * 16 + x,
                                section["Y"] * 16 + y,
                                chunk["zPos"] * 16 + k,
                            )
                            == "minecraft:air"
                            for x in range(16)
                            for y in range(16)
                            for k in range(16)
                        )
                for tile in chunk.get("block_entities", []):
                    if all(
                        box["min" + a.upper()] <= tile[a] <= box["max" + a.upper()]
                        for a in "xyz"
                    ):
                        target[side].append(tile)
        expected_x = box["maxX"] // 16 - box["minX"] // 16 + 1
        expected_z = box["maxZ"] // 16 - box["minZ"] // 16 + 1
        for receipt, foot in (("moonFare", "moonFooting"), ("homeFare", "homeFooting")):
            cells = lunar_slots[f[receipt]["leg"]]
            local = [float(v) for v in f[foot]["localFeet"].strip("()").split(",")]
            xs, zs = {x for x, _ in cells}, {k for _, k in cells}
            assert (math.floor(local[0]) // 16, math.floor(local[2]) // 16) in cells
            assert len(xs) == expected_x and len(zs) == expected_z
            assert max(xs) - min(xs) + 1 == len(xs) and max(zs) - min(zs) + 1 == len(zs)
            assert len(cells) == expected_x * expected_z, receipt
        assert not set.intersection(
            *lunar_slots.values()
        ), "the lunar landing and return slots coincide"
        assert not target["moon"] and len(target["home"]) == yard["packedBlockEntities"]
        (hc,) = [t for t in target["home"] if t["id"] == "starbridge:ship_core"]
        assert (
            uuid_words(hc["OriginalRocket"]) == logical
            and uuid_words(hc["CoreId"]) == core
        )
        assert uuid_words(hc["PaidVoyage"]) == f["homeFare"]["leg"]
        assert hc["Energy"] == f["homeFare"]["balance"]
        origin = bpos(h["Bridge"])
        offset = minus(tuple(hc[a] for a in "xyz"), origin)
        lo = bpos(h["Min"])
        hi = bpos(h["Max"])
        expected = {bpos(r["P"]): r["State"]["Name"] for r in h["Cells"]}
        cols = SavedColumns(home)
        checked = 0
        for x in range(lo[0], hi[0] + 1):
            for y in range(lo[1], hi[1] + 1):
                for k in range(lo[2], hi[2] + 1):
                    pos = (x, y, k)
                    assert cols.block(*plus(pos, offset)) == expected.get(
                        pos, "minecraft:air"
                    ), pos
                    checked += 1
        assert len(expected) == yard["occupiedManifestBlocks"] and checked == 19 * 8 * n
        (barrel,) = [t for t in target["home"] if t["id"] == "minecraft:barrel"]
        (blast,) = [t for t in target["home"] if t["id"] == "minecraft:blast_furnace"]
        assert tuple(barrel[a] for a in "xyz") == plus(plus(origin, offset), (3, 0, 2))
        assert tuple(blast[a] for a in "xyz") == plus(plus(origin, offset), (3, -3, 7))
        assert [(i["id"], i["Count"]) for i in barrel["Items"]] == [
            ("minecraft:copper_ingot", 1)
        ]
        assert all(i["id"] == "minecraft:coal" for i in blast.get("Items", []))
        diamonds = sum(
            i["Count"]
            for t in target["home"]
            if t["id"] == "minecraft:chest"
            for i in t.get("Items", [])
            if i["id"] == "minecraft:diamond"
        )
        assert diamonds == yard["diamonds"]
        furnace = sum(
            i["Count"]
            for t in target["home"]
            if t["id"] == "minecraft:furnace"
            for i in t.get("Items", [])
            if i["id"] in ("minecraft:iron_ingot", "minecraft:iron_ore")
        )
        assert furnace == yard["furnaceInputsAndOutputs"]
        ids = {
            m.rsplit("/", 1)[-1][:-4]
            for m in z.namelist()
            if m.startswith("world/playerdata/") and m.endswith(".dat")
        }
        assert ids == {con["host"], con["guest"]}
        assert all(
            compound(z.read("world/playerdata/" + identity + ".dat"))["Dimension"]
            == "minecraft:overworld"
            and compound(z.read("world/playerdata/" + identity + ".dat"))["Health"] > 0
            for identity in ids
        )
        feet = [f["homeFooting"], f["moonFooting"], *server["visitorFooting"].values()]
        assert all(
            abs(foot["gap"]) < 0.075
            and foot.get("health", foot.get("pilotHealth", 0)) > 0
            for foot in feet
        )
        assert (
            all(
                f[k]["receiptMatches"]
                and not f[k]["pending"]
                and f[k]["charged"] == f[k]["energyBefore"] - f[k]["balance"] == 12000
                for k in ("homeFare", "moonFare")
            )
            and f["homeFare"]["leg"] != f["moonFare"]["leg"]
        )
        steps = read("visitor/wide-engineer-complete.json")
        assert steps == [
            json.loads(line)
            for line in z.read("visitor/wide-engineer-log.jsonl").splitlines()
        ]
        container_steps = [
            (r["action"], r["screen"]) for r in steps if r["screen"] != "world"
        ]
        assert container_steps == [
            ("took raw copper using the barrel screen", "ContainerScreen"),
            ("loaded carried ore into blast furnace", "BlastFurnaceScreen"),
            (
                "collected freshly smelted copper from the output slot",
                "BlastFurnaceScreen",
            ),
            ("stowed the smelted ingot on the upper deck", "ContainerScreen"),
        ]
        assert live["captain"] == con["host"] and live["crew"] == con["guest"]
        assert set(live["suits"]) == {"FleetCaptain", "FleetVisitor"}
        for suit in live["suits"].values():
            assert suit == [
                "galaxyspace:item.spacesuit_" + part
                for part in ("helmet", "plate", "leg", "boots")
            ]
        assert (
            live["upperBarrelScreen"]
            < live["lowerBlastFurnaceScreen"]
            <= live["oreInLowerFurnace"]
            < live["ingotCarried"]
            < live["orbitEnd"]
        )
        assert live["inputConsumed"] and live["ingotsInUpperBarrel"] == 1
        observations = live["positions"]
        assert {r["core"] for r in observations} == {core}
        assert {r["menu"] for r in observations} >= {"ChestMenu", "BlastFurnaceMenu"}
        assert all(
            b["tick"] - a["tick"] == 2 for a, b in zip(observations, observations[1:])
        )
        positions = [r["position"] for r in observations]
        assert (
            len(steps) == 13
            and max(q[1] for q in positions) - min(q[1] for q in positions) >= 2.95
            and max(math.dist(a, b) for a, b in zip(positions, positions[1:])) < 1.7
        )
        result = {
            "length": n,
            "originalRocket": logical,
            "core": core,
            "volumeChecked": checked,
            "blocks": len(expected),
            "blockEntities": len(target["home"]),
            "crewAliveAtHome": len(ids),
            "diamonds": diamonds,
            "furnaceProductsAndInputs": furnace,
            "copperInUpperBarrel": 1,
            "clearedLunarColumnsByLeg": {k: len(v) for k, v in lunar_slots.items()},
        }
        for member, key in [
            ("frames.csv", "host"),
            ("visitor/guest-frames.csv", "visitor"),
        ]:
            rows = list(csv.DictReader(io.StringIO(z.read(member).decode("utf8"))))
            durations = sorted(
                (int(r["endNs"]) - int(r["startNs"])) / 1e6
                for r in rows
                if r["startPhase"] == r["endPhase"] == "return-space"
                and (
                    r["startScreen"] == r["endScreen"] == "OrbitScreen"
                    if key == "host"
                    else r["startWorld"] == r["endWorld"]
                )
            )
            assert len(durations) >= 8
            result[key + "ReturnMs"] = {
                "samples": len(durations),
                "p50": round(durations[math.ceil(len(durations) * 0.50) - 1], 3),
                "p95": round(durations[math.ceil(len(durations) * 0.95) - 1], 3),
                "peak": round(durations[-1], 3),
            }
        return result


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("archive", type=Path, nargs="+")
    a = p.parse_args()
    print(json.dumps([audit_archive(f) for f in a.archive], indent=2))
