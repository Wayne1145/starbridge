"""Audit a finished integrated trip, compute phase percentiles from consecutive render frames, and freeze the evidence."""

import argparse
import csv
import hashlib
import json
import gzip
import math
import sys
from collections import defaultdict, Counter
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from upstream_runtime_region import read_compound, chunks  # noqa: E402


def check(ok, why):
    if not ok:
        raise ValueError(why)


def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def read(p):
    return json.loads(p.read_bytes())


def nbt(p):
    b = p.read_bytes()
    return read_compound(gzip.decompress(b) if b[:2] == b"\x1f\x8b" else b)


def stats(rows):
    v = sorted((int(r["endNs"]) - int(r["startNs"])) / 1e6 for r in rows)
    return {
        "intervals": len(v),
        "seconds": round(sum(v) / 1000, 3),
        "p50ms": round(v[math.ceil(len(v) * 0.5) - 1], 3),
        "p95ms": round(v[math.ceil(len(v) * 0.95) - 1], 3),
        "peakMs": round(v[-1], 3),
        "over100ms": sum(x > 100 for x in v),
    }


def audit(run):
    pilot = read(run / "pilot-result.json")
    server = read(run / "integrated-server.json")
    f = server["flight"]
    n = server["length"]
    meta = read(run / "launch-result.json")
    graphics = read(run / "graphics.json")
    check(
        pilot["pass"]
        and server["integrated"]
        and server["actualSingleplayerOwner"]
        and server["hostCount"] == 1
        and not server["publishedLan"]
        and not server["allowCommands"],
        "singleplayer host or controls",
    )
    check(
        f["finished"]
        and "error" not in f
        and not list((run / "crash-reports").glob("*.txt")),
        "flight or launch errors",
    )
    log = (run / "logs/latest.log").read_text(encoding="utf8", errors="replace")
    check("All dimensions are saved" in log, "no clean disk save")
    with (run / "frames.csv").open(encoding="utf8") as stream:
        rows = list(csv.DictReader(stream))
        check(len(rows) == pilot["frames"], "last raw frames missing")
    check(
        all(
            int(r["seq"]) == i + 1
            and int(r["endNs"]) > int(r["startNs"])
            and (i == 0 or rows[i - 1]["endNs"] == r["startNs"])
            for i, r in enumerate(rows)
        ),
        "non-contiguous render stamps",
    )
    phases = defaultdict(list)
    for r in rows:
        phases[r["startPhase"]].append(r)
    required = [
        "fixed-cabin",
        "outbound-space",
        "outbound-approach",
        "outbound-handoff",
        "lunar-deck",
        "return-space",
        "return-approach",
        "return-handoff",
        "home-deck",
    ]
    check(all(phases.get(k) for k in required), "missing rendered flight phase")
    for prefix in ("outbound", "return"):
        check(
            sum(r["startScreen"] == "OrbitScreen" for r in phases[prefix + "-space"])
            >= 8,
            "third person screen not rendered",
        )
        check(
            sum(r["startScreen"] == "OrbitScreen" for r in phases[prefix + "-approach"])
            >= 8,
            "landing instrument not rendered",
        )
    dim_changes = [
        {
            "from": r["startWorld"],
            "to": r["endWorld"],
            "phase": r["startPhase"],
            "intervalMs": round((int(r["endNs"]) - int(r["startNs"])) / 1e6, 3),
            "seq": int(r["seq"]),
        }
        for r in rows
        if r["startWorld"] != r["endWorld"]
    ]
    check(
        any(e["to"] == "retrofit:legacy_dim_-28" for e in dim_changes)
        and any(e["from"] == "retrofit:legacy_dim_-28" for e in dim_changes),
        "no measured dimension changes",
    )
    inputs = (run / "input.csv").read_text(encoding="utf8")
    check(
        all(
            s in inputs
            for s in (
                "used-original-bridge",
                "used-paired-terminal",
                "route.starbridge.moon",
                "route.starbridge.home",
            )
        )
        and inputs.count("click-orbit.starbridge.touchdown") >= 2,
        "missing interactive path",
    )
    world = run / "saves" / ("IntegratedOrbit" + str(n))
    manifest = nbt(run / "inputs" / f"{n}.nbt")
    cells = manifest["Hull"]["Cells"]
    shape = {
        "width": 7,
        "height": 6,
        "length": n,
        "occupied": len(cells),
        "blockEntities": sum("Tile" in c for c in cells),
        "furnaces": sum(c["State"]["Name"] == "minecraft:furnace" for c in cells),
        "chests": sum(c["State"]["Name"] == "minecraft:chest" for c in cells),
    }
    journal = world / "data/starbridge_passages.dat"
    check(nbt(journal)["Legs"] == [], "outstanding journal")
    check(
        f["moonFare"]["leg"] != f["homeFare"]["leg"]
        and all(
            f[k]["charged"] == 12000 and f[k]["receiptMatches"]
            for k in ["homeFare", "moonFare"]
        ),
        "receipt mismatch",
    )
    native_shapes = [f[k] for k in ("moonHull", "homeHull")]
    check(
        all(
            t["blocks"] == shape["occupied"]
            and t["blockEntities"] == shape["blockEntities"]
            and t["cargoStations"] == shape["chests"]
            for t in native_shapes
        ),
        "dimensions or block counts differ at endpoints",
    )
    vs_file = world / "data/vs_ship_data.dat"
    vs = json.loads(nbt(vs_file)["data"]["vs_pipeline"])
    body = next(ship for ship in vs["ships"] if ship["id"] == f["home"]["shipId"])
    box = body["shipAABB"]
    regions = []
    disk = {}
    for label, folder in [
        ("home", world / "region"),
        ("moon", world / "dimensions/retrofit/legacy_dim_-28/region"),
    ]:
        tiles = []
        for rx in range(box["minX"] // 512, box["maxX"] // 512 + 1):
            for rz in range(box["minZ"] // 512, box["maxZ"] // 512 + 1):
                path = folder / f"r.{rx}.{rz}.mca"
                if not path.is_file():
                    continue
                regions.append(path)
                for chunk in chunks(path):
                    tiles.extend(
                        tile
                        for tile in chunk.get("block_entities", [])
                        if all(
                            box["min" + axis.upper()]
                            <= tile[axis]
                            <= box["max" + axis.upper()]
                            for axis in "xyz"
                        )
                    )
        cargo = [
            it
            for t in tiles
            if t["id"] == "minecraft:chest"
            for it in t.get("Items", [])
        ]
        ovens = [t for t in tiles if t["id"] == "minecraft:furnace"]
        contents = [it for t in ovens for it in t.get("Items", [])]
        disk[label] = {
            "blockEntities": len(tiles),
            "furnaces": len(ovens),
            "burningAtSave": sum(t.get("BurnTime", 0) > 0 for t in ovens),
            "diamonds": sum(
                it["Count"] for it in cargo if it["id"] == "minecraft:diamond"
            ),
            "ore": sum(
                it["Count"] for it in contents if it["id"] == "minecraft:iron_ore"
            ),
            "ingots": sum(
                it["Count"] for it in contents if it["id"] == "minecraft:iron_ingot"
            ),
        }
    check(
        disk["home"]["diamonds"] == shape["chests"] * 17
        and disk["home"]["ore"] + disk["home"]["ingots"] == shape["furnaces"] * 8
        and disk["home"]["ingots"] > 0
        and disk["home"]["blockEntities"] == shape["blockEntities"]
        and not any(disk["moon"].values()),
        "on-disk freight, machine progress or source clearance",
    )
    goods = {}
    for k in ("lunar", "home"):
        leg = f[k]
        check(
            leg["diamonds"] == shape["chests"] * 17
            and leg["furnaceItems"] == shape["furnaces"] * 8
            and leg["crewAlive"]
            and leg["original"] == f["lunar"]["original"],
            "cargo or player",
        )
        goods[k] = leg
    feet = {k: v for k, v in f.items() if isinstance(v, dict) and "gap" in v}
    check(
        len(feet) == 2
        and all(abs(d["gap"]) <= 0.075 and d["pilotHealth"] > 0 for d in feet.values()),
        "measured foot support on both decks",
    )
    pp = list((world / "playerdata").glob("*.dat"))
    check(
        len(pp) == 1 and nbt(pp[0])["Dimension"] == "minecraft:overworld",
        "saved pilot not at home",
    )
    stage = {
        k: {
            **stats(items),
            "topScreens": dict(Counter(r["startScreen"] for r in items).most_common(4)),
        }
        for k, items in phases.items()
    }
    boundary = [
        {
            "from": r["startPhase"],
            "to": r["endPhase"],
            "intervalMs": round((int(r["endNs"]) - int(r["startNs"])) / 1e6, 3),
        }
        for r in rows
        if r["startPhase"] != r["endPhase"]
    ]
    packed = HERE / "evidence"
    packed.mkdir(exist_ok=True)
    arch = packed / (run.name + ".zip")
    with ZipFile(arch, "w", ZIP_DEFLATED) as z:
        for name in (
            "integrated-server.json",
            "pilot-result.json",
            "frames.csv",
            "graphics.json",
            "input.csv",
            "options.txt",
            "launch-result.json",
        ):
            z.write(run / name, name)
        z.write(journal, "world/data/starbridge_passages.dat")
        z.write(vs_file, "world/data/vs_ship_data.dat")
        z.write(pp[0], "world/playerdata/" + pp[0].name)
        z.write(run / "inputs" / f"{n}.nbt", "inputs/" + str(n) + ".nbt")
        z.writestr(
            "server-save.txt",
            "\n".join(
                line
                for line in log.splitlines()
                if "All dimensions are saved" in line
                or "Running " in line
                and "ticks behind" in line
            ),
        )
    return {
        "run": run.name,
        "length": n,
        "pass": True,
        "graphics": graphics,
        "population": 1,
        "durationSecondsIncludingStartup": meta["seconds"],
        "nativeHull": shape,
        "originalRocket": f["lunar"]["original"],
        "restorationWallMs": round(server["restoreMs"], 3),
        "functionalRoundTripPassed": True,
        "stableThirdPersonView": {
            phase: stats(
                [
                    r
                    for r in rows
                    if r["startPhase"] == r["endPhase"] == phase
                    and r["startScreen"] == r["endScreen"] == "OrbitScreen"
                ]
            )
            for phase in ("outbound-space", "return-space")
        },
        "stableApproachInstrument": {
            phase: stats(
                [
                    r
                    for r in rows
                    if r["startPhase"] == r["endPhase"] == phase
                    and r["startScreen"] == r["endScreen"] == "OrbitScreen"
                ]
            )
            for phase in ("outbound-approach", "return-approach")
        },
        "renderIntervalsByPhase": stage,
        "allFlightFrames": stats([r for r in rows if r["startPhase"] in required]),
        "phaseTransitions": boundary,
        "clientDimensionChanges": dim_changes,
        "footing": feet,
        "cargo": goods,
        "persistedFreight": disk,
        "savedRegions": {pp.relative_to(run).as_posix(): digest(pp) for pp in regions},
        "fares": {k: f[k] for k in ["moonFare", "homeFare"]},
        "emptyDiskLedger": True,
        "savedPlayerDimension": "minecraft:overworld",
        "installed": meta["jars"],
        "sourceManifestSha256": digest(run / "inputs" / f"{n}.nbt"),
        "evidence": {
            "file": arch.relative_to(ROOT).as_posix(),
            "sha256": digest(arch),
            "framesSha256": digest(run / "frames.csv"),
        },
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    a = parser.parse_args()
    r = audit(a.run)
    (a.run / "audit.json").write_text(json.dumps(r, indent=2) + "\n", encoding="utf8")
    print(r["run"], r["allFlightFrames"])
