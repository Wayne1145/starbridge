"""Run and inspect two rendered 300-block landings on an isolated hosted Linux runner."""
import argparse
import json
from pathlib import Path
import re
import shutil
import sys
import time

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path[:0] = [str(ROOT / 'qa/linux-fleet'), str(ROOT / 'qa/home-berths')]
from prepare import FORGE, MC, hosted_only, prepare_installation, sha
from run import launch, stop_own_process, stop_server, write_json
from runtime import command
from audit_parallel_approach import HOME, MOON, bounds, collect, nbt, require, ships, summary, uuid

PILOTS = (('OrbitCaptain', '00000000000040008000000000004356'),
          ('OrbitEngineer', '0000000000004000800000000000454e'))
FOOTPRINT = [300, 19, 8]


def read(file):
    return json.loads(file.read_bytes())


def wait_until(condition, processes, seconds, label):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if any(process.poll() is not None for process in processes):
            raise RuntimeError(f'A game process exited while waiting for {label}')
        if condition():
            return
        time.sleep(1)
    raise TimeoutError(label)


def setup(root, evidence, fixture, flight_fixture):
    server = root / 'server'
    roles = [root / name for name, _ in PILOTS]
    installed = prepare_installation(root, fixture, roles)
    installed['originalEquipmentFixtureSha256'] = sha(flight_fixture)
    write_json(evidence / 'installed.json', installed)
    (server / 'server.properties').write_text(
        'server-ip=127.0.0.1\nserver-port=26751\nonline-mode=false\nenforce-secure-profile=false\n'
        'allow-flight=true\ngamemode=survival\ndifficulty=peaceful\nspawn-protection=0\nmax-players=2\n'
        'view-distance=4\nsimulation-distance=4\nlevel-seed=576618501\nenable-command-block=false\n', 'utf8')
    for role in (server, *roles):
        shutil.copyfile(flight_fixture, role / 'mods' / flight_fixture.name)
        (role / 'starbridge-concurrent-berths.fixture').touch()
        (role / 'remote-wide-berths.fixture').touch()
    for role in roles:
        (role / 'options.txt').write_text(
            'version:3465\nlang:zh_cn\nonboardAccessibility:false\nfullscreen:false\npauseOnLostFocus:false\n'
            'renderDistance:4\nsimulationDistance:4\nguiScale:2\nmaxFps:24\nmipmapLevels:0\ntutorialStep:none\n', 'utf8')
        (role / 'config/forge-client.toml').write_text('[client]\nshowLoadWarnings=false\n', 'utf8')


def client_command(launcher, role, identity):
    args = command(launcher, role)
    args[args.index('--username') + 1] = role.name
    args[args.index('--uuid') + 1] = identity
    args[1:1] = ['-Dio.netty.eventLoopThreads=4', '-Dretrofit.minecraftHome=' + str(role / 'legacy-launcher')]
    return ['xvfb-run', '-a', '-s', '-screen 0 1024x768x24 -nolisten tcp +extension GLX',
            *args, '--quickPlayMultiplayer', '127.0.0.1:26751']


