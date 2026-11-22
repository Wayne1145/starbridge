"""Start two ordinary survival players in a new, fully modded game on a GitHub-hosted Linux worker."""
import argparse
import hashlib
import io
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
from continuation import ARCHIVES, CAMP_RUN, SURFACE_CAMP, choose_yard_parent, fetch_checkpoint
from yard import REGION, inspect_yard
from prepare import FORGE, MC, hosted_only, prepare_installation
from run import launch, stop_own_process, stop_server, write_json
from runtime import command, sha

YARD_STAGES = ('yard', 'surface-yard', 'surface-workshop', 'surface-cast')
STAGES = ('workbench', 'materials', *YARD_STAGES, 'terminal')
WORKSHOP_BAG = {
    'OrbitCaptain': {'minecraft:bucket': 2, 'minecraft:iron_ingot': 6, 'minecraft:stone_pickaxe': 1},
    'OrbitEngineer': {'starbridge:navigation_slate': 1, 'minecraft:stone_axe': 1},
}
CAST_BAG = {
    'OrbitCaptain': {**WORKSHOP_BAG['OrbitCaptain'], 'minecraft:bucket': 1, 'minecraft:water_bucket': 1},
    'OrbitEngineer': WORKSHOP_BAG['OrbitEngineer'],
}
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


def terminal_orders():
    archive, digest = ARCHIVES[SURFACE_CAMP]
    if 'sha256:' + sha(archive) != digest:
        raise ValueError('Archived survival camp differs from its recorded digest')
    next_orders = {
        'OrbitCaptain': ['walk -88 64 -21', 'stand -88 64 -18', 'open minecraft:chest', 'close'],
        'OrbitEngineer': ['awaitblock minecraft:sugar_cane -87 69 -18',
                          'dig -87 68 -18', 'dig -89 68 -18', 'stand -87 68 -18', 'stand -89 68 -18',
                          'gather minecraft:sugar_cane minecraft:sugar_cane 3',
                          'stand -88 64 -21', 'stand -88 64 -19', 'stand -88 64 -18',
                          'open minecraft:chest', 'take minecraft:compass 1', 'take minecraft:glass_pane 1',
                          'close', 'open minecraft:crafting_table', 'craft minecraft:paper minecraft:paper 3',
                          'craft starbridge:navigation_slate starbridge:navigation_slate 1', 'close',
                          'stand -88 64 -21', 'stand -79 64 -23', 'readslatecheck'],
    }
    with ZipFile(archive) as proof, ZipFile(io.BytesIO(proof.read('cloud-world.zip'))) as world:
        result = {}
        for role, upcoming in next_orders.items():
            previous = world.read(role + '/expedition-orders.txt').decode('utf8').splitlines()
            completed = [line.strip() for line in previous if line.strip() and not line.lstrip().startswith('#')]
            position = int(world.read(role + '/expedition-cursor.txt'))
            result[role] = completed[:position] + upcoming
        return result


def orders_for(stage):
    if stage == 'terminal': return terminal_orders()
    if stage == 'surface-yard':
        terminal = terminal_orders()
        return {name: terminal[name] + Path(__file__).with_name(stem + '-surface-yard.txt').read_text('utf8').splitlines()
                for name, stem in (('OrbitCaptain', 'captain'), ('OrbitEngineer', 'engineer'))}
    if stage == 'surface-workshop':
        yard = orders_for('surface-yard')
        return {name: yard[name] + Path(__file__).with_name(stem + '-surface-workshop.txt').read_text('utf8').splitlines()
                for name, stem in (('OrbitCaptain', 'captain'), ('OrbitEngineer', 'engineer'))}
    if stage == 'surface-cast':
        orders = orders_for('surface-workshop')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-cast.txt').read_text('utf8').splitlines()
        orders['OrbitEngineer'] += ['daylight', 'stand -88 64 -21', 'stand -88 64 -18']
        return orders
    starter = ORDERS.read_text('utf8').splitlines()
    extras = {'OrbitCaptain': 'captain-materials.txt', 'OrbitEngineer': 'engineer-materials.txt'}
    materials = {name: starter + Path(__file__).with_name(file).read_text('utf8').splitlines() for name, file in extras.items()}
    if stage == 'workbench': return {name: list(starter) for name in ROLES}
    if stage == 'materials': return materials
    if stage != 'yard': raise ValueError('Unknown expedition stage')
    recorded = {'OrbitCaptain':35, 'OrbitEngineer':14}
    return {name: [line.strip() for line in materials[name] if line.strip() and not line.lstrip().startswith('#')][:recorded[name]]
            + Path(__file__).with_name(stem + '-yard.txt').read_text('utf8').splitlines()
            for name,stem in (('OrbitCaptain','captain'),('OrbitEngineer','engineer'))}


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
            if stage in YARD_STAGES: expected = {}  # The finished pad and seat are in the saved region.
            if stage in ('terminal', 'surface-yard') and name == 'OrbitEngineer': expected = {'starbridge:navigation_slate': 1}
            if stage == 'terminal' and name == 'OrbitCaptain': expected = {}
            if stage == 'surface-workshop': expected = WORKSHOP_BAG[name]
            if stage == 'surface-cast': expected = CAST_BAG[name]
            if cursor != goal(orders[name]) or any(items.get(item, 0) < count for item, count in expected.items()):
                raise ValueError(name + ' did not retain the products of the recorded survival actions')
        result[name] = {'cursor': cursor, 'health': profile['Health'], 'inventory': items,
                        'beganEmpty': True, 'survival': True, 'op': False}
    return result


