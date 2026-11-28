"""Replay the device picker, native CC computer and MCP bridge in a fresh local Forge world."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from local_client_policy import create_private_desktop, require_local_client
sys.path.insert(0, str(HERE.parent / 'concurrent-orbits'))
from soak_host import Child


def relocate(text, source, destination):
    for a, b in ((source.as_posix(), destination.as_posix()), (str(source), str(destination))):
        text = text.replace(a.replace(chr(92), chr(92) * 2), b.replace(chr(92), chr(92) * 2)).replace(a, b)
    return text


def cache_copy(src, dst):
    # Only Maven libraries are shared. Retrofit can regenerate its bootstrap and transformed game jars.
    if Path(src).suffix == '.jar' and 'libraries' in Path(src).parts:
        try:
            os.link(src, dst)
            return dst
        except OSError:
            pass
    return shutil.copy2(src, dst)


def prepare(run, template, product, fixture, dock, port, *, marker='starbridge-controls.fixture'):
    run.mkdir(exist_ok=False)
    for side in ('server', 'LunarTester'):
        src, dest = template / side, run / side
        for directory in ('mods', 'retrofit/mods', 'config'):
            (dest / directory).mkdir(parents=True, exist_ok=True)
        for directory in ('mods', 'retrofit/mods'):
            for jar in (src / directory).glob('*.jar'):
                if not jar.name.startswith('starbridge'):
                    shutil.copy2(jar, dest / directory / jar.name)
        for jar, directory in ((product, 'mods'), (fixture, 'mods'), (dock, 'retrofit/mods')):
            shutil.copy2(jar, dest / directory / jar.name)
        shutil.copytree(src / 'retrofit/.retrofit', dest / 'retrofit/.retrofit', copy_function=cache_copy)
        (dest / 'retrofit/retrofit.properties').write_text('download-files=no\n')
        (dest / marker).touch()
        for name in ('ExtraPlanets.cfg', 'fml.toml', 'forge-client.toml'):
            if (src / 'config' / name).is_file():
                shutil.copy2(src / 'config' / name, dest / 'config' / name)
        args = relocate((src / 'launch.args').read_text('utf8'), src, dest)
        args = re.sub(r'-Xmx[0-9]+[mM]', '-Xmx1728m', args)
        (dest / 'launch.args').write_text(args, 'utf8')
        if (src / 'base.args').is_file():
            (dest / 'base.args').write_text(relocate((src / 'base.args').read_text('utf8'), src, dest), 'utf8')
        if side == 'server':
            shutil.copy2(src / 'eula.txt', dest / 'eula.txt')
            (dest / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nmax-players=1\nallow-flight=true\ngamemode=survival\ndifficulty=peaceful\n')
        else:
            shutil.copy2(src / 'options.txt', dest / 'options.txt')
            shutil.copytree(src / 'natives', dest / 'natives')
            (dest / 'lunar-server.txt').write_text(f'127.0.0.1:{port}')


def run_game(run, java):
    require_local_client(ROOT)
    name = 'StarbridgeDevices' + uuid.uuid4().hex[:10]
    desktop = create_private_desktop(ROOT, name)
    children, bridge = [], None
    server = run / 'server'
    result = server / 'controls-result.json'
    log = (run / 'mcp-session.log').open('wb')
    try:
        children.append(Child(java, server, name, ['@launch.args']))
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline and children[0].alive():
            text = server / 'logs/latest.log'
            if text.is_file() and 'Done (' in text.read_text('utf8', errors='replace'):
                break
            time.sleep(1)
        else:
            raise RuntimeError('The dedicated server did not finish loading')
        children.append(Child(java, run / 'LunarTester', name, ['@launch.args']))
        bridge = subprocess.Popen([sys.executable, str(HERE / 'mcp_session.py'), str(server)], cwd=ROOT, stdout=log, stderr=log, creationflags=subprocess.CREATE_NO_WINDOW)
        deadline = time.monotonic() + 285
        while time.monotonic() < deadline:
            if result.is_file() and json.loads(result.read_bytes()).get('pass') is True:
                if bridge.wait(timeout=20) != 0:
                    raise RuntimeError('MCP client failed')
                return
            if (run / 'LunarTester/device-dismiss-failed.txt').exists():
                raise RuntimeError('A late server update reopened the closed helm')
            if result.exists() or not all(child.alive() for child in children) or bridge.poll() not in (None, 0):
                raise RuntimeError('The isolated trial ended before both controls completed')
            time.sleep(1)
        raise RuntimeError('No finished device trial within the allotted time')
    finally:
        if bridge and bridge.poll() is None:
            bridge.terminate()
            bridge.wait(timeout=10)
        for child in children:
            child.close()
        log.close()
        desktop.close()


if __name__ == '__main__':
    require_local_client(ROOT)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True, help='Prepared original pack with server and LunarTester directories')
    parser.add_argument('--label', required=True)
    parser.add_argument('--port', type=int, default=26877)
    parser.add_argument('--java', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch('[a-z0-9-]+', args.label):
        parser.error('Choose a simple label for a fresh workspace instance')
    instance = ROOT / ('run-bridge-controls-' + args.label)
    for port in (args.port, 18765):
        with socket.socket() as check:
            check.bind(('127.0.0.1', port))
    jars = ROOT / 'build/libs'
    product = jars / 'starbridge-1.20.1-0.3.0-dev.jar'
    fixture = ROOT / 'build/qa/bridge-controls/starbridge-controls-fixture-0.1.0.jar'
    dock = jars / 'starbridge-dock-1.7.10-0.3.0-dev.jar'
    prepare(instance, args.runtime.resolve(strict=True), product, fixture, dock, args.port)
    outcome = {'productSha256': hashlib.sha256(product.read_bytes()).hexdigest(), 'fixtureSha256': hashlib.sha256(fixture.read_bytes()).hexdigest(), 'legacySha256': hashlib.sha256(dock.read_bytes()).hexdigest()}
    try:
        run_game(instance, args.java.resolve(strict=True))
        outcome['pass'] = True
    finally:
        outcome.setdefault('pass', False)
        (instance / 'run-result.json').write_text(json.dumps(outcome, indent=2), 'utf8')
