"""Print only the seven garden counts from the project's already sealed QA checkpoint."""

from hashlib import sha256
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "qa/survival-expedition"))
from inspect_checkpoint import read_nbt, ROLES
from upstream_runtime_region import chunks


def inspect():
    garden = json.loads(
        (ROOT / "qa/survival-expedition/survival-tin-verification.json").read_bytes()
    )["gardenCheckpoint"]
    record = ROOT / "run-survival-expedition-a1/recordings" / garden["episode"]
    region = record / "world-evidence/r.-1.-1.mca"
    character = record / "playerdata" / (ROLES["OrbitEngineer"] + ".dat")
    level = record / "world-evidence/level.dat"
    for path in (region, character, level):
        assert (
            sha256(path.read_bytes()).hexdigest()
            == garden["rawSha256"][path.relative_to(ROOT).as_posix()]
        )
    player = read_nbt(character)
    chunk = next(c for c in chunks(region) if (c["xPos"], c["zPos"]) == (-6, -2))
    chest = next(
        tile
        for tile in chunk["block_entities"]
        if (tile["x"], tile["y"], tile["z"]) == (-89, 64, -18)
    )

    def block(x, y, z):
        section = next(
            s["block_states"] for s in chunk["sections"] if s["Y"] == y // 16
        )
        palette = section["palette"]
        if len(palette) == 1:
            return palette[0]
        bits = max(4, (len(palette) - 1).bit_length())
        index = (y % 16) * 256 + (z % 16) * 16 + x % 16
        per_word = 64 // bits
        return palette[
            (section["data"][index // per_word] >> (bits * (index % per_word)))
            & ((1 << bits) - 1)
        ]

    def count(items, name):
        return sum(item["Count"] for item in items if item["id"] == "minecraft:" + name)

    assert block(-87, 69, -18)["Name"] == block(-89, 69, -18)["Name"] == "minecraft:air"
    assert (
        block(-87, 68, -18)["Name"]
        == block(-89, 68, -18)["Name"]
        == "minecraft:sugar_cane"
    )
    assert block(-88, 67, -18)["Name"] == "minecraft:water"
    assert not any(
        item["id"]
        in ("minecraft:sugar_cane", "minecraft:paper", "starbridge:navigation_slate")
        for item in player["Inventory"] + chest["Items"]
    )
    return {
        "eastAge": int(block(-87, 68, -18)["Properties"]["age"]),
        "westAge": int(block(-89, 68, -18)["Properties"]["age"]),
        "iron": count(player["Inventory"], "iron_ingot"),
        "redstone": count(player["Inventory"], "redstone"),
        "compass": count(chest["Items"], "compass"),
        "panes": count(chest["Items"], "glass_pane"),
        "randomTickSpeed": int(read_nbt(level)["Data"]["GameRules"]["randomTickSpeed"]),
    }


if __name__ == "__main__":
    actual = inspect()
    expected = json.loads(Path(__file__).with_name("checkpoint.json").read_bytes())
    if actual != expected:
        raise SystemExit(
            "The seven published numbers differ from the sealed test checkpoint"
        )
    print(json.dumps(actual, indent=2))
