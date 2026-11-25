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
from continuation import NETHER_EXIT
from yard import REGION, count_potion, count_variant, has_arc_control_feed, has_arc_hatches, has_pressed_tin, has_titanium_ore, inspect_yard
from prepare import FORGE, MC, hosted_only, prepare_installation
from run import launch, stop_own_process, stop_server, write_json
from runtime import command, sha

YARD_STAGES = ('yard', 'surface-yard', 'surface-workshop', 'surface-cast-water', 'surface-cast', 'surface-press', 'surface-power', 'surface-metals', 'surface-portal-flint', 'surface-portal-stock', 'surface-portal-built', 'surface-nether', 'surface-nether-supplies', 'surface-brick-stock', 'surface-brew-water', 'surface-brew-reagents', 'surface-brew-drinks', 'surface-blast-bricks', 'surface-arc-coils', 'surface-arc-frames', 'surface-arc-tin', 'surface-arc-ports', 'surface-arc-control-circuit')
STAGES = ('workbench', 'materials', *YARD_STAGES, 'terminal')
WORKSHOP_BAG = {
    'OrbitCaptain': {'minecraft:bucket': 2, 'minecraft:iron_ingot': 6, 'minecraft:stone_pickaxe': 1},
    'OrbitEngineer': {'starbridge:navigation_slate': 1, 'minecraft:stone_axe': 1},
}
CAST_BAG = {
    'OrbitCaptain': {**WORKSHOP_BAG['OrbitCaptain'], 'minecraft:bucket': 1, 'minecraft:water_bucket': 1},
    'OrbitEngineer': WORKSHOP_BAG['OrbitEngineer'],
}
POWER_BAG = {
    'OrbitCaptain': {**CAST_BAG['OrbitCaptain'], 'minecraft:iron_ingot': 3, 'minecraft:iron_pickaxe': 1},
    'OrbitEngineer': {**CAST_BAG['OrbitEngineer'], 'libvulpes:battery': 3, 'libvulpes:tile.coalgenerator': 1},
}
WORKSHOP_SUPPLIES = {'surface-workshop': WORKSHOP_BAG, 'surface-cast-water': CAST_BAG, 'surface-cast': CAST_BAG,
                     'surface-press': CAST_BAG, 'surface-power': POWER_BAG,
                     'surface-metals': {'OrbitCaptain': {**POWER_BAG['OrbitCaptain'], 'minecraft:diamond': 5, 'minecraft:diamond_pickaxe': 1},
                                        'OrbitEngineer': POWER_BAG['OrbitEngineer']},
                     'surface-portal-flint': {
                         'OrbitCaptain': {**POWER_BAG['OrbitCaptain'], 'minecraft:iron_ingot': 2, 'minecraft:diamond': 3,
                                          'minecraft:diamond_pickaxe': 1, 'minecraft:flint_and_steel': 1},
                         'OrbitEngineer': POWER_BAG['OrbitEngineer'],
                     },
                     'surface-portal-built': {
                         'OrbitCaptain': {**POWER_BAG['OrbitCaptain'], 'minecraft:iron_ingot': 2, 'minecraft:diamond': 3,
                                          'minecraft:diamond_pickaxe': 1, 'minecraft:flint_and_steel': 1},
                         'OrbitEngineer': POWER_BAG['OrbitEngineer'],
                     }}
