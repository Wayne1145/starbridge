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
import sys
import shlex
import time
import uuid
import _winapi

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from local_client_policy import create_private_desktop, require_local_client
CLIENT = "OrbitCaptain"
ENGINEER = "OrbitEngineer"
BARITONE = "6767478e2dd51c6ef278dcf76aabc78f238bc545e8806f82db718b9225ced45e"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify_server_args(file):
    text = file.read_text('utf-8-sig')
    arguments = shlex.split(text, comments=True, posix=True)
    targets = [arguments[i + 1] for i, arg in enumerate(arguments[:-1]) if arg == '--launchTarget']
    targets.extend(arg.split('=', 1)[1] for arg in arguments if arg.startswith('--launchTarget='))
    if (targets != ['forgeserver'] or 'nogui' not in arguments or any(arg.startswith('@') for arg in arguments)
            or any(arg.lower() in {'forgeclient', 'net.minecraft.client.main.main'} for arg in arguments)):
        raise ValueError('The diagnostic profile must launch a dedicated Forge server with nogui')


def prepare(instance, template, port, crew=False, actor=CLIENT, fixture=None, *, server_only=False):
    assert instance.parent == ROOT and instance.name.startswith(
        "run-survival-expedition-"
    )
    qa = fixture or ROOT / "build/qa/survival-expedition/starbridge-survival-expedition-1.0.0.jar"
    pathfinder = ROOT / "build/survival-deps/baritone-api-forge-1.10.5.jar"
    assert qa.is_file()
    if not server_only:
        assert sha(pathfinder) == BARITONE
    instance.mkdir(exist_ok=True)
    sides = ("server",) if server_only else (("server", CLIENT, ENGINEER) if crew else ("server", actor))
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
        (instance / role / "expedition-join-port.txt").write_text(str(port), encoding="ascii")
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


def java17():
    for candidate in (os.environ.get("JAVA_HOME"), r"C:\Program Files\Java\jdk-17"):
        if not candidate:
            continue
        home = Path(candidate)
        binary, version = home / "bin/java.exe", home / "release"
        if binary.is_file() and version.is_file() and any(
            line.startswith(('JAVA_VERSION="17.', 'JAVA_VERSION="17"'))
            for line in version.read_text(encoding="utf-8").splitlines()
        ):
            return binary
    raise RuntimeError("Set JAVA_HOME to an installed JDK 17 for this fixture")


def archive_episode(instance, episode, inventory, finish, start):
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
            "machine-fabrication-probe.json",
            "original-machine-layout-probe.json",
            "original-workshop-assembly-probe.json",
            "generator-power-probe.json",
            "workshop-power-recipes-probe.json",
            "shipyard-recipes-probe.json",
            "shipyard-supply-chain-probe.json",
            "gold-ore-press-feed-probe.json",
            "original-arc-furnace-probe.json",
            "survival-recipes.json",
            "survival-first-guide.json",
            "survival-obsidian-site.json",
            "survival-scout-route.json",
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


def run_diagnostic(instance, episode, java, minutes, result_name):
    server = instance / "server"
    verify_server_args(server / "launch.args")
    result = server / result_name
    before = result.stat().st_mtime_ns if result.is_file() else None
    finish = "time limit"
    start = time.monotonic()
    try:
        with (episode / "server.log").open("wb") as console:
            process = subprocess.Popen([str(java), "@launch.args"], cwd=server, stdin=subprocess.PIPE,
                    stdout=console, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            print("server", process.pid, flush=True)
            try:
                while time.monotonic() - start < minutes * 60:
                    if result.is_file() and result.stat().st_mtime_ns != before:
                        finish = {
                            "generator-power-probe.json": "generator energy observed",
                            "blast-brick-probe.json": "recipe audit",
                            "original-workshop-assembly-probe.json": "workshop structures observed",
                            "original-arc-furnace-probe.json": "arc furnace result observed",
                        }[result_name]
                        break
                    if process.poll() is not None:
                        finish = "process exited"
                        break
                    if (instance / "stop-after-save").exists():
                        finish = "operator checkpoint"
                        break
                    time.sleep(1)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b"stop\n")
                        process.stdin.flush()
                    except (BrokenPipeError, OSError):
                        pass
                    try:
                        process.wait(timeout=35)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
                process.stdin.close()
    finally:
        archive_episode(instance, episode, ("server",), finish, start)
    print(episode, finish, flush=True)


