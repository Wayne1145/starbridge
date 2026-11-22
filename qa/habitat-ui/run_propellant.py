"""Check the four fluid hatch diagrams in a fresh Chinese survival game on a private Windows desktop."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from local_client_policy import create_private_desktop, require_local_client
sys.path.insert(0, str(ROOT/'qa/native-fuel'))
from run_windows import available, controls, Child


def prepare(template, run, modern, fixture, *, first_steps=False, press_diagram=False):
    folder = run/'HabitatCaptain'
    folder.mkdir(parents=True, exist_ok=False)
    for directory in ('mods', 'retrofit/mods', 'natives', 'config'):
        (folder/directory).mkdir(parents=True)
    for jar in (template/'mods').glob('*.jar'):
        if not jar.name.startswith('starbridge-'):
            shutil.copy2(jar, folder/'mods'/jar.name)
    for directory in ('retrofit/mods', 'natives'):
        for path in (template/directory).iterdir():
            if path.is_file():
                shutil.copy2(path, folder/directory/path.name)
    shutil.copytree(template/'retrofit/.retrofit', folder/'retrofit/.retrofit', copy_function=controls.cache_copy)
    for name in ('options.txt', 'config/forge-client.toml', 'config/ExtraPlanets.cfg'):
        shutil.copy2(template/name, folder/name)
    if 'lang:zh_cn' not in (folder/'options.txt').read_text('utf8'):
        raise ValueError('Expected Chinese game settings')
    (folder/'retrofit/retrofit.properties').write_text('download-files=never\n', 'utf8')
    for path in (modern, fixture):
        shutil.copy2(path, folder/'mods'/path.name)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        (folder/'habitat-port').write_text(str(sock.getsockname()[1]), 'ascii')
    args = controls.relocate((template/'launch.args').read_text('utf8'), template, folder)
    (folder/'launch.args').write_text(args, 'utf8')
    markers = ['starbridge-habitat.host', 'starbridge-starter.fixture']
    if first_steps:
        options = folder/'options.txt'
        text, count = re.subn(r'^key_key\.use:[^\n]*', 'key_key.use:key.keyboard.u', options.read_text('utf8'), flags=re.MULTILINE)
        if count != 1:
            raise ValueError('Could not bind Use in the isolated test profile')
        options.write_text(text, 'utf8')
        markers.append('starbridge-first-steps.fixture')
    elif press_diagram:
        markers.extend(('starbridge-materials.fixture', 'starbridge-workshop.fixture', 'starbridge-press-cycle.fixture'))
    else:
        markers.extend(('starbridge-materials.fixture', 'starbridge-field-survey.fixture',
                        'starbridge-supply.fixture', 'starbridge-fuel-guide.fixture'))
    for marker in markers:
        (folder/marker).touch()
    return folder


def main():
    require_local_client(ROOT)
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('template', type=Path, help='Known working Chinese HabitatCaptain instance with upstream originals')
    p.add_argument('modern', type=Path)
    p.add_argument('fixture', type=Path)
    p.add_argument('label')
    p.add_argument('--java', type=Path, required=True)
    check = p.add_mutually_exclusive_group()
    check.add_argument('--press-diagram', action='store_true', help='Open the press layout and the actual nine-ingot workbench recipe')
    check.add_argument('--first-steps', action='store_true', help='Check the vanilla advancement tab and its path to the shipyard')
    p.add_argument('--layouts', action='store_true', help='Check both native multiblock layouts and the original projector recipe')
    args = p.parse_args()
    if not re.fullmatch('[a-z0-9-]+', args.label):
        p.error('Use a new local test name')
    if any(a < b for a,b in zip(available(), (7200, 9500, 4500))):
        raise SystemExit('Waiting for space to run a rendered game')
    run = ROOT/('run-habitat-'+args.label)
    folder = prepare(args.template.resolve(strict=True), run, args.modern, args.fixture, first_steps=args.first_steps, press_diagram=args.press_diagram)
    if args.layouts:
        (folder/'starbridge-machine-layout.fixture').touch()
    name = 'StarbridgeGuide' + uuid.uuid4().hex[:12]
    desktop = create_private_desktop(ROOT, name)
    host = None
    start = time.monotonic()
    result = {'pass': False, 'production': hashlib.sha256((folder/'mods'/args.modern.name).read_bytes()).hexdigest(),
              'qa': hashlib.sha256((folder/'mods'/args.fixture.name).read_bytes()).hexdigest()}
    try:
        host = Child(args.java.resolve(strict=True), folder, name, ['@launch.args'])
        print('Integrated survival client PID', host.pid, flush=True)
        names = ['propellant-guide-result.json', 'supply-client.json', 'supply-server.json']
        if args.first_steps:
            names = ['first-steps-result.json', 'starter-result.json']
        elif args.press_diagram:
            names = ['tin-recipe-route.json']
        while time.monotonic() - start < 330:
            if not host.alive():
                raise RuntimeError('Game exited before the requested GUI checks completed')
            if any(a < b for a,b in zip(available(), (1300, 2500, 2200))):
                raise RuntimeError('Reached system memory or disk guard')
            if args.layouts:
                names.append('machine-layout-result.json')
            if all((folder/n).is_file() for n in names) and all(json.loads((folder/n).read_bytes()).get('pass') for n in names):
                result['pass'] = True
                break
            time.sleep(1)
        if not result['pass']:
            raise TimeoutError('Timed out in the graphical guide')
    except Exception as error:
        result['error'] = str(error)
        raise
    finally:
        (folder/'habitat-stop').touch()
        if host is not None:
            until = time.monotonic() + 25
            while host.alive() and time.monotonic() < until:
                time.sleep(.5)
            host.close()
        desktop.close()
        result['elapsedSeconds'] = round(time.monotonic() - start, 2)
        (run/'launch-result.json').write_text(json.dumps(result, indent=2), 'utf8')
        print(result, flush=True)


if __name__ == '__main__':
    main()
