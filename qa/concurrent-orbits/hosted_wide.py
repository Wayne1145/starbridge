"""Run and inspect two rendered 300-block landings on an isolated hosted Linux runner."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from zipfile import ZIP_DEFLATED, ZipFile, Path as ZipPath

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


def setup(root, evidence, fixture, flight_fixture, *, preload):
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
    if preload:
        (server / 'wide-assembly-preload.fixture').touch()
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


def template_timings(intervals, assemblies, required):
    placement = [row for row in intervals if row['phase'] == 'vsTemplatePlacement']
    lookups = [row for row in intervals if row['phase'] == 'vsTemplateChunkLookups']
    if not required and not placement and not lookups:
        return []
    require(len(placement) == len(lookups) == len(assemblies), 'Placement and chunk timings for each assembly')
    for assembly, template, lookup in zip(assemblies, placement, lookups):
        require(assembly['tick'] == template['tick'] == lookup['tick']
                and 0 < lookup['ns'] <= template['ns'] <= assembly['ns'] and lookup['count'] > 0,
                'Template and nested chunk timing must fit within the associated assembly')
    timings = [{'tick': row['tick'], 'placementNs': row['ns'], 'chunkLookupNs': lookup['ns'],
                'chunkLookups': lookup['count']} for row, lookup in zip(placement, lookups)]
    managed = [row for row in intervals if row['phase'] == 'vsTemplateManagedChunkTasks']
    if required or managed:
        require(len(managed) == len(timings), 'Chunk task timings for each template placement')
        for measured, work in zip(timings, managed):
            require(measured['tick'] == work['tick'] and 0 <= work['ns'] <= measured['chunkLookupNs']
                    and 0 <= work['count'] <= measured['chunkLookups'], 'Chunk task timing must fit within its enclosing lookups')
            measured.update(managedChunkTaskNs=work['ns'], managedChunkTasks=work['count'])
    return timings



def source_timings(intervals, assemblies):
    phases = ('vsSourceRemoval', 'vsSourceBarriers', 'vsSourceClear')
    rows = {phase: [row for row in intervals if row['phase'] == phase] for phase in phases}
    if not any(rows.values()):
        return []
    require(all(len(items) == len(assemblies) for items in rows.values()), 'One measurement per source phase per ship')
    results = []
    for assembly, removal, barrier, clear in zip(assemblies, *(rows[phase] for phase in phases)):
        require({assembly['tick'], removal['tick'], barrier['tick'], clear['tick']} == {assembly['tick']}
                and 0 < barrier['ns'] + clear['ns'] <= removal['ns'] <= assembly['ns'],
                'Source phases must fit inside synchronous assembly')
        results.append({'tick': assembly['tick'], 'removalNs': removal['ns'],
                        'barrierNs': barrier['ns'], 'clearNs': clear['ns']})
    return results



def audit_preloaded_plots(evidence, destinations):
    log = (evidence / 'assembly-storage.log').read_text('utf8')
    matches = re.findall(r'Predicted VS plot (.+?) has (\d+) of (\d+) chunks ready at conversion', log)
    forecasts = [{'center': [int(axis) for axis in coordinates.split(', ')],
                  'readyChunks': int(ready), 'totalChunks': int(total)} for coordinates, ready, total in matches]
    ready = {tuple(plot['center']) for plot in forecasts if plot['readyChunks'] == plot['totalChunks'] > 0}
    centers = {tuple((low + high + 1) // 2 for low, high in zip(row['bounds'][:3], row['bounds'][3:]))
               for row in destinations}
    require(len(forecasts) == len(ready) == len(destinations) == 2 and ready == centers
            and all(row['readyChunks'] == row['totalChunks'] > 0 for row in destinations),
            'Both actual template destinations must match the preloaded storage chunks')
    return forecasts


def audit(root, world, *, require_template_timings=False, forecast_evidence=None):
    server = root / 'server'
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
        require(ship['chunkClaimDimension'] == 'minecraft:dimension:retrofit:legacy_dim_-28',
                'The ship was saved on the original Galacticraft Moon')
        cargo = collect(world, MOON, hull)
        m = summary(cargo)
        # The fixture furnace replaces a wall block; the core and two chests add three blocks.
        require(m['occupiedBlocks'] == 20103 and m['machinesById'] == {
            'minecraft:barrel': 4500, 'minecraft:chest': 2, 'minecraft:furnace': 1,
            'retrofit:legacy_tile': 1, 'starbridge:ship_core': 1,
        }, 'Incomplete 300-block hull after shutdown')
        coordinates = tuple(zip(*cargo[0]))
        occupied_size = [max(axis) - min(axis) + 1 for axis in coordinates]
        require(occupied_size == [19, 8, 300], f'Unexpected occupied hull dimensions: {occupied_size}')
        m['occupiedSize'] = occupied_size
        x, y, z = after['storageBridge']
        region = world / MOON.as_posix() / f'r.{x // 512}.{z // 512}.mca'
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
    require(set(native) == {r['arrived']['nativeShip'] for r in results}, 'No unused native ship records remain after the two landings')
    require(abs(first['x'] - second['x']) >= 400, 'The two surface berths are far enough apart to be independent')
    require(nbt(world / 'data/starbridge_passages.dat')['Legs'] == [], 'Passage journal settled')
    reservation_data = world / 'data/starbridge_assembly_storage.dat'
    if reservation_data.is_file():
        require(nbt(reservation_data)['Plots'] == [], 'Temporary assembly plot journal settled')
    destinations = [row for row in timing['footprints'] if row['phase'] == 'templateDestination']
    if trial.get('assemblyPreloaded'):
        require(len(destinations) == 2 and all(row['readyChunks'] == row['totalChunks'] > 0 for row in destinations),
                'Both actual template destinations must be fully preloaded')
    timings = template_timings(timing['intervals'], assemblies, require_template_timings)
    removal = source_timings(timing['intervals'], assemblies)
    forecasts = audit_preloaded_plots(forecast_evidence, destinations) if forecast_evidence else []
    if require_template_timings:
        require(len(removal) == len(assemblies), 'Source removal timings are missing')
    return {'pass': True, 'confirmationTick': dispatch, 'assemblies': assemblies, 'ships': results,
            **({'templateTimings': timings} if timings else {}),
            **({'templateDestinations': destinations} if destinations else {}),
            **({'sourceTimings': removal} if removal else {}),
            **({'predictedPlots': forecasts} if forecasts else {})}


def retained_world(root, evidence):
    """Keep the stopped region files that the independent audit reads."""
    server = root / 'server'
    world = server / 'world'
    native = ships(world)
    result = read(server / 'berth-result.json')
    paths = {world / name for name in ('level.dat', 'data/vs_ship_data.dat', 'data/starbridge_passages.dat')}
    reservation_data = world / 'data/starbridge_assembly_storage.dat'
    if reservation_data.is_file(): paths.add(reservation_data)
    for landing in result['rounds'][0]['landings']:
        paths.add(world / 'playerdata' / (landing['owner'] + '.dat'))
        x0, _, z0, x1, _, z1 = bounds(native[landing['nativeShip']])
        for x in range(x0 // 512, x1 // 512 + 1):
            for z in range(z0 // 512, z1 // 512 + 1):
                candidate = world / MOON / f'r.{x}.{z}.mca'
                if candidate.is_file(): paths.add(candidate)
        ox, _, oz = landing['sourceBridge']
        for x in range((ox - 1) // 512, (ox + 17) // 512 + 1):
            for z in range((oz - 1) // 512, (oz + 298) // 512 + 1):
                candidate = world / HOME / f'r.{x}.{z}.mca'
                if candidate.is_file(): paths.add(candidate)
    if sum(path.stat().st_size for path in paths) > 96 * 1024 ** 2:
        raise ValueError('Selected world files exceed the proof archive size limit')
    with ZipFile(evidence / 'wide-berths-saved-world.zip', 'x', ZIP_DEFLATED, compresslevel=3) as output:
        for path in sorted(paths):
            output.write(path, path.relative_to(root).as_posix())


def diagnostics(root, evidence):
    log = root / 'server' / 'java.log'
    if log.is_file():
        assembly = []
        with log.open(encoding='utf8', errors='replace') as source:
            after_failure = 0
            for line in source:
                if 'Cannot prepare ship storage' in line or 'will be checked after restart' in line:
                    after_failure = 32
                elif any(message in line for message in ('Predicted VS plot', 'Large ship assembly reused', 'Cabin handoff', 'Cannot preload ship storage')):
                    assembly.append(line)
                if after_failure:
                    assembly.append(line)
                    after_failure -= 1
        (evidence / 'assembly-storage.log').write_text(''.join(assembly), encoding='utf8')
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
                    path = dest / relative
                    path.parent.mkdir(exist_ok=True)
                    path.write_bytes(stream.read())


def run(root, evidence, fixture, flight_fixture, *, profile=False, preload=False, reserve=False):
    hosted_only()
    require(not (preload and reserve), 'Use either fixed fixture plots or dynamic plot previews')
    evidence.mkdir(parents=True, exist_ok=True)
    setup(root, evidence, fixture, flight_fixture, preload=preload)
    server_root = root / 'server'
    recording = evidence / 'wide-berths-server.jfr'
    recording_args = []
    if profile:
        settings = server_root / 'execution-samples.jfc'
        subprocess.run(['jfr', 'configure', '--input', 'none', '--output', str(settings),
                        '+jdk.ExecutionSample#enabled=true', '+jdk.ExecutionSample#period=5ms'],
                       check=True, timeout=20)
        recording_args = [
            '-XX:FlightRecorderOptions=stackdepth=96',
            f'-XX:StartFlightRecording=name=wide-berths,settings={settings},maxsize=48m,dumponexit=true,filename={recording}',
        ]
    server = None
    clients = []
    try:
        server = launch(['java', '-Xms384m', '-Xmx3300m', '-XX:ActiveProcessorCount=2', '-Djava.awt.headless=true',
                         '-Dio.netty.eventLoopThreads=6', '-Dretrofit.minecraftHome=' + str(server_root / 'legacy-launcher'),
                         *recording_args, *(['-Dstarbridge.test.skipLandingPreload=true'] if preload else []),
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
    if profile:
        require(recording.is_file() and recording.stat().st_size > 0, 'Dedicated server recording is missing')

    retained_world(root, evidence)
    write_json(evidence / 'wide-berths-audit.json', audit(root, root / 'server' / 'world',
                    require_template_timings=True, forecast_evidence=evidence if reserve else None))


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--root', required=True, type=Path)
    cli.add_argument('--audit-saved', action='store_true')
    cli.add_argument('--preload', action='store_true', help='Warm two known fixture storage plots')
    cli.add_argument('--reserve', action='store_true', help='Require both dynamically preloaded plots to match the actual template footprints')
    cli.add_argument('--profile', action='store_true', help='Record the dedicated server with JDK Flight Recorder')
    for argument in ('evidence', 'fixture', 'flight-fixture'):
        cli.add_argument('--' + argument, type=Path)
    args = cli.parse_args()
    if args.audit_saved:
        root = args.root.resolve()
        with ZipFile(root / 'wide-berths-saved-world.zip') as archive:
            print(json.dumps(audit(root, ZipPath(archive) / 'server' / 'world',
                                   forecast_evidence=root if args.reserve else None), indent=2))
    elif all((args.evidence, args.fixture, args.flight_fixture)):
        run(args.root.resolve(), args.evidence.resolve(), args.fixture.resolve(strict=True),
            args.flight_fixture.resolve(strict=True), profile=args.profile, preload=args.preload, reserve=args.reserve)
    else:
        cli.error('A hosted run requires the evidence directory and both fixture jars')