def run(
    instance,
    template,
    port,
    max_minutes,
    recipe_only=False,
    crew=False,
    actor=CLIENT,
    power_only=False,
    fixture=None,
    workshop_only=False,
    arc_furnace_only=False,
):
    server_only = recipe_only or power_only or workshop_only or arc_furnace_only
    if not server_only:
        require_local_client(ROOT)
    if server_only and (instance / "server/first-join.json").exists():
        raise ValueError(
            "Use a separate pristine diagnostic world. This expedition already has player history."
        )
    if workshop_only and (instance / "server/world").exists():
        raise ValueError("The workshop probe requires a new diagnostic world")
    if power_only and not instance.name.startswith("run-survival-expedition-power-"):
        raise ValueError(
            "Power diagnostics place native machines in a separate run-survival-expedition-power-* instance"
        )
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", port))
    if arc_furnace_only and (not instance.name.startswith("run-survival-expedition-arc-") or (instance / "server").exists()):
        raise ValueError("Arc furnace diagnostics require a new run-survival-expedition-arc-* instance")
    inventory = prepare(instance, template, port, crew, actor, fixture, server_only=server_only)
    if recipe_only:
        (instance / "server/recipe-diagnostics.fixture").touch()
    if power_only:
        (instance / "server/power-diagnostics.fixture").touch()
    if workshop_only:
        (instance / "server/workshop-layout-diagnostics.fixture").touch()
    if arc_furnace_only:
        (instance / "server/arc-furnace-diagnostics.fixture").touch()
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
    if server_only:
        result_name = ("original-arc-furnace-probe.json" if arc_furnace_only else
                       "original-workshop-assembly-probe.json" if workshop_only else
                       "generator-power-probe.json" if power_only else "blast-brick-probe.json")
        run_diagnostic(instance, episode, java17(), max_minutes, result_name)
        return
    os.environ["MESA_SHADER_CACHE_DISABLE"] = "true"
    os.environ["__GL_SHADER_DISK_CACHE"] = "0"
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
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
    java = java17()
    deskname = "StarbridgeExpedition" + uuid.uuid4().hex[:12]
    desktop = create_private_desktop(ROOT, deskname)
    running = {}
    files = []

    def spawn(side):
        require_local_client(ROOT)
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
        roles = [role for role in inventory if role != "server"]
        for index, role in enumerate(roles):
            spawn(role)
            if index + 1 < len(roles):
                # Let the first legacy handshake and chunk stream finish before starting a second JVM.
                until = time.monotonic() + 140
                while time.monotonic() < until and alive("server") and alive(role):
                    if f"{role} joined the game" in (episode / "server.log").read_text(encoding="utf8", errors="replace"):
                        break
                    time.sleep(1)
        while time.monotonic() - start < max_minutes * 60:
            if not all(alive(side) for side in running):
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
        desktop.close()
        archive_episode(instance, episode, inventory, finish, start)
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
    diagnostics = p.add_mutually_exclusive_group()
    diagnostics.add_argument("--recipe-only", action="store_true")
    diagnostics.add_argument("--power-only", action="store_true")
    diagnostics.add_argument("--workshop-only", action="store_true")
    diagnostics.add_argument("--arc-furnace-only", action="store_true")
    p.add_argument("--crew", action="store_true")
    p.add_argument("--fixture", type=Path, help="Use the test JAR from a pinned independent build")
    p.add_argument("--actor", choices=(CLIENT, ENGINEER), default=CLIENT)
    a = p.parse_args()
    if not (a.recipe_only or a.power_only or a.workshop_only or a.arc_furnace_only):
        require_local_client(ROOT)
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
            a.fixture,
            a.workshop_only,
            a.arc_furnace_only,
        )
