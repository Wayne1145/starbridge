"""Install the two original releases needed for the Mars chart on the independent GUI runner."""
import gzip
from hashlib import sha256
import struct
import uuid
import zlib
from pathlib import Path
import sys
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from upstream_runtime_cache import prepare as prepare_legacy
from upstream_runtime_region import read_compound

SOURCES = {
    'Retrofit': ('https://github.com/Astryxx/Retrofit/releases/download/BETA-0.6.2/retrofit-forge-0.6.2-beta.jar', 1164891),
    'Galacticraft Legacy': ('https://mediafilez.forgecdn.net/files/4501/439/Galacticraft-1.7-3.0.12.504.jar', 12583586),
}


def install(game):
    from runtime import PACK, hosted_only
    hosted_only()
    lock = {row['name']: row for row in PACK['dependencies']}
    installed = {}
    for name, (url, size) in SOURCES.items():
        package = lock[name]
        folder = game / package['directory']
        folder.mkdir(parents=True, exist_ok=True)
        request = Request(url, headers={'User-Agent': 'Starbridge-Forge-QA/1'})
        with urlopen(request, timeout=40) as response:
            address = urlsplit(response.url)
            if address.scheme != 'https' or address.hostname not in ('github.com','release-assets.githubusercontent.com','objects.githubusercontent.com','mediafilez.forgecdn.net'):
                raise ValueError('Unexpected original release host')
            data = response.read(size+1)
        if len(data) != size or sha256(data).hexdigest() != package['sha256']:
            raise ValueError('Original release disagrees with manifest: '+name)
        (folder / package['file']).write_bytes(data)
        installed[package['file']] = package['sha256']
    prepare_legacy(game, client=True)
    (game/'retrofit/retrofit.properties').write_text('download-files=never\n','utf8')
    return installed


def saved_calibration(game):
    save = game / 'saves/Habitat'
    world = read_compound(gzip.decompress((save / 'level.dat').read_bytes()))['Data']
    player = world.get('Player')
    if player is None:
        saved_player, = (save / 'playerdata').glob('*.dat')
        player = read_compound(gzip.decompress(saved_player.read_bytes()))
    if world['allowCommands'] != 0 or player['playerGameType'] != 0:
        raise ValueError('The saved Mars calibration must belong to a survival player in a world with cheats off')
    schematic, = (item for item in player['Inventory'] if item['Slot'] == 33)
    if (schematic['id'], schematic['Count'], schematic.get('tag', {}).get('LegacyMeta')) != ('galacticraftcore:item.schematic', 1, 1):
        raise ValueError('Original T2 schematic did not remain in the same player inventory slot')
    # The participant placed their helm at (63,149,63), overworld chunk (3,3).
    data = (save / 'region/r.0.0.mca').read_bytes()
    location = (3 * 32 + 3) * 4
    start = int.from_bytes(data[location:location+3], 'big') * 4096
    length = int.from_bytes(data[start:start+4], 'big')
    if not start or not 1 <= length <= len(data)-start-4:
        raise ValueError('The region no longer contains the saved bridge')
    unpack = {1: gzip.decompress, 2: zlib.decompress, 3: lambda value: value}[data[start+4]]
    chunk = read_compound(unpack(data[start+5:start+4+length]))
    hull, = (tile for tile in chunk['block_entities'] if (tile['x'],tile['y'],tile['z']) == (63,149,63))
    if hull['id'] != 'starbridge:ship_core' or hull['NavigationTier'] != 2 or hull['Owner'] != player['UUID']:
        raise ValueError('Saved helm, original owner and T2 research progress did not agree')
    owner = str(uuid.UUID(bytes=struct.pack('>4i', *hull['Owner'])))
    return {'owner': owner, 'navigationTier': 2, 'retainedOriginalItem': schematic['id'], 'inventorySlot': 33,
            'originalMetadata': 1, 'survival': True, 'cheats': False}