def audit(root):
    server = root / 'server'
    world = server / 'world'
    result = read(server / 'berth-result.json')
    timing = read(server / 'landing-profile.json')
    require(result['pass'] and len(result['rounds']) == 1, 'One complete pair of original lunar landings is required')
    trial, = result['rounds']
    require(trial['footprint'] == FOOTPRINT and trial['equalSite'] is False, 'Two distinct 300 × 19 × 8 berths')
    require(read(server / 'ops.json') == [] and nbt(world / 'level.dat')['Data']['allowCommands'] == 0, 'Non-operator and cheats off')
    require('All dimensions are saved' in (server / 'logs/latest.log').read_text('utf8', errors='replace'), 'Clean world save')
    require(len(trial['confirmation']) == len(trial['landings']) == 2, 'Two independent clients and ships')
    assemblies = [r for r in timing['intervals'] if r['phase'] == 'nativeAssembly']
    first, second = trial['confirmation']
    dispatch = first['processedTick']
    require(first['receivedTick'] <= dispatch and second['receivedTick'] <= dispatch == second['processedTick'],
            'Two received GUI confirmations entered the production handler on the same tick')
    require(len(assemblies) == 2 and dispatch < assemblies[0]['tick'] < assemblies[1]['tick'], 'Assemblies ran on separate ticks')
    native = ships(world)
    results = []
    for index, ((name, _), before, after) in enumerate(zip(PILOTS, trial['confirmation'], trial['landings'])):
        role = root / name
        equipment = read(server / f'berth-equipment-{index}.json')
        require(len(equipment['recipes']) == 4 and len({r['equipment'] for r in equipment['recipes']}) == 4,
                name + ' wore four distinct sealed pieces made from the original recipes')
        frame = read(role / 'wide-berth-map.json')
        picture = role / 'screenshots' / frame['frame']
        site = [before['x'], before['z']]
        require(frame['frame'] == 'select-ready-0.png' and frame['site'] == after['selectedSite'] == site
                and frame['language'] == 'zh_cn' and frame['footprint'] == FOOTPRINT
                and frame['clearance'] == 'CLEAR' and frame['buttonActive'] is True,
                name + ' confirmed the rendered clear map and actual ship footprint')
        require(picture.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'Missing captured landing map for ' + name)
        actions = (role / 'berth-input.log').read_text('utf8').splitlines()
        required_actions = ['route.starbridge.moon', 'nav.starbridge.kit.title', 'rendered four sealed lunar suit slots',
                            'nav.starbridge.kit.back', 'nav.starbridge.cruise', 'orbit.starbridge.landing',
                            'orbit.starbridge.brake', 'orbit.starbridge.touchdown']
        require([row.split(' ', 1)[1] for row in actions if row.split(' ', 1)[1] in required_actions] == required_actions,
                name + ' actual interaction sequence is missing or duplicated')
        pre = before['before']
        require(pre['core'] == after['core'] and pre['rocket'] == after['rocket'] and pre['owner'] == after['owner']
                and pre['FE'] - after['FE'] == 12000 and not pre['receipt'], 'One landing fare charged to the original ship')
        require(after['nativeShip'] in native and after['aboard'] and after['alive'] and not after['orbit'], 'Pilot aboard landed ship')
        ship = native[after['nativeShip']]
        hull = bounds(ship)
        size = [hull[i + 3] - hull[i] + 1 for i in range(3)]
        require(size == [19, 8, 300] and ship['chunkClaimDimension'] == 'minecraft:dimension:retrofit:legacy_dim_-28',
                'The full ship was saved on the original Galacticraft Moon')
        cargo = collect(world, MOON, hull)
        m = summary(cargo)
        require(m['occupiedBlocks'] == 20104 and m['machinesById'].get('minecraft:barrel') == 4500,
                'Incomplete 300-block hull after shutdown')
        x, y, z = after['storageBridge']
        region = world / MOON / f'r.{x // 512}.{z // 512}.mca'
        from upstream_runtime_region import chunks
        core, = (tile for chunk in chunks(region) for tile in chunk.get('block_entities', [])
                 if [tile[k] for k in 'xyz'] == [x, y, z])
        require(uuid(core, 'CoreId') == after['core'] and uuid(core, 'OriginalRocket') == after['rocket']
                and uuid(core, 'PaidVoyage') == after['receipt'] and core['Energy'] == after['FE'], 'Saved identity, receipt and energy')
        cx, cy, cz = after['sourceBridge']
        origin = [cx - 1, cy - 1, cz - 1, cx + 17, cy + 6, cz + 298]
        require(not any(collect(world, HOME, origin)[:3]), 'No duplicate fixed cabin remains at its origin')
        diamonds = cargo[2][x + 2, y, z + 2]
        emeralds = cargo[2][x + 1, y, z + 297]
        require(any(s['Slot'] == 0 and s['id'] == 'minecraft:diamond' and s['Count'] == 11 + 7 * index == after['diamonds'] for s in diamonds)
                and any(s['Slot'] == 26 and s['id'] == 'minecraft:emerald' and s['Count'] == 14 + 7 * index == after['tailEmeralds'] for s in emeralds),
                'Distinct cargo at the bridge and near the tail persisted in the original slots')
        player = nbt(world / 'playerdata' / (after['owner'] + '.dat'))
        require(player['Dimension'] == 'retrofit:legacy_dim_-28' and player['Health'] > 0 and player['playerGameType'] == 0,
                'Living survival player saved on the Moon')
        kit = role / 'screenshots/sealed-lunar-kit.png'
        require(kit.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'The equipment status screenshot is missing')
        results.append({'pilot': name, 'site': site, 'mapSha256': sha(picture), 'kitSha256': sha(kit),
                        'hull': m, 'arrived': after})
    for identity in ('core', 'rocket', 'owner', 'receipt', 'nativeShip'):
        require(len({r['arrived'][identity] for r in results}) == 2, 'Both landings require distinct ' + identity)
    require(abs(first['x'] - second['x']) >= 400, 'The two surface berths are far enough apart to be independent')
    require(nbt(world / 'data/starbridge_passages.dat')['Legs'] == [], 'Passage journal settled')
    return {'pass': True, 'confirmationTick': dispatch, 'assemblies': assemblies, 'ships': results}


