"""Prepare the original server exclusion and verify its surviving cargo after the hosted client exits."""
import gzip
from pathlib import Path
import sys

from hosted_originals import read_compound


def configure(game):
    (game / 'starbridge-forbidden-ui.fixture').touch()
    directory = game / 'retrofit/config/advRocketry'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'advancedRocketry.cfg').write_text('general {\n    S:rocketBlockBlackList <\n        minecraft:chest\n    >\n}\n', 'utf8')


def saved_cargo(game):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'original-flight'))
    from inspect_deck import SavedColumns
    save = game / 'saves/Habitat'
    world = read_compound(gzip.decompress((save / 'level.dat').read_bytes()))['Data']
    player = world.get('Player')
    if player is None:
        savefile, = (save / 'playerdata').glob('*.dat')
        player = read_compound(gzip.decompress(savefile.read_bytes()))
    columns = SavedColumns(save / 'region')
    if world['allowCommands'] or player['playerGameType'] != 0 or columns.block(63,151,60) != 'minecraft:air' or columns.block(61,149,60) != 'minecraft:barrel':
        raise ValueError('Expected an empty chest cell and the surviving barrel on the saved survival ship')
    barrel, = (v for v in columns.cache[3,3]['block_entities'] if (v['x'],v['y'],v['z']) == (61,149,60))
    if barrel['id'] != 'minecraft:barrel' or barrel['Items'] != [{'id':'minecraft:diamond', 'Count':7,'Slot':0}]:
        raise ValueError('Expected seven diamonds in the original barrel slot')
    return {'survival':True, 'cheats':False, 'cleared':[63,151,60], 'barrel':[61,149,60], 'diamonds':7}
