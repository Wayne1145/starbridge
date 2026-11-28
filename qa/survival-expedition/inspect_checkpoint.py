"""Verify early survival progress against an immutable snapshot taken after the server saved."""

import argparse
import gzip
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from upstream_runtime_region import chunks, read_compound  # noqa: E402 - repository-local reader

ROLES = {
    "OrbitCaptain": "2fa55aec-b98c-3df9-bace-2b93f661d709",
    "OrbitEngineer": "ccccf009-cdfd-3c33-9009-4aa05c0d5e46",
}
PLACEMENT = re.compile(r"Observed placed (\S+) at (-?\d+), (-?\d+), (-?\d+)")


def read_json(path):
    return json.loads(path.read_bytes())


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def read_nbt(path):
    raw = path.read_bytes()
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    return read_compound(raw)


def region_name(x, z):
    return f"r.{x // 512}.{z // 512}.mca"


def sample_blocks(region, positions):
    states = {}
    for chunk in chunks(region):
        for x, y, z in positions:
            if (x // 16, z // 16) != (chunk["xPos"], chunk["zPos"]):
                continue
            section = next(s for s in chunk["sections"] if s["Y"] == y // 16)
            block_states = section["block_states"]
            palette = block_states["palette"]
            cell_index = (y % 16) * 256 + (z % 16) * 16 + (x % 16)
            bits = max(4, (len(palette) - 1).bit_length())
            per_word = 64 // bits
            word = (
                block_states.get("data", [0])[cell_index // per_word]
                if len(palette) > 1
                else 0
            )
            palette_index = (word >> (cell_index % per_word * bits)) & ((1 << bits) - 1)
            states[(x, y, z)] = palette[palette_index]["Name"]
    return states


def capture(world, episode, regions):
    if not (episode / "end.json").is_file():
        raise ValueError(
            "Wait for the launcher to close and the server to finish saving first"
        )
    dest = episode / "world-evidence"
    dest.mkdir(exist_ok=False)
    for name in sorted(regions):
        shutil.copyfile(world / "region" / name, dest / name)
    shutil.copyfile(world / "level.dat", dest / "level.dat")
    shutil.copyfile(world.parent / "ops.json", dest / "ops.json")


def inspect(episode, save_world=False):
    instance = episode.parents[1]
    snap = episode / "world-evidence"
    events, observed, first, files = {}, {}, {}, set()
    for role, uuid in ROLES.items():
        log = episode / role / "survival-events.log"
        files.update(
            [
                log,
                episode / role / "expedition-orders.txt",
                episode / role / "expedition-cursor.txt",
            ]
        )
        events[role] = log.read_text(encoding="utf-8").splitlines()
        observed[role] = [
            (name, (int(x), int(y), int(z)))
            for name, x, y, z in PLACEMENT.findall("\n".join(events[role]))
        ]
        individual = episode / "server" / f"first-join-{uuid}.json"
        if not individual.exists() and role == "OrbitCaptain":
            individual = episode / "server/first-join.json"
        first[role] = read_json(individual)
        files.add(individual)
    positions = {pos for role in observed.values() for _, pos in role}
    region_names = {region_name(x, z) for x, _, z in positions}
    if save_world:
        capture(instance / "server/world", episode, region_names)
    seen = {}
    for name in sorted(region_names):
        filename = snap / name
        files.add(filename)
        seen.update(sample_blocks(filename, positions))
    placements = {
        role: [
            dict(
                item=item,
                position=pos,
                inSavedWorld=seen.get(pos),
                match=seen.get(pos) == item,
            )
            for item, pos in history
        ]
        for role, history in observed.items()
    }
    saved_players = {}
    for name, uuid in ROLES.items():
        filename = episode / "playerdata" / (uuid + ".dat")
        data = read_nbt(filename)
        saved_players[name] = {
            "gameMode": data["playerGameType"],
            "world": data["Dimension"],
            "health": data["Health"],
            "inventory": [(s["id"], s["Count"]) for s in data["Inventory"]],
        }
        files.add(filename)
    server_events_file = episode / "server/survival-events.log"
    server_events = server_events_file.read_text(encoding="utf-8")
    level = read_nbt(snap / "level.dat")["Data"]
    checks = {
        "bothStartedWithEmptyInventories": all(
            p["items"] == {}
            and not p["op"]
            and not p["creative"]
            and not p["allowCommands"]
            for p in first.values()
        ),
        "cheatsOffOnDisk": level["allowCommands"] == 0
        and read_json(snap / "ops.json") == [],
        "bothSurvivalOnDisk": all(p["gameMode"] == 0 for p in saved_players.values()),
        "captainCraftedConcrete": "OrbitCaptain crafted 16 tile.concrete"
        in server_events,
        "engineerCraftedSeat": "OrbitEngineer crafted 1 seat" in server_events,
        "savedOriginalConcrete": any(
            v["match"] and v["item"] == "advancedrocketry:tile.concrete"
            for v in placements["OrbitCaptain"]
        ),
        "savedOriginalSeat": any(
            v["match"] and v["item"] == "advancedrocketry:seat"
            for v in placements["OrbitEngineer"]
        ),
        "serverSaved": "All dimensions are saved"
        in (episode / "server.log").read_text(encoding="utf-8", errors="replace"),
    }
    files.update(
        [
            snap / "level.dat",
            snap / "ops.json",
            server_events_file,
            episode / "server.log",
            episode / "installed.json",
            episode / "end.json",
            episode / "server/survival-recipes.json",
        ]
    )
    return {
        "version": "0.3.0-dev",
        "runtime": "CI run 44947273292 in a Forge 47.4.28 dedicated server, with its original mods and expansions and two rendered non-op survival clients",
        "scope": "First original construction materials. This checkpoint ends before ship assembly, a lunar flight or a dungeon.",
        "world": instance.name,
        "episode": episode.name,
        "checkpointsPassed": all(checks.values()),
        "checks": checks,
        "firstJoin": first,
        "onDisk": saved_players,
        "placements": placements,
        "worldTime": level["Time"],
        "deaths": {
            role: sum(
                line.startswith("Used ordinary death screen Respawn") for line in lines
            )
            for role, lines in events.items()
        },
        "rawSha256": {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(files)},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("episode", type=Path)
    parser.add_argument(
        "--capture",
        action="store_true",
        help="Copy the relevant world files once after clean shutdown",
    )
    parser.add_argument("--out", type=Path, required=True)
    opts = parser.parse_args()
    result = inspect(opts.episode.resolve(), opts.capture)
    opts.out.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        "Checkpoint:",
        result["checkpointsPassed"],
        "raw hashes:",
        len(result["rawSha256"]),
    )
    raise SystemExit(0 if result["checkpointsPassed"] else 1)
