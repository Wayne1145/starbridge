"""Freeze original launch and docking failures for comparison with subsequent runs."""

import json
import math
from zipfile import ZIP_DEFLATED, ZipFile

from verify import baseline, flight_events, load, uuid_words
from yard import HERE, ROOT, sha


def save_failures():
    runs = HERE / ".local"
    archive = HERE / "evidence" / "initial-failures.zip"
    yard = runs / "yard-100-a1"
    scan = load(yard / "loaded-dock-result.json")
    assert (
        not scan["passed"]
        and scan["nativeStatus"] == "NOENGINES"
        and scan["thrust"] < scan["occupiedBlocks"]
    )
    start = runs / "wide-100-a1"
    (crash,) = (start / "visitor/crash-reports").glob("*.txt")
    mixin = [
        line.strip()
        for line in crash.read_text(encoding="utf8", errors="replace").splitlines()
        if "IllegalClassLoadError" in line and "dev.starbridge.qa.wide.Observer" in line
    ]
    assert mixin
    failed = runs / "wide-300-a1"
    world = failed / "saves/IntegratedOrbit300"
    ledger = baseline.nbt(world / "data/starbridge_passages.dat")
    (leg,) = ledger["Legs"]
    assert (
        leg["Committed"]
        and leg["Route"] == "home"
        and len(leg["Crew"]) == 2
        and not any(v["Done"] for v in leg["Crew"])
    )
    pipeline = json.loads(
        baseline.nbt(world / "data/vs_ship_data.dat")["data"]["vs_pipeline"]
    )
    (physical,) = [r for r in pipeline["ships"] if r["id"] == leg["Ship"]]
    ship = physical["transform"]
    assert physical["isStatic"] and physical["chunkClaimDimension"].endswith(
        ":overworld"
    )
    assert ship["shipCoordinatesToWorldCoordinatesRotation"] == dict(x=0, y=0, z=0, w=1)
    assert all(
        v == 1 for v in ship["shipCoordinatesToWorldCoordinatesScaling"].values()
    )
    shift = [
        ship["shipPositionInWorldCoordinates"][a]
        - ship["shipPositionInShipCoordinates"][a]
        for a in "xyz"
    ]
    deaths = {}
    pilot_log = flight_events(failed / "logs/latest.log")
    for rider in leg["Crew"]:
        identity = uuid_words(rider["Player"])
        current = baseline.nbt(world / "playerdata" / (identity + ".dat"))
        old = baseline.nbt(world / "playerdata" / (identity + ".dat_old"))
        assert old["Health"] > 0 and old["Dimension"] == "retrofit:legacy_dim_-28"
        assert current["Health"] == 0 and current["Dimension"] == "minecraft:overworld"
        intended = [rider[a] + d for a, d in zip("XYZ", shift)]
        horizontal = math.dist(intended[::2], current["Pos"][::2])
        deaths[identity] = dict(
            intendedDeck=intended,
            savedFeet=current["Pos"],
            savedHealth=current["Health"],
            previousLunarHealth=old["Health"],
            dropBlocks=round(intended[1] - current["Pos"][1], 3),
            horizontalOffset=round(horizontal, 3),
        )
    assert all(
        n + "从高处摔了下来" in pilot_log for n in ("FleetCaptain", "FleetVisitor")
    )
    details = dict(
        yardScan=scan["nativeStatus"],
        yardMass=scan["occupiedBlocks"],
        yardThrust=scan["thrust"],
        failedMixin="dev.starbridge.qa.wide.Observer",
        loadCrashLocal=crash.relative_to(ROOT).as_posix(),
        loadCrashSha256=sha(crash),
        flightFailure=load(failed / "integrated-failure.json"),
        players=deaths,
        pendingRiders=2,
        paid=True,
        hullParked=True,
        sourceFiles="The launch metadata and NBT records in this archive come from the stopped failed attempt.",
    )
    with ZipFile(archive, "w", ZIP_DEFLATED) as out:
        out.write(
            yard / "loaded-dock-result.json", "yard-100-a1/loaded-dock-result.json"
        )
        out.write(start / "launch-result.json", "wide-100-a1/launch-result.json")
        out.writestr("wide-100-a1/mixin-error.txt", "\n".join(mixin) + "\n")
        for path in (
            "inputs/300.nbt",
            "launch-result.json",
            "input.csv",
            "integrated-failure.json",
            "wide-job-verified.json",
            "visitor/wide-engineer-complete.json",
            "visitor/guest-worlds.txt",
        ):
            out.write(failed / path, "wide-300-a1/" + path)
        out.writestr("wide-300-a1/host-flight.log", pilot_log)
        for path in [
            world / "data/vs_ship_data.dat",
            world / "data/starbridge_passages.dat",
            *sorted((world / "playerdata").glob("*.dat*")),
        ]:
            out.write(path, "wide-300-a1/world/" + path.relative_to(world).as_posix())
        timeout = runs / "wide-300-a2"
        error = load(timeout / "integrated-failure.json")
        assert "retrofit:legacy_dim_-28" in error["cause"]
        controls = (timeout / "input.csv").read_text(encoding="utf8")
        assert "return-approach" in controls and "return-handoff" not in controls
        details["firstGuardRun"] = dict(
            file="wide-300-a2/integrated-failure.json",
            phase="return-approach",
            serverTick=error["serverTick"],
            returnHandoffStarted=False,
        )
        for name in (
            "integrated-failure.json",
            "launch-result.json",
            "input.csv",
            "wide-job-verified.json",
            "visitor/guest-worlds.txt",
        ):
            out.write(timeout / name, "wide-300-a2/" + name)
        for player in sorted(
            (timeout / "saves/IntegratedOrbit300/playerdata").glob("*.dat")
        ):
            data = baseline.nbt(player)
            assert (
                data["Health"] == 20 and data["Dimension"] == "retrofit:legacy_dim_-28"
            )
            out.write(player, "wide-300-a2/world/playerdata/" + player.name)
        out.writestr("diagnosis.json", json.dumps(details, indent=2) + "\n")
    print(archive.relative_to(ROOT), sha(archive), deaths)


if __name__ == "__main__":
    save_failures()
