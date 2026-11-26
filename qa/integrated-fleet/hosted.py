"""Run the original 100-block cabin trip in an isolated Linux integrated game."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import uuid
from zipfile import ZipFile

from audit import audit

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'qa/linux-fleet'))
from prepare import prepare_installation
from runtime import command, hosted_only, sha

HULL_ARCHIVE = HERE / 'evidence/run-100-releaselan.zip'
HULL_ARCHIVE_SHA256 = '1d5a9977f1259bf1a9e83fbc2dc931df2e6b0e2c36f6f8e7b264277b66ea11cc'
OPTIONS = ('version:3465\nlang:zh_cn\nonboardAccessibility:false\nfullscreen:false\n'
           'pauseOnLostFocus:false\nrenderDistance:5\nsimulationDistance:4\nmaxFps:30\n'
           'mipmapLevels:0\ntutorialStep:none\nguiScale:2\n')


def install_hull(game):
    if sha(HULL_ARCHIVE) != HULL_ARCHIVE_SHA256:
        raise ValueError('The original ship archive differs from the verified LAN trial')
    inputs = game / 'inputs'
    inputs.mkdir()
    with ZipFile(HULL_ARCHIVE) as archive:
        (inputs / '100.nbt').write_bytes(archive.read('inputs/100.nbt'))


def client(root, directory, username):
    args = command(root / 'launcher', directory)
    args[args.index('--username') + 1] = username
    args[args.index('--uuid') + 1] = uuid.uuid3(uuid.NAMESPACE_DNS, username).hex
    args[args.index('-Xmx2304m')] = '-Xmx3072m' if username == 'FleetCaptain' else '-Xmx2048m'
    args[1:1] = ['-Dstarbridge.integrated.length=100', '-Dio.netty.eventLoopThreads=6',
                 f'-Dretrofit.minecraftHome={directory / "legacy-launcher"}']
    display = ['xvfb-run', '-a', '-s', '-screen 0 1024x768x24 -nolisten tcp +extension GLX']
    with (directory / 'java.log').open('xb') as output:
        return subprocess.Popen([*display, *args], cwd=directory, stdin=subprocess.DEVNULL,
                                stdout=output, stderr=subprocess.STDOUT, start_new_session=True)


def stop_client(process):
    if process is None or process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=12)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def diagnostics(role, dest):
    if not role.is_dir():
        return
    dest.mkdir(parents=True, exist_ok=True)
    for name in ('integrated-failure.json', 'pilot-result.json', 'integrated-server.json',
                 'guest-result.json', 'guest-worlds.txt', 'lan-connection.json', 'input.csv',
                 'graphics.json', 'screenshots/lunar-return-wait.png'):
        path = role / name
        if path.is_file():
            shutil.copyfile(path, dest / path.name)
    for name in ('java.log', 'logs/latest.log', 'frames.csv', 'guest-frames.csv'):
        path = role / name
        if path.is_file():
            with path.open('rb') as stream:
                stream.seek(max(0, path.stat().st_size - 128 * 1024))
                (dest / path.name).write_bytes(stream.read())


def run(root, evidence, integrated_fixture, fleet_fixture, flight_fixture, lan):
    hosted_only()
    host = root / 'host'
    visitor = host / 'visitor'
    roles = [host, visitor] if lan else [host]
    evidence.mkdir(parents=True, exist_ok=True)
    installation = prepare_installation(root, integrated_fixture, roles)
    for role in roles:
        for fixture in (fleet_fixture, flight_fixture):
            shutil.copyfile(fixture, role / 'mods' / fixture.name)
        (role / 'options.txt').write_text(OPTIONS, encoding='utf8')
        (role / 'config/fml.toml').write_text('earlyWindowControl=false\n', encoding='utf8')
        (role / 'config/forge-client.toml').write_text('[client]\nshowLoadWarnings=false\n', encoding='utf8')
    (host / 'integrated-flight.fixture').touch()
    if lan:
        (host / 'integrated-lan-host.fixture').touch()
        (visitor / 'integrated-lan-guest.fixture').touch()
    install_hull(host)
    (evidence / 'installed.json').write_text(json.dumps(installation, indent=2) + '\n', encoding='utf8')

    started = time.monotonic()
    captain = guest = None
    try:
        captain = client(root, host, 'FleetCaptain')
        # The guest waits at its title screen until the integrated host opens the LAN world.
        if lan:
            guest = client(root, visitor, 'FleetVisitor')
        while captain.poll() is None:
            problem = host / 'integrated-failure.json'
            if problem.is_file():
                raise RuntimeError(problem.read_text(encoding='utf8'))
            if guest is not None and guest.poll() is not None and not (host / 'pilot-result.json').is_file():
                raise RuntimeError(f'The LAN guest exited before the return ({guest.returncode})')
            if time.monotonic() - started > 560:
                raise TimeoutError('The integrated flight did not finish within 560 seconds')
            time.sleep(1)
        if captain.returncode != 0 or (guest is not None and guest.wait(timeout=30) != 0):
            raise RuntimeError('A graphical client did not exit cleanly')
        jars = {path.name: sha(path) for folder in ('mods', 'retrofit/mods') for path in (host / folder).glob('*.jar')}
        if lan:
            guest_jars = {path.name: sha(path) for folder in ('mods', 'retrofit/mods') for path in (visitor / folder).glob('*.jar')}
            if guest_jars != jars:
                raise ValueError('Host and visitor mod files differ')
        metadata = {'pid': captain.pid, 'guestPid': guest.pid if guest else None, 'reason': 'client exited',
                    'seconds': round(time.monotonic() - started, 3), 'os': 'GitHub-hosted Linux with Xvfb', 'jars': jars}
        (host / 'launch-result.json').write_text(json.dumps(metadata, indent=2) + '\n', encoding='utf8')
        result = audit(host, evidence)
        result['source'] = installation['source']
        (evidence / 'audit.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
        screenshot = host / 'screenshots/returned-deck.png'
        if screenshot.is_file():
            shutil.copyfile(screenshot, evidence / screenshot.name)
    finally:
        for process in (captain, guest):
            stop_client(process)
        for role in roles:
            diagnostics(role, evidence / role.name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--integrated-fixture', type=Path, required=True)
    parser.add_argument('--fleet-fixture', type=Path, required=True)
    parser.add_argument('--flight-fixture', type=Path, required=True)
    parser.add_argument('--lan', action='store_true')
    options = parser.parse_args()
    run(options.root.resolve(), options.evidence.resolve(), options.integrated_fixture.resolve(),
        options.fleet_fixture.resolve(), options.flight_fixture.resolve(), options.lan)
