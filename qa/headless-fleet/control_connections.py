"""Two normal TCP sessions on a stock Forge server which accepts vanilla clients."""

from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from pathlib import Path
import argparse
import math
import re
import shutil
import socket
import struct
import subprocess
import threading
import time
import uuid

from check_dedicated import (
    BASE,
    QA,
    VANILLA_SERVER_ARGS,
    immutable_jar,
    number,
    packet,
    read_number,
    read_nbt,
    sha,
    store,
)

PORT = 26943
NAMES = ("SBControlOne", "SBControlTwo")


def utf(value):
    encoded = value.encode("utf8")
    return number(len(encoded)) + encoded


def peer(name, both_joined, stop_after):
    # The packet identifiers below are those of Minecraft 1.20.1's ConnectionProtocol.PLAY.
    result = {"name": name, "chunks": 0, "keepalives": 0, "moves": 0}
    position = None
    with (
        socket.create_connection(("127.0.0.1", PORT), timeout=6) as sock,
        sock.makefile("rb") as stream,
    ):

        def send(kind, payload=b""):
            sock.sendall(packet(number(kind) + payload))

        send(0, number(763) + utf("127.0.0.1") + struct.pack(">H", PORT) + b"\x02")
        send(0, utf(name) + b"\x00")
        playing = False
        deadline = time.monotonic() + 76
        while time.monotonic() < deadline:
            size = read_number(stream)
            if not 0 < size <= 2**21:
                raise ValueError("Unexpected Minecraft packet length")
            content = stream.read(size)
            if len(content) != size:
                raise EOFError("Incomplete packet")
            data = BytesIO(content)
            kind = read_number(data)
            if not playing:
                if kind == 0:
                    raise RuntimeError(
                        "Login rejected: "
                        + data.read()[0:250].decode("utf8", errors="replace")
                    )
                if kind == 4:
                    query = read_number(data)
                    send(2, number(query) + b"\x00")  # Unimplemented optional query.
                elif kind == 2:
                    result["identity"] = str(uuid.UUID(bytes=data.read(16)))
                    playing = True
                else:
                    raise RuntimeError(
                        "Unexpected login request on the stock loopback control"
                    )
            elif kind == 0x28:
                result["playLogin"] = True
                send(0x08, utf("en_us") + b"\x03\x00\x01\x00\x01\x00\x01")
            elif kind == 0x3C:
                x, y, z, _, _, flags = struct.unpack(">dddffB", data.read(33))
                tx = read_number(data)
                if flags & 7:
                    raise ValueError("Control expects an absolute spawn")
                position = (x, y, z)
                result.setdefault("spawn", list(position))
                send(0, number(tx))
                send(0x14, struct.pack(">ddd?", *position, True))
            elif kind == 0x24:
                result["chunks"] += 1
            elif kind == 0x23:
                send(0x12, data.read(8))
                result["keepalives"] += 1
                if position and result["chunks"] and result.get("playLogin"):
                    if "firstActive" not in result:
                        result["firstActive"] = time.time()
                        if both_joined is not None:
                            both_joined.wait(timeout=12)
                    x, y, z = position
                    after = (x + 0.125, y, z)
                    send(0x14, struct.pack(">ddd?", *after, True))
                    result["moves"] += 1
                    result["lastMove"] = list(after)
                    if result["keepalives"] >= stop_after:
                        # Let the dedicated tick process the final input before closing the socket.
                        time.sleep(0.35)
                        result["departed"] = time.time()
                        return result
            elif kind == 0x32:
                send(0x20, data.read(4))
            elif kind == 0x1A:
                raise RuntimeError("Play session disconnected by server")
    raise TimeoutError("No verified play heartbeat")


def player(server, identity):
    path = server / "world/playerdata" / (identity + ".dat")
    tag = read_nbt(path)
    return {
        "dimension": tag["Dimension"],
        "position": tag["Pos"],
        "health": tag["Health"],
        "sha256": sha(path),
    }


