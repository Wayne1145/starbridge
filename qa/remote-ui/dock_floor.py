"""Verify that the survival player's repair persisted in the original pad's saved blocks."""
import gzip
from hosted_originals import read_compound
from upstream_runtime_region import chunks


def saved_floor(game):
    save = game / 'saves/Habitat'
    world = read_compound(gzip.decompress((save / 'level.dat').read_bytes()))['Data']
    player = world.get('Player')
    if player is None:
        player_file, = (save / 'playerdata').glob('*.dat')
        player = read_compound(gzip.decompress(player_file.read_bytes()))
    if world['allowCommands'] or player['playerGameType'] != 0:
        raise ValueError('Expected a survival world with cheats off')
    chunk, = (c for c in chunks(save / 'region/r.0.0.mca') if (c['xPos'], c['zPos']) == (3, 3))
    states, = (s['block_states'] for s in chunk['sections'] if s['Y'] == 148 // 16)
    palette = states['palette']
    bits = max(4, (len(palette) - 1).bit_length())

    def block(x, z):
        index = (148 % 16) * 256 + (z % 16) * 16 + x % 16
        per_word = 64 // bits
        return palette[(states['data'][index // per_word] >> ((index % per_word) * bits)) & ((1 << bits) - 1)]['Name']

    for x in range(61, 64):
        for z in range(60, 63):
            name = 'tile.dockingpad' if (x, z) == (61, 62) else 'launchpad'
            if block(x, z) != 'advancedrocketry:' + name:
                raise ValueError(f'Unexpected original floor block at {x}, 148, {z}')
    if any(item['id'] == 'advancedrocketry:launchpad' for item in player['Inventory']):
        raise ValueError('Spare launchpad still in inventory')
    return {'survival': True, 'cheats': False, 'repairedCell': [63, 148, 61], 'originalLaunchpads': 8,
            'originalLandingPads': 1, 'remainingSparePads': 0}
