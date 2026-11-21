"""Freeze a reproducible evidence cutoff; optionally wait for this isolated server's final save."""

import argparse
import hashlib
import json
import time
from datetime import datetime, timezone
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

from check_soak import DURATION, inspect, persist, read


def sha(content):
    return hashlib.sha256(content).hexdigest()


def snapshot(run, archive):
    # Files below are small QA records. Do not collect launcher credentials, caches or player data.
    files = {}
    observe = inspect(run)
    for name in (
        "installed.json",
        "supervisor.json",
        "server/soak-start.json",
        "server/soak-fixture.json",
        "server/soak-windows.jsonl",
        "server/soak-events.jsonl",
        "server/soak-result.json",
        "Peers/peer-counts.json",
        "Peers/fleet-peer-error",
        "server/ops.json",
    ):
        file = run / name
        if file.is_file():
            data = file.read_bytes()
            if name.endswith(".jsonl"):
                data = b"".join(
                    row for row in data.splitlines(keepends=True) if row.endswith(b"\n")
                )
            if name == "server/soak-windows.jsonl":
                data = b"".join(
                    data.splitlines(keepends=True)[: observe["windowCount"]]
                )
            files[name] = data
    archive.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(archive, "w", ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)
    observe["archiveSha256"] = sha(archive.read_bytes())
    # Window files in a live run continue growing; this identifies the original, exact prefix.
    observe["prefixes"] = {
        n: {"bytes": len(data), "sha256": sha(data)}
        for n, data in files.items()
        if n.endswith(".jsonl")
    }
    return observe


def closeout(run):
    observation = snapshot(run, run / "soak-final-evidence.zip")
    server = run / "server"
    supervisor = json.loads((run / "supervisor.json").read_bytes())
    mismatches = []
    installation = json.loads((run / "installed.json").read_bytes())
    for side in ("Peers", "server"):
        installed_now = {
            p.name: sha(p.read_bytes())
            for folder in ("mods", "retrofit/mods")
            for p in (run / side / folder).glob("*.jar")
        }
        if (
            installed_now != installation[side]
            or installation["server"] != installation["Peers"]
        ):
            mismatches.append("installation differs on " + side)
    if supervisor["state"] != "finished":
        mismatches.append(
            "supervisor ended with " + supervisor.get("reason", supervisor["state"])
        )
    if observation["status"] != "passed":
        mismatches.append("window audit ended with " + observation["status"])
    if list((server / "crash-reports").glob("*.txt")) or list(
        (run / "Peers/crash-reports").glob("*.txt")
    ):
        mismatches.append("a crash report exists")
    log = server / "logs/latest.log"
    if not log.is_file() or "All dimensions are saved" not in log.read_text(
        encoding="utf8", errors="replace"
    ):
        mismatches.append("clean server save not observed")
    with ZipFile(run / "soak-final-evidence.zip") as frozen:
        population = (
            [
                json.loads(x)
                for x in frozen.read("server/soak-windows.jsonl").splitlines()
            ]
            if "server/soak-windows.jsonl" in frozen.namelist()
            else []
        )
    # Each ramp's actual delay is reported. Only full-population windows count toward its coverage.
    seen_full = set()
    reduced_after_full = []
    for row in population:
        target = row["target"]
        if row["minimumOnline"] == row["observedMoving"] == target:
            seen_full.add(target)
        elif target in seen_full:
            reduced_after_full.append(row["utc"])
    if reduced_after_full:
        mismatches.append("cohort fell below its target after filling")
    for people, hours in ((10, 2), (20, 6), (30, 40)):
        coverage = (
            observation["cohorts"].get(str(people), {}).get("fullCohortWallSeconds", 0)
        )
        # Up to 300 s for logins plus two boundary samples. This never extends the 48-hour clock.
        if hours * 3600 - coverage > 420:
            mismatches.append(
                f"{people}-connection full cohort measured only {coverage} s of {hours} h"
            )
    if observation["serverResult"].get("elapsedWallSeconds", 0) < DURATION:
        mismatches.append("server monotonic timer did not reach 48 h")
    result = {
        "pass": not mismatches,
        "finishedUtc": datetime.now(timezone.utc).isoformat(),
        "issues": mismatches,
        "shortWindowsAfterCohortFilledUtc": reduced_after_full,
        "observation": observation,
    }
    persist(run / "soak-final-audit.json", result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument(
        "--snapshot",
        type=Path,
        help="write an archive at the current observation cutoff",
    )
    mode.add_argument(
        "--on-finish",
        action="store_true",
        help="archive and audit after the supervisor closes the game",
    )
    args = parser.parse_args()
    run = args.run.resolve(strict=True)
    if not (run / "server/starbridge-orbit-soak.fixture").is_file():
        parser.error("choose an isolated soak")
    if args.snapshot:
        print(json.dumps(snapshot(run, args.snapshot), indent=2))
        return
    while True:
        supervisor = read(run / "supervisor.json")
        if supervisor is None:
            time.sleep(2)
            continue
        if supervisor["state"] in ("finished", "interrupted"):
            report = closeout(run)
            raise SystemExit(0 if report["pass"] else 1)
        time.sleep(45)


if __name__ == "__main__":
    main()
