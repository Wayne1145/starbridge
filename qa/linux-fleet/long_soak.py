"""Run one continuous fleet on an operator's dedicated, headless Linux host."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time

from audit import audit_save, identity, require, WORLDS
from prepare import FORGE, MC, ROOT, prepare
from run import stop_own_process, stop_server
from runtime import command, prepare as prepare_launcher, require_runtime_host
from package import fetch_package

sys.path.insert(0, str(ROOT / "qa/concurrent-orbits"))
from check_soak import lines, persist, read
from record_soak import closeout

STAGES = {10: 7200, 20: 21600, 30: 144000}
NAMES = {f"FleetQA{i:02}": identity(f"FleetQA{i:02}") for i in range(1, 31)}
REPO = "Wayne1145/starbridge"


def utc():
    return datetime.now(timezone.utc).isoformat()


def verify_stages(rows, pid):
    """Replay consecutive real minutes; the ramp is observable and earns no full-cohort time."""
    covered = dict.fromkeys(STAGES, 0.0)
    previous = 0
    previous_members = set()
    last_tick = None
    last_elapsed = 0.0
    distances = None
    for row in rows:
        count = row["target"]
        require(row["pid"] == pid and count in STAGES, "Unexpected server process or cohort")
        if count != previous:
            require(
                count == previous + 10 and (not previous or covered[previous] >= STAGES[previous]),
                "A cohort ended with missing full-population time or ran out of order",
            )
        expected = set(list(NAMES)[:count])
        members, moving = set(row["members"]), set(row["movingMembers"])
        require(
            previous_members <= members <= expected
            and moving <= members
            and len(members) == row["online"] == len(row["members"])
            and len(moving) == row["observedMoving"] == len(row["movingMembers"]),
            "Cohort membership was lost, duplicated or replaced",
        )
        require(
            60 <= row["windowWallSeconds"] <= 150 and row["windowTicks"] > 0, "Invalid observation window"
        )
        require(
            last_tick is None or row["serverTick"] == last_tick + row["windowTicks"],
            "Missing or repeated tick interval",
        )
        require(
            abs(row["quorumWallSeconds"] - last_elapsed - row["windowWallSeconds"]) < 0.00001,
            "Missing or duplicated monotonic interval",
        )
        last_tick, last_elapsed = row["serverTick"], row["quorumWallSeconds"]
        routes = row["ships"]
        require(
            len(routes) == 3
            and all(
                ship["active"]
                and not ship["approach"]
                and ship["source"] == "home"
                and ship["target"] == "moon"
                for ship in routes
            ),
            "A fixture route ended or reached its destination",
        )
        current = [ship["distance"] for ship in routes]
        require(
            distances is None or all(after < before for before, after in zip(distances, current)),
            "A ship made no progress between minute observations",
        )
        distances = current
        full = members == moving == expected and row["minimumOnline"] == row["maximumOnline"] == count
        require(full or covered[count] == 0, "Cohort dropped below its measured population")
        if full:
            covered[count] += row["windowWallSeconds"]
        previous, previous_members = count, members
    require(
        all(covered[n] >= seconds for n, seconds in STAGES.items()), "Incomplete 2/6/40-hour cohort coverage"
    )
    return covered


def audit(run):
    legacy = closeout(run)
    require(
        legacy["pass"], "Continuous window, installation or clean-save check failed: " + str(legacy["issues"])
    )
    state = read(run / "supervisor.json")
    require(state["serverExit"] == state["clientExit"] == 0, "A child did not exit normally")
    pid = state["processes"]["server"]
    witness = read(run / "server/linux-witness.json") or {}
    require(
        witness.get("pid") == pid and witness.get("originalWorlds") == WORLDS,
        "Original worlds or server identity differ",
    )
    fixture = read(run / "server/soak-fixture.json")
    require(
        fixture["pid"] == pid
        and [(s["length"], s["width"], s["height"]) for s in fixture["ships"]]
        == [(100, 8, 4), (200, 8, 4), (300, 8, 4)]
        and len({s["id"] for s in fixture["ships"]}) == 3
        and fixture["furnaces"] == 30,
        "Unexpected flight cabins",
    )
    windows = lines(run / "server/soak-windows.jsonl")
    covered = verify_stages(windows, pid)
    last_sample = datetime.fromisoformat(windows[-1]["utc"].replace("Z", "+00:00"))
    peer_time = datetime.fromisoformat(state["peerSnapshotUtc"])
    require(abs((last_sample - peer_time).total_seconds()) < 150, "Final complete peer counters are stale")
    events = [event["event"] for event in lines(run / "server/soak-events.jsonl")]
    for hour in range(48):
        suffix = " on all 3 ships, elapsed hour " + str(hour)
        operations = [event[: -len(suffix)] for event in events if event.endswith(suffix)]
        require(
            operations == ["cruise", "brake", "cruise"], "Missing cruise/brake cycle in hour " + str(hour)
        )
    peers = read(run / "measured-peers.json") or []
    require(
        len(peers) == 30
        and {p["name"] for p in peers} == set(range(1, 31))
        and all(
            p["joined"]
            and p["alive"]
            and p["positioned"]
            and min(p["movement"], p["chunks"], p["keepalives"]) > 0
            for p in peers
        ),
        "Missing live play traffic from one of the thirty Forge identities",
    )
    return {
        "pass": True,
        "fullCohortSeconds": covered,
        "save": audit_save(run / "server", NAMES),
        "source": read(run / "installed.json")["source"],
        "closedUtc": utc(),
        "windowsArchiveSha256": legacy["observation"]["archiveSha256"],
    }


def start_child(args, directory):
    # Xvfb and game processes need neither the operator's session nor credentials used to fetch CI artifacts.
    env = {
        k: os.environ[k]
        for k in ("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME", "LD_LIBRARY_PATH")
        if k in os.environ
    }
    env.update(LIBGL_ALWAYS_SOFTWARE="1", GALLIUM_DRIVER="llvmpipe", ALSOFT_DRIVERS="null")
    with (directory / "java.log").open("xb") as log:
        return subprocess.Popen(
            args,
            cwd=directory,
            env=env,
            stdin=subprocess.PIPE,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            text=True,
            encoding="utf8",
        )


def supervise(run, host, *, clock=time.monotonic):
    require(host, "The dedicated host name is required")
    require_runtime_host(host)
    server_dir, peer_dir = run / "server", run / "Peers"
    state = {
        "state": "starting",
        "startUtc": utc(),
        "port": 26750,
        "supervisor": os.getpid(),
        "processes": {},
    }
    statefile = run / "supervisor.json"
    require(
        not statefile.exists() and not (server_dir / "world").exists(),
        "Expected a fresh, unused long-test world",
    )
    persist(statefile, state)
    config = ROOT / "qa/concurrent-orbits/soak_log4j.xml"
    flags = [
        "-Dio.netty.eventLoopThreads=12",
        "-Dfile.encoding=UTF-8",
        "-XX:ActiveProcessorCount=2",
        "-Dlog4j.configurationFile=" + str(config),
        "-Dlog4j2.configurationFile=" + str(config),
    ]
    server = peers = None
    result = None
    previous_live = None
    started = clock()
    try:
        require(not (run / "STOP").exists(), "Stop requested")
        server = start_child(
            [
                "java",
                *flags,
                "-Djava.awt.headless=true",
                "-Xms384m",
                "-Xmx3072m",
                "-Dretrofit.minecraftHome=" + str(server_dir / "legacy-launcher"),
                f"@libraries/net/minecraftforge/forge/{MC}-{FORGE}/unix_args.txt",
                "nogui",
            ],
            server_dir,
        )
        state["processes"]["server"] = server.pid
        persist(statefile, state)
        while True:
            result = read(server_dir / "soak-result.json")
            if result is not None:
                require(result.get("pass"), "In-game measurement failed: " + str(result.get("cause")))
                break
            require(not (run / "STOP").exists(), "Stop requested")
            require(
                server.poll() is None and (peers is None or peers.poll() is None), "A test process exited"
            )
            require(not (peer_dir / "fleet-peer-error").is_file(), "Connection process reported a disconnect")
            require(shutil.disk_usage(run).free >= 4 * 1024**3, "Free space fell below 4 GiB")
            require(clock() - started < 49 * 3600, "Supervisor deadline exceeded")
            if peers is None and (server_dir / "soak-peers.txt").exists():
                client = [
                    "-Xmx3072m" if a.startswith("-Xmx") else a for a in command(run / "launcher", peer_dir)
                ]
                client[1:1] = [*flags, "-Dretrofit.minecraftHome=" + str(peer_dir / "legacy-launcher")]
                peers = start_child(
                    ["xvfb-run", "-a", "-s", "-screen 0 1024x768x24 -nolisten tcp +extension GLX", *client],
                    peer_dir,
                )
                state["processes"]["Peers"] = peers.pid  # Xvfb wrapper owns the client and its display.
                persist(statefile, state)
            live = server_dir / "soak-live.json"
            if live.exists():
                require(time.time() - live.stat().st_mtime < 300, "Server minute observations are stale")
            else:
                require(clock() - started < 25 * 60, "No initial complete observation")
            sample = read(server_dir / "soak-live.json")
            if sample and sample["utc"] != previous_live:
                previous_live = sample["utc"]
                state.update(state="measuring", heartbeatUtc=utc(), latestSample=sample)
                persist(statefile, state)
            counters = read(peer_dir / "peer-counts.json")
            if (
                counters
                and time.time() - (peer_dir / "peer-counts.json").stat().st_mtime < 30
                and len(counters) == 30
                and all(p["alive"] and p["joined"] and p["positioned"] for p in counters)
            ):
                # Keep live counters on the host, independent of disconnect notifications during stop.
                if previous_live != state.get("peerSnapshotWindow"):
                    persist(run / "measured-peers.json", counters)
                    state.update(peerSnapshotWindow=previous_live, peerSnapshotUtc=utc())
            time.sleep(2)
        require(peers is not None, "The connection process never started")
        require(server.wait(timeout=120) == 0, "Dedicated server failed to stop cleanly")
        (peer_dir / "linux-fleet.stop").touch()
        require(peers.wait(timeout=60) == 0, "Connection process failed to stop cleanly")
        state.update(state="finished", reason="measured completion")
    except BaseException as err:
        state.update(state="interrupted", reason=str(err) or type(err).__name__)
        raise
    finally:
        stop_server(server)
        (peer_dir / "linux-fleet.stop").touch(exist_ok=True)
        for child in (server, peers):
            stop_own_process(child)
        state.update(
            finishUtc=utc(),
            result=result,
            serverExit=server.returncode if server else None,
            clientExit=peers.returncode if peers else None,
        )
        persist(statefile, state)


def run_long(root, host, fixture, requested):
    require(host, "The dedicated host name is required")
    require_runtime_host(host)
    for program in ("java", "gh", "git", "xvfb-run", "Xvfb", "xauth"):
        require(shutil.which(program), "Missing program: " + program)
    version = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True)
    require(re.search(r'version "17(?:\.|\+|")', version), "Select JDK 17 on the test host")
    fixture = fixture.resolve(strict=True)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    require(not root.exists(), "Choose a new, unused instance directory")
    root.mkdir(parents=True, exist_ok=False)
    fetch_package(root / "package", REPO, commit, requested, REPO)
    persist(root / "runtime.json", prepare_launcher(root / "launcher", dedicated_host=host))
    bundle = prepare(root, fixture, dedicated_host=host)
    normalized = {Path(k).name: v for k, v in bundle["installed"].items()}
    require(len(normalized) == len(bundle["installed"]), "Duplicate JAR basename")
    persist(root / "installed.json", dict(bundle, server=normalized, Peers=normalized))
    (root / "server/full-cohort-hours.fixture").touch()
    oldterm = signal.signal(signal.SIGTERM, lambda signum, frame: sys.exit(128 + signum))
    try:
        try:
            supervise(root, host)
            final = audit(root)
        except BaseException as error:
            persist(
                root / "long-result.json",
                {"pass": False, "error": str(error) or type(error).__name__, "closedUtc": utc()},
            )
            if (root / "supervisor.json").is_file() and not (root / "soak-final-audit.json").exists():
                closeout(root)
            raise
        persist(root / "long-result.json", final)
    finally:
        signal.signal(signal.SIGTERM, oldterm)
    return final


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--root", type=Path, required=True)
    cli.add_argument(
        "--dedicated-host",
        required=True,
        help="Hostname of the independent Linux machine running this command",
    )
    cli.add_argument(
        "--fixture", type=Path, default=ROOT / "build/qa/linux-fleet/starbridge-linux-fleet-qa-0.1.0.jar"
    )
    cli.add_argument("--build-run", default="", help="Successful Build and test run for this checkout")
    args = cli.parse_args()
    print(
        json.dumps(run_long(args.root.resolve(), args.dedicated_host, args.fixture, args.build_run), indent=2)
    )
