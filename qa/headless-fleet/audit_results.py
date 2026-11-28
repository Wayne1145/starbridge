"""Verify and summarize the graphless control run and the full-pack refused login from disk."""

import argparse
import json
from pathlib import Path
import re
import struct
import uuid
from zipfile import ZipFile

from check_dedicated import QA, ROOT, BASE, sha, saved, assess, store
from audit_large import read_nbt, persisted_freight
from upstream_runtime_region import chunks


def saved_bridge(server):
    world = server / "world"
    blueprint = json.loads((BASE / "server/fleet-result.json").read_bytes())["flight"]
    all_bodies = json.loads(
        read_nbt(world / "data/vs_ship_data.dat")["data"]["vs_pipeline"]
    )["ships"]
    body = next(b for b in all_bodies if b["id"] == blueprint["home"]["shipId"])
    bounds = body["shipAABB"]
    _, files = persisted_freight(world / "region", bounds)
    bridges = [
        t
        for f in files
        for c in chunks(f)
        for t in c.get("block_entities", [])
        if t["id"] == "starbridge:ship_core"
        and all(
            bounds["min" + a.upper()] <= t[a] <= bounds["max" + a.upper()]
            for a in "xyz"
        )
    ]
    if len(bridges) != 1:
        raise AssertionError("Expected exactly one bridge on the home ship")
    bridge = bridges[0]
    return dict(
        energy=bridge["Energy"],
        **{
            name: str(uuid.UUID(bytes=struct.pack(">4i", *bridge[name])))
            for name in ("CoreId", "OriginalRocket", "PaidVoyage")
        },
    )


