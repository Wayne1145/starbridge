"""Produce a genuine native AR launch manifest with both original Galacticraft add-ons active."""

from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import socket
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "qa/concurrent-orbits"))
from prepare_soak import shared  # noqa: E402

PACK = ROOT / "run-gc-pilot-ci44947273292-crew2/server"
JAVA = Path("C:/Program Files/Java/jdk-17/bin/java.exe")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stage_originals(source, destination):
    target = destination / "retrofit"
    target.mkdir(exist_ok=True)
    for name in ("config", ".retrofit", "mods"):
        shutil.copytree(source / "retrofit" / name, target / name, copy_function=shared)
    for name in (
        "retrofit.properties",
        "legacy-dimensions.txt",
        "item-attributes.properties",
    ):
        p = source / "retrofit" / name
        if p.exists():
            shutil.copy2(p, target / name)
    shutil.copytree(source / "config", destination / "config")


def run(length, label, jar):
    if not re.fullmatch("[a-z0-9-]+", label):
        raise ValueError("Choose an unused instance name")
    (HERE / ".local").mkdir(exist_ok=True)
    dest = HERE / ".local" / f"yard-{length}-{label}"
    dest.mkdir()
    port = 26756
    with socket.socket() as s:
        s.bind(("127.0.0.1", port))
    stage_originals(PACK, dest)
    mods = dest / "mods"
    mods.mkdir()
    for prefix in ("retrofit-", "valkyrienskies-", "kotlinforforge-", "cc-tweaked-"):
        (p,) = (PACK / "mods").glob(prefix + "*.jar")
        shared(p, mods / p.name)
    shutil.copyfile(jar, mods / jar.name)
    shutil.copytree(
        ROOT / "run-native-fleet-large300b/server/libraries",
        dest / "libraries",
        copy_function=shared,
    )
    shutil.copyfile(PACK / "eula.txt", dest / "eula.txt")
    (dest / "starbridge-disposable-shipyard.fixture").touch()
    cfg = dest / "retrofit/config/advRocketry/advancedRocketry.cfg"
    text, n = re.subn(
        r"(?m)^(\s*D:buildSpeedMultiplier=).*$",
        r"\g<1>0.01",
        cfg.read_text(encoding="utf8"),
    )
    assert n == 1
    cfg.write_text(text, encoding="utf8")
    (dest / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nlevel-seed=576618501\nmax-tick-time=120000\nallow-flight=true\n",
        encoding="utf8",
    )
    args = [
        str(JAVA),
        "-Xms384m",
        "-Xmx3072m",
        "-XX:ActiveProcessorCount=3",
        "-Djava.awt.headless=true",
        f"-Dstarbridge.loadedDockLength={length}",
        "-Dstarbridge.wideLoadedDock=true",
        "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt",
        "nogui",
    ]
    with (dest / "console.log").open("wb") as log:
        result = subprocess.run(
            args,
            cwd=dest,
            stdout=log,
            stderr=subprocess.STDOUT,
            timeout=420,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
    record = json.loads((dest / "loaded-dock-result.json").read_bytes())
    assert result.returncode == 0 and record["passed"], record
    manifest = (
        dest / "world/data/starbridge-original-ships" / (record["rocket"] + ".nbt")
    )
    if not manifest.is_file():
        raise ValueError("No native ship manifest for the launched rocket")
    print(
        dest.name,
        record["rocket"],
        record["width"],
        record["height"],
        manifest.name,
        sha(manifest),
        flush=True,
    )
    (dest / "installed.json").write_text(
        json.dumps(
            {
                p.name: sha(p)
                for directory in (mods, dest / "retrofit/mods")
                for p in directory.glob("*.jar")
            },
            indent=2,
        ),
        encoding="utf8",
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("length", type=int, choices=[100, 200, 300])
    p.add_argument("attempt")
    p.add_argument("jar", type=Path)
    a = p.parse_args()
    run(a.length, a.attempt, a.jar.resolve(strict=True))
