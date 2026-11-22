"""Fly a new full-originals LAN world with the exact manifest from its Advanced Rocketry launch."""

import argparse
import ctypes
import json
import re
import shutil
import socket
import sys
import time
import uuid
from pathlib import Path
from zipfile import ZipFile
from yard import HERE, ROOT, JAVA, sha, shared, stage_originals

sys.path.insert(0, str(ROOT / "qa/concurrent-orbits"))
from soak_host import Child, U  # noqa: E402

PACK = ROOT / "run-gc-pilot-ci44947273292-crew2/OrbitCaptain"
TEMPLATE = ROOT / "run-native-fleet-large300b/FleetCaptain"
HOST_HEAP_MIB = 3840
GUEST_HEAP_MIB = 2304
# The heaps total 6 GiB; the host also runs the renderer, native physics and a long-lived test server.
REQUIRED_FREE_GIB = 10.5


class Memory(ctypes.Structure):
    _fields_ = [("size", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
        (n, ctypes.c_ulonglong) for n in "total available pt pa vt va ext".split()
    ]


def memory():
    value = Memory()
    value.size = ctypes.sizeof(value)
    ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(value))
    return value.available / 2**30


def write_args(dest, args):
    (dest / "launch.args").write_text(
        "\n".join(json.dumps(a, ensure_ascii=False) for a in args) + "\n",
        encoding="utf8",
    )


