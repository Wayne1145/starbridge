"""Click the in-game installation check on an isolated title screen, without opening a save."""

import argparse
import hashlib
import json
import re
import shutil
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'qa/native-fuel'))
from run_windows import Child, U, available, controls  # noqa: E402


def prepare(folder, template, modern, fixture, mode):
    folder.mkdir(parents=True, exist_ok=False)
    shutil.copytree(template / 'natives', folder / 'natives')
    (folder / 'config').mkdir()
    (folder / 'mods').mkdir()
    shutil.copy2(template / 'options.txt', folder / 'options.txt')
    shutil.copy2(template / 'config/forge-client.toml', folder / 'config/forge-client.toml')
    args = controls.relocate((template / 'launch.args').read_text('utf8'), template, folder)
    (folder / 'launch.args').write_text(args, 'utf8')
    if mode not in ('main-only', 'slate-only'):
        for name in ('mods', 'retrofit/mods'):
            (folder / name).mkdir(exist_ok=True, parents=True)
            for f in (template / name).glob('*.jar'):
                if f.name.startswith('starbridge-') and not (mode == 'full' and f.name.startswith('starbridge-dock-')):
                    continue
                shutil.copy2(f, folder / name / f.name)
        shutil.copy2(template / 'config/ExtraPlanets.cfg', folder / 'config/ExtraPlanets.cfg')
        shutil.copytree(template / 'retrofit/.retrofit', folder / 'retrofit/.retrofit', copy_function=controls.cache_copy)
        (folder / 'retrofit/retrofit.properties').write_text('download-files=never\n', 'utf8')
    for f in (modern, fixture):
        shutil.copy2(f, folder / 'mods' / f.name)
    if mode == 'slate-only':
        for marker in ('starbridge-setup-slate.fixture', 'starbridge-habitat.host', 'starbridge-starter.fixture'):
            (folder / marker).touch()
    else:
        (folder / 'starbridge-setup.fixture').write_text(mode, 'utf8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('main-only', 'full', 'no-dock', 'slate-only'))
    parser.add_argument('label')
    parser.add_argument('template', type=Path, help='Known working full-pack HabitatCaptain client')
    parser.add_argument('modern', type=Path)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('--java', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch('[a-z0-9-]+', args.label):
        parser.error('Use a fresh trial name')
    if any(a < b for a, b in zip(available(), (7000, 9000, 3000))):
        raise SystemExit('Insufficient memory or disk for a rendered client')
    folder = ROOT / ('run-setup-' + args.label) / 'HabitatCaptain'
    prepare(folder, args.template.resolve(strict=True), args.modern, args.fixture, args.mode)
    desktop_name = 'StarbridgeSetup' + uuid.uuid4().hex[:10]
    desktop = U.CreateDesktopW(desktop_name, None, None, 0, 0x01FF, None)
    if not desktop:
        raise OSError('Could not create the isolated test desktop')
    child = None
    try:
        child = Child(args.java.resolve(strict=True), folder, desktop_name, ['@launch.args'])
        started = time.monotonic()
        while time.monotonic() - started < 180:
            result_file = folder / ('flight-slate-result.json' if args.mode == 'slate-only' else 'flight-setup-result.json')
            if result_file.is_file():
                result = json.loads(result_file.read_bytes())
                if not result.get('pass'):
                    raise RuntimeError(result)
                check = dict(result, productSha256=hashlib.sha256(args.modern.read_bytes()).hexdigest(),
                             fixtureSha256=hashlib.sha256(args.fixture.read_bytes()).hexdigest())
                (folder.parent / 'verified.json').write_text(json.dumps(check, indent=2) + '\n', 'utf8')
                print(json.dumps(check), flush=True)
                return
            if not child.alive():
                raise RuntimeError('Game exited before reaching the setup screen')
            if any(a < b for a, b in zip(available(), (1500, 2600, 2200))):
                raise RuntimeError('Memory or disk guard reached')
            time.sleep(1)
        raise TimeoutError('The client did not finish the menu checks')
    finally:
        if child is not None:
            if args.mode == 'slate-only' and child.alive():
                (folder / 'habitat-stop').touch()
                stop_deadline = time.monotonic() + 35
                while child.alive() and time.monotonic() < stop_deadline:
                    time.sleep(.25)
            child.close()
        U.CloseDesktop(desktop)


if __name__ == '__main__':
    main()
