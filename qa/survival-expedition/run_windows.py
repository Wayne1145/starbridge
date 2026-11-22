"""Run the original pack in a private Windows desktop, retaining one survival world across sessions."""

import argparse
import ctypes
from contextlib import contextmanager
from ctypes import wintypes as w
import hashlib
import json
import msvcrt
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid
import _winapi

ROOT = Path(__file__).resolve().parents[2]
CLIENT = "OrbitCaptain"
ENGINEER = "OrbitEngineer"
BARITONE = "6767478e2dd51c6ef278dcf76aabc78f238bc545e8806f82db718b9225ced45e"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(instance, template, port, crew=False, actor=CLIENT):
    assert instance.parent == ROOT and instance.name.startswith(
        "run-survival-expedition-"
    )
    qa = ROOT / "build/qa/survival-expedition/starbridge-survival-expedition-1.0.0.jar"
    pathfinder = ROOT / "build/survival-deps/baritone-api-forge-1.10.5.jar"
    assert qa.is_file() and sha(pathfinder) == BARITONE
    instance.mkdir(exist_ok=True)
    sides = ("server", CLIENT, ENGINEER) if crew else ("server", actor)
    for side in sides:
        target, source = instance / side, template / side
        if not target.exists():
            target.mkdir()
            for name in ("mods", "retrofit/mods", "config", "tmp", "qa-home"):
                (target / name).mkdir(parents=True, exist_ok=True)
            for name in ("mods", "retrofit/mods"):
                for jar in (source / name).glob("*.jar"):
                    if "fixture" not in jar.name:
                        shutil.copyfile(jar, target / name / jar.name)
            # No dimension, chunk, player data, fixtures or other saved gameplay is copied.
            shutil.copytree(
                source / "retrofit/.retrofit", target / "retrofit/.retrofit"
            )
            (target / "retrofit/retrofit.properties").write_text("download-files=no\n")
            shutil.copyfile(
                ROOT / "pack/config/ExtraPlanets.cfg",
                target / "config/ExtraPlanets.cfg",
            )
            if side != "server":
                shutil.copytree(source / "natives", target / "natives")
                shutil.copyfile(source / "options.txt", target / "options.txt")
                shutil.copyfile(
                    source / "config/forge-client.toml",
                    target / "config/forge-client.toml",
                )
            else:
                (target / "eula.txt").write_text("eula=true\n")
                (target / "server.properties").write_text(
                    f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nmax-players=2\nallow-flight=false\nview-distance=5\nsimulation-distance=4\nmax-tick-time=60000\nlevel-seed=576618501\ngamemode=survival\ndifficulty=normal\ninitial-disabled-packs=\nenable-command-block=false\n"
                )
            arguments = (source / "launch.args").read_text(encoding="utf-8")

            def relocate(path, dest, text):
                for variant, updated in (
                    (path.as_posix(), dest.as_posix()),
                    (str(path).replace("\\", "\\\\"), str(dest).replace("\\", "\\\\")),
                    (str(path), str(dest)),
                ):
                    text = text.replace(variant, updated)
                return text

            arguments = relocate(source, target, arguments)
            arguments = (
                "\n".join(
                    line
                    for line in arguments.splitlines()
                    if not line.startswith('"-Dstarbridge.')
                )
                + "\n"
            )
            if side != "server":
                arguments += f'"--quickPlayMultiplayer"\n"127.0.0.1:{port}"\n'
            assert str(template.name) not in arguments
            (target / "launch.args").write_text(arguments, encoding="utf-8")
            (target / "survival-expedition.fixture").touch()
        assert not list(target.glob("starbridge-*.fixture"))
        shutil.copyfile(qa, target / "mods" / qa.name)
        if side != "server":
            shutil.copyfile(pathfinder, target / "mods" / pathfinder.name)
    # Existing single-player-capacity trials can continue in the same server world.
    settings = instance / "server/server.properties"
    settings.write_text(settings.read_text().replace("max-players=1", "max-players=2"))
    for role in sides[1:]:
        script = instance / role / "expedition-orders.txt"
        if not script.exists():
            shutil.copyfile(Path(__file__).with_name("first-workbench.txt"), script)
    inventory = {
        side: {
            folder: {
                jar.name: sha(jar)
                for jar in sorted((instance / side / folder).glob("*.jar"))
            }
            for folder in ("mods", "retrofit/mods")
        }
        for side in sides
    }
    return inventory


