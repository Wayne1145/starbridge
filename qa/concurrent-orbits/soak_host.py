"""Run two dedicated QA processes on a hidden Win32 desktop and supervise their 48-hour measurement."""

from __future__ import annotations
import argparse
import ctypes
import datetime
import json
import msvcrt
import os
import shutil
import subprocess
import threading
import time
import uuid
from ctypes import wintypes as W
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
QA = Path(__file__).resolve().parent
K = ctypes.WinDLL("kernel32", use_last_error=True)
U = ctypes.WinDLL("user32", use_last_error=True)


class Startup(ctypes.Structure):
    _fields_ = (
        [
            ("cb", W.DWORD),
            ("reserved", W.LPWSTR),
            ("desktop", W.LPWSTR),
            ("title", W.LPWSTR),
        ]
        + [(n, W.DWORD) for n in ("x", "y", "sx", "sy", "cx", "cy", "attrs", "flags")]
        + [
            ("show", W.WORD),
            ("rest", W.WORD),
            ("restptr", ctypes.c_void_p),
            ("stdin", W.HANDLE),
            ("stdout", W.HANDLE),
            ("stderr", W.HANDLE),
        ]
    )


class ProcessInfo(ctypes.Structure):
    _fields_ = [
        ("process", W.HANDLE),
        ("thread", W.HANDLE),
        ("pid", W.DWORD),
        ("tid", W.DWORD),
    ]


K.CreateProcessW.argtypes = [
    W.LPCWSTR,
    W.LPWSTR,
    ctypes.c_void_p,
    ctypes.c_void_p,
    W.BOOL,
    W.DWORD,
    ctypes.c_void_p,
    W.LPCWSTR,
    ctypes.POINTER(Startup),
    ctypes.POINTER(ProcessInfo),
]
K.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]
K.CloseHandle.argtypes = [W.HANDLE]
K.TerminateProcess.argtypes = [W.HANDLE, W.UINT]
U.CreateDesktopW.argtypes = [
    W.LPCWSTR,
    W.LPCWSTR,
    ctypes.c_void_p,
    W.DWORD,
    W.DWORD,
    ctypes.c_void_p,
]
U.CreateDesktopW.restype = W.HANDLE
U.CloseDesktop.argtypes = [W.HANDLE]


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def latest(path):
    try:
        return json.loads(path.read_bytes())
    except (OSError, ValueError):
        return None


def write(path, data):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2) + "\n", encoding="utf8")
    os.replace(tmp, path)


def pump(fd, output):
    with os.fdopen(fd, "rb", buffering=0) as src:
        with output.open("ab", buffering=0) as f:
            while chunk := src.read(65536):
                if f.tell() > 4 * 1024**2:
                    shutil.copyfile(output, output.with_suffix(".previous.log"))
                    f.seek(0)
                    f.truncate(0)
                f.write(chunk)


class Child:
    def __init__(self, java, folder, desktop, args):
        if folder.name != "server" and (ROOT / ".tools/no-local-game-clients").is_file():
            raise RuntimeError("Local graphical game tests are disabled on this workstation")
        self.name = folder.name
        ri, wi = os.pipe()
        ro, wo = os.pipe()
        si = Startup()
        si.cb = ctypes.sizeof(si)
        si.desktop = desktop
        si.flags = 0x101
        si.show = 0
        si.stdin = msvcrt.get_osfhandle(ri)
        si.stdout = si.stderr = msvcrt.get_osfhandle(wo)
        for h in (si.stdin, si.stdout):
            os.set_handle_inheritable(h, True)
        pi = ProcessInfo()
        argv = [
            str(java),
            "-Dlog4j.configurationFile=" + str(QA / "soak_log4j.xml"),
            "-Dlog4j2.configurationFile=" + str(QA / "soak_log4j.xml"),
            *args,
        ]
        cmd = ctypes.create_unicode_buffer(subprocess.list2cmdline(argv))
        ok = K.CreateProcessW(
            str(java),
            cmd,
            None,
            None,
            True,
            0x08000000,
            None,
            str(folder),
            ctypes.byref(si),
            ctypes.byref(pi),
        )
        os.close(ri)
        os.close(wo)
        if not ok:
            os.close(wi)
            os.close(ro)
            raise ctypes.WinError(ctypes.get_last_error())
        self.pid = pi.pid
        self.handle = pi.process
        K.CloseHandle(pi.thread)
        self.input = os.fdopen(wi, "wb", buffering=0)
        self.drainer = threading.Thread(
            target=pump, args=(ro, folder / "soak-console.log"), daemon=True
        )
        self.drainer.start()

    def alive(self):
        return K.WaitForSingleObject(self.handle, 0) == 258

    def close(self):
        if self.alive() and self.name == "server":
            try:
                self.input.write(b"stop\n")
            except OSError:
                pass
            K.WaitForSingleObject(self.handle, 30000)
        if self.alive():
            K.TerminateProcess(self.handle, 1)
            K.WaitForSingleObject(self.handle, 7000)
        self.input.close()
        K.CloseHandle(self.handle)
        self.drainer.join(2)