def terminal_guide(path):
    guide = read_json(path)
    if (guide['player'] != ROLES['OrbitEngineer'] or not guide['pass'] or not guide['checkedInstallation']
            or guide['heldItem'] != 'starbridge:navigation_slate' or not guide['useKey']
            or (guide['requiredLoaded'], guide['optionalLoaded']) != (8, 3)
            or guide['clicked'] != ['install', 'gui.done', 'shipyard', 'elevation', 'plan', 'gui.done']):
        raise ValueError('The engineer did not open the installation, elevation and plan views from the crafted terminal')
    return guide


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
    for name in ('ops.json', *('first-join-' + uid + '.json' for uid in ROLES.values())):
        if (root / 'server' / name).is_file(): shutil.copyfile(root / 'server' / name, evidence / ('server-' + name))
    engineer = root / 'OrbitEngineer'
    for source in (engineer / 'survival-first-guide.json', *(
        engineer / 'screenshots' / filename for filename in (
            'survival-craft-starbridge-navigation_slate.png',
            'survival-guide-install.png', 'survival-guide-plan.png', 'survival-guide-elevation.png',
        )
    )):
        if source.is_file():
            shutil.copyfile(source, evidence / ('OrbitEngineer-' + source.name))
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
    if stage == 'terminal' and not previous:
        previous = SURFACE_CAMP
    if stage in ('surface-yard', 'surface-workshop', 'surface-cast') and (not previous.isascii() or not previous.isdigit()):
        raise ValueError('Select the run number of the matching original surface camp')
    evidence.mkdir(parents=True, exist_ok=True)
    installation = configure(root, fixture, api)
    write_json(evidence / 'installed.json', installation)
    if previous == SURFACE_CAMP:
        stage = 'terminal'
    orders = orders_for(stage)
    expected_parent = None
    if previous == 'latest-yard': previous, expected_parent = choose_yard_parent()
    parent = fetch_checkpoint(previous, root, evidence, orders) if previous else None
    starts = {name: int((root / name / 'expedition-cursor.txt').read_text('ascii')) if (root / name / 'expedition-cursor.txt').is_file() else 0 for name in ROLES}
    if expected_parent and (parent['worldSha256']!=expected_parent['worldSha256'] or starts!=expected_parent['cursors']):
        raise ValueError('Restored world or role cursors differ from the chosen continuation')
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
        wait_for(finished, [server, *processes.values()], (35 if stage in (*YARD_STAGES, 'terminal') else 22 if stage == 'materials' else 16) * 60, 'both players to finish the ' + stage + ' stage')
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
                yard = inspect_yard(dedicated/REGION, completed, cast=stage == 'surface-cast') if stage in YARD_STAGES else None
                guide, guide_problem = None, None
                if completed and stage == 'terminal':
                    try:
                        guide = terminal_guide(root / 'OrbitEngineer/survival-first-guide.json')
                    except (OSError, KeyError, ValueError) as problem:
                        guide_problem = str(problem)
                passed = completed and all(clean_clients.values()) and guide_problem is None
                write_json(evidence / 'pair-result.json', {'completed': passed, 'stage': stage, 'previousRun': previous or None,
                           **({'terminalGuide': guide} if guide else {}),
                           **({'terminalGuideProblem': guide_problem} if guide_problem else {}),
                           'yard': yard, 'cleanClientExits': clean_clients, 'clientExitCodes': {name: p.returncode for name,p in processes.items()},
                           'serverExit': server.returncode, 'startingCursors': starts,
                           'previousCheckpoint': {k:v for k,v in parent.items() if k!='firstJoin'} if parent else None, 'firstJoin': origins, 'profiles': saved, 'checkpoint': digest})
                if stage == 'yard' and all(clean_clients.values()) and len(clean_clients)==2 and previous:
                    write_json(evidence/'yard-resume.json', {'run':int(os.environ['GITHUB_RUN_ID']), 'camp':int(CAMP_RUN),
                               'worldSha256':digest['sha256'], 'cursors':{r:saved[r]['cursor'] for r in ROLES}})
                if guide_problem is not None:
                    raise ValueError(guide_problem)
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
    trial.add_argument('--stage', choices=STAGES, default='workbench')
    args = parser.parse_args()
    if args.action == 'pathfinder': pathfinder(args.target)
    else: run(args.root.resolve(),args.evidence.resolve(),args.fixture.resolve(strict=True),args.api.resolve(strict=True), args.previous, args.stage)
