"""Continue a stopped original Galacticraft Moon expedition on a separate Windows desktop."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import time
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from local_client_policy import create_private_desktop, require_local_client
sys.path.insert(0, str(HERE.parent / 'native-fuel'))
from run_windows import Child, available, controls
from audit_lunar_walk import saved_player

ROLE = 'OrbitEngineer'
MAIN = 'starbridge-1.20.1-0.3.0-dev.jar'
FIXTURE = 'starbridge-flight-fixture-1.0.0.jar'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def report(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', 'utf8')


def instance(label):
    if not re.fullmatch('[a-z0-9-]+', label):
        raise ValueError(f'Invalid run label {label!r}')
    return ROOT / ('run-lunar-local-' + label)


def prepare(previous, run, fixture, mode, product=None, observe=False):
    src, dst = previous / ROLE, run / ROLE
    launch = json.loads((previous / 'launch-result.json').read_bytes())
    if digest(src / 'mods' / MAIN) != launch['production']:
        raise ValueError('The saved source and its recorded flight mod differ')
    old_player = saved_player(previous)
    if old_player['health'] <= 0 or 'SHIP' not in old_player['marks']:
        raise ValueError('A living survival player with the original ship marker is required')
    if mode == 'dungeon':
        discovery = json.loads((src / 'observed-entrance.json').read_bytes())
        if not discovery.get('capturedFacingTheMasonry') or not discovery.get('identifiedInFieldGuide'):
            raise ValueError('The player has not confirmed visible dungeon masonry on this expedition')
    run.mkdir(exist_ok=False)
    dst.mkdir()
    for directory in ('mods', 'retrofit/mods', 'config', 'natives'):
        shutil.copytree(src / directory, dst / directory)
    shutil.copy2(fixture, dst / 'mods' / FIXTURE)
    if product is not None:
        shutil.copy2(product, dst / 'mods' / MAIN)
    shutil.copytree(src / 'retrofit/.retrofit', dst / 'retrofit/.retrofit', copy_function=controls.cache_copy)
    (dst / 'retrofit/retrofit.properties').write_text('download-files=never\n', 'utf8')
    shutil.copytree(src / 'saves/LunarTrail', dst / 'saves/LunarTrail', ignore=shutil.ignore_patterns('session.lock'))
    shutil.copy2(src / 'options.txt', dst / 'options.txt')
    args = controls.relocate((src / 'launch.args').read_text('utf8'), src, dst)
    (dst / 'launch.args').write_text(args, 'utf8')
    for flag in ('starbridge-lunar-trail.fixture', 'lunar-local-resume.fixture'):
        (dst / flag).touch()
    shutil.copy2(src / 'lunar-trail-live.json', dst / 'lunar-deck-checkpoint.json')
    if mode == 'search':
        shutil.copy2(src / 'lunar-walk-live.json', dst / 'lunar-search-checkpoint.json')
        if (src / 'walked-rims.json').is_file():
            shutil.copy2(src / 'walked-rims.json', dst / 'walked-rims.json')
        (dst / 'lunar-continue-field-search.fixture').touch()
        (dst / 'lunar-wide-recon.fixture').touch()
    else:
        shutil.copy2(src / 'observed-entrance.json', dst / 'observed-entrance.json')
        (dst / 'lunar-continue-dungeon.fixture').touch()
        if (src / 'lunar-dungeon-audit.json').is_file():
            shutil.copy2(src / 'lunar-dungeon-audit.json', dst / 'lunar-dungeon-audit.json')
    if observe or mode == 'dungeon':
        (dst / 'field-operator.fixture').write_text('player field observation\n', 'utf8')
        report(dst / 'field-input.json', {'id': 1, 'steps': [{'action': 'photo', 'ticks': 3}]})
    return {'mode': mode, 'source': previous.name, 'sourcePlayer': old_player, 'sourceProduction': launch['production'],
            'production': digest(dst / 'mods' / MAIN),
            'qa': digest(dst / 'mods' / FIXTURE), 'sourceLevelSha256': digest(src / 'saves/LunarTrail/level.dat')}


def play(run, java, mode, minutes, result):
    require_local_client(ROOT)
    folder = run / ROLE
    name = 'StarbridgeLunar' + uuid.uuid4().hex[:12]
    desktop = create_private_desktop(ROOT, name)
    child = None
    start = time.monotonic()
    minimum = available()
    try:
        child = Child(java, folder, name, ['@launch.args'])
        print('Private integrated Moon client PID', child.pid, flush=True)
        low = 0
        while child.alive():
            sample = available()
            minimum = tuple(min(a, b) for a, b in zip(minimum, sample))
            low = low + 1 if sample[0] < 1300 or sample[1] < 2300 or sample[2] < 2800 else 0
            reason = None
            if (folder / 'lunar-trail-result.json').is_file():
                reason = 'expedition result recorded'
            elif mode == 'search' and (folder / 'observed-entrance.json').is_file():
                reason = 'first-person observation confirmed in the field guide'
            elif low >= 3:
                reason = 'resource checkpoint'
            elif (run / 'request-stop-trail').exists():
                reason = 'operator checkpoint'
            elif time.monotonic() - start >= minutes * 60:
                reason = 'timed checkpoint'
            if reason is not None:
                result['reason'] = reason
                (folder / 'stop-local-lunar-test').touch()
                break
            time.sleep(2)
        else:
            result['reason'] = 'client exited'
        for _ in range(90):
            if not child.alive():
                break
            time.sleep(1)
        result['normalExit'] = not child.alive()
    finally:
        if child is not None:
            child.close()
        desktop.close()
        result['seconds'] = round(time.monotonic() - start)
        result['minimumFreeMiB_physical_commit_disk'] = minimum
        report(run / 'launch-result.json', result)
    print(result['reason'], flush=True)


if __name__ == '__main__':
    require_local_client(ROOT)
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('source', help='Stopped lunar run label, e.g. transect-h')
    cli.add_argument('label', help='Unused destination label')
    cli.add_argument('--fixture', type=Path, required=True)
    cli.add_argument('--product', type=Path, help='Test a different build in the copy. Both digests are recorded.')
    cli.add_argument('--observe', action='store_true', help='Wait for normal field_inputs camera controls on joining')
    cli.add_argument('--after', type=Path, help='Wait for this shared-renderer completion marker')
    cli.add_argument('--mode', choices=('search', 'dungeon'), default='search')
    cli.add_argument('--minutes', type=int, default=25, choices=range(1, 121), metavar='1-120')
    cli.add_argument('--java', type=Path, default=Path('C:/Program Files/Java/jdk-17/bin/java.exe'))
    opt = cli.parse_args()
    before, run = instance(opt.source), instance(opt.label)
    result = prepare(before, run, opt.fixture.resolve(strict=True), opt.mode, opt.product, opt.observe)
    report(run / 'prepared.json', result)
    print('Prepared separate save from', before.name, flush=True)
    while True:
        ram, commit, disk = available()
        if (run / 'request-stop-trail').exists():
            raise SystemExit('Preparation kept, render job withdrawn')
        if (opt.after is None or opt.after.is_file()) and ram >= 7000 and commit >= 10000 and disk >= 3500:
            break
        time.sleep(3)
    play(run, opt.java.resolve(strict=True), opt.mode, opt.minutes, result)