WORKSHOP_SUPPLIES['surface-portal-stock'] = {
    **WORKSHOP_SUPPLIES['surface-portal-flint'],
    'OrbitCaptain': {**WORKSHOP_SUPPLIES['surface-portal-flint']['OrbitCaptain'], 'minecraft:obsidian': 10},
}
WORKSHOP_SUPPLIES['surface-nether'] = WORKSHOP_SUPPLIES['surface-portal-built']
WORKSHOP_SUPPLIES['surface-nether-supplies'] = {
    'OrbitCaptain': {**WORKSHOP_SUPPLIES['surface-nether']['OrbitCaptain'], 'minecraft:netherrack': 8, 'minecraft:glowstone_dust': 6},
    'OrbitEngineer': WORKSHOP_SUPPLIES['surface-nether']['OrbitEngineer'],
}
WORKSHOP_SUPPLIES['surface-brick-stock'] = {
    **WORKSHOP_SUPPLIES['surface-nether-supplies'],
    'OrbitEngineer': {**POWER_BAG['OrbitEngineer'], 'minecraft:bricks': 24},
}
WORKSHOP_SUPPLIES['surface-brew-water'] = {
    **WORKSHOP_SUPPLIES['surface-brick-stock'],
    'OrbitEngineer': {**WORKSHOP_SUPPLIES['surface-brick-stock']['OrbitEngineer'], 'minecraft:potion': 12},
}
WORKSHOP_SUPPLIES['surface-brew-reagents'] = {
    **WORKSHOP_SUPPLIES['surface-brew-water'],
    'OrbitCaptain': {**WORKSHOP_SUPPLIES['surface-brew-water']['OrbitCaptain'],
                     'minecraft:blaze_rod': 3, 'minecraft:nether_wart': 4, 'minecraft:magma_cream': 2},
}
WORKSHOP_SUPPLIES['surface-brew-drinks'] = WORKSHOP_SUPPLIES['surface-brew-water']
WORKSHOP_SUPPLIES['surface-blast-bricks'] = {
    'OrbitCaptain': WORKSHOP_SUPPLIES['surface-brew-drinks']['OrbitCaptain'],
    'OrbitEngineer': {**POWER_BAG['OrbitEngineer'], 'advancedrocketry:utilblock': 96},
}
WORKSHOP_SUPPLIES['surface-arc-coils'] = {
    **WORKSHOP_SUPPLIES['surface-blast-bricks'],
    'OrbitCaptain': {**WORKSHOP_SUPPLIES['surface-blast-bricks']['OrbitCaptain'], 'libvulpes:libvulpescoil0': 3},
}
WORKSHOP_SUPPLIES['surface-arc-frames'] = {
    **WORKSHOP_SUPPLIES['surface-arc-coils'],
    'OrbitEngineer': {**WORKSHOP_SUPPLIES['surface-arc-coils']['OrbitEngineer'],
                      'libvulpes:blockstructureblock': 14, 'libvulpes:blockhatch': 2},
}
WORKSHOP_SUPPLIES['surface-arc-tin'] = {
    **WORKSHOP_SUPPLIES['surface-arc-frames'],
    'OrbitCaptain': {**WORKSHOP_SUPPLIES['surface-arc-frames']['OrbitCaptain'], 'minecraft:redstone': 6},
    'OrbitEngineer': {**WORKSHOP_SUPPLIES['surface-arc-frames']['OrbitEngineer'], 'libvulpes:libvulpesproductplate': 12},
}
WORKSHOP_SUPPLIES['surface-arc-ports'] = {
    **WORKSHOP_SUPPLIES['surface-arc-frames'],
    'OrbitEngineer': {**WORKSHOP_SUPPLIES['surface-arc-frames']['OrbitEngineer'],
                      'libvulpes:blockstructureblock': 11, 'libvulpes:rfbattery': 3},
}
WORKSHOP_SUPPLIES['surface-arc-control-circuit'] = {
    'OrbitCaptain': {item: count for item, count in WORKSHOP_SUPPLIES['surface-arc-ports']['OrbitCaptain'].items()
                     if item != 'minecraft:diamond'},
    'OrbitEngineer': {**WORKSHOP_SUPPLIES['surface-arc-ports']['OrbitEngineer'],
                      'minecraft:diamond': 1, 'minecraft:redstone': 3, 'minecraft:oak_slab': 1},
}
ARC_COIL_STAGES = YARD_STAGES[YARD_STAGES.index('surface-arc-coils'):]
ARC_HATCH_STAGES = YARD_STAGES[YARD_STAGES.index('surface-arc-frames'):]
TITANIUM_STAGES = YARD_STAGES[YARD_STAGES.index('surface-metals'):]
BARITONE = 'baritone-api-forge-1.10.5.jar'
BARITONE_SHA = '6767478e2dd51c6ef278dcf76aabc78f238bc545e8806f82db718b9225ced45e'
ORDERS = ROOT / 'qa/survival-expedition/first-workbench.txt'
RECIPE_REPORTS = ('rutile-harvest-probe.json', 'arc-controller-feed-probe.json', 'original-machine-layout-probe.json',
                  'shipyard-supply-chain-probe.json', 'blast-brick-probe.json')


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
        # The actual game window still renders through this client's dedicated Xvfb instance.
        (client / 'config/fml.toml').write_text('earlyWindowControl=false\n', 'utf8')
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
    if stage in ('surface-cast-water', 'surface-cast'):
        orders = orders_for('surface-workshop')
        casting = Path(__file__).with_name('captain-surface-cast.txt').read_text('utf8').splitlines()
        if stage == 'surface-cast-water':
            casting = casting[:casting.index('waterat -106 62 -24') + 1]
        orders['OrbitCaptain'] += casting
        orders['OrbitEngineer'] += ['daylight', 'stand -88 64 -21', 'stand -88 64 -18']
        if stage == 'surface-cast':
            orders['OrbitEngineer'].append('use -88 64 -20')  # Shut the open door at the water checkpoint.
        return orders
    if stage == 'surface-press':
        orders = orders_for('surface-cast')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-press.txt').read_text('utf8').splitlines()
        orders['OrbitEngineer'] += Path(__file__).with_name('engineer-surface-press.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-power':
        press = orders_for('surface-press')
        return {name: press[name] + Path(__file__).with_name(stem + '-surface-power.txt').read_text('utf8').splitlines()
                for name, stem in (('OrbitCaptain', 'captain'), ('OrbitEngineer', 'engineer'))}
    if stage == 'surface-metals':
        power = orders_for('surface-power')
        return {name: power[name] + Path(__file__).with_name(stem + '-surface-metals.txt').read_text('utf8').splitlines()
                for name, stem in (('OrbitCaptain', 'captain'), ('OrbitEngineer', 'engineer'))}
    if stage == 'surface-portal-flint':
        orders = orders_for('surface-metals')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-portal-flint.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-portal-stock':
        orders = orders_for('surface-portal-flint')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-portal-stock.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-portal-built':
        orders = orders_for('surface-portal-stock')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-portal-built.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-nether':
        orders = orders_for('surface-portal-built')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-nether.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-nether-supplies':
        orders = orders_for('surface-nether')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-nether-supplies.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-brick-stock':
        orders = orders_for('surface-nether-supplies')
        orders['OrbitEngineer'] += Path(__file__).with_name('engineer-surface-brick-stock.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-brew-water':
        orders = orders_for('surface-brick-stock')
        orders['OrbitEngineer'] += Path(__file__).with_name('engineer-surface-brew-water.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-brew-reagents':
        orders = orders_for('surface-brew-water')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-brew-reagents.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-brew-drinks':
        previous = orders_for('surface-brew-reagents')
        return {name: previous[name] + Path(__file__).with_name(stem + '-surface-brew-drinks.txt').read_text('utf8').splitlines()
                for name, stem in (('OrbitCaptain', 'captain'), ('OrbitEngineer', 'engineer'))}
    if stage == 'surface-blast-bricks':
        orders = orders_for('surface-brew-drinks')
        orders['OrbitEngineer'] += Path(__file__).with_name('engineer-surface-blast-bricks.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-arc-coils':
        orders = orders_for('surface-blast-bricks')
        orders['OrbitCaptain'] += Path(__file__).with_name('captain-surface-arc-coils.txt').read_text('utf8').splitlines()
        return orders
    if stage == 'surface-arc-frames':
        orders = orders_for('surface-arc-coils')
        orders['OrbitEngineer'] += Path(__file__).with_name('engineer-surface-arc-frames.txt').read_text('utf8').splitlines()
        return orders
    arc_predecessors = {
        'surface-arc-tin': 'surface-arc-frames',
        'surface-arc-ports': 'surface-arc-tin',
        'surface-arc-control-circuit': 'surface-arc-ports',
    }
    if stage in arc_predecessors:
        orders = orders_for(arc_predecessors[stage])
        return {name: orders[name] + Path(__file__).with_name(f'{stem}-{stage}.txt').read_text('utf8').splitlines()
                for name, stem in (('OrbitCaptain', 'captain'), ('OrbitEngineer', 'engineer'))}
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
        if profile['playerGameType'] != 0 or not valid_saved_dimension(stage, name, completed, profile['Dimension']):
            raise ValueError(name + ' did not save in the expected survival dimension')
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
            if stage in WORKSHOP_SUPPLIES: expected = WORKSHOP_SUPPLIES[stage][name]
            if stage == 'surface-press' and name == 'OrbitEngineer' and not has_pressed_tin(profile['Inventory']):
                raise ValueError('Four original tin plates with their original variant must be in the engineer inventory')
            if stage in TITANIUM_STAGES and name == 'OrbitCaptain' and not has_titanium_ore(profile['Inventory']):
                raise ValueError('Twelve original titanium ores with their exact variant must be in the captain inventory')
            if name == 'OrbitEngineer' and stage in ('surface-brew-water', 'surface-brew-reagents'):
                if count_potion(profile['Inventory'], 'minecraft:water') < 12:
                    raise ValueError('Twelve filled water bottles must be in the engineer inventory')
            if name == 'OrbitEngineer' and stage == 'surface-brew-drinks':
                if any(count_potion(profile['Inventory'], potion) < 6 for potion in ('minecraft:fire_resistance', 'minecraft:strength')):
                    raise ValueError('Six bottles of each of the two regular potions must be in the engineer inventory')
            if stage in ARC_COIL_STAGES and name == 'OrbitCaptain':
                if count_variant(profile['Inventory'], 'libvulpes:libvulpescoil0', 2) < 3:
                    raise ValueError('The captain must carry three gold coils of the original variant')
            if stage in ARC_HATCH_STAGES and name == 'OrbitEngineer' and not has_arc_hatches(profile['Inventory']):
                raise ValueError('The engineer must carry an original item input and output hatch')
            if stage == 'surface-arc-control-circuit' and name == 'OrbitEngineer' and not has_arc_control_feed(profile['Inventory']):
                raise ValueError('The engineer must carry the original control circuit and copper ingot variants')
            if stage == 'surface-arc-tin' and name == 'OrbitEngineer':
                if count_variant(profile['Inventory'], 'libvulpes:libvulpesproductplate', 5) < 12:
                    raise ValueError('The engineer must carry twelve actual tin plates')
            if cursor != goal(orders[name]) or any(items.get(item, 0) < count for item, count in expected.items()):
                raise ValueError(name + ' did not retain the products of the recorded survival actions')
        result[name] = {'cursor': cursor, 'health': profile['Health'], 'inventory': items,
                        'beganEmpty': True, 'survival': True, 'op': False}
    return result


def valid_saved_dimension(stage, role, completed, dimension):
    if stage == 'surface-nether' and role == 'OrbitCaptain':
        return dimension == 'minecraft:the_nether' or (not completed and dimension == 'minecraft:overworld')
    if stage in ('surface-nether-supplies', 'surface-brew-reagents') and role == 'OrbitCaptain' and not completed:
        return dimension in ('minecraft:overworld', 'minecraft:the_nether')
    return dimension == 'minecraft:overworld'


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
        nether_exit = root / NETHER_EXIT
        if nether_exit.is_file():
            archive.write(nether_exit, NETHER_EXIT)
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


def start_dedicated(directory):
    return launch(['java', '-XX:ActiveProcessorCount=2', '-Xms384m', '-Xmx3072m', '-Djava.awt.headless=true',
                   '-Dio.netty.eventLoopThreads=8', '-Dretrofit.minecraftHome=' + str(directory / 'legacy-launcher'),
                   f'@libraries/net/minecraftforge/forge/{MC}-{FORGE}/unix_args.txt', 'nogui'], directory)


def run_recipes(root, evidence, fixture):
    hosted_only()
    evidence.mkdir(parents=True, exist_ok=True)
    dedicated = root / 'server'
    installation = prepare_installation(root, fixture, [])
    write_json(evidence / 'installed.json', installation)
    (dedicated / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=26751\nview-distance=3\nsimulation-distance=3\n'
        'enable-rcon=false\nenable-query=false\n', 'utf8')
    for marker in ('survival-expedition.fixture', 'recipe-diagnostics.fixture'):
        (dedicated / marker).touch()
    server = None
    try:
        server = start_dedicated(dedicated)
        # The probe writes the brick result last, after all earlier reports are complete.
        wait_for(lambda: (dedicated / RECIPE_REPORTS[-1]).is_file(), [server], 450, 'dedicated recipe report')
        stop_server(server)
        if server.wait(timeout=90) != 0:
            raise RuntimeError('Dedicated recipe server failed to save')
        if 'All dimensions are saved' not in (dedicated / 'logs/latest.log').read_text('utf8', errors='replace'):
            raise RuntimeError('Dedicated recipe server did not confirm its final save')
        for report in RECIPE_REPORTS:
            read_json(dedicated / report)
            shutil.copyfile(dedicated / report, evidence / report)
    finally:
        stop_server(server)
        stop_own_process(server)
        diagnostics(root, evidence)


def run(root, evidence, fixture, api, previous='', stage='workbench'):
    hosted_only()
    if stage == 'terminal' and not previous:
        previous = SURFACE_CAMP
    if stage.startswith('surface-') and (not previous.isascii() or not previous.isdigit()):
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
        server = start_dedicated(dedicated)
        wait_for(lambda: (dedicated / 'survival-recipes.json').exists(), [server], 450, 'dedicated server startup')
        for name in ROLES:
            client = root / name
            processes[name] = launch(player_cmd(root, client), client)
            wait_for(lambda: joined(dedicated, name), [server, *processes.values()], 300, name + ' to join')
        both_joined = True
        # Release both players only after the server recorded their starting inventories.
        for name in ROLES:
            (root / name / 'expedition-orders.txt').write_text('\n'.join(orders[name]) + '\n', 'utf8')
        commands = {name: [line.strip() for line in orders[name] if line.strip() and not line.lstrip().startswith('#')]
                    for name in ROLES}
        next_update = 0.0

        def finished():
            nonlocal next_update
            cursors = {}
            for name in ROLES:
                file = root / name / 'expedition-cursor.txt'
                value = file.read_text('utf8').strip() if file.exists() else ''
                cursors[name] = int(value) if value.isdecimal() else starts[name]
            now = time.monotonic()
            if now >= next_update:
                next_update = now + 60
                for name, cursor in cursors.items():
                    actions = commands[name]
                    pending = actions[cursor] if cursor < len(actions) else 'finished'
                    print(f'[{stage}] {name} {cursor}/{len(actions)} {pending}', flush=True)
            return all(cursor == len(commands[name]) for name, cursor in cursors.items())

        minutes = 35 if stage in (*YARD_STAGES, 'terminal') else 22 if stage == 'materials' else 16
        if stage == 'surface-portal-flint':
            minutes = 10
        wait_for(finished, [server, *processes.values()], minutes * 60, 'both players to finish the ' + stage + ' stage')
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
                yard = inspect_yard(dedicated/REGION, completed, stage) if stage in YARD_STAGES else None
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
    recipes = commands.add_parser('recipes')
    for option in ('root', 'evidence', 'fixture'):
        recipes.add_argument('--' + option, required=True, type=Path)
    trial = commands.add_parser('run')
    for option in ('root', 'evidence', 'fixture', 'api'): trial.add_argument('--'+option, required=True, type=Path)
    trial.add_argument('--previous', default='')
    trial.add_argument('--stage', choices=STAGES, default='workbench')
    args = parser.parse_args()
    if args.action == 'pathfinder':
        pathfinder(args.target)
    elif args.action == 'recipes':
        run_recipes(args.root.resolve(), args.evidence.resolve(), args.fixture.resolve(strict=True))
    else:
        run(args.root.resolve(), args.evidence.resolve(), args.fixture.resolve(strict=True),
            args.api.resolve(strict=True), args.previous, args.stage)
