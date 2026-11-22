"""Walk the regenerated lunar surface using the published mod in a hosted rendered client."""

import argparse
import gzip
import json
import math
import os
from pathlib import Path
import shutil
import signal
import struct
import uuid
from zipfile import ZIP_DEFLATED, ZipFile
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "qa/remote-ui"))
sys.path.insert(0, str(ROOT / "qa/navigation-calibration"))
from runtime import command, hosted_only, PACK, sha
from package import MAIN
from smoke import png
sys.path.insert(0, str(ROOT / "qa/home-berths"))
from remote import install_physics
from hosted_originals import install
from upstream_runtime_region import chunks, read_compound

JSON_FILES = [
    "field-start",
    "field-loaded",
    "field-health",
    "field-progress",
    "field-result",
    "field-failed",
    "field-native-interaction",
    "field-boss",
    "field-key",
    "field-vessel",
    "field-map-signal",
    "field-boarded",
    "field-return",
]
PICTURES = [
    "profile-open",
    "profile-narrow",
    "profile-hollow",
    "profile-channel",
    "profile-unsurveyed",
    "exposed-brick",
    "surface-masonry",
    "identified-brick",
    "natural-face",
]


def check(valid, message):
    if not valid:
        raise ValueError(message)


def saved_player(game):
    save = game / "saves/FieldWalk"
    data = read_compound(gzip.decompress((save / "level.dat").read_bytes()))["Data"]
    player = data.get("Player")
    if player is None:
        (owner,) = (save / "playerdata").glob("*.dat")
        player = read_compound(gzip.decompress(owner.read_bytes()))
    check(
        data["allowCommands"] == 0
        and player["playerGameType"] == 0
        and player["Dimension"] == ("minecraft:overworld" if (game / "field-return.json").is_file()
                                    else json.loads((game / "field-start.json").read_bytes())["originalMoon"]),
        "Cheats disabled, survival, in the last witnessed dimension",
    )
    (slate,) = [
        stack
        for stack in player["Inventory"]
        if stack["id"] == "starbridge:navigation_slate"
    ]
    marks = (
        slate.get("tag", {})
        .get("StarbridgeFieldNotes", {})
        .get(json.loads((game / "field-start.json").read_bytes())["originalMoon"], {})
    )
    check("SHIP" in marks, "Ship mark persisted on the held slate")
    returned = None
    if (game / "field-return.json").is_file():
        expected = json.loads((game / "field-return.json").read_bytes())
        x, y, z = expected["storageCore"]
        positions = {(x,y,z), (x+1,y,z)}
        files = {save / "region" / f"r.{a // 512}.{c // 512}.mca" for a,_,c in positions}
        tiles = {(t["x"],t["y"],t["z"]):t for f in files for chunk in chunks(f)
                 for t in chunk.get("block_entities", []) if (t["x"],t["y"],t["z"]) in positions}
        helm = tiles[x,y,z]
        def identity(raw):
            return str(uuid.UUID(bytes=struct.pack(">4i", *raw)))
        for tag, key in (("CoreId","core"),("OriginalRocket","rocket"),("Owner","pilot"),("PaidVoyage","receipt")):
            check(identity(helm[tag]) == expected[key], "Saved expedition helm " + tag)
        check(helm["id"] == "starbridge:ship_core" and helm["Energy"] == expected["afterFE"],"Saved hull and charge")
        check(identity(player["UUID"]) == expected["pilot"], "Same saved explorer")
        journal = read_compound((save / "data/starbridge_passages.dat").read_bytes())
        check(journal["Legs"] == [], "Voyage settled at normal shutdown")
        barrel = tiles[x+1,y,z]
        check(barrel["id"] == "minecraft:barrel" and barrel["Items"][0]["id"] == "minecraft:amethyst_shard"
              and barrel["Items"][0]["Count"] == 3,"Same marked departure cargo")
        returned = {"core":expected["core"],"rocket":expected["rocket"],"pilot":expected["pilot"],
                    "receipt":expected["receipt"],"amethystShards":3,"settled":True}
    return {
        "returnedShip": returned,
        "ship": marks["SHIP"],
        "site": marks.get("SITE"),
        "world": player["Dimension"],
        "feet": player["Pos"],
        "health": player["Health"],
        "hunger": player["foodLevel"],
        "seed": data["WorldGenSettings"]["seed"],
        "cheats": False,
        "survival": True,
        "originalKeyCount": sum(
            i["Count"]
            for i in player["Inventory"]
            if i["id"] == "galacticraftcore:item.key"
        ),
    }