class Startup(ctypes.Structure):
    _fields_ = (
        [
            ("cb", w.DWORD),
            ("reserved", w.LPWSTR),
            ("desktop", w.LPWSTR),
            ("title", w.LPWSTR),
        ]
        + [(f"dim{i}", w.DWORD) for i in range(7)]
        + [
            ("flags", w.DWORD),
            ("show", w.WORD),
            ("reserved2", w.WORD),
            ("reserved3", ctypes.c_void_p),
            ("stdin", w.HANDLE),
            ("stdout", w.HANDLE),
            ("stderr", w.HANDLE),
        ]
    )


class Process(ctypes.Structure):
    _fields_ = [
        ("process", w.HANDLE),
        ("thread", w.HANDLE),
        ("pid", w.DWORD),
        ("tid", w.DWORD),
    ]


def run(
    instance,
    template,
    port,
    max_minutes,
    recipe_only=False,
    crew=False,
    actor=CLIENT,
    power_only=False,
):
    if not recipe_only and (ROOT / ".tools/no-local-game-clients").is_file():
        raise RuntimeError("Local graphical game tests are disabled on this workstation")
    if (power_only or recipe_only) and (instance / "server/first-join.json").exists():
        raise ValueError(
            "Use a separate pristine diagnostic world. This expedition already has player history."
        )
    if power_only and not instance.name.startswith("run-survival-expedition-power-"):
        raise ValueError(
            "Power diagnostics place native machines in a separate run-survival-expedition-power-* instance"
        )
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
    inventory = prepare(instance, template, port, crew, actor)
    if recipe_only:
        (instance / "server/recipe-diagnostics.fixture").touch()
    if power_only:
        (instance / "server/power-diagnostics.fixture").touch()
    episode = (
        instance
        / "recordings"
        / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:4])
    )
    episode.mkdir(parents=True)
    (episode / "installed.json").write_text(
        json.dumps(inventory, indent=2), encoding="utf-8"
    )
    for key, sub in {
        "TEMP": "tmp",
        "TMP": "tmp",
        "USERPROFILE": "profile",
        "APPDATA": "appdata",
        "LOCALAPPDATA": "localappdata",
    }.items():
        value = instance / "_runtime" / sub
        value.mkdir(parents=True, exist_ok=True)
        os.environ[key] = str(value)
    os.environ["MESA_SHADER_CACHE_DISABLE"] = "true"
    os.environ["__GL_SHADER_DISK_CACHE"] = "0"
    user = ctypes.WinDLL("user32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    user.CreateDesktopW.argtypes = [
        w.LPCWSTR,
        w.LPCWSTR,
        ctypes.c_void_p,
        w.DWORD,
        w.DWORD,
        ctypes.c_void_p,
    ]
    user.CreateDesktopW.restype = w.HANDLE
    user.CloseDesktop.argtypes = [w.HANDLE]
    kernel.CreateProcessW.argtypes = [
        w.LPCWSTR,
        w.LPWSTR,
        ctypes.c_void_p,
        ctypes.c_void_p,
        w.BOOL,
        w.DWORD,
        ctypes.c_void_p,
        w.LPCWSTR,
        ctypes.POINTER(Startup),
        ctypes.POINTER(Process),
    ]
    kernel.CloseHandle.argtypes = [w.HANDLE]
    deskname = "StarbridgeExpedition" + uuid.uuid4().hex[:12]
    desktop = user.CreateDesktopW(deskname, None, None, 0, 0x1FF, None)
    assert desktop, ctypes.get_last_error()
    java = (
        Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17"))
        / "bin/java.exe"
    )
    assert java.is_file()
    running = {}
    files = []

    def spawn(side):
        target = instance / side
        console = (episode / (side + ".log")).open("wb")
        r, writer = os.pipe()
        cmdin = os.fdopen(writer, "wb", 0)
        files.extend([console, cmdin])
        out = msvcrt.get_osfhandle(console.fileno())
        inp = msvcrt.get_osfhandle(r)
        os.set_handle_inheritable(out, True)
        os.set_handle_inheritable(inp, True)
        settings = Startup()
        settings.cb = ctypes.sizeof(settings)
        settings.desktop = deskname
        settings.flags = 0x101
        settings.show = 0
        settings.stdin = inp
        settings.stdout = settings.stderr = out
        proc = Process()
        try:
            args = ctypes.create_unicode_buffer(
                subprocess.list2cmdline([str(java), "@launch.args"])
            )
            assert kernel.CreateProcessW(
                str(java),
                args,
                None,
                None,
                True,
                0x08000000,
                None,
                str(target),
                ctypes.byref(settings),
                ctypes.byref(proc),
            ), ctypes.get_last_error()
        finally:
            os.close(r)
            os.set_handle_inheritable(out, False)
        kernel.CloseHandle(proc.thread)
        running[side] = (proc.process, cmdin)
        print(side, proc.pid, flush=True)

    def alive(side):
        return _winapi.WaitForSingleObject(running[side][0], 0) == 258

    finish = "time limit"
    start = time.monotonic()
    try:
        spawn("server")
        while alive("server") and time.monotonic() - start < 180:
            if "Done (" in (episode / "server.log").read_text(
                encoding="utf8", errors="replace"
            ):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Survival server did not reach readiness")
        if not (recipe_only or power_only):
            for role in inventory:
                if role != "server":
                    spawn(role)
        while time.monotonic() - start < max_minutes * 60:
            if (
                power_only
                and (instance / "server/generator-power-probe.json").is_file()
                and (instance / "server/generator-power-probe.json").stat().st_mtime
                >= episode.stat().st_ctime
            ):
                finish = "generator energy observed"
                break
            result_file = instance / "server/blast-brick-probe.json"
            if (
                recipe_only
                and result_file.exists()
                and result_file.stat().st_mtime >= episode.stat().st_ctime
            ):
                finish = "recipe audit"
                break
            if not alive("server") or (
                not recipe_only
                and any(not alive(side) for side in running if side != "server")
            ):
                finish = "process exited"
                break
            if (instance / "stop-after-save").exists():
                finish = "operator checkpoint"
                break
            if any(
                file.stat().st_mtime >= episode.stat().st_ctime
                for side in running
                for file in (instance / side / "crash-reports").glob("*.txt")
            ):
                finish = "crash report"
                break
            time.sleep(1)
    finally:
        if "server" in running and alive("server"):
            try:
                running["server"][1].write(b"stop\n")
                _winapi.WaitForSingleObject(running["server"][0], 35000)
            except OSError:
                pass
        for side, (handle, _) in running.items():
            if alive(side):
                _winapi.TerminateProcess(handle, 0)
            _winapi.CloseHandle(handle)
        for f in files:
            f.close()
        user.CloseDesktop(desktop)
        for side in inventory:
            copy_to = episode / side
            copy_to.mkdir(exist_ok=True)
            for pattern in (
                "first-join*.json",
                "survival-inventory*.json",
                "survival-events.log",
                "expedition-orders.txt",
                "expedition-cursor.txt",
                "blast-brick-probe.json",
                "survival-recipes.json",
            ):
                for source in (instance / side).glob(pattern):
                    shutil.copyfile(source, copy_to / source.name)
        for player in (instance / "server/world/playerdata").glob("*.dat"):
            dest = episode / "playerdata"
            dest.mkdir(exist_ok=True)
            shutil.copyfile(player, dest / player.name)
        checkpoint = instance / "stop-after-save"
        if checkpoint.is_file():
            checkpoint.rename(episode / "stop-request")
        (episode / "end.json").write_text(
            json.dumps(
                {"reason": finish, "seconds": round(time.monotonic() - start)}, indent=2
            )
        )
    print(episode, finish, flush=True)


