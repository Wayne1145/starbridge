"""Launch one real integrated game on a hidden Windows desktop using immutable existing Forge libraries."""

import argparse
import ctypes
import hashlib
import json
import re
import shutil
import sys
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "qa/concurrent-orbits"))
from soak_host import Child, U  # noqa: E402
from prepare_soak import shared  # noqa: E402


def run_game(length, attempt, profile=False, modern=None):
    if not re.fullmatch("[a-z0-9-]+", attempt):
        raise ValueError("Choose an unused alphanumeric run name")
    dest = HERE / ".local" / f"run-{length}-{attempt}"
    source = ROOT / "run-native-fleet-large300b/FleetCaptain"
    java = Path("C:/Program Files/Java/jdk-17/bin/java.exe")
    artifact = HERE / ".local/build/starbridge-integrated-qa.jar"
    if dest.exists():
        raise ValueError("Preserve the previous run and choose another name")
    if shutil.disk_usage(HERE).free < 6 * 1024**3:
        raise RuntimeError("Workspace low on disk space")
    dest.mkdir(parents=True)
    dest.joinpath("integrated-flight.fixture").touch()
    for n in ("mods", "retrofit/.retrofit", "retrofit/mods"):
        shutil.copytree(source / n, dest / n, copy_function=shared)
    for n in ("retrofit/config", "config", "natives"):
        if (source / n).is_dir():
            shutil.copytree(source / n, dest / n)
    for n in (
        "retrofit.properties",
        "legacy-dimensions.txt",
        "item-attributes.properties",
    ):
        if (source / "retrofit" / n).is_file():
            shutil.copy2(source / "retrofit" / n, dest / "retrofit" / n)
    if profile:(dest/'profile-flight.fixture').touch()
    if modern is not None:
        jars=list((dest/'mods').glob('starbridge-1.20.1-*.jar'))
        if len(jars)!=1:raise ValueError('Expected one project JAR')
        # Replace the directory entry, never write into a hard-linked template.
        fresh=jars[0].with_suffix('.independent');shutil.copyfile(modern,fresh);os.replace(fresh,jars[0])
    shutil.copy2(artifact, dest / "mods" / artifact.name)
    (dest / "inputs").mkdir()
    shutil.copy2(
        ROOT / "run-native-fleet-large300b/server/inputs" / f"{length}.nbt",
        dest / "inputs" / f"{length}.nbt",
    )
    args = [
        json.loads(v)
        for v in (source / "launch.args").read_text(encoding="utf8").splitlines()
        if v.strip()
    ]
    args = [
        v.replace(str(source), str(dest)).replace(source.as_posix(), dest.as_posix())
        for v in args
        if not v.startswith("-Dstarbridge.")
    ]
    for flag in (
        "--quickPlayMultiplayer",
        "--quickPlaySingleplayer",
        "--quickPlayRealms",
    ):
        if flag in args:
            i = args.index(flag)
            del args[i : i + 2]
    args = [
        "-Xmx3072m"
        if x.startswith("-Xmx")
        else "-XX:ActiveProcessorCount=3"
        if x.startswith("-XX:ActiveProcessorCount")
        else x
        for x in args
    ]
    if "--username" not in args:
        raise ValueError("No local QA identity")
    args[args.index("--username") + 1] = "FleetCaptain"
    args = ["-Dstarbridge.integrated.length=" + str(length), *args]
    for key, n in [("--width", "840"), ("--height", "480")]:
        if key in args:
            args[args.index(key) + 1] = n
    (dest / "launch.args").write_text(
        "\n".join(json.dumps(x, ensure_ascii=False) for x in args) + "\n",
        encoding="utf8",
    )
    (dest / "options.txt").write_text(
        "version:3465\ntutorialStep:none\nonboardAccessibility:false\nskipMultiplayerWarning:true\npauseOnLostFocus:false\nrenderDistance:6\nsimulationDistance:5\nmaxFps:120\nenableVsync:false\nguiScale:2\nlang:zh_cn\nfullscreen:false\ngraphicsMode:0\nsoundCategory_master:0.0\n",
        encoding="utf8",
    )
    name = "StarbridgeSolo" + uuid.uuid4().hex[:10]
    desk = U.CreateDesktopW(name, None, None, 0, 0x1FF, None)
    if not desk:
        raise ctypes.WinError(ctypes.get_last_error())
    child = None
    when = time.monotonic()
    reason = "timeout"
    try:
        child = Child(java, dest, name, ["@launch.args"])
        print("actual integrated client", child.pid, "length", length, flush=True)
        while time.monotonic() - when < 560:
            if not child.alive():
                reason = "client exited"
                break
            if list((dest / "crash-reports").glob("*.txt")):
                reason = "crashed"
                break
            if (
                dest / "integrated-failure.json"
            ).exists() and time.monotonic() - when > 15:
                reason = "fixture failure"
                break
            time.sleep(1)
    finally:
        if child:
            child.close()
        U.CloseDesktop(desk)
    meta = {
        "pid": child.pid,
        "seconds": round(time.monotonic() - when, 3),
        "reason": reason,
        "os": "Windows, isolated non-foreground desktop",
        "heapMiB": 3072,
        "vCPU": 3,
        "jars": {
            p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for n in ("mods", "retrofit/mods")
            for p in (dest / n).glob("*.jar")
        },
    }
    (dest / "launch-result.json").write_text(
        json.dumps(meta, indent=2) + "\n", encoding="utf8"
    )
    print(reason, flush=True)
    return (dest / "pilot-result.json").is_file() and (
        dest / "integrated-server.json"
    ).is_file()


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("length", type=int, choices=(100, 200, 300))
    p.add_argument("attempt")
    p.add_argument("--profile", action="store_true")
    p.add_argument("--modern", type=Path)
    a = p.parse_args()
    raise SystemExit(0 if run_game(a.length, a.attempt, a.profile, a.modern) else 1)