def verify(full, control, output):
    full_dir = QA / ".local" / full
    control_dir = QA / ".local" / control
    root = full_dir / "server"
    raw_full = full_dir / "headless-result.json"
    raw_control = control_dir / "control-result.json"
    native = json.loads(raw_full.read_bytes())
    peers = json.loads(raw_control.read_bytes())
    probe = native["stockForgeLogin"]
    checks = assess(native)
    if not all(checks.values()) or not all(peers["checks"].values()):
        raise AssertionError("Regression did not pass")
    stock = root / "stock-login.json"
    assert json.loads(stock.read_bytes()) == probe
    current = saved(root)
    assert current == native["before"] == saved(BASE / "server")
    bridge = saved_bridge(root)
    original = saved_bridge(BASE / "server")
    assert bridge == original
    fares = json.loads((BASE / "server/fleet-result.json").read_bytes())["flight"]
    assert (
        bridge["PaidVoyage"] == fares["homeFare"]["leg"]
        and bridge["energy"] == fares["homeFare"]["balance"] == 74868
        and bridge["OriginalRocket"] == fares["home"]["original"]
    )
    raw = {}
    phases = []
    for instance, labels, stages in [
        (full_dir, ("login", "reopen"), native["stages"]),
        (control_dir, ("pair", "reconnect"), peers["stages"]),
    ]:
        for label, stage in zip(labels, stages, strict=True):
            console = instance / "server" / (label + "-console.log")
            trace = instance / "server" / (label + "-classes.log")
            assert (
                sha(console) == stage["consoleSha256"]
                and sha(trace) == stage["classTraceSha256"]
            )
            text = console.read_text("utf8")
            loaded = trace.read_text("utf8")
            assert (
                "All dimensions are saved" in text
                and "] net.minecraft.server.MinecraftServer source:" in loaded
                and "] net.minecraft.network.Connection source:" in loaded
                and not re.search(
                    r"\] (net\.minecraft\.client\.Minecraft|org\.lwjgl\.glfw\.\S+) source:",
                    loaded,
                )
            )
            if label == "login":
                assert (
                    re.search(r"name=SBHeadlessProbe,.*lost connection:", text)
                    and "SBHeadlessProbe joined the game" not in text
                )
            phases.append(
                {
                    "case": instance.name,
                    "phase": label,
                    "pid": stage["serverPID"],
                    "exit": stage["exit"],
                    "allDimensionsSaved": True,
                    "minecraftClientAndGlfwClassesLoaded": False,
                }
            )
            for file in [console, trace]:
                raw[file.relative_to(QA).as_posix()] = sha(file)
    for file in [
        raw_full,
        raw_control,
        stock,
        root / "world/data/starbridge_passages.dat",
        root / "world/data/vs_ship_data.dat",
    ]:
        raw[file.relative_to(QA).as_posix()] = sha(file)
    installed = json.loads((BASE / "run-result.json").read_bytes())["installed"]
    assert all(sha(root / file) == checksum for file, checksum in installed.items())
    source = next(
        (ROOT / ".gradle-user/caches/forge_gradle/maven_downloader").rglob(
            "forge-1.20.1-47.4.28-sources.jar"
        )
    )
    runtime = next((root / "libraries/net/minecraft/server").rglob("*-srg.jar"))
    with ZipFile(runtime) as z:
        assert "net/minecraft/client/Minecraft.class" not in z.namelist()
    with ZipFile(source) as z:
        dep = z.read("net/minecraftforge/network/ConfigSync.java").decode()
        assert (
            "if (!Minecraft.m_91087_().m_91090_())" in dep
            and "mc.acceptSyncedConfig" in dep
        )
    clean_peers = []
    for stage in peers["stages"]:
        clean_peers.append(
            [
                {
                    k: e[k]
                    for k in [
                        "name",
                        "identity",
                        "chunks",
                        "keepalives",
                        "moves",
                        "spawn",
                        "lastMove",
                    ]
                }
                for e in stage["endpoints"]
            ]
        )
    data = {
        "date": "2026-11-22",
        "minecraft": "1.20.1",
        "forge": "47.4.28",
        "java": 17,
        "fullPackBuild": {
            "actionsRun": 44969267385,
            "commit": "a90ea7c",
            "modernJarSha256": native["gameJarSha256"],
            "fixtureJarSha256": native["fixtureSha256"],
        },
        "launch": {
            "target": "forgeserver",
            "jvm": "-Djava.awt.headless=true",
            "argument": "nogui",
            "processCreation": "CREATE_NO_WINDOW",
            "network": "IPv4 loopback",
            "phases": phases,
        },
        "fullPack": {
            "case": full,
            "ordinaryPlayConnections": 0,
            "qualifiedSoakSeconds": 0,
            "fixture": "One networking endpoint on an actual TCP socket in the dedicated JVM, using the ordinary Forge login handlers. The stock handler stopped before PLAY.",
            "queriesReceived": probe["customQueries"],
            "queriesHandled": probe["handledQueries"],
            "logicalSide": probe["logicalSide"],
            "lastQuery": probe["lastQuery"],
            "exception": probe["exception"],
            "where": probe["stack"][:4],
            "statusRequests": [s["status"] for s in native["stages"]],
            "sourceJarSha256": sha(source),
            "sourcePackFilesMatched": len(installed),
            "dedicatedMinecraftJarSha256": sha(runtime),
            "additionalSourceFinding": "ConfigSync.receiveSyncedConfig also tests Minecraft.getInstance().isLocalServer() before applying a remote configuration. This is a source finding, not a second observed exception.",
            "savedBridge": bridge,
            "cargo": current["freight"],
            "offlinePlayers": len(current["playerSaves"]),
            "offlinePlayerSetAndBytesUnchanged": True,
            "pendingFareLegs": len(current["passages"]["Legs"]),
            "checks": checks,
        },
        "networkControl": {
            "case": control,
            "configuration": peers["configuration"],
            "ordinaryPlayConnectionsAtOnce": 2,
            "firstStageAndReconnect": clean_peers,
            "overlapAfterBothProvedActiveSeconds": peers[
                "overlapSecondsAfterBothActive"
            ],
            "checks": peers["checks"],
            "countsTowardFullPackSoak": False,
        },
        "earlierDiagnosticAttempts": {
            "recovery-a": "An immediate ServerStartedEvent connection arrived before Forge allowed logins. Both saved stops passed, without testing registry sync.",
            "recovery-b": "The diagnostic accessed Connection.channel() before activation and crashed the disposable copy. The probe now inspects the channel only from its packet callback.",
            "recovery-c": "The initial network thread inherited the server logical side. The final diagnostic opened the endpoint on the CLIENT logical thread group and passed the side check.",
        },
        "localEvidenceSha256": raw,
    }
    store(output, data)
    print(
        "Verified both independent runs:", full, control, "; qualifying soak seconds: 0"
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("full")
    p.add_argument("control")
    p.add_argument("output", type=Path)
    args = p.parse_args()
    verify(args.full, args.control, args.output)