def diagnostics(root, evidence):
    for name in ('server', *(name for name, _ in PILOTS)):
        role = root / name
        dest = evidence / name
        dest.mkdir(exist_ok=True)
        for relative in ('berth-result.json', 'berth-live.json', 'landing-profile.json', 'berth-input.log',
                         'crew-health.jsonl', 'berth-equipment-0.json', 'berth-equipment-1.json',
                         'wide-berth-map.json', 'ops.json', 'screenshots/select-ready-0.png', 'screenshots/sealed-lunar-kit.png'):
            file = role / relative
            if file.is_file():
                path = dest / relative
                path.parent.mkdir(exist_ok=True)
                shutil.copyfile(file, path)
        for relative in ('java.log', 'logs/latest.log'):
            file = role / relative
            if file.is_file():
                with file.open('rb') as stream:
                    stream.seek(max(0, file.stat().st_size - 128 * 1024))
                    (dest / file.name).write_bytes(stream.read())


def run(root, evidence, fixture, flight_fixture):
    hosted_only()
    evidence.mkdir(parents=True, exist_ok=True)
    setup(root, evidence, fixture, flight_fixture)
    server_root = root / 'server'
    server = None
    clients = []
    try:
        server = launch(['java', '-Xms384m', '-Xmx3300m', '-XX:ActiveProcessorCount=2', '-Djava.awt.headless=true',
                         '-Dio.netty.eventLoopThreads=6', '-Dretrofit.minecraftHome=' + str(server_root / 'legacy-launcher'),
                         f'@libraries/net/minecraftforge/forge/{MC}-{FORGE}/unix_args.txt', 'nogui'], server_root)
        wait_until(lambda: re.search(r'Done \([\d.]+s\)!', (server_root / 'java.log').read_text('utf8', errors='replace')),
                   [server], 400, 'dedicated server startup')
        for name, identity in PILOTS:
            role = root / name
            clients.append((role, launch(client_command(root / 'launcher', role, identity), role)))
        wait_until(lambda: (server_root / 'berth-result.json').is_file(), [server, *(p for _, p in clients)],
                   1100, 'both Chinese clients and two complete original lunar landings')
        result = read(server_root / 'berth-result.json')
        require(result['pass'] is True, str(result))
    finally:
        for role, _ in clients:
            (role / 'wide-berth.stop').touch()
        try:
            for _, process in clients:
                stop_own_process(process)
            stop_server(server)
            if server is not None:
                server.wait(timeout=90)
        finally:
            stop_own_process(server)
            diagnostics(root, evidence)
    require(server.returncode == 0 and len(clients) == 2 and all(p.returncode == 0 for _, p in clients),
            'Both clients and the dedicated server must exit normally')
    write_json(evidence / 'wide-berths-audit.json', audit(root))


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    for argument in ('root', 'evidence', 'fixture', 'flight-fixture'):
        cli.add_argument('--' + argument, required=True, type=Path)
    args = cli.parse_args()
    run(args.root.resolve(), args.evidence.resolve(), args.fixture.resolve(strict=True), args.flight_fixture.resolve(strict=True))
