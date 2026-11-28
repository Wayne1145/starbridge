"""Audit a finished native fleet flight against both clients, original hull, and saved world."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import re
import struct
import sys
import uuid
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from upstream_runtime_region import chunks, read_compound


def read_nbt(path: Path):
    data = path.read_bytes()
    return read_compound(gzip.decompress(data) if data.startswith(b'\x1f\x8b') else data)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def block_position(packed: int) -> tuple[int, int, int]:
    def signed(value: int, bits: int) -> int:
        sign = 1 << (bits - 1)
        return (value & (sign - 1)) - (value & sign)

    return signed(packed >> 38, 26), signed(packed, 12), signed(packed >> 12, 26)


def deck_footprint(local_feet: str, spans: tuple[int, int, int]) -> dict:
    # The server verified these feet were on the hull. One full span on either side includes
    # every occupied cell even if the captain changed position between landings.
    position = [math.floor(float(value)) for value in local_feet.strip('()').split(',')]
    bounds = {}
    for axis, coordinate, span in zip('XYZ', position, spans, strict=True):
        bounds['min' + axis] = coordinate - span
        bounds['max' + axis] = coordinate + span
    return bounds


def persisted_freight(region: Path, box: dict) -> tuple[dict, list[Path]]:
    regions = []
    entities = []
    for rx in range(box['minX'] // 512, box['maxX'] // 512 + 1):
        for rz in range(box['minZ'] // 512, box['maxZ'] // 512 + 1):
            path = region / f'r.{rx}.{rz}.mca'
            if not path.exists():
                continue
            regions.append(path)
            for chunk in chunks(path):
                for tile in chunk.get('block_entities', []):
                    if all(box['min' + axis.upper()] <= tile[axis] <= box['max' + axis.upper()] for axis in 'xyz'):
                        entities.append(tile)
    inventories = [item for tile in entities if tile['id'] in ('minecraft:chest', 'minecraft:furnace')
                   for item in tile.get('Items', [])]
    return {'blockEntities': len(entities), 'diamonds': sum(v['Count'] for v in inventories if v['id'] == 'minecraft:diamond'),
            'furnaceItems': sum(v['Count'] for v in inventories if v['id'] in ('minecraft:iron_ore', 'minecraft:iron_ingot'))}, regions


def full_pack_runtime(root: Path, launcher: dict, logs: list[Path]) -> tuple[dict, dict]:
    lock = json.loads((Path(__file__).resolve().parents[2] / 'pack/original-spaceflight.lock.json').read_bytes())
    dependencies = {entry['directory'] + '/' + entry['file']: entry['sha256'] for entry in lock['dependencies']}
    projects = {entry['directory'] + '/' + entry['file'] for entry in lock['project']}
    tools = {'mods/starbridge-connected-fleet-0.1.0.jar', 'mods/starbridge-flight-fixture-1.0.0.jar'}
    expected = set(dependencies) | projects | tools
    checked = {}
    for role, path in zip(('server', 'FleetCaptain', 'Peers'), logs):
        side = root / role
        actual = {jar.relative_to(side).as_posix(): digest(jar) for folder in ('mods', 'retrofit/mods')
                  for jar in (side / folder).glob('*.jar')}
        text = path.read_text('utf8', errors='replace')
        ids = ('advancedRocketry', 'GalacticraftCore', 'GalaxySpace', 'ExtraPlanets')
        checked[role] = {
            'lockedArtifacts': set(actual) == expected and all(actual.get(k) == v for k, v in dependencies.items())
                               and actual == launcher['installed'],
            'legacyClasspathEntries': [int(n) for n in re.findall(r'Legacy classpath: (\d+) entries', text)],
            'legacyBlockRegistrations': [int(n) for n in re.findall(r'Registered (\d+) legacy blocks', text)],
            'originalMods': {mod: mod in text for mod in ids},
            'refusedHandshake': '[retrofit:legacy] rejected' in text or '1.7.10 mods are not loaded' in text,
        }
    checks = {
        'fullPackMatchesLock': all(state['lockedArtifacts'] for state in checked.values()),
        'fullPackActuallyLoaded': all(state['legacyClasspathEntries'] == ([23] if role == 'server' else [47])
                                       and 408 in state['legacyBlockRegistrations'] and all(state['originalMods'].values())
                                       and not state['refusedHandshake'] for role, state in checked.items()),
    }
    return checks, checked


def audit(root: Path) -> dict:
    server = root / 'server'
    screen = root / 'FleetCaptain'
    world = server / 'world'
    report_path = server / 'fleet-result.json'
    report = json.loads(report_path.read_bytes())
    length = report['pilotShipLength']
    source = server / 'inputs' / f'{length}.nbt'
    native = read_nbt(source)
    rocket = str(uuid.UUID(bytes=struct.pack('>4i', *native['Rocket'])))
    core = str(uuid.UUID(bytes=struct.pack('>4i', *native['Hull']['Core'])))
    cells = native['Hull']['Cells']
    lower, upper = [block_position(native['Hull'][key]) for key in ('Min', 'Max')]
    width, height, manifest_length = [b - a + 1 for a, b in zip(lower, upper)]
    if min(width, height, manifest_length) < 1 or manifest_length != length:
        raise ValueError('The source manifest does not describe the selected flight hull')
    stations = sum(c['State']['Name'] == 'minecraft:chest' for c in cells)
    peers_path = root / 'Peers' / 'peer-counts.json'
    peers = json.loads(peers_path.read_bytes())
    logs = [server / 'logs/latest.log', screen / 'logs/latest.log', root / 'Peers/logs/latest.log']
    server_log, captain_log, _ = [p.read_text(encoding='utf8', errors='replace') for p in logs]
    cohort = report['stages'][-1]
    flight = report['flight']
    checks = {}
    checks['roundTrip'] = report.get('passed') is True and flight.get('finished') is True
    checks['liveConnections'] = (cohort['uniquePlayerIds'] == cohort['realTcpPlayers'] == 30
                                  and len(peers) == 29 and all(p['joined'] and p['positioned']
                                  and min(p['chunks'], p['keepalives'], p['movement']) > 0 for p in peers))
    end = server_log.find('Completed 30 clients for')
    checks['continuousConnections'] = end >= 0 and not re.search(r'Fleet(?:Captain|QA\d+) left the game', server_log[:end])
    properties = (server / 'server.properties').read_text(encoding='utf8')
    checks['ordinarySurvival'] = (json.loads((server / 'ops.json').read_bytes()) == []
                                  and read_nbt(world / 'level.dat')['Data']['allowCommands'] == 0
                                  and 'gamemode=survival' in properties)
    controls = re.findall(r'FLEET UI (\S+) clicked=true', captain_log)
    routes = [i for i, key in enumerate(controls) if key.startswith('route.starbridge.')]
    leg_inputs = [controls[start:stop] for start, stop in zip(routes, routes[1:] + [len(controls)])]
    checks['graphicalControls'] = ('FLEET using paired terminal on the Moon' in captain_log
            and [keys[0] for keys in leg_inputs] == ['route.starbridge.moon', 'route.starbridge.home']
            and all(keys.count('nav.starbridge.cruise') == 1 and 'orbit.starbridge.landing' in keys
                    and keys.count('orbit.starbridge.touchdown') >= 1 for keys in leg_inputs))
    for leg, arrival, dimension in [('moon', 'lunar', 'retrofit:legacy_dim_-28'), ('home', 'home', 'minecraft:overworld')]:
        state = flight.get(arrival, {})
        cargo = flight.get(leg + 'Hull', {})
        feet = flight.get(leg + 'Footing', {})
        fare = flight.get(leg + 'Fare', {})
        checks[leg + 'Cargo'] = (state.get('original') == rocket and state.get('dimension') == dimension
                                 and state.get('diamonds') == 17 * stations and state.get('furnaceItems') == 8 * stations
                                 and cargo == {'length': length, 'width': width, 'height': height, 'cargoStations': stations, 'core': core,
                                               'blocks': len(cells), 'blockEntities': sum('Tile' in c for c in cells)})
        checks[leg + 'Feet'] = state.get('crewAlive') is True and feet.get('pilotHealth', 0) > 0 and abs(feet.get('gap', 1)) <= .075
        checks[leg + 'Fare'] = (fare.get('receiptMatches') is True and fare.get('pending') is False
                                and fare.get('charged') == 12000 and fare['energyBefore'] - fare['balance'] == 12000)
    checks['distinctReceipts'] = flight.get('moonFare', {}).get('leg') != flight.get('homeFare', {}).get('leg')
    journal = world / 'data/starbridge_passages.dat'
    captain_file = world / 'playerdata/26b30ff7-0ad8-3061-adaf-6017614fa42b.dat'
    captain = read_nbt(captain_file)
    checks['save'] = (read_nbt(journal) == {'Format': 1, 'Legs': []} and captain['Dimension'] == 'minecraft:overworld' and captain['Health'] > 0)
    crashes = sorted(p.name for p in (server / 'crash-reports').glob('*.txt'))
    vs_save = world / 'data/vs_ship_data.dat'
    vs_record = json.loads(read_nbt(vs_save)['data']['vs_pipeline'])
    home_ship_id = flight.get('home', {}).get('shipId')
    home_body = next((v for v in vs_record['ships'] if v['id'] == home_ship_id), None)
    freight = {}
    regions_read = set()
    moon_region = world / 'dimensions/retrofit/legacy_dim_-28/region'
    moon_feet = flight.get('moonFooting', {}).get('localFeet')
    if moon_feet:
        # Reassembly can allocate a different VS yard on each leg. Check the actual outbound
        # location as well as the mirror of the final home yard.
        former_yard = deck_footprint(moon_feet, (width, height, length))
        freight['retiredMoonYard'], files = persisted_freight(moon_region, former_yard)
        regions_read.update(files)
    if home_body is not None:
        target_box = home_body['shipAABB']
        for name, region in [('home', world / 'region'), ('moon', moon_region)]:
            freight[name], files = persisted_freight(region, target_box)
            regions_read.update(files)
    tile_count = sum('Tile' in c for c in cells)
    checks['persistedFreight'] = (home_body is not None and home_body['chunkClaimDimension'].endswith('minecraft:overworld')
            and freight['home'] == {'blockEntities': tile_count, 'diamonds': 17 * stations, 'furnaceItems': 8 * stations}
            and all(n == 0 for n in freight['moon'].values())
            and moon_feet is not None and all(n == 0 for n in freight['retiredMoonYard'].values()))
    checks['cleanShutdown'] = (not crashes and 'All dimensions are saved' in server_log and 'Player not watching chunk that we thought they were' not in server_log)
    checks['wallTps'] = cohort.get('performancePassed') is True and cohort['wallTps'] >= cohort['minimumWallTps']
    artifacts = [source, report_path, peers_path, journal, captain_file, vs_save, *logs, server / 'server.properties',
                 server / 'ops.json', world / 'level.dat', *sorted(regions_read)]
    installed = {}
    for name in ['server', 'FleetCaptain', 'Peers']:
        base = root / name
        modern = next((base / 'mods').glob('starbridge-1.20.1-*.jar'))
        companion = next((base / 'retrofit/mods').glob('starbridge-dock-*.jar'))
        fixture = next((base / 'mods').glob('starbridge-connected-fleet-*.jar'))
        installed[name] = [digest(p) for p in [modern, companion, fixture]]
    checks['matchedInstallations'] = all(v == installed['server'] for v in installed.values())
    runtime = {}
    admission = {}
    launcher_path = root / 'run-result.json'
    if launcher_path.is_file():
        launcher = json.loads(launcher_path.read_bytes())
        if 'legacyCaches' in launcher:
            pack_checks, runtime = full_pack_runtime(root, launcher, logs)
            checks.update(pack_checks)
            yard = Path(__file__).resolve().parents[2] / 'docs/wide-fleet-verification.json'
            original = json.loads(yard.read_bytes())['originalLaunches']['300']
            checks['originalWideHull'] = (digest(source) == launcher['originalWideManifest'] == original['sha256']
                                          and (width, height, length) == (19, 8, 300))
            expected_names = {'FleetCaptain'} | {f'FleetQA{number:02}' for number in range(1, 30)}
            joins = re.findall(r'^\[([0-9T:.,-]+)\].*?\b(Fleet(?:Captain|QA\d+)) joined the game',
                               server_log[:end] if end >= 0 else server_log, re.MULTILINE)
            checks['namedConnectedCohort'] = (len(joins) == 30 and {name for _, name in joins} == expected_names
                    and {peer['name'] for peer in peers} == set(range(1, 30)) and all(peer['alive'] for peer in peers)
                    and cohort['renderedClients'] == 1 and cohort['headlessForgePlayConnections'] == 29)
            if joins:
                first, last = (datetime.fromisoformat(t.replace(',', '.')) for t in (joins[0][0], joins[-1][0]))
                admission = {'firstJoin': joins[0][0], 'lastJoin': joins[-1][0],
                             'firstToLastJoinSeconds': (last - first).total_seconds(),
                             'simultaneousMeasuredSeconds': cohort['seconds']}
                retries = root / 'Peers/native-peer-retry.log'
                admission['beforeJoinRetries'] = retries.read_text('utf8').splitlines() if retries.is_file() else []
                if retries.is_file():
                    artifacts.append(retries)
            artifacts.append(launcher_path)
    return {'run': root.name, 'pass': all(checks.values()), 'checks': checks, 'length': length, 'width': width, 'height': height,
            'original': rocket, 'core': core, 'nativeBlockCount': len(cells), 'nativeBlockEntities': sum('Tile' in c for c in cells),
            'freightStations': stations, 'admission': admission, 'fullPackRuntime': runtime, 'persistedFreight': freight, 'controlsPerLeg': leg_inputs, 'flight': flight, 'stage': cohort, 'installed': installed,
            'crashReports': crashes, 'warnings': {'assemblyWaits': server_log.count('Timed out waiting for chunks to start ticking after assembly!'),
                'crossDimensionWatchTasks': server_log.count('Player received watch task for chunk in dimension that they are not also in!'),
                'serverBacklogsMs': [int(v) for v in re.findall(r'Running (\d+)ms or \d+ ticks behind', server_log)]},
            'rawSha256': {p.relative_to(root).as_posix(): digest(p) for p in artifacts}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = audit(args.run)
    args.output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf8')
    print(result['run'], result['pass'], {k: v for k, v in result['checks'].items() if not v})
    sys.exit(not result['pass'])
