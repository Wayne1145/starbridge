"""Recheck the saved surface camp and its first original machine against the frozen campaign recordings."""

import argparse
from pathlib import Path

from inspect_checkpoint import ROLES, read_json, read_nbt, sample_blocks, sha
from upstream_runtime_region import chunks


def verify(report: Path) -> None:
    record = read_json(report)
    root = Path(__file__).resolve().parents[2]
    episode = root / record["world"] / "recordings" / record["episode"]
    snapshot = episode / "world-evidence"
    region = snapshot / "r.-1.-1.mca"
    for path, expected in record["rawSha256"].items():
        if sha(root / path) != expected:
            raise ValueError(f"Changed raw evidence: {path}")
    assert read_nbt(snapshot / "level.dat")["Data"]["allowCommands"] == 0
    assert read_json(snapshot / "ops.json") == []
    for uuid in ROLES.values():
        assert read_nbt(episode / "playerdata" / f"{uuid}.dat")["playerGameType"] == 0

    x, y, z = -88, 64, -18
    shell = {
        (x + dx, y + height, z + dz)
        for dx in range(-2, 3)
        for dz in range(-2, 3)
        for height in range(3)
        if height == 2 or max(abs(dx), abs(dz)) == 2
    }
    doors = {(x, y, z - 2), (x, y + 1, z - 2)}
    indoor_chest, outside_chest, bench = (-89, 64, -18), (-81, 64, -23), (-87, 64, -19)
    positions = shell | {indoor_chest, outside_chest, bench}
    steps = []
    if "stairs" in record:
        steps = [(-93 - n, 64 - n, -18) for n in range(1, 53)]
        positions.update(steps)
        positions.update((sx, sy + 1, sz) for sx, sy, sz in steps)
    block = sample_blocks(region, positions)
    assert len(shell - doors) == 55 and all(block[p] == "minecraft:birch_planks" for p in shell - doors)
    assert all(block[p] == "minecraft:birch_door" for p in doors)
    assert block[indoor_chest] == block[outside_chest] == "minecraft:chest"
    assert block[bench] == "minecraft:crafting_table"

    boxes = {
        (tile["x"], tile["y"], tile["z"]): {stack["id"]: stack["Count"] for stack in tile["Items"]}
        for chunk in chunks(region)
        for tile in chunk.get("block_entities", [])
        if tile.get("id") == "minecraft:chest" and (tile["x"], tile["y"], tile["z"]) in (indoor_chest, outside_chest)
    }
    if steps:
        contents = boxes[indoor_chest]
        assert contents["advancedrocketry:tile.blockhandpress"] == 1
        assert contents["minecraft:redstone"] == 7
        clear = {"minecraft:air", "minecraft:cave_air"}
        assert all(block[p] in clear | {"minecraft:torch"} for p in steps)
        assert all(block[sx, sy + 1, sz] in clear for sx, sy, sz in steps)
        assert [n for n, pos in enumerate(steps, 1) if block[pos] == "minecraft:torch"] == record["stairs"]["torchSteps"]
        events = (episode / "server/survival-events.log").read_text(encoding="utf8")
        assert "OrbitEngineer crafted 1 piston" in events and "OrbitEngineer crafted 1 tile.blockhandpress" in events
    else:
        assert boxes[outside_chest] == record["storage"][str(outside_chest)]
        assert boxes[indoor_chest] == record["storage"][str(indoor_chest)]
    print(f"{record['episode']}: camp match, {len(record['rawSha256'])} raw hashes match")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, nargs="+")
    for report in parser.parse_args().report:
        verify(report)
