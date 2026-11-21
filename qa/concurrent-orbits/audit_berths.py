"""Cross-check the two real clients against the dedicated server after a berth run."""

import argparse
import gzip
import struct
import sys
import uuid
import hashlib
import json
from pathlib import Path


PILOTS = ("OrbitCaptain", "OrbitEngineer")
MOON = "retrofit:legacy_dim_-28"
SPLIT_WORLDS = ("retrofit:legacy_dim_-14", "retrofit:legacy_dim_-1007")


def load(path):
    return json.loads(path.read_bytes())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(condition, message):
    if not condition:
        raise ValueError(message)


def audit_split(run, trial, jars):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
    from upstream_runtime_region import chunks, read_compound

    def nbt(path):
        raw = path.read_bytes()
        return read_compound(
            gzip.decompress(raw) if raw.startswith(b"\x1f\x8b") else raw
        )

    def to_uuid(words):
        return str(uuid.UUID(bytes=struct.pack(">iiii", *words)))

    world = run / "server/world"
    ledger = world / "data/starbridge_passages.dat"
    check(nbt(ledger).get("Legs") == [], "Unsettled on-disk passage ledger")
    check(
        any(n.startswith("ExtraPlanets-") for n in jars)
        and any(n.startswith("GalaxySpace-") for n in jars),
        "Both original expansions required",
    )
    check(
        trial["simultaneousOccupiedDestinationTicks"] >= 200,
        "Two distinct worlds were not occupied together for 200 ticks",
    )
    output = []
    for i, (visit, landed) in enumerate(zip(trial["firstVisits"], trial["landings"])):
        region = (
            world / "dimensions/retrofit" / SPLIT_WORLDS[i].split(":")[1] / "region"
        )
        check(
            visit["provider"] == ("ExtraPlanets", "GalaxySpace")[i]
            and visit["world"]
            == landed["world"]
            == landed["playerWorld"]
            == SPLIT_WORLDS[i],
            "Unexpected original world",
        )
        check(
            not visit["selectedColumnPreviouslyLoaded"]
            and not visit["selectedColumnPresentOnDiskBeforeVisit"]
            and visit["crewBefore"] == 0,
            "Warm destination",
        )
        check(
            visit["surveyRequestToFirstClearMapMs"] > 0
            and visit["confirmToSettledDeliveryMs"]
            >= visit["confirmToPlayerDimensionChangeMs"]
            > 0,
            "Inconsistent arrival timing",
        )
        check(
            visit["selectedColumnNewGenerationEvents"] > 0
            and visit["surveyRequestToFirstColumnLoadMs"] > 0,
            "No first generation observed for the selected column",
        )
        x, y, z = landed["storageBridge"]
        want = {(x, y, z), (x + 2, y, z + 2), (x - 1, y, z + 3)}
        regions = {region / f"r.{xx // 512}.{zz // 512}.mca" for xx, _, zz in want}
        tiles = {
            (tile["x"], tile["y"], tile["z"]): tile
            for f in regions
            for cr in chunks(f)
            for tile in cr.get("block_entities", [])
            if (tile["x"], tile["y"], tile["z"]) in want
        }
        check(set(tiles) == want, "Incomplete ship storage on disk")
        core = tiles[x, y, z]
        chest = tiles[x + 2, y, z + 2]
        oven = tiles[x - 1, y, z + 3]
        cargo = sum(
            it["Count"]
            for it in chest.get("Items", [])
            if it["id"] == "minecraft:diamond"
        )
        units = sum(
            it["Count"]
            for it in oven.get("Items", [])
            if it["id"] in ("minecraft:iron_ore", "minecraft:iron_ingot")
        )
        check(
            cargo == landed["diamonds"] and units == landed["furnaceUnits"],
            "Persisted cargo mismatch",
        )
        check(
            to_uuid(core["CoreId"]) == landed["core"]
            and core["Energy"] == landed["FE"]
            and to_uuid(core["PaidVoyage"]) == landed["receipt"],
            "Disk core identity, energy or receipt differs",
        )
        saved_player = world / "playerdata" / (landed["owner"] + ".dat")
        pp = nbt(saved_player)
        check(
            pp["Dimension"] == landed["world"] and pp["Health"] > 0,
            "Player not saved alive on their chosen world",
        )
        check(
            visit["registeredTitle"]
            in (run / PILOTS[i] / "berth-input.log").read_text(encoding="utf8"),
            "Original world selection absent from client inputs",
        )
        config_hash = {
            str(i): digest(run / i / "config/ExtraPlanets.cfg")
            for i in ("server", *PILOTS)
        }
        check(
            len(set(config_hash.values())) == 1,
            "Expansion co-existence configuration varies across clients and server",
        )
        output.append(
            {
                "pilot": PILOTS[i],
                "world": pp["Dimension"],
                "health": pp["Health"],
                "diskDiamonds": cargo,
                "diskFurnaceUnits": units,
                "paidReceipt": landed["receipt"],
                "ledgerEntries": 0,
                "configSha256": next(iter(config_hash.values())),
                "timings": visit,
                "rawSha256": {
                    f.relative_to(run).as_posix(): digest(f)
                    for f in [*regions, saved_player, ledger]
                },
            }
        )
    return output


