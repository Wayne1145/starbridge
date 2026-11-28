"""Freeze a manual voyage or replay its recorded GUI inputs, server trajectory and returned save."""

import argparse
import csv
import io
import itertools
import json
import math
import shutil
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
WIDE = ROOT / "qa/wide-fleet"
sys.path.insert(0, str(WIDE))
import verify as wide  # noqa: E402
from recheck import audit_archive  # noqa: E402
from yard import sha  # noqa: E402


def rows(z, name):
    return [json.loads(line) for line in z.read(name).splitlines()]


def audit_diversion(pilot, server):
    moon = [v for v in pilot if v["target"] == "moon"]
    events = iter(moon)

    def observed(name):
        row = next((r for r in events if r["action"] == name), None)
        assert row is not None, name
        return row

    turn = observed("course-change-at-speed")
    changed = observed("diverted-under-thrust")
    observed("stopped-after-diversion")
    safe = observed("safe-berth-before-abort")
    observed("click:go_around")
    outside = observed("outside-atmosphere-after-abort")
    landed = observed("stopped-in-entry")
    assert turn["speed"] >= 16 and changed["speed"] >= 15 and not changed["automatic"]

    def components(record):
        return [record["velocity"][axis] for axis in "xyz"]

    a, b = components(turn), components(changed)
    cosine = sum(x * y for x, y in zip(a, b)) / (math.hypot(*a) * math.hypot(*b))
    course = math.degrees(math.acos(max(-1.0, min(1.0, cosine))))
    assert course > 0.25 and abs(math.remainder(changed["yaw"] - turn["yaw"], 360)) > 6
    assert safe["distance"] <= 180 and safe["speed"] < 0.05
    assert outside["distance"] >= 235 and outside["speed"] < 0.05
    assert landed["distance"] <= 180 and landed["speed"] < 0.05
    samples = [v for v in server if v["target"] == "moon"]
    start = next(
        i for i, r in enumerate(samples) if r["distance"] <= 180 and r["speed"] < 0.05
    )
    retreat = next(
        i for i in range(start + 1, len(samples)) if samples[i]["distance"] >= 235
    )
    end = next(
        i
        for i in range(retreat + 1, len(samples))
        if samples[i]["distance"] <= 180 and samples[i]["speed"] < 0.05
    )
    witness = samples[start : end + 1]
    assert all(
        not q["pendingPassage"]
        and q["paidPassage"] == ""
        and q["manual"]
        and not q["cruise"]
        for q in witness
    )
    highest = max(q["distance"] for q in witness)
    used = witness[0]["energy"] - witness[-1]["energy"]
    assert 0 <= used < 12000
    return {
        "speedDuringTurn": changed["speed"],
        "changeInVelocityDirectionDegrees": round(course, 3),
        "airframeYawChange": round(
            abs(math.remainder(changed["yaw"] - turn["yaw"], 360)), 2
        ),
        "airframePitchChange": round(abs(changed["pitch"] - turn["pitch"]), 2),
        "safeEntryBeforeAbort": safe["distance"],
        "maximumRangeAfterGoAround": round(highest, 2),
        "secondEntry": landed["distance"],
        "noFareDuringReapproach": True,
        "pilotAndDriveEnergyUsedFE": used,
        "serverSamplesWithoutAPassageOrDebit": len(witness),
        "goAroundControl": "The pilot clicks the rendered Go around button. The mod powers the initial retreat. The captain manually reorients, advances and brakes for the second approach.",
    }