def stage(server, jdk, phase, run_peers):
    output = server / (phase + "-console.log")
    trace = server / (phase + "-classes.log")
    args = [
        jdk / "java.exe",
        "-Djava.awt.headless=true",
        "-Xmx1500m",
        "-XX:ActiveProcessorCount=3",
        f"-Xlog:class+load=info:file={trace.name}:uptime,level,tags:filecount=0",
        "@" + VANILLA_SERVER_ARGS,
        "nogui",
    ]
    with output.open("wb") as logfile:
        proc = subprocess.Popen(
            args,
            cwd=server,
            stdin=subprocess.PIPE,
            stdout=logfile,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        try:
            deadline = time.monotonic() + 110
            while "Done (" not in output.read_text("utf8", errors="replace"):
                if proc.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Control server did not start")
                time.sleep(0.4)
            time.sleep(1)
            endpoints = run_peers()
        finally:
            if proc.poll() is None:
                proc.stdin.write(b"stop\n")
                proc.stdin.flush()
                try:
                    proc.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=10)
                    raise
            proc.stdin.close()
    text = output.read_text("utf8", errors="replace")
    classes = trace.read_text("utf8", errors="replace")
    joined = re.findall(r"\b(SBControl\w+) joined the game", text)
    left = re.findall(r"\b(SBControl\w+) left the game", text)
    if (
        sorted(joined) != sorted(e["name"] for e in endpoints)
        or sorted(left) != sorted(joined)
        or "All dimensions are saved" not in text
        or proc.returncode
    ):
        raise AssertionError("Control log does not confirm sessions and clean shutdown")
    if "] net.minecraft.server.MinecraftServer source:" not in classes or re.search(
        r"\] (?:org\.lwjgl\.glfw\.\S+|net\.minecraft\.client\.Minecraft) source:",
        classes,
    ):
        raise AssertionError(
            "Missing dedicated class trace or unexpected graphics class"
        )
    return {
        "serverPID": proc.pid,
        "exit": proc.returncode,
        "endpoints": endpoints,
        "joined": joined,
        "left": left,
        "disk": {e["identity"]: player(server, e["identity"]) for e in endpoints},
        "consoleSha256": sha(output),
        "classTraceSha256": sha(trace),
    }


def run(label, jdk):
    if not re.fullmatch("[a-z0-9-]+", label):
        raise ValueError("Use an unused local control name")
    root = QA / ".local" / label
    root.mkdir(parents=True, exist_ok=False)
    server = root / "server"
    server.mkdir()
    with socket.socket() as check:
        check.bind(("127.0.0.1", PORT))
    shutil.copytree(
        BASE / "server/libraries", server / "libraries", copy_function=immutable_jar
    )
    shutil.copy2(BASE / "server/eula.txt", server / "eula.txt")
    (server / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nenforce-secure-profile=false\nnetwork-compression-threshold=-1\nmax-players=2\nmax-tick-time=60000\nview-distance=3\nsimulation-distance=3\nlevel-type=minecraft:flat\nlevel-seed=180339\ndifficulty=peaceful\ngamemode=survival\n",
        encoding="utf8",
    )

    def two():
        ready = threading.Barrier(2)
        with ThreadPoolExecutor(max_workers=2) as pool:
            return [
                f.result()
                for f in [pool.submit(peer, name, ready, 2) for name in NAMES]
            ]

    first = stage(server, jdk, "pair", two)
    store(root / "pair-result.json", first)
    second = stage(server, jdk, "reconnect", lambda: [peer(NAMES[0], None, 1)])
    e = second["endpoints"][0]
    original = first["disk"][e["identity"]]
    overlap = min(p["departed"] for p in first["endpoints"]) - max(
        p["firstActive"] for p in first["endpoints"]
    )
    checks = {
        "separateTwoSockets": len(set(p["identity"] for p in first["endpoints"])) == 2
        and all(
            p["playLogin"] and p["chunks"] and p["keepalives"] >= 2
            for p in first["endpoints"]
        ),
        "movingAndOverlapping": overlap > 10
        and all(p["moves"] >= 2 for p in first["endpoints"]),
        "savedActualMovement": all(
            math.dist(first["disk"][p["identity"]]["position"], p["lastMove"]) < 0.02
            for p in first["endpoints"]
        ),
        "sameSavedPlayerOnReconnect": e["identity"] == first["endpoints"][0]["identity"]
        and math.dist(e["spawn"], original["position"]) < 0.02
        and original["health"] > 0,
    }
    store(
        root / "control-result.json",
        {
            "case": label,
            "configuration": "Unmodified Forge 47.4.28, no gameplay mods, vanilla protocol accepted by this control",
            "claim": "Two ordinary loopback PLAY sessions, separate from the full-pack soak",
            "overlapSecondsAfterBothActive": overlap,
            "stages": [first, second],
            "checks": checks,
        },
    )
    if not all(checks.values()):
        raise AssertionError(checks)
    print(checks, round(overlap, 3))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("label")
    p.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Java/jdk-17/bin"))
    a = p.parse_args()
    run(a.label, a.jdk)
