"""Cross-check the network client, the dedicated server and the stopped Minecraft save."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'qa/original-flight'))
from inspect_deck import SavedColumns, nbt


def require(value, message):
    if not value:
        raise ValueError(message)


def identity(name):
    # Java's UUID.nameUUIDFromBytes sets UUID v3 bits on the bare MD5 of OfflinePlayer:name.
    return str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))


NAMES = {f'FleetQA{i:02}': identity(f'FleetQA{i:02}') for i in range(1, 11)}
WORLDS = {'moon': 'Galacticraft/retrofit:legacy_dim_-28',
          'extraplanets.venusep': 'ExtraPlanets/retrofit:legacy_dim_-14',
          'galaxyspace.ceres': 'GalaxySpace/retrofit:legacy_dim_-1007'}


def audit_window(pid, fixture, witness, window, peers):
    require(fixture['pid'] == witness['pid'] == window['pid'] == pid, 'Measurements belong to different server processes')
    require(witness['identities'] == NAMES and set(witness['moves']) == set(NAMES)
            and min(witness['moves'].values()) >= 10, 'The server did not observe each expected identity moving')
    require(witness['originalWorlds'] == WORLDS, 'Pinned original providers or dimensions differ')
    require(witness['elapsedSeconds'] >= 75 and witness['firstTick'] <= window['serverTick'] <= witness['lastTick'],
            'Minute sample is outside the uninterrupted witness interval')
    require(all(window[key] == 10 for key in ('target', 'online', 'minimumOnline', 'maximumOnline', 'observedMoving'))
            and window['totalQuorumDeficitTicks'] == 0, 'Ten connections must be present through the complete sample')
    lengths = [100, 200, 300]
    require([s['length'] for s in fixture['ships']] == lengths and all(s['width'] == 8 and s['height'] == 4 for s in fixture['ships'])
            and len({s['id'] for s in fixture['ships']}) == 3 and fixture['furnaces'] == 30, 'Unexpected fixed flight cabins')
    require(witness['lastTick'] > witness['firstTick'] and 60 <= window['windowWallSeconds'] <= witness['elapsedSeconds']
            and window['windowTicks'] > 0 and abs(window['windowTicks'] / window['windowWallSeconds'] - window['tps']) < .001,
            'Tick count and monotonic time disagree')
    require(window['cargo'] == fixture['diamonds'] == [3, 5, 7] and window['stoneThisWindow'] > 0, 'Cargo or furnaces did not survive')
    require(len(window['ships']) == 3 and all(s['active'] and s['cruise'] and not s['paused'] and s['speed'] > 0
            and s['source'] == 'home' and s['target'] == 'moon' for s in window['ships']), 'All three routes must be moving')
    require(len(peers) == 10 and {p['name'] for p in peers} == set(range(1, 11)) and all(
        p['alive'] and p['joined'] and p['positioned'] and p['movement'] >= 10 and p['chunks'] > 0
        and p['keepalives'] > 0 and p['packets'] > p['keepalives'] for p in peers), 'Forge client did not confirm all ten network identities')
    return {'seconds': witness['elapsedSeconds'], 'windowSeconds': window['windowWallSeconds'], 'tps': window['tps'],
            'p95TickMs': window['tickP95CeilMs'], 'worstTickMs': window['peakTickMs'], 'stone': window['stoneThisWindow'],
            'lengths': lengths, 'identities': len(peers), 'dimensions': witness['originalWorlds']}


def audit_two_hours(fixture, rows, finished):
    expected = list(NAMES)
    pid = fixture['pid']
    require(rows and finished['pid'] == pid, 'Missing ten-connection completion for this process')
    elapsed = 0.0
    last_tick = None
    for row in rows:
        require(row['pid'] == pid and row['target'] == row['minimumOnline'] == row['maximumOnline'] == 10
                and row['members'] == row['movingMembers'] == expected, 'Continuous identity sample is incomplete')
        require(row['cargo'] == fixture['diamonds'] and row['stoneThisWindow'] > 0
                and len(row['ships']) == 3 and all(ship['active'] and not ship['paused'] for ship in row['ships']),
                'A ship, its cargo or its machinery stopped operating')
        duration = row['windowWallSeconds']
        elapsed += duration
        require(60 <= duration <= 150 and abs(elapsed - row['quorumWallSeconds']) < .00001
                and (last_tick is None or last_tick + row['windowTicks'] == row['serverTick']),
                'Missing, repeated or discontinuous server window')
        last_tick = row['serverTick']
    require(elapsed >= 7200 and abs(elapsed - finished['seconds']) < .00001 and last_tick == finished['tick'],
            'Ten players did not complete two hours of measured flight')
    return {'hours': elapsed / 3600, 'windows': len(rows), 'worstTickMs': max(row['peakTickMs'] for row in rows),
            'minWindowTps': min(row['tps'] for row in rows)}


def audit_save(server, identities=NAMES):
    world = server / 'world'
    require(json.loads((server / 'ops.json').read_bytes()) == [], 'Operator list is not empty')
    stored = {p.stem: p for p in (world / 'playerdata').glob('*.dat')}
    require(set(stored) == set(identities.values()), 'Stopped save does not contain exactly the expected participant files')
    players = {}
    for index, (name, expected) in enumerate(identities.items()):
        player = nbt(stored[expected])
        uid = str(uuid.UUID(bytes=struct.pack('>4i', *player['UUID'])))
        x, y, z = player['Pos']
        ship = index % 3
        require(uid == expected and player['playerGameType'] == 0 and player['Health'] > 0
                and player['Dimension'] == 'minecraft:overworld' and 0 < x - 640 * ship < 7 and 155 <= y <= 158
                and 0 < z < [100, 200, 300][ship], 'Saved survival account outside its cabin: ' + name)
        players[name] = {'shipLength': [100, 200, 300][ship], 'health': player['Health'], 'position': player['Pos']}
    columns = SavedColumns(world / 'region')
    for index, count in enumerate((3, 5, 7)):
        x, y, z = 640 * index + 5, 156, 10
        require(columns.block(x, y, z) == 'minecraft:chest', 'Missing saved cargo chest')
        matches = [tile for tile in columns.cache[x // 16, z // 16]['block_entities']
                   if (tile['x'], tile['y'], tile['z']) == (x, y, z) and tile['id'] == 'minecraft:chest']
        require(len(matches) == 1 and [(i['Slot'], i['id'], i['Count']) for i in matches[0]['Items']]
                == [(0, 'minecraft:diamond', count)], 'Saved chest lost or duplicated cargo')
    return {'players': players, 'diamonds': [3, 5, 7], 'operators': 0}