def supervise(run, java, peer_after=None):
    if (ROOT / ".tools/no-local-game-clients").is_file():
        raise SystemExit("Local graphical game tests are disabled on this workstation")
    if (
        run.parent != ROOT
        or not (run / "server/starbridge-orbit-soak.fixture").is_file()
        or (run / "supervisor.json").exists()
    ):
        raise ValueError("Expected a fresh prepared workspace instance")
    name = "StarbridgeSoak" + uuid.uuid4().hex[:10]
    desktop = U.CreateDesktopW(name, None, None, 0, 0x01FF, None)
    if not desktop:
        raise ctypes.WinError(ctypes.get_last_error())
    children = []
    log = {
        "supervisor": os.getpid(),
        "port": 26750,
        "startUtc": utc(),
        "processes": {},
        "state": "starting",
        "heapLimitsMiB": {
            side: next(
                int(json.loads(arg)[4:-1])
                for arg in (run / side / "launch.args")
                .read_text(encoding="utf8")
                .splitlines()
                if json.loads(arg).startswith("-Xmx")
            )
            for side in ("server", "Peers")
        },
    }
    started = time.monotonic()
    last = 0
    server = run / "server"
    reason = ""
    result = None

    def spawn(side, args):
        c = Child(java, run / side, name, args)
        children.append(c)
        log["processes"][side] = c.pid
        write(run / "supervisor.json", log)

    try:
        spawn(
            "server",
            [
                "@launch.args",
                "@libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt",
                "nogui",
            ],
        )
        while True:
            elapsed = time.monotonic() - started
            result = latest(server / "soak-result.json")
            if result is not None:
                reason = (
                    "measured completion" if result.get("pass") else "in-game failure"
                )
                break
            if (run / "STOP").exists():
                reason = "stop requested"
                break
            if not all(c.alive() for c in children):
                reason = "test process exited"
                break
            if (run / "Peers/fleet-peer-error").is_file():
                reason = "network hub reported disconnection"
                break
            if (
                len(children) == 1
                and (server / "soak-peers.txt").exists()
                and (peer_after is None or peer_after.is_file())
            ):
                spawn("Peers", ["@launch.args"])
            clock = latest(server / "soak-start.json")
            live = latest(server / "soak-live.json")
            if elapsed - last >= 15 or not last:
                last = elapsed
                free = shutil.disk_usage(run).free
                log.update(
                    state="measuring" if clock else "connecting",
                    heartbeatUtc=utc(),
                    supervisedSeconds=round(elapsed, 2),
                    quorum=clock,
                    latestSample=live,
                    freeBytes=free,
                )
                write(run / "supervisor.json", log)
                if free < 4 * 1024**3:
                    reason = "workspace free space below four GiB"
                    break
                if (
                    clock
                    and live
                    and time.time() - (server / "soak-live.json").stat().st_mtime > 300
                ):
                    reason = "server samples stale for five minutes"
                    break
            if elapsed > 49 * 3600:
                reason = "measurement missed 49-hour supervisor deadline"
                break
            time.sleep(2)
    finally:
        for child in children:
            child.close()
        U.CloseDesktop(desktop)
        log.update(
            state="finished" if result and result.get("pass") else "interrupted",
            reason=reason,
            finishUtc=utc(),
            result=result,
        )
        write(run / "supervisor.json", log)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("run", type=Path)
    p.add_argument(
        "--java", type=Path, default=Path("C:/Program Files/Java/jdk-17/bin/java.exe")
    )
    p.add_argument(
        "--peer-after",
        type=Path,
        help="Wait for a concurrent graphical test to close before opening the peer hub",
    )
    a = p.parse_args()
    supervise(
        a.run.resolve(),
        a.java.resolve(strict=True),
        a.peer_after.resolve() if a.peer_after else None,
    )
