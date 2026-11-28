"""Prepare two isolated directories under a new workspace run; share only immutable libraries."""

from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shutil
import socket
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def shared(src, dst):
    if Path(src).suffix == ".jar":
        try:
            os.link(src, dst)
            return dst
        except OSError:
            pass
    return shutil.copy2(src, dst)


def prepare(
    directory,
    product,
    fixture,
    reference,
    originals=None,
    modern=None,
    expected_sha=None,
):
    if directory.parent != ROOT or not re.fullmatch(
        r"run-concurrent-soak-[a-z0-9-]+", directory.name
    ):
        raise ValueError(
            "Choose a new disposable run-concurrent-soak directory in the workspace"
        )
    if shutil.disk_usage(ROOT).free < 6 * 1024**3:
        raise RuntimeError("Need six GiB of free space before starting")
    with socket.socket() as s:
        s.bind(("127.0.0.1", 26750))
    directory.mkdir(exist_ok=False)
    installed = {}
    with ZipFile(product) as distribution:
        project = {
            n.rsplit("/", 1)[-1]: distribution.read(n)
            for n in distribution.namelist()
            if n.endswith(".jar")
        }
    if modern is not None:
        if not expected_sha or sha(modern) != expected_sha:
            raise ValueError(
                "The selected modern jar does not match the required SHA-256"
            )
        project["starbridge-1.20.1-0.3.0-dev.jar"] = modern.read_bytes()
    if (
        expected_sha
        and hashlib.sha256(project["starbridge-1.20.1-0.3.0-dev.jar"]).hexdigest()
        != expected_sha
    ):
        raise ValueError("Mismatched bundled modern jar")
    for side in ["server", "Peers"]:
        src = reference / side
        pack = (
            (originals / ("server" if side == "server" else "OrbitCaptain"))
            if originals
            else src
        )
        dst = directory / side
        dst.mkdir()
        mods = dst / "mods"
        mods.mkdir()
        for name in (
            "retrofit-forge-0.6.2-beta.jar",
            "kotlinforforge-4.11.0-all.jar",
            "valkyrienskies-120-2.4.11.jar",
        ):
            shared(src / "mods" / name, mods / name)
        if originals:
            cc = list((pack / "mods").glob("cc-tweaked-*.jar"))
            if len(cc) == 1:
                shared(cc[0], mods / cc[0].name)
        (mods / "starbridge-1.20.1-0.3.0-dev.jar").write_bytes(
            project["starbridge-1.20.1-0.3.0-dev.jar"]
        )
        shutil.copyfile(fixture, mods / fixture.name)
        legacy = dst / "retrofit"
        legacy.mkdir()
        shutil.copytree(
            pack / "retrofit/.retrofit", legacy / ".retrofit", copy_function=shared
        )
        shutil.copytree(pack / "retrofit/config", legacy / "config")
        (legacy / "mods").mkdir()
        wanted = ["AdvancedRocketry-", "LibVulpes-", "Galacticraft-"]
        if originals:
            wanted += ["ExtraPlanets-", "GalaxySpace-"]
        for prefix in wanted:
            if len(list((pack / "retrofit/mods").glob(prefix + "*.jar"))) != 1:
                raise ValueError("Missing or ambiguous locked mod: " + prefix)
        for orig in (pack / "retrofit/mods").glob("*.jar"):
            if orig.name.startswith(tuple(wanted)):
                shared(orig, legacy / "mods" / orig.name)
        (legacy / "mods/starbridge-dock-1.7.10-0.3.0-dev.jar").write_bytes(
            project["starbridge-dock-1.7.10-0.3.0-dev.jar"]
        )
        for n in (
            "retrofit.properties",
            "item-attributes.properties",
            "legacy-dimensions.txt",
        ):
            if (pack / "retrofit" / n).exists():
                shutil.copy2(pack / "retrofit" / n, legacy / n)
        shutil.copytree(src / "config", dst / "config")
        if originals:
            shutil.copy2(
                pack / "config/ExtraPlanets.cfg", dst / "config/ExtraPlanets.cfg"
            )
        installed[side] = {
            p.name: sha(p)
            for p in [*mods.glob("*.jar"), *(legacy / "mods").glob("*.jar")]
        }
        if side == "server":
            shutil.copytree(src / "libraries", dst / "libraries", copy_function=shared)
            (dst / "starbridge-orbit-soak.fixture").touch()
            shutil.copy2(src / "eula.txt", dst / "eula.txt")
            props = "server-ip=127.0.0.1\nserver-port=26750\nonline-mode=false\nenforce-secure-profile=false\nmax-players=30\nspawn-protection=0\nallow-flight=true\nview-distance=3\nsimulation-distance=3\nmax-tick-time=90000\nlevel-seed=576618501\ngamemode=survival\ndifficulty=peaceful\n"
            (dst / "server.properties").write_text(props, encoding="utf8")
            args = [
                "-Xms384m",
                "-Xmx2560m",
                "-XX:ActiveProcessorCount=2",
                "-Djava.awt.headless=true",
                "-Dfile.encoding=UTF-8",
            ]
        else:
            shutil.copytree(src / "natives", dst / "natives")
            (dst / "starbridge-soak-peers.fixture").touch()
            args = [
                json.loads(line)
                for line in (src / "launch.args")
                .read_text(encoding="utf8")
                .splitlines()
                if line.strip()
            ]
            args = [
                a.replace(str(src), str(dst)).replace(src.as_posix(), dst.as_posix())
                for a in args
            ]
            args = [
                ("-Xmx2048m" if originals else "-Xmx1600m")
                if a.startswith("-Xmx")
                else "-XX:ActiveProcessorCount=2"
                if a.startswith("-XX:ActiveProcessorCount")
                else a
                for a in args
            ]
            (dst / "options.txt").write_text(
                "version:3465\ntutorialStep:none\nonboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:3\nmaxFps:20\nfullscreen:false\nsoundCategory_master:0.0\n",
                encoding="utf8",
            )
        args = ["-Dio.netty.eventLoopThreads=12", *args]
        (dst / "launch.args").write_text(
            "\n".join(json.dumps(a, ensure_ascii=False) for a in args) + "\n",
            encoding="utf8",
        )
    (directory / "installed.json").write_text(
        json.dumps(installed, indent=2) + "\n", encoding="utf8"
    )
    print(directory)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("run", type=Path)
    p.add_argument("distribution", type=Path)
    p.add_argument("fixture", type=Path)
    p.add_argument(
        "--reference", type=Path, default=ROOT / "run-native-fleet-crewdeck1"
    )
    p.add_argument(
        "--original-template",
        type=Path,
        help="Full server and OrbitCaptain pack with both original expansions and their co-existence config",
    )
    p.add_argument(
        "--modern-jar",
        type=Path,
        help="An independently verified modern project artifact",
    )
    p.add_argument(
        "--modern-sha256", help="Pin the selected modern project artifact by SHA-256"
    )
    a = p.parse_args()
    prepare(
        a.run.resolve(),
        a.distribution,
        a.fixture,
        a.reference.resolve(),
        a.original_template,
        a.modern_jar,
        a.modern_sha256,
    )
