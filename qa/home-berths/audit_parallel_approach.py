"""Read both wide hulls and their simulated crews back from stopped lunar and home regions."""
import argparse
import json
from pathlib import Path
from uuid import UUID

from audit_wide_transfer import HOME, MOON, collect, digest, nbt, summary
from audit_wide_approach import relative, require
from upstream_runtime_region import chunks


def ships(world):
    pipeline = nbt(world / "data/vs_ship_data.dat")["data"]["vs_pipeline"]
    return {ship["id"]: ship for ship in json.loads(pipeline)["ships"]}


def bounds(ship):
    return [ship["shipAABB"][prefix + axis] for prefix in ("min", "max") for axis in "XYZ"]


def uuid(tile, key):
    return str(UUID(bytes=b"".join((word & 0xffffffff).to_bytes(4, "big") for word in tile[key])))


def audit(source, result):
    initial = json.loads((source / "large-flight-audit.json").read_bytes())
    trial = json.loads((result / "home-berth-result.json").read_bytes())
    profile = json.loads((result / "landing-profile.json").read_bytes())
    log = result / "logs/latest.log"
    require(trial["pass"] and trial["players"] == trial["simulatedPilots"] == 2 and trial["clientProcesses"] == 0,
            "Expected a dedicated trial with two simulated pilots")
    require("All dimensions are saved" in log.read_text("utf8", errors="replace"), "Both worlds must be saved")
    before, after = source / "world", result / "world"
    old = bounds(ships(before)[initial["flight"]["home"]["shipId"]])
    original = collect(before, HOME, old)
    native = ships(after)
    arrivals = trial["arrivals"]
    require(len(arrivals) == 2 and all(len({ship[key] for ship in arrivals}) == 2 for key in ("core", "rocket", "pilot", "ship", "fareReceipt")),
            "Two distinct ships, captains and receipts")
    require((arrivals[0]["core"], arrivals[0]["rocket"]) == (initial["core"], initial["original"]), "Original ship identity")
    landed = []
    for arrival in arrivals:
        ship = native[arrival["ship"]]
        require(ship["chunkClaimDimension"] == "minecraft:dimension:retrofit:legacy_dim_-28", "Ship remains on original Moon")
        target = bounds(ship)
        cargo = collect(after, MOON, target)
        for source_map, target_map in zip(original[:2], cargo[:2]):
            require(relative(source_map, old[:3]) == relative(target_map, target[:3]), "Full block and machine positions survived")
        x, y, z = arrival["corePosition"]
        chest = (x - target[0], y - 3 - target[1], z - 1 - target[2])
        slots = relative(original[2], old[:3])
        slots[chest] = sorted([dict(s, Slot=26, Count=arrival["diamonds"]) if s["Slot"] == 0 else s for s in slots[chest]], key=lambda s: s["Slot"])
        require(slots == relative(cargo[2], target[:3]), "All saved inventory slots survived the transfer")
        require(not any(collect(after, HOME, target)[:3]), "No duplicate ship remains in source storage")
        region = after / MOON / f"r.{x // 512}.{z // 512}.mca"
        core, = (tile for chunk in chunks(region) for tile in chunk.get("block_entities", []) if [tile[k] for k in "xyz"] == [x, y, z])
        require((uuid(core, "CoreId"), uuid(core, "OriginalRocket"), uuid(core, "PaidVoyage")) ==
                (arrival["core"], arrival["rocket"], arrival["fareReceipt"]), "Original hull and receipt match stopped data")
        require(core["Energy"] == arrival["postFare"] and arrival["preFare"] - core["Energy"] == 12_000, "Exact per-hull fare")
        player_file = after / "playerdata" / (arrival["pilot"] + ".dat")
        captain = nbt(player_file)
        require(captain["Dimension"] == "retrofit:legacy_dim_-28" and captain["playerGameType"] == 0 and captain["Health"] > 0,
                "Original living survival captain is on the Moon")
        landed.append({**arrival, "bounds": target, "hull": summary(cargo), "captainFeet": captain["Pos"], "playerSha256": digest(player_file)})
    require(not any(collect(after, HOME, old)[:3]) and nbt(after / "data/starbridge_passages.dat")["Legs"] == [],
            "Original source storage and passage journal cleared")
    assemblies = [row for row in profile["intervals"] if row["phase"] == "nativeAssembly"]
    assembly_ticks = [row["tick"] for row in assemblies]
    if "retainedSurfaceChunks" in trial:
        require(trial["serverActions"] == ["SURVEY", "CLEAR", "LAND"]
                and set(trial["retainedSurfaceChunks"]) == {a["core"] for a in arrivals}
                and all(v > 0 for v in trial["retainedSurfaceChunks"].values()), "Both confirmed surfaces were retained")
        require(len(assembly_ticks) == 2 and trial["confirmationTick"] < assembly_ticks[0] < assembly_ticks[1],
                "The two new confirmations were processed on separate ticks")
    else:
        require(assembly_ticks == [trial["confirmationTick"]] * 2, "Both assemblies ran on the requested tick")
    overlap = trial.get("simultaneousStorageWarmTicks")
    if overlap is not None:
        require(overlap > 0, "Both ships must have active physical storage tickets at the same time")
        transfers = [row["tick"] for row in profile["intervals"] if row["phase"] == "beginPassage"]
        require(len(transfers) == 2 and transfers[0] < transfers[1], "Native portals still consume separate ticks")
    return {"serverResultSha256": digest(result / "home-berth-result.json"), "timingsSha256": digest(result / "landing-profile.json"),
            **({"simultaneousStorageWarmTicks": overlap} if overlap is not None else {}),
            "serverLogSha256": digest(log), "confirmationTick": trial["confirmationTick"], "assemblyTicks": assembly_ticks,
            "retainedSurfaceChunks": trial.get("retainedSurfaceChunks"),
            "assemblyMs": [row["ns"] / 1_000_000 for row in assemblies],
            "passages": [row for row in profile["intervals"] if row["phase"] in ("beginPassage", "selectBerth", "nativePortal")],
            "chunkReadiness": profile["footprints"], "landingTick": trial["settledAtTick"], "landed": landed}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("result", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(audit(args.source, args.result), indent=2) + "\n"
    if args.output:
        args.output.write_text(result, encoding="utf8")
    else:
        print(result, end="")
