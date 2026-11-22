"""Fly a previously assembled wide original ship on a full-pack server with 30 distinct Forge connections."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import sys
import time
import uuid
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from local_client_policy import create_private_desktop, require_local_client
sys.path.insert(0, str(ROOT / "qa/native-fuel"))
from run_windows import available, controls, Child  # noqa: E402 (shared Windows test host)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_cache(cache, *, client):
    manifest = cache / "versions/1.7.10.json"
    version = json.loads(manifest.read_bytes())
    if version.get("id") != "1.7.10":
        raise ValueError(f"Wrong legacy Minecraft manifest in {cache}")
    side = "client" if client else "server"
    name = "1.7.10.jar" if client else "minecraft_server.1.7.10.jar"
    required = [("versions/" + name, version["downloads"][side])]
    if client:
        # Retrofit supplies Forge's versions of these four libraries in place of the vanilla releases.
        replaced = {"com.google.guava:guava", "org.apache.commons:commons-lang3",
                    "org.apache.logging.log4j:log4j-api", "org.apache.logging.log4j:log4j-core"}
        for library in version["libraries"]:
            artifact = library.get("downloads", {}).get("artifact")
            if artifact and library["name"].rsplit(":", 1)[0] not in replaced:
                required.append(("libraries/" + artifact["path"], artifact))
    for relative, artifact in required:
        jar = cache / relative
        if not jar.is_file() or jar.stat().st_size != artifact["size"]:
            raise ValueError(f"Missing or incomplete {side} runtime file: {jar}")
        if hashlib.sha1(jar.read_bytes()).hexdigest() != artifact["sha1"]:
            raise ValueError(f"Legacy {side} runtime checksum mismatch: {jar}")
    return {"minecraft": side, "manifestSha256": digest(manifest), "checkedArtifacts": len(required)}


def prepare(run, template, originals, fixtures, large_hull, bundle, port):
    with ZipFile(bundle) as z:
        lock = json.loads(z.read("original-spaceflight.lock.json"))
        expected = json.loads((ROOT / "pack/original-spaceflight.lock.json").read_bytes())
        # Older development archives predate the help links for the two Starbridge jars.
        for bundled, current in zip(lock["project"], expected["project"]):
            if "source" not in bundled:
                current.pop("source", None)
        if lock != expected:
            raise ValueError("The distribution's dependency list differs from the project lock")
        sums = dict(
            (name, h)
            for h, name in (
                line.split("  ", 1)
                for line in z.read("SHA256SUMS.txt").decode().splitlines()
            )
        )
        project = {}
        for entry in lock["project"]:
            name, target = entry["file"], entry["directory"]
            if Path(name).name != name or target not in ("mods", "retrofit/mods"):
                raise ValueError("Unexpected mod path")
            blob = z.read(target + "/" + name)
            if hashlib.sha256(blob).hexdigest() != sums[target + "/" + name]:
                raise ValueError("Distribution checksum mismatch")
            project[target + "/" + name] = blob
    dependencies = {}
    for entry in lock["dependencies"]:
        relative = entry["directory"] + "/" + entry["file"]
        sources = [originals / relative, template / "server" / relative]
        candidate = next((path for path in sources if path.is_file() and digest(path) == entry["sha256"]), None)
        if candidate is None:
            raise ValueError(f"A verified copy of {relative} is required by the full pack")
        dependencies[relative] = candidate
    roles = ["server", "FleetCaptain", "Peers"]
    caches = {name: check_cache(template / name / "retrofit/.retrofit", client=name != "server") for name in roles}
    run.mkdir(exist_ok=False)
    for name in roles:
        side = run / name
        basis = template / name
        side.mkdir()
        for d in ["mods", "retrofit/mods", "config"]:
            (side / d).mkdir(parents=True, exist_ok=True)
        for relative, jar in dependencies.items():
            shutil.copy2(jar, side / relative)
        for path, blob in project.items():
            (side / path).write_bytes(blob)
        for q in [
            "starbridge-connected-fleet-0.1.0.jar",
            "starbridge-flight-fixture-1.0.0.jar",
        ]:
            shutil.copy2(fixtures / q, side / "mods" / q)
        shutil.copytree(
            basis / "retrofit/.retrofit",
            side / "retrofit/.retrofit",
            copy_function=controls.cache_copy,
        )
        (side / "retrofit/retrofit.properties").write_text(
            "download-files=never\n", "utf8"
        )
        shutil.copy2(
            originals / "config/ExtraPlanets.cfg", side / "config/ExtraPlanets.cfg"
        )
        args = controls.relocate((basis / "launch.args").read_text("utf8"), basis, side)
        args = re.sub(
            r"-Dstarbridge\.fleet\.port=\d+", f"-Dstarbridge.fleet.port={port}", args
        )
        args = re.sub(
            r"-Dstarbridge\.fleet\.length=\d+", "-Dstarbridge.fleet.length=300", args
        )
        logging_config = (ROOT / "qa/connected-fleet/wide_log4j.xml").as_uri()
        args = (f'"-Dlog4j.configurationFile={logging_config}"\n'
                f'"-Dlog4j2.configurationFile={logging_config}"\n'
                '"-Dio.netty.eventLoopThreads=12"\n' + args)
        (side / "launch.args").write_text(args, "utf8")
        (side / "starbridge-native-fleet.fixture").touch()
        if name == "server":
            shutil.copytree(
                basis / "libraries",
                side / "libraries",
                copy_function=controls.cache_copy,
            )
            shutil.copy2(basis / "eula.txt", side / "eula.txt")
            props = (basis / "server.properties").read_text("utf8")
            props = re.sub(r"(?m)^server-port=\d+$", f"server-port={port}", props)
            if not all(
                line in props.splitlines()
                for line in [
                    f"server-port={port}",
                    "server-ip=127.0.0.1",
                    "gamemode=survival",
                    "max-players=30",
                ]
            ):
                raise ValueError(
                    "Expected a loopback survival server for 30 connections"
                )
            (side / "server.properties").write_text(props, "utf8")
            (side / "inputs").mkdir()
            for length in [100, 200]:
                shutil.copy2(
                    basis / "inputs" / f"{length}.nbt",
                    side / "inputs" / f"{length}.nbt",
                )
            shutil.copy2(large_hull, side / "inputs/300.nbt")
            # The original flight fixture uses this flag to furnish GalaxySpace's native airtight suit.
            (side / "starbridge-split-worlds.fixture").touch()
            (side / "fleet-thirty-only.fixture").touch()
            (side / "fleet-flight-only.fixture").touch()
        else:
            shutil.copytree(basis / "natives", side / "natives")
            shutil.copy2(basis / "options.txt", side / "options.txt")
            if (basis / "config/forge-client.toml").is_file():
                shutil.copy2(
                    basis / "config/forge-client.toml",
                    side / "config/forge-client.toml",
                )
            (
                side
                / (
                    "starbridge-fleet-render.fixture"
                    if name == "FleetCaptain"
                    else "starbridge-fleet.fixture"
                )
            ).touch()
            if name == "Peers":
                (side / "starbridge-wide-load-peers.fixture").touch()
    installed = {}
    for sub in ["mods", "retrofit/mods"]:
        for jar in (run / "server" / sub).glob("*.jar"):
            installed[sub + "/" + jar.name] = digest(jar)
            if any(
                digest(run / side / sub / jar.name) != installed[sub + "/" + jar.name]
                for side in ["FleetCaptain", "Peers"]
            ):
                raise ValueError("Participant mod mismatch at " + jar.name)
    return {
        "bundle": digest(bundle),
        "installed": installed,
        "originalWideManifest": digest(large_hull),
        "port": port,
        "legacyCaches": caches,
    }


def run_case(run, java, report):
    require_local_client(ROOT)
    desktop_name = "StarbridgeWideLoad" + uuid.uuid4().hex[:10]
    desk = create_private_desktop(ROOT, desktop_name)
    children = []
    start = time.monotonic()
    low = available()
    server = run / "server"
    try:

        def launch(name, extra):
            child = Child(java, run / name, desktop_name, ["@launch.args", *extra])
            children.append(child)
            (run / "processes.json").write_text(json.dumps({c.name: c.pid for c in children}), "utf8")
            print(name, child.pid, flush=True)

        launch(
            "server",
            [
                "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt",
                "nogui",
            ],
        )
        while time.monotonic() - start < 1300:
            sample = available()
            low = tuple(min(a, b) for a, b in zip(low, sample))
            if any(a < b for a, b in zip(sample, (1300, 2600, 2500))):
                raise RuntimeError("Resource guard " + str(sample))
            if (run / "request-stop").is_file():
                raise RuntimeError("Requested a saved checkpoint")
            done = server / "fleet-result.json"
            if done.exists():
                result = json.loads(done.read_bytes())
                report["pass"] = result.get("passed") is True
                if not report["pass"]:
                    raise RuntimeError(
                        str(
                            result.get(
                                "reason", result.get("error", "Original flight failed")
                            )
                        )
                    )
                return
            for role in ("server", "FleetCaptain", "Peers"):
                path = run / role / "logs/latest.log"
                if path.exists():
                    log = path.read_text("utf8", errors="replace")
                    fatal = next((line for line in log.splitlines() if "1.7.10 mods are not loaded" in line
                                  or ("[retrofit:legacy]" in line and "rejected" in line)), None)
                    if fatal:
                        raise RuntimeError(f"{role}: {fatal}")
            if (run / "Peers/fleet-peer-error").is_file():
                raise RuntimeError("Peer hub reported a failure")
            if any(not c.alive() for c in children):
                raise RuntimeError(
                    "A participating process exited before the flight finished"
                )
            if (
                len(children) == 1
                and (server / "fleet-peer-request.txt").is_file()
                and sample[0] > 4900
                and sample[1] > 6800
            ):
                launch("FleetCaptain", [])
            elif len(children) == 2 and sample[0] > 2900 and sample[1] > 4300:
                log = server / "logs/latest.log"
                if log.is_file() and "FleetCaptain joined the game" in log.read_text(
                    "utf8", errors="replace"
                ):
                    launch("Peers", [])
            time.sleep(2)
        raise TimeoutError(
            "Wide ship did not complete the orbit during the available window"
        )
    except Exception as problem:
        report["error"] = str(problem)
        raise
    finally:
        for c in children:
            c.close()
        desk.close()
        report.update(elapsedSeconds=round(time.monotonic() - start, 2), minimumMiB=low)
        (run / "run-result.json").write_text(json.dumps(report, indent=2), "utf8")


def main():
    require_local_client(ROOT)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("label")
    p.add_argument("--runtime", required=True, type=Path)
    p.add_argument("--originals", required=True, type=Path)
    p.add_argument("--fixtures", required=True, type=Path)
    p.add_argument("--hull", required=True, type=Path)
    p.add_argument("--bundle", required=True, type=Path)
    p.add_argument("--java", required=True, type=Path)
    p.add_argument("--port", type=int, default=26903)
    p.add_argument("--wait-slot", type=int, default=0)
    a = p.parse_args()
    if not re.fullmatch("[a-z0-9-]+", a.label):
        p.error("Use a fresh instance name")
    end = time.monotonic() + max(0, a.wait_slot)
    while any(x < need for x, need in zip(available(), (12100, 16400, 4800))):
        if time.monotonic() >= end:
            raise SystemExit(
                "Waiting for resources to run one server and two game processes"
            )
        time.sleep(3)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", a.port))
    run = ROOT / ("run-wide-load-" + a.label)
    report = prepare(
        run,
        a.runtime.resolve(strict=True),
        a.originals.resolve(strict=True),
        a.fixtures.resolve(strict=True),
        a.hull.resolve(strict=True),
        a.bundle.resolve(strict=True),
        a.port,
    )
    report["pass"] = False
    run_case(run, a.java.resolve(strict=True), report)


if __name__ == "__main__":
    main()