def run(length, attempt, yard, product, fixture_cache=None):
    if not re.fullmatch("[a-z0-9-]+", attempt):
        raise ValueError("Use a new run name")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 26753))
    available = memory()
    print("RAM available before hidden clients, GiB", round(available, 2), flush=True)
    if available < REQUIRED_FREE_GIB:
        raise RuntimeError("Not enough headroom for two full originals clients")
    dest = HERE / ".local" / f"wide-{length}-{attempt}"
    dest.mkdir()
    proved = json.loads((yard / "loaded-dock-result.json").read_bytes())
    assert (
        proved["passed"]
        and proved["length"] == length
        and proved["width"] == 19
        and proved["height"] == 8
    )
    manifest = (
        yard / "world/data/starbridge-original-ships" / (proved["rocket"] + ".nbt")
    )
    assert manifest.is_file()
    for mark in (
        "integrated-flight.fixture",
        "integrated-lan-host.fixture",
        "wide-fleet-host.fixture",
        "starbridge-split-worlds.fixture",
        "check-moon-tickers.fixture",
    ):
        (dest / mark).touch()
    stage_originals(PACK, dest)
    shutil.copytree(TEMPLATE / "natives", dest / "natives")
    mods = dest / "mods"
    mods.mkdir()
    for prefix in ("retrofit-", "kotlinforforge-", "valkyrienskies-", "cc-tweaked-"):
        (p,) = (PACK / "mods").glob(prefix + "*.jar")
        shared(p, mods / p.name)
    equipment = (
        ROOT
        / "run-concurrent-berths-full3/server/mods/starbridge-flight-fixture-1.0.0.jar"
    )
    with ZipFile(equipment) as z:
        assert b"starbridge-split-worlds.fixture" in z.read(
            "dev/starbridge/flightqa/RadiationEquipment.class"
        )
    fixtures = [
        product,
        equipment,
        ROOT / "build/qa/connected-fleet/starbridge-connected-fleet-0.1.0.jar",
        ROOT / "qa/integrated-fleet/.local/build/starbridge-integrated-qa.jar",
        HERE / ".local/build/starbridge-wide-qa.jar",
    ]
    if fixture_cache is not None:
        fixtures = [product] + [
            fixture_cache / p.name for p in fixtures if p != product
        ]
    for p in fixtures:
        shutil.copy2(p, mods / p.name)
    (dest / "inputs").mkdir()
    shutil.copy2(manifest, dest / "inputs" / f"{length}.nbt")
    options = ROOT / "qa/integrated-fleet/.local/run-100-release-solo2/options.txt"
    shutil.copyfile(options, dest / "options.txt")
    args = [
        json.loads(v)
        for v in (TEMPLATE / "launch.args").read_text(encoding="utf8").splitlines()
        if v.strip()
    ]
    args = [
        v.replace(str(TEMPLATE), str(dest)).replace(
            TEMPLATE.as_posix(), dest.as_posix()
        )
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
        f"-Xmx{HOST_HEAP_MIB}m"
        if a.startswith("-Xmx")
        else "-XX:ActiveProcessorCount=3"
        if a.startswith("-XX:ActiveProcessorCount")
        else a
        for a in args
    ]
    for flag, value in [
        ("--username", "FleetCaptain"),
        ("--width", "840"),
        ("--height", "480"),
    ]:
        args[args.index(flag) + 1] = value
    args.insert(0, "-Dstarbridge.integrated.length=" + str(length))
    args.insert(0, "-Dstarbridge.fleetFlightLimit=4800")
    write_args(dest, args)
    visitor = dest / "visitor"
    visitor.mkdir()
    for tag in ("integrated-lan-guest.fixture", "wide-fleet-guest.fixture"):
        (visitor / tag).touch()
    for name in ("mods", "retrofit", "config", "natives"):
        shutil.copytree(dest / name, visitor / name, copy_function=shared)
    shutil.copy2(dest / "options.txt", visitor / "options.txt")
    guest_args = [
        v.replace(str(dest), str(visitor)).replace(dest.as_posix(), visitor.as_posix())
        for v in args
    ]
    guest_args[guest_args.index("--username") + 1] = "FleetVisitor"
    guest_args[guest_args.index("--uuid") + 1] = uuid.uuid3(
        uuid.NAMESPACE_DNS, "FleetVisitor"
    ).hex
    write_args(
        visitor,
        [
            f"-Xmx{GUEST_HEAP_MIB}m"
            if a.startswith("-Xmx")
            else "-XX:ActiveProcessorCount=2"
            if a.startswith("-XX:ActiveProcessorCount")
            else a
            for a in guest_args
        ],
    )
    when = time.monotonic()
    desktop = "StarbridgeWide" + uuid.uuid4().hex[:10]
    desk = U.CreateDesktopW(desktop, None, None, 0, 0x1FF, None)
    if not desk:
        raise ctypes.WinError(ctypes.get_last_error())
    host = guest = None
    reason = "timeout"
    try:
        host = Child(JAVA, dest, desktop, ["@launch.args"])
        guest = Child(JAVA, visitor, desktop, ["@launch.args"])
        print("wide LAN host/visitor", host.pid, guest.pid, flush=True)
        while time.monotonic() - when < 560:
            if list((dest / "crash-reports").glob("*.txt")) or list(
                (visitor / "crash-reports").glob("*.txt")
            ):
                reason = "crashed"
                break
            if (dest / "integrated-failure.json").exists():
                reason = "fixture failure"
                break
            if not host.alive():
                reason = "client exited"
                break
            if not guest.alive() and not (dest / "pilot-result.json").is_file():
                reason = "guest exited"
                break
            time.sleep(0.75)
    finally:
        if host:
            host.close()
        if guest:
            deadline = time.monotonic() + 5
            while guest.alive() and time.monotonic() < deadline:
                time.sleep(0.25)
            guest.close()
        U.CloseDesktop(desk)
    result = {
        "pid": host.pid,
        "guestPid": guest.pid,
        "reason": reason,
        "seconds": round(time.monotonic() - when, 3),
        "heapMiB": HOST_HEAP_MIB,
        "visitorHeapMiB": GUEST_HEAP_MIB,
        "startAvailableGiB": round(available, 2),
        "jars": {
            p.name: sha(p)
            for folder in ("mods", "retrofit/mods")
            for p in (dest / folder).glob("*.jar")
        },
        "producer": yard.name,
        "producerManifest": str(manifest.relative_to(ROOT).as_posix()),
        "sourceManifestSha256": sha(manifest),
    }
    (dest / "launch-result.json").write_text(
        json.dumps(result, indent=2), encoding="utf8"
    )
    print(reason, flush=True)
    return (
        reason == "client exited"
        and (dest / "pilot-result.json").exists()
        and (visitor / "guest-result.json").exists()
        and (dest / "wide-job-verified.json").exists()
    )


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("length", type=int, choices=[100, 200, 300])
    p.add_argument("attempt")
    p.add_argument("yard", type=Path)
    p.add_argument("product", type=Path)
    p.add_argument("--fixture-cache", type=Path)
    a = p.parse_args()
    raise SystemExit(
        0
        if run(
            a.length,
            a.attempt,
            a.yard.resolve(),
            a.product.resolve(strict=True),
            a.fixture_cache,
        )
        else 1
    )