def audit(run):
    server = run / "server"
    result = load(server / "berth-result.json")
    check(
        result.get("pass") is True,
        f"Dedicated server did not pass: {result.get('exception')}",
    )
    split = (server / "starbridge-split-worlds.fixture").is_file()
    rounds = load(server / "berth-rounds.json")
    check(
        rounds == result["rounds"] and len(rounds) == (3 if split else 2),
        "Missing independent completed rounds",
    )
    witness = {}
    for folder in (server, *(run / name for name in PILOTS)):
        manifest = {
            p.name: digest(p)
            for base in (folder / "mods", folder / "retrofit/mods")
            for p in sorted(base.glob("*.jar"))
        }
        if witness:
            check(manifest == witness, f"Different installation in {folder.name}")
        witness = manifest
        check(
            not list((folder / "crash-reports").glob("*.txt")),
            f"Crash in {folder.name}",
        )

    check(
        load(server / "ops.json") == [],
        "An operator was assigned on the dedicated server",
    )
    server_log = (server / "logs/latest.log").read_text(
        encoding="utf8", errors="replace"
    )
    check(
        all(f"{pilot} joined the game" in server_log for pilot in PILOTS),
        "Missing a server login",
    )
    check(
        "All dimensions are saved" in server_log
        or "All dimensions are saved"
        in (server / "console.log").read_text(encoding="utf8", errors="replace"),
        "Missing clean server save",
    )

    tickets = set()
    round_ids = set()
    loser = rounds[0]["deniedPilot"]
    check(loser in PILOTS, "No rejected captain identified")
    for n, trial in enumerate(rounds):
        check(trial["round"] == n, "Round number does not match")
        inputs = trial["confirmation"]
        check(
            len(inputs) == 2 and len({r["processedTick"] for r in inputs}) == 1,
            "Not in one tick",
        )
        check(
            all(r["receivedTick"] <= r["processedTick"] for r in inputs),
            "Invalid input chronology",
        )
        same = (inputs[0]["x"], inputs[0]["z"]) == (inputs[1]["x"], inputs[1]["z"])
        check(
            same == trial["equalSite"] == (n == 0),
            "Wrong initial relationship between sites",
        )
        check(len(trial["landings"]) == 2, "Missing an arrival")
        for i, (request, landed) in enumerate(zip(inputs, trial["landings"])):
            before = request["before"]
            check(
                before["core"] not in round_ids, "Same test ship reused across rounds"
            )
            round_ids.add(before["core"])
            check(
                before["world"] == "minecraft:overworld"
                and before["orbit"]
                and before["fixedCabin"]
                and not before["pending"]
                and before["receipt"] == "",
                "Invalid initial cabin state",
            )
            check(
                all(
                    before[k] == landed[k]
                    for k in ("core", "rocket", "owner", "diamonds", "furnaceUnits")
                ),
                "Different ship, owner or cargo after landing",
            )
            check(
                before["diamonds"] == 11 + 7 * i + 2 * n
                and before["furnaceUnits"] == 6,
                "Incorrect fixture cargo",
            )
            check(
                landed["world"] == (SPLIT_WORLDS[i] if n == 2 else MOON)
                and landed["aboard"]
                and landed["alive"]
                and not landed["pending"]
                and not landed["orbit"]
                and landed["oldFixedCabinCleared"]
                and landed["nativeShip"] != -1,
                "Arrival, source cleanup or physical ship missing",
            )
            check(
                before["FE"] - landed["FE"] == (28000 if n == 2 else 12000)
                and landed["receipt"]
                and landed["receipt"] not in tickets,
                "Fare or receipt mismatch",
            )
            tickets.add(landed["receipt"])
            revised = landed["selectedSite"] != [request["x"], request["z"]]
            check(
                revised == (n == 0 and PILOTS[i] == loser),
                "Unexpected change to captain's chosen berth",
            )
            x, _, z = landed["position"]
            sx, sz = landed["selectedSite"]
            check(
                (x - sx - 0.5) ** 2 + (z - sz - 0.5) ** 2 < 25,
                "Ship far from selected map position",
            )

    denied = rounds[0]["denied"]
    initial = rounds[0]["confirmation"][PILOTS.index(loser)]["before"]
    check(
        all(
            denied[k] == initial[k]
            for k in (
                "core",
                "owner",
                "rocket",
                "world",
                "FE",
                "receipt",
                "diamonds",
                "furnaceUnits",
                "orbit",
                "fixedCabin",
                "nativeShip",
                "pending",
            )
        ),
        "Rejected confirmation changed original ship, cargo, route state or charge",
    )
    denied_gui = load(run / loser / "occupied-0.json")
    check(
        denied_gui["screen"] == "OrbitScreen"
        and denied_gui["clearance"] == "OCCUPIED"
        and denied_gui["buttonEnabled"] is False
        and denied_gui["ship"] == initial["core"],
        "Missing blocked instrument",
    )
    check(
        (denied_gui["x"], denied_gui["z"])
        == (rounds[0]["confirmation"][0]["x"], rounds[0]["confirmation"][0]["z"]),
        "Client displayed rejection for a different site",
    )
    other = PILOTS[1 - PILOTS.index(loser)]
    check(
        not (run / other / "occupied-0.json").exists(),
        "Both pilots saw their berth rejected",
    )
    input_files, images = {}, {}
    for pilot in PILOTS:
        folder = run / pilot
        text = (folder / "berth-input.log").read_text(encoding="utf8")
        check(
            text.count("route.starbridge.moon") == 2
            and text.count("nav.starbridge.cruise") == len(rounds)
            and text.count("orbit.starbridge.landing") == len(rounds)
            and text.count("orbit.starbridge.touchdown")
            == len(rounds) + int(pilot == loser),
            f"Unexpected client clicks for {pilot}",
        )
        check(
            text.count("correct round=0") == int(pilot == loser),
            "Correction not made on the rejected client",
        )
        for name in [f"select-ready-{i}" for i in range(len(rounds))] + (
            ["occupied-0", "correct-ready-0"] if pilot == loser else []
        ):
            path = folder / "screenshots" / (name + ".png")
            check(
                path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"),
                f"Invalid screenshot {path}",
            )
            images[path.relative_to(run).as_posix()] = digest(path)
        input_files[f"{pilot}/berth-input.log"] = digest(folder / "berth-input.log")
    return {
        "splitWorlds": audit_split(run, rounds[-1], witness) if split else None,
        "run": run.name,
        "pass": True,
        "deniedPilot": loser,
        "installation": witness,
        "inputSha256": input_files,
        "screenshotSha256": images,
        "serverResultSha256": digest(server / "berth-result.json"),
    }


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("run", type=Path)
    print(json.dumps(audit(cli.parse_args().run), indent=2))
