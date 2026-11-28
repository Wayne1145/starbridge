"""Verify cold starts and original terrain without exporting positions, bearings, or paths."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

from audit_moon import (
    ROOT,
    PAIRED,
    NATURAL,
    Terrain,
    cave_audit,
    observations,
    seed_tag,
    structure,
)
from cold_server import CASES, rewards, sha


def region_digest(world, dimension):
    # Region names can disclose locations. Retain only one commitment covering the entire directory.
    folder = world / "dimensions/retrofit" / dimension / "region"
    combined = hashlib.sha256()
    for file in sorted(folder.glob("*.mca")):
        combined.update(file.name.encode() + b"\0" + bytes.fromhex(sha(file)))
    return combined.hexdigest()


def read_json(file):
    return json.loads(file.read_bytes())


def verify(run, output):
    history = read_json(run / "cold-result.json")
    rows = {}
    for name, (world, dimension) in CASES.items():
        raw = history["cases"][name]
        dest = run / ("world-" + name)
        console = run / (name + "-console.log")
        log = console.read_text("utf8")
        native_before, _ = rewards(world, dimension)
        native_after, _ = rewards(dest, dimension)
        if (
            native_before != raw["before"]["rewards"]
            or native_after != raw["after"]["rewards"]
            or sha(console) != raw["consoleSha256"]
        ):
            raise AssertionError("Loot record or console hash mismatch")
        if not (
            "All dimensions are saved" in log
            and "Done (" in log
            and "forgeserver" in log
            and "nogui" in log
            and "joined the game" not in log
            and "Encountered an unexpected exception" not in log
        ):
            raise AssertionError("Expected a clean unoccupied dedicated start and save")
        acknowledged = len(set(re.findall(r"\[Server\] sb_dev_loaded_(\d+)_ok", log)))
        if (
            acknowledged != raw["loadedSiteChunks"]
            or not raw["originalUnchanged"]
            or raw["exit"] != 0
        ):
            raise AssertionError("Cold load did not acknowledge every requested chunk")
        if (
            sha(world / "level.dat") != raw["sourceLevelSha256"]
            or sha(dest / "level.dat") != raw["copyLevelSha256"]
        ):
            raise AssertionError("World changed since shutdown")

        def holdings(items):
            return [
                {key: value for key, value in item.items() if key != "chunkStatus"}
                for item in items
            ]

        if holdings(native_before) != holdings(native_after) or any(
            x["chunkStatus"] != "minecraft:full" for x in native_after
        ):
            raise AssertionError(
                "Original treasure did not persist in a fully loaded chunk"
            )
        row = {
            "sourceWorld": world.relative_to(ROOT).as_posix(),
            "loadedSiteChunks": acknowledged,
            "serverExit": raw["exit"],
            "allDimensionsSaved": True,
            "playersJoined": 0,
            "sourceLevelSha256": raw["sourceLevelSha256"],
            "coldLevelSha256": raw["copyLevelSha256"],
            "sourceRegionDigest": region_digest(world, dimension),
            "coldRegionDigest": region_digest(dest, dimension),
            "consoleSha256": sha(console),
            "rewardBefore": native_before,
            "rewardAfter": native_after,
        }
        if name in ("natural", "paired"):
            orig = Terrain(world)
            orig.find_masonry()
            a, _ = structure(orig)
            cold = Terrain(dest)
            cold.find_masonry()
            b, _ = structure(cold)
            for field in [
                "nativeMasonry",
                "exposedFloorColumns",
                "walkFromShaftBottomToBoss",
                "walkFromShaftBottomToTreasure",
                "ordinaryWalkFromLocalRimToShaftBottom",
            ]:
                if a[field] != b[field]:
                    raise AssertionError(
                        "Masonry or passage geometry changed after cold load"
                    )
            if not (
                b["exposedFloorColumns"] > 0
                and b["exposedFloorInFullChunks"]
                and b["walkFromShaftBottomToBoss"]
                and b["walkFromShaftBottomToTreasure"]
            ):
                raise AssertionError("No open shaft or supported route to both rooms")
            row["sourceGeometry"] = a
            row["coldGeometry"] = b
            if name == "natural":
                source_bosses = [
                    x for x in a["originalTiles"] if x["kind"] == "Dungeon Boss Spawner"
                ]
                cold_bosses = [
                    x for x in b["originalTiles"] if x["kind"] == "Dungeon Boss Spawner"
                ]
                if (
                    source_bosses
                    or len(cold_bosses) != 1
                    or cold_bosses[0]["bossClass"]
                    != "micdoodle8.mods.galacticraft.core.entities.EntitySkeletonBoss"
                ):
                    raise AssertionError(
                        "Expected the original boss tile to materialize on full chunk load"
                    )
        rows[name] = row
    cave_world = ROOT / "run-lunar-local-wide-recon-i/OrbitEngineer/saves/LunarTrail"
    terrain = Terrain(cave_world)
    terrain.find_masonry()
    report, opening = structure(terrain)
    route = observations(opening)
    cave = cave_audit(terrain)
    if not (
        report["walkFromShaftBottomToBoss"]
        and report["walkFromShaftBottomToTreasure"]
        and not report["bossAccessibleTreatingWebsAsWalls"]
    ):
        raise AssertionError(
            "Web collision control did not discriminate between the two models"
        )
    if (
        not cave["startUnderNaturalRoof"]
        or cave["coveredAirBordersMasonry"]
        or cave["coveredAirTouchedSearchBoundary"]
        or cave["coveredAirTouchedUnknown"]
    ):
        raise AssertionError("Cave connectivity is inconclusive")
    evidence = hashlib.sha256()
    cameras = rays = 0
    hits = 0
    confirmed = False
    for tag in ("transect-h", "wide-recon-i", "near-wall-j"):
        player = ROOT / ("run-lunar-local-" + tag) / "OrbitEngineer"
        for f in sorted(player.glob("field-eye-*.json")):
            evidence.update(bytes.fromhex(sha(f)))
            view = read_json(f)
            cameras += 1
            state = view.get("visibleBlock")
            if not isinstance(state, str):
                continue
            rays += 1
            hits += int("moonblock" in state and "meta=14" in state)
        confirmed |= (
            (player / "field-visible-entrance.json").is_file()
            or (player / "field-visible-dungeon-brick.json").is_file()
            or (player / "screenshots/field-visible-dungeon-brick.png").is_file()
        )
    natural_notes = read_json(ROOT / "docs/lunar-natural-search-verification.json")
    confirmed |= bool(natural_notes["outcomes"]["naturalDungeonConfirmed"])
    if hits or confirmed or not route["anyRecordedPositionInRange"]:
        raise AssertionError("Check player discovery evidence afresh")
    paired_run = PAIRED.parent / "flight-result.json"
    play = read_json(paired_run)
    if not (
        play["pass"]
        and play["originalBossAndDroppedKey"]
        and play["bothAliveAboard"]
        and play["returnLoot"] > 0
    ):
        raise AssertionError("Missing previous two-person encounter evidence")
    experiments = {}
    for name in ("ceres", "venus"):
        file = CASES[name][0].parent / "encounter-result.json"
        case = read_json(file)
        if not (
            case["pass"]
            and case["observedDroppedKey"]
            and any(
                not i["locked"] and i["tier"] == 4 and i["occupiedSlots"]
                for i in rows[name]["rewardAfter"]
            )
        ):
            raise AssertionError("Native expansion reward was missing")
        experiments[name] = {
            "caseSha256": sha(file),
            "originalDroppedKeyObservedPreviously": True,
            "passedPreviously": True,
        }
    result = {
        "date": "2026-11-22",
        "build": "a90ea7c / Actions 44969267385",
        "minecraft": "1.20.1",
        "forge": "47.4.28",
        "jdk": 17,
        "observationsOnly": True,
        "fullPackFilesMatched": len(history["installedModHashes"]),
        "rawColdReportSha256": sha(run / "cold-result.json"),
        "worldsHaveDifferentSeeds": seed_tag(PAIRED) != seed_tag(NATURAL),
        "coldRestarts": rows,
        "naturalExpedition": {
            "checkpointsAssessed": 3,
            "uniqueRecordedPositions": route["distinctRecordedPositions"],
            "configuredRenderDistanceChunks": sorted(
                {x["renderDistanceChunks"] for x in route["checkpoints"]}
            ),
            "anyRecordedPositionWithinEntranceChunkTrackingRange": route[
                "anyRecordedPositionInRange"
            ],
            "cameraObservations": cameras,
            "observationsWithRecordedBlock": rays,
            "confirmedOriginalMasonryRays": hits,
            "playerDiscoveryObserved": confirmed,
            "cameraEvidenceDigest": evidence.hexdigest(),
            "preExcavationCave": cave,
            "caveSourceRegionDigest": region_digest(cave_world, "legacy_dim_-28"),
        },
        "priorGameplay": {
            "pairedFlightResultSha256": sha(paired_run),
            "paidRoundtripAndNativeBossDropObservedPreviously": True,
            "originalRewardInEarlierFlight": play["originalReward"],
            "remainingFreightOnReturn": play["returnLoot"],
            "expansions": experiments,
        },
        "limits": [
            "Chunk tracking range does not demonstrate a direct sightline or that the player recognized the entrance.",
            "Passage geometry uses supported feet, two blocks of clearance, single-block ascent and descents of up to three blocks. Cobwebs allow passage with reduced speed.",
            "The rim check is local. Safe survival descent with lunar gravity was not played in this audit.",
            "The cave connectivity check excludes routes across the open surface or through newly mined blocks.",
        ],
    }
    if not result["worldsHaveDifferentSeeds"]:
        raise AssertionError("Check the two archived world identities")
    output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf8"
    )
    print(
        "Verified four clean cold starts and the aggregate natural expedition evidence."
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("run", type=Path)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    verify(args.run, args.output)