@contextmanager
def instance_lock(instance):
    if instance.parent != ROOT or not instance.name.startswith(
        "run-survival-expedition-"
    ):
        raise ValueError("Select an isolated expedition directory in the workspace")
    instance.mkdir(exist_ok=True)
    with (instance / "expedition.lock").open("a+b") as handle:
        if handle.seek(0, os.SEEK_END) == 0:
            handle.write(b"1")
            handle.flush()
        handle.seek(0)
        try:
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as exc:
            raise RuntimeError(
                "Another launcher already owns this expedition world"
            ) from exc
        try:
            yield
        finally:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--world", type=Path, required=True)
    p.add_argument("--runtime", type=Path, required=True)
    p.add_argument("--port", type=int, default=26983)
    p.add_argument("--minutes", type=int, default=45)
    p.add_argument("--recipe-only", action="store_true")
    p.add_argument("--power-only", action="store_true")
    p.add_argument("--crew", action="store_true")
    p.add_argument("--actor", choices=(CLIENT, ENGINEER), default=CLIENT)
    a = p.parse_args()
    with instance_lock(a.world.resolve()):
        run(
            a.world.resolve(),
            a.runtime.resolve(),
            a.port,
            a.minutes,
            a.recipe_only,
            a.crew,
            a.actor,
            a.power_only,
        )
