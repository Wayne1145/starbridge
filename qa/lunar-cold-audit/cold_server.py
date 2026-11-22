"""Reopen isolated copies with the pinned full dedicated pack. Console coordinates stay local."""

from __future__ import annotations
import argparse
import json
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from audit_moon import (
    QA,
    ROOT,
    PAIRED,
    NATURAL,
    Terrain,
    sha_bytes,
    structure,
    unpack_region,
    read_compound,
)

sys.path.insert(0, str(ROOT / "qa/headless-fleet"))
from check_dedicated import BASE, VANILLA_SERVER_ARGS, immutable_jar, sha  # noqa: E402

CASES = {
    "paired": (PAIRED, "legacy_dim_-28"),
    "natural": (NATURAL, "legacy_dim_-28"),
    "ceres": (ROOT / "run-production-encounter-a3/server/world", "legacy_dim_-1007"),
    "venus": (ROOT / "run-production-encounter-a4/server/world", "legacy_dim_-14"),
}


def rewards(world, dimension):
    details = []
    needed = set()
    for f in sorted(
        (world / "dimensions/retrofit" / dimension / "region").glob("*.mca")
    ):
        rx, rz = map(int, f.name.split(".")[1:3])
        if max(abs(rx), abs(rz)) > 10000:
            continue
        region = f.read_bytes()
        for off in range(1024):
            raw = unpack_region(region, off)
            if not raw or not any(
                w in raw.lower() for w in [b"treasure", b"dungeon boss spawner"]
            ):
                continue
            c = read_compound(raw)
            for tile in c.get("block_entities", []):
                old = tile.get("legacy", {})
                name = old.get("id", "")
                if "Treasure Chest" not in name:
                    continue
                needed.add((tile["x"] // 16, tile["z"] // 16))
                items = sorted(old.get("Items", []), key=lambda v: v["Slot"])
                details.append(
                    {
                        "nativeTile": name,
                        "tier": old["tier"],
                        "locked": bool(old["isLocked"]),
                        "occupiedSlots": len(items),
                        "totalItems": sum(s["Count"] for s in items),
                        "inventorySha256": sha_bytes(
                            json.dumps(
                                items, sort_keys=True, separators=(",", ":")
                            ).encode()
                        ),
                        "chunkStatus": c["Status"],
                    }
                )
    return sorted(details, key=lambda v: (v["locked"], v["inventorySha256"])), needed


def snapshot(world, name, dimension):
    items, chunks = rewards(world, dimension)
    shape = None
    if dimension == "legacy_dim_-28":
        t = Terrain(world)
        t.find_masonry()
        shape, _ = structure(t)
        for x, y, z in t.bricks:
            chunks.add((x // 16, z // 16))
    if not chunks or len(chunks) > 96:
        raise RuntimeError("Invalid number of native site chunks")
    return {"rewards": items, "structure": shape}, sorted(chunks)


def ready(proc, console, expr, timeout):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if proc.poll() is not None:
            raise RuntimeError("Dedicated process stopped before its observation")
        text = console.read_text("utf8", errors="replace")
        if expr(text):
            return
        time.sleep(0.4)
    raise TimeoutError("Dedicated check did not receive the required marker")


def run(label, cases, jdk):
    if not re.fullmatch("[a-z0-9-]+", label):
        raise ValueError("Invalid isolated case name")
    root = QA / ".local" / label
    root.mkdir(parents=True, exist_ok=False)
    with socket.socket() as check:
        check.bind(("127.0.0.1", 26944))
    original = BASE / "server"
    for d in ["libraries", "retrofit", "mods"]:
        shutil.copytree(original / d, root / d, copy_function=immutable_jar)
    shutil.copytree(original / "config", root / "config")
    shutil.copy2(original / "eula.txt", root / "eula.txt")
    pack = json.loads((BASE / "run-result.json").read_bytes())["installed"]
    if not all(sha(root / file) == expected for file, expected in pack.items()):
        raise RuntimeError("Full pack hash mismatch")
    (root / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=26944\nonline-mode=false\nenforce-secure-profile=false\nmax-players=1\nmax-tick-time=120000\nview-distance=3\nsimulation-distance=3\ngamemode=survival\ndifficulty=normal\nenable-rcon=false\n",
        encoding="utf8",
    )
    if "--launchTarget forgeserver" not in (root / VANILLA_SERVER_ARGS).read_text(
        "utf8"
    ):
        raise ValueError("Wrong Forge bootstrap")
    result = {
        "version": "1.20.1 / Forge 47.4.28",
        "build": "a90ea7c",
        "installedModHashes": pack,
        "cases": {},
    }
    for name in cases:
        source, dim = CASES[name]
        dest = root / ("world-" + name)
        shutil.copytree(source, dest)
        before, targets = snapshot(dest, name, dim)
        source_level_hash = sha(source / "level.dat")
        console = root / (name + "-console.log")
        with console.open("wb") as log:
            args = [
                jdk / "java.exe",
                "-Xms384m",
                "-Xmx3400m",
                "-XX:ActiveProcessorCount=3",
                "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8",
                "@" + VANILLA_SERVER_ARGS,
                "--world",
                dest.name,
                "nogui",
            ]
            process = subprocess.Popen(
                args,
                cwd=root,
                stdin=subprocess.PIPE,
                stdout=log,
                stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW,
            )

            def send(command):
                process.stdin.write((command + "\n").encode())
                process.stdin.flush()

            try:
                ready(
                    process, console, lambda s: re.search(r"Done \([\d.]+s\)!", s), 140
                )
                for cx, cz in targets:
                    send(f"execute in retrofit:{dim} run forceload add {cx*16} {cz*16}")
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline:
                    for idx, (cx, cz) in enumerate(targets):
                        send(
                            f"execute in retrofit:{dim} if loaded {cx*16} 55 {cz*16} run say sb_dev_loaded_{idx}_ok"
                        )
                    time.sleep(1.2)
                    text = console.read_text("utf8", errors="replace")
                    count = len(
                        set(re.findall(r"\[Server\] sb_dev_loaded_(\d+)_ok", text))
                    )
                    if count == len(targets):
                        break
                else:
                    raise TimeoutError(
                        "Not all previously observed native structure chunks were loaded"
                    )
                send("save-all flush")
                ready(
                    process,
                    console,
                    lambda s: "Saved the game" in s or "Saved the world" in s,
                    50,
                )
            finally:
                if process.poll() is None:
                    send("stop")
                    try:
                        process.wait(timeout=65)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=12)
                        raise
                process.stdin.close()
        after, _ = snapshot(dest, name, dim)
        record = {
            "sourceLevelSha256": source_level_hash,
            "copyLevelSha256": sha(dest / "level.dat"),
            "originalUnchanged": sha(source / "level.dat") == source_level_hash,
            "loadedSiteChunks": len(targets),
            "exit": process.returncode,
            "allDimensionsSaved": "All dimensions are saved"
            in console.read_text("utf8", errors="replace"),
            "consoleSha256": sha(console),
            "before": before,
            "after": after,
        }
        if (
            process.returncode
            or not record["allDimensionsSaved"]
            or not record["originalUnchanged"]
        ):
            raise RuntimeError("Invalid cold save")
        result["cases"][name] = record
        (root / "cold-result.json").write_text(
            json.dumps(result, indent=2) + "\n", "utf8"
        )
        print(name, "cold save OK, checked", len(targets), "native chunks", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("label")
    p.add_argument("cases", choices=CASES, nargs="+")
    p.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Java/jdk-17/bin"))
    a = p.parse_args()
    run(a.label, a.cases, a.jdk)
