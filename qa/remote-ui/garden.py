"""Public garden data and redacted results from a fresh, generated integrated world."""

import gzip
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from upstream_runtime_region import chunks, read_compound

CHECKPOINT = ROOT / "qa/survival-expedition/hosted-garden/checkpoint.json"
INPUTS = {
    "eastAge": 12,
    "westAge": 4,
    "iron": 7,
    "redstone": 6,
    "compass": 1,
    "panes": 16,
    "randomTickSpeed": 3,
}


def original_inputs():
    data = json.loads(CHECKPOINT.read_bytes())
    if data != INPUTS or any(type(number) is not int for number in data.values()):
        raise ValueError(
            "The garden input must contain only the seven audited integer measurements"
        )
    return data


def verify_crop(start, growth, server):
    if (
        start.get("inputs") != original_inputs()
        or start.get("initialInventoryEmpty") is not True
        or start.get("upperSpacesEmpty") is not True
    ):
        raise ValueError("Unexpected starting garden")
    if (
        not (growth.get("east") is True or growth.get("west") is True)
        or growth.get("randomTickSpeed") != 3
    ):
        raise ValueError("A new upper stem and normal random ticks are required")
    planted = start.get("plantedTick")
    observed = growth.get("grewAtTick")
    if (
        type(planted) is not int
        or growth.get("plantedTick") != planted
        or type(observed) is not int
        or observed <= planted
    ):
        raise ValueError(
            "The growing crop must be observed after the initial empty upper spaces"
        )
    if server != {
        "eastCut": True,
        "westCut": True,
        "craftedPaper": 3,
        "craftedTerminal": 1,
        "stored": 1,
    }:
        raise ValueError(
            "Expected both server break events, exactly three paper, one crafted terminal and one deposit"
        )
    return {
        "ageAtStart": [start["inputs"]["eastAge"], start["inputs"]["westAge"]],
        "grewAfterWorldTicks": observed - planted,
        "upperCaneObserved": [k for k in ("east", "west") if growth[k]],
        "server": server,
    }


def saved_garden(game):
    # Publish numeric counts. Raw generated level.dat, playerdata and region files stay on the worker.
    save = game / "saves/Habitat"
    evidence = verify_crop(
        *(
            json.loads((game / name).read_bytes())
            for name in (
                "garden-start.json",
                "garden-growth.json",
                "garden-server.json",
            )
        )
    )
    world = read_compound(gzip.decompress((save / "level.dat").read_bytes()))["Data"]
    player = world.get("Player")
    if player is None:
        (data,) = (save / "playerdata").glob("*.dat")
        player = read_compound(gzip.decompress(data.read_bytes()))
    if (
        world["allowCommands"]
        or world["GameRules"]["randomTickSpeed"] != "3"
        or player["playerGameType"] != 0
    ):
        raise ValueError(
            "A saved survival garden with cheats off and normal growth is required"
        )
    chunk = next(
        ch
        for ch in chunks(save / "region/r.0.0.mca")
        if (ch["xPos"], ch["zPos"]) == (3, 3)
    )
    (chest,) = (
        t
        for t in chunk["block_entities"]
        if t["id"] == "minecraft:chest" and (t["x"], t["y"], t["z"]) == (60, 149, 61)
    )

    def count(entries, item):
        return sum(i["Count"] for i in entries if i["id"] == item)

    slate = "starbridge:navigation_slate"
    if count(chest["Items"], slate) != 1 or count(player["Inventory"], slate) != 0:
        raise ValueError("Expected one deposited terminal and none on the player")
    amounts = {
        name: count(player["Inventory"], "minecraft:" + name)
        for name in ("compass", "glass_pane", "iron_ingot", "redstone", "paper")
    }
    if amounts != {
        "compass": 0,
        "glass_pane": 15,
        "iron_ingot": 6,
        "redstone": 4,
        "paper": 2,
    }:
        raise ValueError(
            "Saved inventory does not match normal crafting consumption: "
            + str(amounts)
        )
    evidence.update(
        {
            "storedTerminals": 1,
            "carriedTerminals": 0,
            "materialsAfterCraft": amounts,
            "randomTickSpeed": 3,
            "cheats": False,
            "survival": True,
            "scenario": "Fresh integrated world with the recorded crop and material counts supplied by the fixture.",
        }
    )
    return evidence
