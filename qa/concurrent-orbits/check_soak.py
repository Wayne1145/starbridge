"""Independently read a running or finished soak. Pass --watch to retain periodic observations."""

from __future__ import annotations
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time

DURATION = 48 * 3600


def read(path):
    try:
        return json.loads(path.read_bytes())
    except (OSError, ValueError):
        return None


def lines(path):
    if not path.is_file():
        return []
    raw = path.read_bytes().splitlines(keepends=True)
    return [json.loads(line) for line in raw if line.endswith(b"\n")]


def time_of(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


def inspect(run):
    server = run / "server"
    supervisor = read(run / "supervisor.json") or {}
    start = read(server / "soak-start.json") or {}
    result = read(server / "soak-result.json") or {}
    rows = lines(server / "soak-windows.jsonl")
    peers = read(run / "Peers/peer-counts.json") or []
    groups = defaultdict(list)
    gaps, problems = [], []
    expected_pid = supervisor.get("processes", {}).get("server")
    previous = None
    threshold = 150
    for row in rows:
        groups[row["target"]].append(row)
        if row["pid"] != expected_pid:
            problems.append(f"server PID changed at {row['utc']}")
        if len(row["ships"]) != 3 or not all(s["active"] for s in row["ships"]):
            problems.append(f"inactive spaceship at {row['utc']}")
        if row["cargo"] != [3, 5, 7] or row["stoneThisWindow"] <= 0:
            problems.append(f"cargo or smelting failed at {row['utc']}")
        if row["minimumOnline"] < 10 or row["observedMoving"] < 10:
            problems.append(
                f"fewer than ten connected and moving accounts at {row['utc']}"
            )
        if previous:
            delta = time_of(row["utc"]) - time_of(previous["utc"])
            if row["serverTick"] - previous["serverTick"] != row["windowTicks"]:
                problems.append(f"missing tick interval before {row['utc']}")
            if (
                delta > threshold
                or delta <= 0
                or abs(delta - row["windowWallSeconds"]) > 10
            ):
                gaps.append(
                    {
                        "before": previous["utc"],
                        "after": row["utc"],
                        "seconds": delta,
                        "monotonicSeconds": row["windowWallSeconds"],
                    }
                )
        elif start and (
            time_of(row["utc"]) - time_of(start["utc"]) > threshold
            or row["windowWallSeconds"] > threshold
        ):
            gaps.append(
                {
                    "before": start["utc"],
                    "after": row["utc"],
                    "seconds": time_of(row["utc"]) - time_of(start["utc"]),
                }
            )
        previous = row
    cohorts = {}
    for count, stage in groups.items():
        complete = [
            w
            for w in stage
            if w["minimumOnline"] == count and w["observedMoving"] >= count
        ]
        rest = [w for w in stage if w not in complete]
        seconds = sum(w["windowWallSeconds"] for w in complete)
        cohorts[str(count)] = {
            "fullCohortWindows": len(complete),
            "fullCohortWallSeconds": round(seconds, 3),
            "rampOrReducedPopulationWindows": len(rest),
            "meanTPSFullCohort": round(
                sum(w["windowTicks"] for w in complete) / seconds, 3
            )
            if seconds
            else None,
            "maxTickMs": max(w["peakTickMs"] for w in stage),
            "maximumPerWindowP95CeilMs": max(w["tickP95CeilMs"] for w in stage),
            "firstSampleUtc": stage[0]["utc"],
            "lastSampleUtc": stage[-1]["utc"],
        }
    endpoints = [r for r in peers if r["alive"] and r["joined"] and r["positioned"]]
    completed = bool(
        result.get("pass")
        and result.get("elapsedWallSeconds", 0) >= DURATION
        and all(
            str(k) in cohorts and cohorts[str(k)]["fullCohortWindows"] > 0
            for k in (10, 20, 30)
        )
    )
    if result.get("pass") and not completed:
        problems.append("Missing required measured duration or cohorts")
    if (
        result.get("pass")
        and previous
        and result["elapsedWallSeconds"] - previous["quorumWallSeconds"] > threshold
    ):
        problems.append("Gap after last persisted sample")
    actual = (
        "failed"
        if result.get("pass") is False
        or supervisor.get("state") == "interrupted"
        or problems
        or gaps
        else "passed"
        if completed
        else "measuring"
        if start
        else "connecting"
    )
    stale = bool(
        previous and not result and time.time() - time_of(previous["utc"]) > threshold
    )
    if stale and actual == "measuring":
        actual = "stale"
    return {
        "status": actual,
        "checkedUtc": datetime.now(timezone.utc).isoformat(),
        "run": run.name,
        "port": supervisor.get("port"),
        "supervisorPID": supervisor.get("supervisor"),
        "processes": supervisor.get("processes", {}),
        "clockStartUtc": start.get("utc"),
        "observedWallSeconds": round(previous["quorumWallSeconds"], 3)
        if previous
        else 0,
        "windowCount": len(rows),
        "firstToLastSamplesUtc": [rows[0]["utc"], rows[-1]["utc"]] if rows else [],
        "cohorts": cohorts,
        "latestOnline": previous["online"] if previous else None,
        "latestHeapMiB": previous["usedHeapMiB"] if previous else None,
        "lastRecordedPeerCounters": {
            "joinedAndPositioned": len(endpoints),
            "withKeepalives": sum(bool(p["keepalives"]) for p in endpoints),
            "totalMovementPackets": sum(p["movement"] for p in endpoints),
        },
        "stoneProducedDuringTimedWindows": sum(r["stoneThisWindow"] for r in rows[1:]),
        "firstWindowIncludesWarmupStone": bool(rows),
        "events": lines(server / "soak-events.jsonl")[-7:],
        "gapsOver150sOrClockAnomalies": gaps,
        "problems": problems,
        "serverResult": result,
        "supervisorReason": supervisor.get("reason"),
    }


def persist(path, value):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, indent=2) + "\n", encoding="utf8")
    os.replace(tmp, path)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("run", type=Path)
    p.add_argument("--watch", action="store_true")
    a = p.parse_args()
    run = a.run.resolve(strict=True)
    if not a.watch:
        print(json.dumps(inspect(run), indent=2))
        return
    if not (run / "server/starbridge-orbit-soak.fixture").is_file():
        raise ValueError("Expected an isolated soak")
    start = time.monotonic()
    marker = run / "observer.json"
    last_signature = None
    while time.monotonic() - start < 50 * 3600:
        result = inspect(run)
        signature = (result["status"], result["windowCount"] // 10)
        if signature != last_signature:
            with (run / "observer-history.jsonl").open("a", encoding="utf8") as output:
                output.write(json.dumps(result) + "\n")
            last_signature = signature
        result["observerPID"] = os.getpid()
        persist(marker, result)
        if result["status"] in ("passed", "failed"):
            # A final report remains readable after both Java processes have exited.
            break
        time.sleep(30)


if __name__ == "__main__":
    main()
