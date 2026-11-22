"""Compare native construction, GUI work, both players, and every occupied return block on disk."""

import argparse
import hashlib
import json
import math
import sys
import uuid
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from yard import HERE, ROOT, sha

sys.path.insert(0, str(ROOT / "qa/integrated-fleet"))
import audit as baseline

sys.path.insert(0, str(ROOT / "qa/original-flight"))
from inspect_deck import SavedColumns
from upstream_runtime_region import chunks


def load(p):
    return json.loads(p.read_bytes())


def flight_events(log):
    # Archive observed game events. Launcher arguments and unrelated local settings stay on disk.
    keys = (
        " logged in with entity id ",
        "[Starbridge]",
        "Native portal ",
        "landed on the Galacticraft Moon",
        "No support",
        "Timed out waiting for chunks",
        "moved while colliding with unloaded ships",
        "从高处摔了下来",
        "fell from a high place",
        "hit the ground too hard",
        "All dimensions are saved",
    )
    return (
        "\n".join(
            line
            for line in log.read_text(encoding="utf8", errors="replace").splitlines()
            if "--" not in line and any(key in line for key in keys)
        )
        + "\n"
    )


def bpos(p):
    def a(k, n):
        return ((k + 2 ** (n - 1)) % 2**n) - 2 ** (n - 1)

    return a(p >> 38, 26), a(p, 12), a(p >> 12, 26)


def plus(a, b):
    return tuple(x + y for x, y in zip(a, b))


def minus(a, b):
    return tuple(x - y for x, y in zip(a, b))


def uuid_words(words):
    return str(
        uuid.UUID(bytes=b"".join((v & 0xFFFFFFFF).to_bytes(4, "big") for v in words))
    )


