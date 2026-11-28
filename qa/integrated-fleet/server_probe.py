"""Boot a fresh loopback Forge server, test the selected build, then stop and audit the save."""

import argparse
import hashlib
import json
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "qa/concurrent-orbits"))
from prepare_soak import shared  # noqa: E402


def run(probe, attempt, product):
    if not re.fullmatch("[a-z0-9-]+", attempt):
        raise ValueError("Choose a new local run name")
    source = ROOT / "run-native-fleet-large300b/server"
    dest = HERE / ".local" / (probe + "-" + attempt)
    port = 26754 if probe == "without-retrofit" else 26755
    with socket.socket() as free:
        free.bind(("127.0.0.1", port))
    dest.mkdir()
    mods = dest / "mods"
    mods.mkdir()
    shutil.copyfile(product, mods / product.name)
    shutil.copytree(source / "libraries", dest / "libraries", copy_function=shared)
    shutil.copy2(source / "eula.txt", dest / "eula.txt")
    if probe == "dungeon":
        for name in ("retrofit", "config"):
            shutil.copytree(
                source / name,
                dest / name,
                copy_function=shared,
                ignore=shutil.ignore_patterns("world-data", "usernamecache.json"),
            )
        for pattern in (
            "retrofit-*.jar",
            "valkyrienskies-*.jar",
            "kotlinforforge-*.jar",
        ):
            shared(
                next((source / "mods").glob(pattern)),
                mods / next((source / "mods").glob(pattern)).name,
            )
        fixture = HERE / ".local/build/starbridge-integrated-qa.jar"
        shutil.copyfile(fixture, mods / fixture.name)
        for marker in ("check-moon-tickers.fixture", "original-dungeon-ticks.fixture"):
            (dest / marker).touch()
    (dest / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nlevel-seed=576618501\ngamemode=survival\ndifficulty=normal\n",
        encoding="utf8",
    )
    java = Path("C:/Program Files/Java/jdk-17/bin/java.exe")
    args = [
        str(java),
        "-Xms384m",
        "-Xmx2304m",
        "-XX:ActiveProcessorCount=2",
        "-Dfile.encoding=UTF-8",
        "-Djava.awt.headless=true",
        "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt",
        "nogui",
    ]
    log = dest / "server.log"
    ready = False
    with (
        log.open("wb") as output,
        subprocess.Popen(
            args,
            cwd=dest,
            stdin=subprocess.PIPE,
            stdout=output,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW,
        ) as proc,
    ):
        try:
            until = time.monotonic() + 210
            while proc.poll() is None and time.monotonic() < until:
                time.sleep(0.7)
                text = log.read_text(encoding="utf8", errors="replace")
                if "Done (" in text and (
                    probe == "without-retrofit"
                    or (dest / "dungeon-ticks.json").exists()
                ):
                    ready = True
                    break
                if (dest / "integrated-failure.json").exists() or list(
                    (dest / "crash-reports").glob("*.txt")
                ):
                    break
        finally:
            if proc.poll() is None:
                proc.stdin.write(b"stop\n")
                proc.stdin.flush()
                try:
                    proc.wait(timeout=35)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait()
    text = log.read_text(encoding="utf8", errors="replace")
    mods_installed = {
        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted([*mods.glob("*.jar"), *(dest / "retrofit/mods").glob("*.jar")])
    }
    stopped = (
        "All dimensions are saved" in text and (dest / "world/level.dat").is_file()
    )
    if not (
        ready
        and proc.returncode == 0
        and stopped
        and "MixinApplyError" not in text
        and "InvalidInjectionException" not in text
    ):
        raise RuntimeError(
            f"Inspect {dest}: ready={ready} saved={stopped} java={proc.returncode}"
        )
    if probe == "without-retrofit" and len(mods_installed) != 1:
        raise RuntimeError("Unexpected mods in the standalone Forge profile")
    evidence = {
        "probe": probe,
        "pass": True,
        "game": "1.20.1",
        "forge": "47.4.28",
        "runtime": subprocess.check_output(
            [str(java), "-version"], stderr=subprocess.STDOUT, text=True
        ).splitlines()[0],
        "port": port,
        "ready": ready,
        "returnCode": proc.returncode,
        "allDimensionsSaved": stopped,
        "installed": mods_installed,
    }
    if probe == "dungeon":
        evidence["spawner"] = json.loads((dest / "dungeon-ticks.json").read_bytes())
        evidence["metadata"] = json.loads(
            (dest / "native-moon-tickers.json").read_bytes()
        )
    (dest / "validation.json").write_text(
        json.dumps(evidence, indent=2) + "\n", encoding="utf8"
    )
    with ZipFile(
        HERE / "evidence" / (dest.name + ".zip"), "w", ZIP_DEFLATED
    ) as archive:
        for f in (
            "validation.json",
            "server.log",
            "world/level.dat",
            "server.properties",
            "dungeon-ticks.json",
            "native-moon-tickers.json",
        ):
            if (dest / f).exists():
                archive.write(dest / f, f)
    print(dest.name, "ready, saved, stopped", flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("probe", choices=["without-retrofit", "dungeon"])
    p.add_argument("attempt")
    p.add_argument("product", type=Path)
    a = p.parse_args()
    run(a.probe, a.attempt, a.product.resolve(strict=True))
