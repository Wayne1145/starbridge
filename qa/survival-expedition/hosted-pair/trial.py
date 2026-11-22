"""Start two ordinary survival players in a new, fully modded game on a GitHub-hosted Linux worker."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from urllib.request import Request, urlopen
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'qa/linux-fleet'))
sys.path.insert(0, str(ROOT / 'qa/remote-ui'))
sys.path.insert(0, str(ROOT / 'qa/survival-expedition'))
from inspect_checkpoint import ROLES, read_nbt
from continuation import fetch_checkpoint
from prepare import FORGE, MC, hosted_only, prepare_installation
from run import launch, stop_own_process, stop_server, write_json
from runtime import command, sha

BARITONE = 'baritone-api-forge-1.10.5.jar'
BARITONE_SHA = '6767478e2dd51c6ef278dcf76aabc78f238bc545e8806f82db718b9225ced45e'
ORDERS = ROOT / 'qa/survival-expedition/first-workbench.txt'


def pathfinder(target):
    hosted_only()
    url = 'https://github.com/cabaletta/baritone/releases/download/v1.10.5/' + BARITONE
    with urlopen(Request(url, headers={'User-Agent': 'Starbridge-Survival-QA/1'}), timeout=35) as response:
        raw = response.read(4825139)
    if len(raw) != 4825138 or hashlib.sha256(raw).hexdigest() != BARITONE_SHA:
        raise ValueError('Baritone does not match the pinned client-only release')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def wait_for(check, processes, limit, description):
    end = time.monotonic() + limit
    while time.monotonic() < end:
        if any(p.poll() is not None for p in processes):
            raise RuntimeError('A game process ended while waiting for ' + description)
        if check():
            return
        time.sleep(1)
    raise TimeoutError(description)


def read_json(path):
    return json.loads(path.read_bytes())


def joined(server, name):
    return (server / ('first-join-' + ROLES[name] + '.json')).is_file()


def player_cmd(root, game):
    args = command(root / 'launcher', game)
    args[args.index('--username') + 1] = game.name
    args[args.index('--uuid') + 1] = ROLES[game.name].replace('-', '')
    args[1:1] = ['-Dio.netty.eventLoopThreads=6', '-Dretrofit.minecraftHome=' + str(game / 'legacy-launcher')]
    # Each player has its own X display, game directory, native libraries and key settings.
    return ['xvfb-run', '-a', '-s', '-screen 0 1024x768x24 -nolisten tcp +extension GLX',
            *args, '--quickPlayMultiplayer', '127.0.0.1:26751']


def configure(root, fixture, api):
    if sha(api) != BARITONE_SHA:
        raise ValueError('Unexpected client pathfinder')
    server = root / 'server'
    roles = [root / name for name in ROLES]
    installation = prepare_installation(root, fixture, roles)
    (server / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=26751\nonline-mode=false\nenforce-secure-profile=false\n'
        'spawn-protection=0\nmax-players=2\nallow-flight=false\nview-distance=5\nsimulation-distance=4\n'
        'gamemode=survival\ndifficulty=normal\nlevel-seed=576618501\nenable-command-block=false\n'
        'enable-rcon=false\nenable-query=false\n', 'utf8')
    for role in (server, *roles):
        (role / 'survival-expedition.fixture').touch()
    for client in roles:
        shutil.copyfile(api, client / 'mods' / BARITONE)
        (client / 'options.txt').write_text('version:3465\nlang:zh_cn\nonboardAccessibility:false\n'
                'fullscreen:false\npauseOnLostFocus:false\nrenderDistance:5\nsimulationDistance:4\n'
                'guiScale:2\nmaxFps:25\nmipmapLevels:0\ntutorialStep:none\n', 'utf8')
        (client / 'config/forge-client.toml').write_text('[client]\nshowLoadWarnings=false\n', 'utf8')
        (client / 'expedition-join-port.txt').write_text('26751','ascii')
    return installation


def orders_for(stage):
    starter = ORDERS.read_text('utf8').splitlines()
    extras = {'OrbitCaptain': 'captain-materials.txt', 'OrbitEngineer': 'engineer-materials.txt'}
    return {name: starter + (Path(__file__).with_name(file).read_text('utf8').splitlines() if stage == 'materials' else [])
            for name, file in extras.items()}


def goal(lines):
    return sum(bool(line.strip()) and not line.lstrip().startswith('#') for line in lines)


def summarize(root, completed, orders, stage, origins):
    server = root / 'server'
    world = server / 'world'
    settings = read_nbt(world / 'level.dat')['Data']
    if (settings['allowCommands'] or settings['GameType'] != 0 or settings['WorldGenSettings']['seed'] != 576618501
            or read_json(server / 'ops.json')):
        raise ValueError('Dedicated world settings or operator list changed')
    result = {}
    for name, uuid in ROLES.items():
        first = origins[name]
        if first['name'] != name or first['items'] or first['creative'] or first['op'] or first['allowCommands']:
            raise ValueError(name + ' did not begin as an empty-inventory survival player')
        profile = read_nbt(world / 'playerdata' / (uuid + '.dat'))
        if profile['playerGameType'] != 0 or profile['Dimension'] != 'minecraft:overworld':
            raise ValueError(name + ' did not save in overworld survival')
        items = {}
        for stack in profile['Inventory']:
            items[stack['id']] = items.get(stack['id'], 0) + stack['Count']
        role = root / name
        cursor_file = role / 'expedition-cursor.txt'
        cursor = int(cursor_file.read_text('utf8').strip()) if cursor_file.is_file() else 0
        if completed:
            expected = {'minecraft:wooden_pickaxe': 1, 'minecraft:cobblestone': 14} if stage == 'workbench' else (
                {'advancedrocketry:tile.concrete': 16} if name == 'OrbitCaptain' else {'advancedrocketry:seat': 1})
            if cursor != goal(orders[name]) or any(items.get(item, 0) < count for item, count in expected.items()):
                raise ValueError(name + ' did not retain the products of the recorded survival actions')
        result[name] = {'cursor': cursor, 'health': profile['Health'], 'inventory': items,
                        'beganEmpty': True, 'survival': True, 'op': False}
    return result


def checkpoint(root, evidence):
    # Only this hosted run's generated world, two cursors and order lists are exported. The
    # runtime, dependencies and launcher configuration can be recreated from the published pack.
    world = root / 'server/world'
    output = evidence / 'cloud-world.zip'
    with ZipFile(output, 'x', compression=ZIP_DEFLATED, compresslevel=3) as archive:
        for file in sorted(world.rglob('*')):
            if file.is_file() and not file.is_symlink() and file.name != 'session.lock':
                archive.write(file, 'world/' + file.relative_to(world).as_posix())
        for name in ROLES:
            for leaf in ('expedition-orders.txt', 'expedition-cursor.txt'):
                file = root / name / leaf
                if file.is_file(): archive.write(file, name + '/' + leaf)
    return {'archive': output.name, 'bytes': output.stat().st_size, 'sha256': sha(output)}


def diagnostics(root, evidence):
    for role in ('server', *ROLES):
        folder = root / role
        for name in ('survival-events.log', 'expedition-cursor.txt', 'java.log', 'logs/latest.log'):
            path = folder / name
            if path.is_file():
                with path.open('rb') as stream:
                    stream.seek(max(0, path.stat().st_size - 96 * 1024))
                    (evidence / (role + '-' + path.name)).write_bytes(stream.read())


def run(root, evidence, fixture, api, previous='', stage='workbench'):
    hosted_only()
    evidence.mkdir(parents=True, exist_ok=True)
    installation = configure(root, fixture, api)
    write_json(evidence / 'installed.json', installation)
    orders = orders_for(stage)
    parent = fetch_checkpoint(previous, root, evidence, orders) if previous else None
    # Resumed players must first join the saved world before their orders start again.
    if parent:
        for name in ROLES:
            (root / name / 'expedition-orders.txt').unlink()
    dedicated = root / 'server'
    server = None
    processes = {}
    both_joined = completed = False
    try:
        server = launch(['java', '-XX:ActiveProcessorCount=2', '-Xms384m', '-Xmx3072m', '-Djava.awt.headless=true',
                         '-Dio.netty.eventLoopThreads=8', '-Dretrofit.minecraftHome=' + str(dedicated / 'legacy-launcher'),
                         f'@libraries/net/minecraftforge/forge/{MC}-{FORGE}/unix_args.txt', 'nogui'], dedicated)
        wait_for(lambda: (dedicated / 'survival-recipes.json').exists(), [server], 450, 'dedicated server startup')
        for name in ROLES:
            client = root / name
            processes[name] = launch(player_cmd(root, client), client)
            wait_for(lambda: joined(dedicated, name), [server, *processes.values()], 300, name + ' to join')
        both_joined = True
        # Release both players only after the server recorded their starting inventories.
        for name in ROLES:
            (root / name / 'expedition-orders.txt').write_text('\n'.join(orders[name]) + '\n', 'utf8')
        def finished():
            for name in ROLES:
                file = root / name / 'expedition-cursor.txt'
                if not file.exists() or file.read_text('utf8').strip() != str(goal(orders[name])): return False
            return True
        wait_for(finished, [server, *processes.values()], (22 if stage == 'materials' else 16) * 60, 'both players to finish the ' + stage + ' stage')
        completed = True
    finally:
        try:
            for name in processes:
                (root / name / 'expedition.stop').touch()
            for process in processes.values():
                stop_own_process(process)
            clean_clients = {name: process.returncode == 0 for name, process in processes.items()}
            if server is not None:
                stop_server(server)
                if server.wait(timeout=90) != 0: raise RuntimeError('Dedicated server failed to save')
            if both_joined and 'All dimensions are saved' in (dedicated / 'logs/latest.log').read_text('utf8',errors='replace'):
                origins = parent['firstJoin'] if parent else {name: read_json(dedicated / ('first-join-' + uid + '.json')) for name, uid in ROLES.items()}
                digest = checkpoint(root, evidence)
                saved = summarize(root, completed, orders, stage, origins)
                passed = completed and all(clean_clients.values())
                write_json(evidence / 'pair-result.json', {'completed': passed, 'stage': stage, 'previousRun': previous or None,
                           'cleanClientExits': clean_clients, 'firstJoin': origins, 'profiles': saved, 'checkpoint': digest})
                if completed and not passed:
                    raise RuntimeError('A client failed to exit normally: ' + str(clean_clients))
            elif completed:
                raise RuntimeError('Dedicated server did not confirm that all dimensions were saved')
        finally:
            stop_own_process(server)
            diagnostics(root, evidence)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    commands.add_parser('pathfinder').add_argument('target',type=Path)
    trial = commands.add_parser('run')
    for option in ('root', 'evidence', 'fixture', 'api'): trial.add_argument('--'+option, required=True, type=Path)
    trial.add_argument('--previous', default='')
    trial.add_argument('--stage', choices=['workbench', 'materials'], default='workbench')
    args = parser.parse_args()
    if args.action == 'pathfinder': pathfinder(args.target)
    else: run(args.root.resolve(),args.evidence.resolve(),args.fixture.resolve(strict=True),args.api.resolve(strict=True), args.previous, args.stage)