def replay(path, require_boundary=False):
    saved = audit_archive(path)
    with ZipFile(path) as z:
        cap = rows(z, "manual-pilot.jsonl")
        server = rows(z, "manual-server.jsonl")
        job = json.loads(z.read("wide-job-verified.json"))
        inputs = [r[2] for r in csv.reader(io.StringIO(z.read("input.csv").decode()))]
        assert [s for s in inputs if s.startswith("used-")] and inputs.count(
            "click-orbit.starbridge.touchdown"
        ) >= 2
        assert all(
            q["core"] == saved["core"] and q["rocket"] == saved["originalRocket"]
            for q in server
        )
        assert (
            {q["target"] for q in cap}
            == {q["target"] for q in server}
            == {"moon", "home"}
        )
        result = {"ship": saved, "legs": {}, "evidenceSha256": sha(path)}
        for target in ("moon", "home"):
            local = [q for q in cap if q["target"] == target]
            samples = [q for q in server if q["target"] == target]
            events = [r["action"] for r in local]
            assert [k for k in events if k == "click:take"] == ["click:take"]
            assert (
                events.count("click:brake") >= 2 and events.count("press:forward") >= 1
            )
            assert all(
                k in events
                for k in (
                    "press:left",
                    "press:up",
                    "press:right",
                    "press:down",
                    "reticle-recentered",
                )
            )
            assert (
                events[0] == "opened-third-person" and events[-1] == "stopped-in-entry"
            )
            assert not any(
                q["automatic"] for q in local if q["stage"] not in ("TAKE", "STOP")
            )
            stop = local[-1]
            assert stop["speed"] < 0.05 and stop["distance"] <= 180
            start = next(
                i for i, q in enumerate(samples) if q["manual"] and not q["cruise"]
            )
            driven = samples[start:]
            assert all(not q["cruise"] for q in driven), "automatic control resumed"

            def point(q):
                return [q[k] for k in ("x", "y", "z")]

            distance = sum(
                math.dist(point(a), point(b)) for a, b in itertools.pairwise(driven)
            )
            initial = samples[0]["distance"]
            advanced = driven[0]["distance"] - min(q["distance"] for q in driven)
            assert (
                advanced > 5900
                and advanced / initial > 0.94
                and max(q["speed"] for q in driven) >= 15
            )
            original_yaw = local[0]["yaw"]
            original_pitch = local[0]["pitch"]
            yaw = max(abs(math.remainder(q["yaw"] - original_yaw, 360)) for q in local)
            pitch = max(abs(q["pitch"] - original_pitch) for q in local)
            assert yaw >= 5 and pitch >= 4
            helm_keys = {
                q["action"][6:]: q["held"][q["action"][6:]]
                for q in local
                if q["action"].startswith("press:")
            }
            result["legs"][target] = {
                "initialDistance": initial,
                "manualDistanceToTargetGained": round(advanced, 2),
                "fractionOfInitialDistance": round(advanced / initial, 5),
                "manualPathUnits": round(distance, 2),
                "serverSamples": len(driven),
                "maxSpeed": max(q["speed"] for q in driven),
                "entryDistance": stop["distance"],
                "entrySpeed": stop["speed"],
                "maximumYawExcursionDegrees": round(yaw, 2),
                "maximumPitchExcursionDegrees": round(pitch, 2),
                "bindingsByGlfwCode": helm_keys,
                "thrustInputs": events.count("press:forward"),
                "brakeClicks": events.count("click:brake"),
            }
        active = [
            q
            for q in server
            if q["target"] == "moon"
            and job["orbitStart"] <= q["tick"] <= job["orbitEnd"]
            and q["manual"]
            and not q["cruise"]
        ]
        assert len(active) >= 25
        result["engineerOverlap"] = {
            "manualSamplesDuringJob": len(active),
            "samplesWithSpeedOverOne": sum(q["speed"] > 1 for q in active),
            "fromServerTick": job["orbitStart"],
            "toServerTick": job["orbitEnd"],
            "rawOreInsertedAt": job["oreInLowerFurnace"],
            "ingotCarriedAt": job["ingotCarried"],
        }
        crew_work_at_speed = result["engineerOverlap"]["samplesWithSpeedOverOne"] > 0
        result["engineerOverlap"]["maximumSpeedDuringJob"] = max(
            q["speed"] for q in active
        )
        result["engineerOverlap"]["workAboveOneUnitPerTickVerified"] = (
            crew_work_at_speed
        )
        # The crew-work case requires a moving ship. The diversion case separately requires
        # a high-speed turn, an uncharged rejected descent and a second successful approach.
        if not require_boundary:
            assert (
                crew_work_at_speed
            ), "engineer job did not overlap a moving manually piloted ship"
        diverted = any(q["action"] == "click:go_around" for q in cap)
        if require_boundary or "boundary" in path.stem:
            assert diverted, "the boundary flight did not abort a clear landing"
        if diverted:
            result["highSpeedDiversionAndGoAround"] = audit_diversion(cap, server)
        result["images"] = [
            m for m in z.namelist() if m.startswith("screenshots/manual-")
        ]
        assert all(
            "screenshots/manual-" + leg + "-underway.png" in result["images"]
            for leg in ("moon", "home")
        )
        return result


def freeze(run, require_boundary=False):
    meta = json.loads((run / "launch-result.json").read_bytes())
    producer = meta["producer"]
    yard = HERE / ".local" / producer
    yard.mkdir(exist_ok=True, parents=True)
    for item in ("loaded-dock-result.json", "installed.json"):
        shutil.copy2(WIDE / ".local" / producer / item, yard / item)
    wide.HERE = HERE
    base = wide.verify(run)
    path = HERE / "evidence" / (run.name + ".zip")
    with ZipFile(path, "a", ZIP_DEFLATED) as z:
        for member in ("manual-pilot.jsonl", "manual-server.jsonl"):
            z.write(run / member, member)
        for picture in sorted((run / "screenshots").glob("manual-*.png")):
            z.write(picture, "screenshots/" + picture.name)
    result = replay(path, require_boundary)
    result.update(
        run=run.name,
        productSha256=base["installed"]["starbridge-1.20.1-0.3.0-dev.jar"],
        sourceManifestSha256=base["sourceManifestSha256"],
        mechanics="Original AR manifest restored in a new cheats-off survival LAN world. Both clients use the real screens, bound keys and container slots. The test reads the displayed navigation state, starts each leg through the cruise button, then takes manual control. The server independently samples position and mode every four ticks. Two locally hosted clients, preset ship, fuel, port and suits.",
        evidence=str(path.relative_to(ROOT).as_posix()),
        captainReturnFrames=base["stableThirdPersonView"]["return-space"],
        visitorReturnFrames=base["visitor"]["renderIntervals"]["return-space"],
    )
    (run / "manual-audit.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf8"
    )
    return result


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--run", type=Path)
    mode.add_argument("--replay", type=Path)
    p.add_argument("--require-boundary", action="store_true")
    args = p.parse_args()
    data = (
        freeze(args.run.resolve(), args.require_boundary)
        if args.run
        else replay(args.replay.resolve(), args.require_boundary)
    )
    print(json.dumps(data, indent=2))