def keep_checkpoint(game, evidence):
    save = game / "saves/FieldWalk"
    world_files = sorted(file for file in save.rglob("*") if file.is_file()
                         and file.suffix in {".dat", ".dat_old", ".mca", ".mcc", ".json", ".toml"})
    check(world_files and sum(f.stat().st_size for f in world_files) <= 384 * 1024 ** 2,
          "Save fits the hosted checkpoint")
    archive = evidence / "field-world.zip"
    with ZipFile(archive, "x", compression=ZIP_DEFLATED, compresslevel=3) as world:
        for file in world_files:
            world.write(file, "world/" + file.relative_to(save).as_posix())
        for stem in JSON_FILES:
            record = game / (stem + ".json")
            if record.is_file():
                world.write(record, "expedition/" + record.name)
    return {"sha256":sha(archive), "bytes":archive.stat().st_size, "worldFiles":len(world_files)}


def verify(result, progress, saved, game):
    check(
        result["marker"] is True and progress["markedShip"] is True,
        "Actual field map ship-marker input",
    )
    profiles = progress["profiles"]
    check(
        result["graphicalProfiles"] == len(profiles) and len(profiles) > 0,
        "Selected a point on the visible surface chart",
    )
    check(
        any(r["shape"] != "UNSURVEYED" for r in profiles),
        "At least one measured surface profile",
    )
    for item in profiles:
        east = item["eastWest"]
        north = item["northSouth"]
        check(
            len(east) == len(north) == 25 and east[12] == north[12],
            "Two transects agree on selected surface",
        )
        if item["shape"] == "UNSURVEYED":
            continue
        center = east[12]
        banks = sorted(
            [max(east[:13]), max(east[12:]), max(north[:13]), max(north[12:])]
        )
        check(
            min(east + north) > -2048 and max(0, banks[1] - center) == item["depth"],
            "Profile depth matches observed columns",
        )
    trail = [sample["feet"] for sample in progress["trail"]]
    check(len(trail) >= 2 and saved["health"] > 0, "Living walker left a trail")
    distance = max(math.hypot(x - trail[0][0], z - trail[0][2]) for x, _, z in trail)
    check(distance >= 12, "Walking displaced the observer horizontally from the deck")
    if "rimInspections" in progress:
        rims = progress["rimInspections"]
        check(progress["closeRims"] == len(rims), "Count only visited rims")
        check(progress["visibleFaceChecks"] == 0 or rims, "Sight checks came from a visited rim")
        for visit in rims:
            x, _, z = visit["feet"]
            rx, rz = visit["rimPoint"]
            cx, cz = visit["surfaceCenter"]
            check(math.hypot(x - rx - .5, z - rz - .5) < 2.6
                  and math.hypot(x - cx - .5, z - cz - .5) < 10,
                  "Reached the selected rim before inspecting it")
    for output, field in [
        ("field-native-interaction", "nativeInteraction"),
        ("field-boss", "boss"),
        ("field-key", "key"),
    ]:
        check(
            result[field] is (game / (output + ".json")).is_file(),
            field + " event and claim agree",
        )
    if result["nativeInteraction"]:
        event = json.loads((game / "field-native-interaction.json").read_bytes())
        check(
            result["masonry"] is True
            and event["block"] == "galacticraftcore:tile.moonblock"
            and event["meta"] == 14,
            "Observed original masonry before interaction",
        )
    if result["key"]:
        check(
            result["boss"] is True and saved["originalKeyCount"] > 0,
            "Original reward secured in player save",
        )
    if (game / "field-return.json").is_file():
        vessel = json.loads((game / "field-vessel.json").read_bytes())
        boarded = json.loads((game / "field-boarded.json").read_bytes())
        home = json.loads((game / "field-return.json").read_bytes())
        check(result["nativeInteraction"] and result["boss"] and result["key"], "Natural brick, boss and key before return")
        check(home["core"] == vessel["core"] == boarded["core"] and home["rocket"] == vessel["rocket"], "Same expedition ship")
        check(home["pilot"] == vessel["pilot"] == boarded["pilot"] and home["aboard"] and home["keys"] > 0,
              "Original witness boarded and reached home with the key")
        check(saved["world"] == "minecraft:overworld" and home["beforeFE"]-home["afterFE"] == vessel["fare"], "Arrival in the saved overworld and paid fare")
        check(saved["returnedShip"]["core"] == vessel["core"] and saved["returnedShip"]["settled"], "Returned hull is present in the offline save")