def verify(run):
    meta = load(run / "launch-result.json")
    yard = HERE / ".local" / meta["producer"]
    raw = load(yard / "loaded-dock-result.json")
    n = raw["length"]
    source = ROOT / meta["producerManifest"]
    native = baseline.nbt(source)
    hull = native["Hull"]
    origin = bpos(hull["Bridge"])
    lo = bpos(hull["Min"])
    hi = bpos(hull["Max"])
    extent = tuple(b - a + 1 for a, b in zip(lo, hi))
    assert (
        raw["passed"]
        and raw["nativeStatus"] == "SUCCESS"
        and raw["pilotAboard"]
        and raw["thrust"] > raw["occupiedBlocks"]
        and extent == (19, 8, n)
    )
    assert (
        uuid_words(native["Rocket"]) == raw["rocket"]
        and uuid_words(hull["Core"]) == raw["core"]
        and meta["sourceManifestSha256"]
        == sha(source)
        == sha(run / "inputs" / f"{n}.nbt")
    )
    baseline.HERE = HERE
    result = baseline.audit(run)
    f = load(run / "integrated-server.json")["flight"]
    assert result["originalRocket"] == raw["rocket"] and result["population"] == 2
    assert all(
        f[k]["width"] == extent[0]
        and f[k]["height"] == extent[1]
        and f[k]["length"] == extent[2]
        and f[k]["core"] == raw["core"]
        and f[k]["blocks"] == raw["occupiedManifestBlocks"]
        and f[k]["blockEntities"] == raw["packedBlockEntities"]
        for k in ("moonHull", "homeHull")
    )
    result["nativeHull"].update(width=extent[0], height=extent[1])
    world = run / "saves" / f"IntegratedOrbit{n}"
    vs = json.loads(
        baseline.nbt(world / "data/vs_ship_data.dat")["data"]["vs_pipeline"]
    )
    body = next(x for x in vs["ships"] if x["id"] == f["home"]["shipId"])
    box = body["shipAABB"]
    region = world / "region"
    tiles = []
    for rx in range(box["minX"] // 512, box["maxX"] // 512 + 1):
        for rz in range(box["minZ"] // 512, box["maxZ"] // 512 + 1):
            path = region / f"r.{rx}.{rz}.mca"
            if not path.exists():
                continue
            for chunk in chunks(path):
                for t in chunk.get("block_entities", []):
                    if all(
                        box["min" + a.upper()] <= t[a] <= box["max" + a.upper()]
                        for a in "xyz"
                    ):
                        tiles.append(t)
    cores = [t for t in tiles if t["id"] == "starbridge:ship_core"]
    assert len(cores) == 1, [t["id"] for t in tiles[:10]]
    received = tuple(cores[0][v] for v in "xyz")
    offset = minus(received, origin)
    saved = SavedColumns(region)
    original_cells = {bpos(r["P"]): r["State"]["Name"] for r in hull["Cells"]}

    def shape_digest(cells):
        return hashlib.sha256(
            "\n".join(
                f"{x},{y},{z} {name}" for (x, y, z), name in sorted(cells.items())
            ).encode()
        ).hexdigest()

    stored_cells = {}
    for y in range(lo[1], hi[1] + 1):
        for z in range(lo[2], hi[2] + 1):
            for x in range(lo[0], hi[0] + 1):
                src = (x, y, z)
                name = saved.block(*plus(src, offset))
                if name != "minecraft:air":
                    stored_cells[src] = name
    assert stored_cells == original_cells, [
        (p, original_cells.get(p), stored_cells.get(p))
        for p in set(original_cells) | set(stored_cells)
        if original_cells.get(p) != stored_cells.get(p)
    ][:16]

    def workstation(regid, delta):
        rows = [t for t in tiles if t["id"] == regid]
        assert len(rows) == 1 and tuple(rows[0][a] for a in "xyz") == plus(
            received, delta
        )
        return rows[0]

    barrel = workstation("minecraft:barrel", (3, 0, 2))
    blast = workstation("minecraft:blast_furnace", (3, -3, 7))
    assert [(i["id"], i["Count"]) for i in barrel["Items"]] == [
        ("minecraft:copper_ingot", 1)
    ]
    assert all(i["id"] == "minecraft:coal" for i in blast.get("Items", []))
    job = load(run / "wide-job-verified.json")
    steps = load(run / "visitor/wide-engineer-complete.json")
    assert (
        job["rocket"] == raw["rocket"]
        and job["core"] == raw["core"]
        and job["ingotsInUpperBarrel"] == 1
        and job["inputConsumed"]
    )
    assert (
        job["upperBarrelScreen"]
        < job["lowerBlastFurnaceScreen"]
        <= job["oreInLowerFurnace"]
        < job["ingotCarried"]
        < job["orbitEnd"]
    )
    assert all(
        all("galaxyspace:" in part for part in suit) for suit in job["suits"].values()
    ), job["suits"]
    assert len(steps) == 13 and len(job["positions"]) > 35
    positions = [r["position"] for r in job["positions"]]
    max_step = max(math.dist(a, b) for a, b in zip(positions, positions[1:]))
    assert (
        max_step < 1.7
        and max(p[1] for p in positions) - min(p[1] for p in positions) >= 2.95
    ), (max_step, positions[:2])
    for mod in (
        "ExtraPlanets-1.7.10-2.1.4.jar",
        "GalaxySpace-1.7.10-1.2.15.jar",
        "Galacticraft-1.7-3.0.12.504.jar",
        "AdvancedRocketry-1.7.10-1.4.0-32.jar",
    ):
        assert (
            meta["jars"][mod]
            == load(yard / "installed.json")[mod]
            == sha(run / "visitor/retrofit/mods" / mod)
        )
    proof = {
        "originalRocket": raw["rocket"],
        "core": raw["core"],
        "sourceManifest": source.relative_to(ROOT).as_posix(),
        "sourceSha256": sha(source),
        "sourceBlocks": len(original_cells),
        "returnedBlocks": len(stored_cells),
        "sourceBlockNamesSha256": shape_digest(original_cells),
        "returnedBlockNamesSha256": shape_digest(stored_cells),
        "fullBoxCellsChecked": extent[0] * extent[1] * extent[2],
        "upperReturnedBarrel": barrel,
        "lowerReturnedBlastFurnace": blast,
        "playerId": job["crew"],
        "captainId": job["captain"],
        "walkedVerticalBlocks": round(
            max(p[1] for p in positions) - min(p[1] for p in positions), 3
        ),
        "worstTwoTickStep": round(max_step, 3),
        "workWindowServerTicks": job["orbitEnd"] - job["orbitStart"],
        "fullOriginalsHashes": True,
    }
    (run / "shape-and-cargo.json").write_text(
        json.dumps(proof, indent=2) + "\n", encoding="utf8"
    )
    archive = HERE / "evidence" / (run.name + ".zip")
    with ZipFile(archive, "a", ZIP_DEFLATED) as z:
        for name in (
            "shape-and-cargo.json",
            "wide-job-verified.json",
            "visitor/wide-engineer-log.jsonl",
            "visitor/wide-engineer-complete.json",
        ):
            z.write(run / name, name)
        # VS assigns a new storage slot when the parked lunar hull becomes a cabin again.
        # A check at only the final home coordinates misses the original lunar landing slot.
        region_paths = {run / member for member in result["savedRegions"]}
        moon_regions = world / "dimensions/retrofit/legacy_dim_-28/region"
        stamps = {f[leg]["leg"] for leg in ("moonFare", "homeFare")}
        for path in sorted(moon_regions.glob("r.*.*.mca")):
            if any(
                "starbridge:passage_checkpoint" in ch
                and uuid_words(ch["starbridge:passage_checkpoint"]) in stamps
                for ch in chunks(path)
            ):
                region_paths.add(path)
        result["savedRegions"] = {}
        for path in sorted(region_paths):
            z.write(path, "world/" + path.relative_to(world).as_posix())
            result["savedRegions"][path.relative_to(run).as_posix()] = sha(path)
        z.write(world / "level.dat", "world/level.dat")
        for name, member in (
            ("logs/latest.log", "host-flight.log"),
            ("visitor/logs/latest.log", "visitor/guest-flight.log"),
        ):
            z.writestr(member, flight_events(run / name))
        z.write(run / "options.txt", "host-options.txt")
        for name in ("wide-hull-cruise.png", "wide-barrel.png", "wide-furnace.png"):
            image = (
                (run if name == "wide-hull-cruise.png" else run / "visitor")
                / "screenshots"
                / name
            )
            if image.is_file():
                z.write(image, "screenshots/" + name)
        if (run / "wide-landing-ui.jsonl").is_file():
            z.write(run / "wide-landing-ui.jsonl", "wide-landing-ui.jsonl")
            for image in sorted((run / "screenshots").glob("wide-landing-*.png")):
                z.write(image, "screenshots/" + image.name)
        for name in ("loaded-dock-result.json", "installed.json"):
            z.write(yard / name, "native-shipyard/" + name)
        for config in (
            "retrofit/config/GalaxySpace/core.conf",
            "config/ExtraPlanets.cfg",
        ):
            if (run / config).exists():
                z.write(run / config, "configuration/" + (run / config).name)
    result.update(
        shapeAndCargo=proof,
        sourceLaunch=raw,
        serverJob={k: v for k, v in job.items() if k != "positions"},
        workstationInputs=steps,
    )
    result["evidence"]["sha256"] = sha(archive)
    (run / "wide-audit.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf8"
    )
    print(
        run.name,
        len(original_cells),
        proof["walkedVerticalBlocks"],
        result["stableThirdPersonView"]["return-space"],
        flush=True,
    )
    return result


if __name__ == "__main__":
    a = argparse.ArgumentParser(description=__doc__)
    a.add_argument("run", type=Path)
    verify(a.parse_args().run.resolve())
