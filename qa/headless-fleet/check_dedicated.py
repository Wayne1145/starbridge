"""Probe the stock Forge login on loopback and reopen a copy of the saved native hull twice."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import time

QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]
sys.path.insert(0, str(ROOT / "qa/connected-fleet"))
from audit_large import deck_footprint, persisted_freight, read_nbt  # noqa: E402

PORT = 26942
BASE = ROOT / "run-wide-load-original300-d"
VANILLA_SERVER_ARGS = "libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def immutable_jar(src, dst):
    if Path(src).suffix == ".jar":
        try:
            os.link(src, dst)
            return dst
        except OSError:
            pass
    return shutil.copy2(src, dst)


def store(path, data):
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf8"
    )


def prepare(destination, fixture):
    original = BASE / "server"
    with socket.socket() as test:
        test.bind(("127.0.0.1", PORT))
    destination.mkdir(parents=True, exist_ok=False)
    for folder in ["libraries", "retrofit", "mods"]:
        shutil.copytree(
            original / folder, destination / folder, copy_function=immutable_jar
        )
    for folder in ["config", "world"]:
        shutil.copytree(original / folder, destination / folder)
    shutil.copy2(original / "eula.txt", destination / "eula.txt")
    shutil.copy2(fixture, destination / "mods" / fixture.name)
    args = (destination / VANILLA_SERVER_ARGS).read_text("utf8")
    if "--launchTarget forgeserver" not in args or re.search(
        "forgeclient|lwjgl|glfw", args, re.I
    ):
        raise ValueError(
            "Expected the dedicated Forge bootstrap and server libraries only"
        )
    (destination / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nenforce-secure-profile=false\nmax-players=2\nspawn-protection=0\nallow-flight=true\nview-distance=3\nsimulation-distance=3\ngenerate-structures=true\ngamemode=survival\ndifficulty=peaceful\nenable-query=false\nenable-rcon=false\n",
        encoding="utf8",
    )
    (destination / "try-stock-login.fixture").touch()


def saved(server):
    world = server / "world"
    reference = json.loads((BASE / "server/fleet-result.json").read_bytes())["flight"]
    bodies = json.loads(
        read_nbt(world / "data/vs_ship_data.dat")["data"]["vs_pipeline"]
    )["ships"]
    ship = next(body for body in bodies if body["id"] == reference["home"]["shipId"])
    previous_berth = deck_footprint(reference["moonFooting"]["localFeet"], (19, 8, 300))
    freight = {}
    for key, region, box in [
        ("home", world / "region", ship["shipAABB"]),
        (
            "retiredMoon",
            world / "dimensions/retrofit/legacy_dim_-28/region",
            previous_berth,
        ),
    ]:
        freight[key], _ = persisted_freight(region, box)
    captain = read_nbt(world / "playerdata/26b30ff7-0ad8-3061-adaf-6017614fa42b.dat")
    return {
        "vsShip": ship["id"],
        "shipClaimDimension": ship["chunkClaimDimension"],
        "freight": freight,
        "captainDimension": captain["Dimension"],
        "captainHealth": captain["Health"],
        "passages": read_nbt(world / "data/starbridge_passages.dat"),
        "playerSaves": {
            f.stem: sha(f) for f in sorted((world / "playerdata").glob("*.dat"))
        },
    }


def number(value):
    data = bytearray()
    while value >= 128:
        data.append((value & 127) | 128)
        value >>= 7
    return bytes(data) + bytes([value])


def read_number(stream):
    value = 0
    for i in range(5):
        raw = stream.read(1)
        if len(raw) != 1:
            raise EOFError("Server closed status packet")
        byte = raw[0]
        value |= (byte & 127) << (i * 7)
        if byte < 128:
            return value
    raise ValueError("Invalid Minecraft VarInt")


def packet(body):
    return number(len(body)) + body


def status():
    host = b"127.0.0.1"
    with (
        socket.create_connection(("127.0.0.1", PORT), timeout=4) as sock,
        sock.makefile("rb") as stream,
    ):
        sock.sendall(
            packet(
                b"\0"
                + number(763)
                + number(len(host))
                + host
                + struct.pack(">H", PORT)
                + b"\x01"
            )
            + packet(b"\0")
        )
        width = read_number(stream)
        if not 0 < width < 2**20 or read_number(stream) != 0:
            raise ValueError("Invalid status reply")
        length = read_number(stream)
        if length > width:
            raise ValueError("Invalid status length")
        data = json.loads(stream.read(length))
        token = 0x53544252
        started = time.perf_counter_ns()
        sock.sendall(packet(b"\x01" + struct.pack(">q", token)))
        if (
            read_number(stream) != 9
            or read_number(stream) != 1
            or stream.read(8) != struct.pack(">q", token)
        ):
            raise ValueError("Status pong mismatch")
        return {
            "minecraft": data["version"],
            "online": data["players"]["online"],
            "fmlNetworkVersion": data.get("forgeData", {}).get("fmlNetworkVersion"),
            "pingMs": round((time.perf_counter_ns() - started) / 1e6, 3),
        }


def one_start(server, jdk, label, try_login):
    log = server / f"{label}-console.log"
    trace = server / f"{label}-classes.log"
    command = [
        str(jdk / "java.exe"),
        "-Xms384m",
        "-Xmx3000m",
        "-XX:ActiveProcessorCount=3",
        "-Djava.awt.headless=true",
        "-Dfile.encoding=UTF-8",
        "-Dio.netty.eventLoopThreads=4",
        f"-Xlog:class+load=info:file={trace.name}:uptime,level,tags:filecount=0",
        "@" + VANILLA_SERVER_ARGS,
        "nogui",
    ]
    with log.open("wb") as output:
        process = subprocess.Popen(
            command,
            cwd=server,
            stdin=subprocess.PIPE,
            stdout=output,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        result = {
            "serverPID": process.pid,
            "launchTarget": "forgeserver",
            "lastArg": "nogui",
            "detachedFromConsole": True,
        }
        try:
            start = time.monotonic()
            probe = server / "stock-login.json"
            while time.monotonic() - start < 180:
                if process.poll() is not None:
                    raise RuntimeError(
                        f"{label} exited before finishing, see {log.name}"
                    )
                text = log.read_text("utf8", errors="replace")
                if re.search(r"Done \([\d.]+s\)!", text):
                    if "status" not in result:
                        try:
                            result["status"] = status()
                        except (OSError, EOFError):
                            pass
                    if "status" in result and (
                        not try_login
                        or probe.exists()
                        and "end" in json.loads(probe.read_bytes())
                    ):
                        break
                time.sleep(0.5)
            else:
                raise TimeoutError(f"{label} did not complete. See {log.name}")
        finally:
            if process.poll() is None:
                process.stdin.write(b"stop\n")
                process.stdin.flush()
                try:
                    process.wait(timeout=50)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)
                    raise RuntimeError(
                        "Dedicated server failed to save before the deadline"
                    )
            process.stdin.close()
        result["exit"] = process.returncode
    text = log.read_text("utf8", errors="replace")
    loaded = trace.read_text("utf8", errors="replace")
    if not all(
        "] " + needed + " source:" in loaded
        for needed in (
            "net.minecraft.server.MinecraftServer",
            "net.minecraft.network.Connection",
        )
    ):
        raise ValueError(f"Incomplete dedicated class trace in {trace.name}")
    result.update(
        allDimensionsSaved="All dimensions are saved" in text,
        glfwClassesLoaded=sorted(
            set(re.findall(r"\] (org\.lwjgl\.glfw\.[\w.$]+) source:", loaded))
        ),
        minecraftClientClassLoaded=bool(
            re.search(r"\] net\.minecraft\.client\.Minecraft source:", loaded)
        ),
        gameJoins=re.findall(r"\b([\w]+) joined the game$", text, re.M),
        consoleSha256=sha(log),
        classTraceSha256=sha(trace),
    )
    return result


def assess(report):
    before = report["before"]
    probe = report["stockForgeLogin"]
    return {
        "stockHandshakeReachedRegistryValidation": (
            probe.get("forgeHandshakeRegistered") is True
            and probe.get("logicalSide") == probe.get("expectedSide") == "CLIENT"
            and probe.get("handledQueries", 0) > 0
            and any(
                "HandshakeHandler.handleRegistryLoading" in line
                for line in probe.get("stack", [])
            )
        ),
        "noPlayAdmissionDuringRefusedLogin": all(
            not phase["gameJoins"] and phase["status"]["online"] == 0
            for phase in report["stages"]
        ),
        "twoCleanSaves": all(
            s["exit"] == 0 and s["allDimensionsSaved"] for s in report["stages"]
        ),
        "serverNetworking": all(
            s["status"]["minecraft"]["protocol"] == 763 for s in report["stages"]
        ),
        "noClientGraphics": all(
            not (s["glfwClassesLoaded"] or s["minecraftClientClassLoaded"])
            for s in report["stages"]
        ),
        "cargoLedgerAndOfflinePlayerRecordsPersisted": all(
            s["save"] == before for s in report["stages"]
        ),
        "knownOriginalCargo": before["freight"]["home"]
        == {"blockEntities": 4530, "diamonds": 221, "furnaceItems": 104}
        and all(v == 0 for v in before["freight"]["retiredMoon"].values()),
        "noUnfinishedFares": before["passages"] == {"Format": 1, "Legs": []},
    }


def run(label, jdk):
    if not re.fullmatch(r"[a-z0-9-]+", label):
        raise ValueError("Invalid instance name")
    fixture = QA / ".local/build/starbridge-headless-diagnostics-0.1.0.jar"
    root = QA / ".local" / label
    server = root / "server"
    if root.exists() or not fixture.is_file():
        raise ValueError("Use a fresh directory and build the diagnostic first")
    prepare(server, fixture)
    reference = saved(BASE / "server")
    before = saved(server)
    if reference != before:
        raise RuntimeError("Copied checkpoint does not match the saved ship")
    report = {
        "case": label,
        "fixtureSha256": sha(fixture),
        "source": "run-wide-load-original300-d",
        "sourceAuditSha256": sha(BASE / "server/large-flight-audit.json"),
        "gameJarSha256": sha(next((server / "mods").glob("starbridge-1.20.1-*.jar"))),
        "before": before,
        "stages": [],
    }
    test_result = root / "headless-result.json"
    store(test_result, report)
    for label, attempt in [("login", True), ("reopen", False)]:
        phase = one_start(server, jdk, label, attempt)
        phase["save"] = saved(server)
        report["stages"].append(phase)
        if attempt:
            report["stockForgeLogin"] = json.loads(
                (server / "stock-login.json").read_bytes()
            )
            (server / "try-stock-login.fixture").rename(
                server / "finished-stock-login.fixture"
            )
        store(test_result, report)
        print(label, phase["exit"], phase["status"], flush=True)
    report["checks"] = assess(report)
    store(test_result, report)
    if not all(report["checks"].values()):
        raise ValueError(report["checks"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("label")
    parser.add_argument(
        "--jdk", type=Path, default=Path("C:/Program Files/Java/jdk-17/bin")
    )
    arguments = parser.parse_args()
    run(arguments.label, arguments.jdk)
