"""Audit a finished native fleet flight against both clients, original hull, and saved world."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import struct
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from upstream_runtime_region import chunks, read_compound


def read_nbt(path: Path):
    data = path.read_bytes()
    return read_compound(gzip.decompress(data) if data.startswith(b'\x1f\x8b') else data)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def persisted_freight(region: Path, box: dict) -> dict:
    # VS stores the same shipyard columns in both dimensions during its native portal transfer.
    entities = []
    for rx in range(box['minX'] // 512, box['maxX'] // 512 + 1):
        for rz in range(box['minZ'] // 512, box['maxZ'] // 512 + 1):
            path = region / f'r.{rx}.{rz}.mca'
            if not path.exists():
                continue
            for chunk in chunks(path):
                for tile in chunk.get('block_entities', []):
                    if all(box['min' + axis.upper()] <= tile[axis] <= box['max' + axis.upper()] for axis in 'xyz'):
                        entities.append(tile)
    inventories = [item for tile in entities if tile['id'] in ('minecraft:chest', 'minecraft:furnace')
                   for item in tile.get('Items', [])]
    return {'blockEntities': len(entities), 'diamonds': sum(v['Count'] for v in inventories if v['id'] == 'minecraft:diamond'),
            'furnaceItems': sum(v['Count'] for v in inventories if v['id'] in ('minecraft:iron_ore', 'minecraft:iron_ingot'))}


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
                                 and cargo == {'length': length, 'width': 7, 'height': 6, 'cargoStations': stations, 'core': core,
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
    if home_body is not None:
        target_box = home_body['shipAABB']
        for name, region in [('home', world / 'region'), ('moon', world / 'dimensions/retrofit/legacy_dim_-28/region')]:
            freight[name] = persisted_freight(region, target_box)
    tile_count = sum('Tile' in c for c in cells)
    checks['persistedFreight'] = (home_body is not None and home_body['chunkClaimDimension'].endswith('minecraft:overworld')
            and freight['home'] == {'blockEntities': tile_count, 'diamonds': 17 * stations, 'furnaceItems': 8 * stations}
            and all(n == 0 for n in freight['moon'].values()))
    checks['cleanShutdown'] = (not crashes and 'All dimensions are saved' in server_log and 'Player not watching chunk that we thought they were' not in server_log)
    checks['wallTps'] = cohort.get('performancePassed') is True and cohort['wallTps'] >= cohort['minimumWallTps']
    artifacts = [source, report_path, peers_path, journal, captain_file, vs_save, *logs, server / 'server.properties']
    installed = {}
    for name in ['server', 'FleetCaptain', 'Peers']:
        base = root / name
        modern = next((base / 'mods').glob('starbridge-1.20.1-*.jar'))
        companion = next((base / 'retrofit/mods').glob('starbridge-dock-*.jar'))
        fixture = next((base / 'mods').glob('starbridge-connected-fleet-*.jar'))
        installed[name] = [digest(p) for p in [modern, companion, fixture]]
    checks['matchedInstallations'] = all(v == installed['server'] for v in installed.values())
    return {'run': root.name, 'pass': all(checks.values()), 'checks': checks, 'length': length,
            'original': rocket, 'core': core, 'nativeBlockCount': len(cells), 'nativeBlockEntities': sum('Tile' in c for c in cells),
            'freightStations': stations, 'persistedFreight': freight, 'controlsPerLeg': leg_inputs, 'flight': flight, 'stage': cohort, 'installed': installed,
            'crashReports': crashes, 'warnings': {'assemblyWaits': server_log.count('Timed out waiting for chunks to start ticking after assembly!'),
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