def run(root, evidence, fixture):
    hosted_only()
    check(os.environ.get("DISPLAY", "").startswith(":"), "Independent Xvfb display")
    game = root / "lunar-field"
    (game / "mods").mkdir(parents=True, exist_ok=False)
    (game / "config").mkdir()
    evidence.mkdir(parents=True, exist_ok=True)
    source = json.loads((root / "package/source.json").read_bytes())
    process = None
    log = game / "java.log"
    try:
        (game / "lunar-field.fixture").touch()
        (game / "options.txt").write_text(
            "version:3465\nlang:zh_cn\nonboardAccessibility:false\nfullscreen:false\npauseOnLostFocus:false\nrenderDistance:8\nsimulationDistance:5\nguiScale:2\nmaxFps:30\nmipmapLevels:0\n",
            "utf8",
        )
        (game / "config/forge-client.toml").write_text(
            "[client]\nshowLoadWarnings=false\n", "utf8"
        )
        originals = install(game)
        originals.update(install_physics(game))
        for file in (root / "package" / MAIN, fixture):
            shutil.copyfile(file, game / "mods" / file.name)
        launch = command(root / "launcher", game)
        (heap,) = [v for v in launch if v.startswith("-Xmx")]
        launch[launch.index(heap)] = "-Xmx3600m"
        launch.insert(1, "-Dretrofit.minecraftHome=" + str(game / "legacy-launcher"))
        with log.open("xb") as stream:
            process = subprocess.Popen(
                launch,
                cwd=game,
                stdout=stream,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        deadline = time.monotonic() + 1900
        next_live = time.monotonic() + 40
        while time.monotonic() < deadline:
            check(process.poll() is None, "Client exited without field checkpoint")
            check(shutil.disk_usage(root).free > 1536 * 1024**2, "Runner storage")
            if (game / "field-failed.json").exists():
                raise ValueError((game / "field-failed.json").read_text("utf8"))
            if (game / "field-result.json").exists():
                break
            if time.monotonic() >= next_live:
                if (game / "field-progress.json").exists():
                    progress = json.loads((game / "field-progress.json").read_bytes())
                    print(
                        "Field",
                        progress["stage"],
                        "walked",
                        round(progress["walked"]),
                        "profiles",
                        len(progress["profiles"]),
                        flush=True,
                    )
                next_live = time.monotonic() + 40
            time.sleep(0.5)
        else:
            raise TimeoutError("Timed out waiting for the field checkpoint")
        (game / "lunar-field.stop").touch()
        check(
            process.wait(timeout=75) == 0
            and "All dimensions are saved"
            in (game / "logs/latest.log").read_text("utf8", errors="replace"),
            "Clean save and exit",
        )
        loaded = json.loads((game / "field-loaded.json").read_bytes())
        retrofit = next(p for p in PACK["dependencies"] if p["name"] == "Retrofit")
        check(
            loaded
            == {
                "starbridge": source["mainJarSha256"],
                "retrofit": retrofit["sha256"],
                "starbridgelunarfieldqa": sha(fixture),
            },
            "Published JAR and fixture loaded",
        )
        saved = saved_player(game)
        checkpoint = keep_checkpoint(game,evidence)
        result = json.loads((game / "field-result.json").read_bytes())
        progress = json.loads((game / "field-progress.json").read_bytes())
        verify(result, progress, saved, game)
        images = {
            stem: png(game / "screenshots" / (stem + ".png"))
            for stem in progress["photos"]
            if stem in PICTURES
        }
        check(any(s.startswith("profile-") for s in images), "Rendered terrain profile")
        (evidence / "field-audit.json").write_text(
            json.dumps(
                {
                    "source": source,
                    "originals": originals,
                    "saved": saved,
                    "checkpoint": checkpoint,
                    "result": result,
                    "screenshots": images,
                    "cleanExit": True,
                },
                indent=2,
            )
            + "\n",
            "utf8",
        )
    except Exception as error:
        (evidence / "failure.json").write_text(
            json.dumps({"error": str(error)[:1800]}, indent=2) + "\n", "utf8"
        )
        if log.exists():
            print(
                "\n".join(log.read_text("utf8", errors="replace").splitlines()[-35:]),
                flush=True,
            )
        raise
    finally:
        if process is not None and process.poll() is None:
            (game / "lunar-field.stop").touch()
            try:
                process.wait(timeout=40)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
        for file in [
            *(game / (s + ".json") for s in JSON_FILES),
            *(game / "screenshots" / (s + ".png") for s in PICTURES),
        ]:
            if file.exists():
                shutil.copyfile(file, evidence / file.name)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--evidence", type=Path, required=True)
    p.add_argument("--fixture", type=Path, required=True)
    opts = p.parse_args()
    run(opts.root.resolve(), opts.evidence.resolve(), opts.fixture.resolve(strict=True))
