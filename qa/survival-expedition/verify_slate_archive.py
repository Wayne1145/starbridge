"""Verify the ordinary survivor's first workbench, personal navigation slate, and graphical shipyard instructions."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
import uuid
from zipfile import ZipFile
import zlib

from inspect_checkpoint import ROLES
from upstream_runtime_region import read_compound


def tag(data):
    return read_compound(gzip.decompress(data) if data.startswith(b"\x1f\x8b") else data)


def unpack_chunk(record):
    codec, data = record[0], record[1:]
    return read_compound({1: gzip.decompress, 2: zlib.decompress, 3: lambda value: value}[codec](data))


def verify(path):
    with ZipFile(path) as bundle:
        manifest = json.loads(bundle.read('files.json'))
        for name, expected in manifest.items():
            assert hashlib.sha256(bundle.read(name)).hexdigest() == expected, name
        def record(name):
            return json.loads(bundle.read(name))
        def history(name):
            return bundle.read(name).decode('utf8')

        first = record('first/engineer-join.json')
        assert first['name'] == 'OrbitEngineer' and first['items'] == {}
        assert not any(first[k] for k in ('creative', 'op', 'allowCommands'))
        assert '16807 OrbitEngineer crafted 1 crafting_table' in history('first/server-events.log')
        assert '16847 complete 4 place minecraft:crafting_table' in history('first/engineer-events.log')
        saved = tag(bundle.read('last/engineer.dat'))
        assert str(uuid.UUID(bytes=struct.pack('>4i',*saved['UUID']))) == ROLES['OrbitEngineer']
        assert saved['playerGameType'] == 0 and record('last/ops.json') == []
        level = tag(bundle.read('last/level.dat'))['Data']
        assert level['allowCommands'] == 0 and level['GameRules']['randomTickSpeed'] == '3'
        guide = record('last/guide.json')
        assert guide['player'] == ROLES['OrbitEngineer'] and guide['heldItem'] == 'starbridge:navigation_slate'
        assert guide['pass'] and guide['checkedInstallation'] and (guide['requiredLoaded'], guide['optionalLoaded']) == (8,3)
        assert guide['clicked'] == ['install','gui.done','shipyard','elevation','plan','gui.done']
        operations = history('last/engineer-events.log')
        timeline = ['craft minecraft:compass minecraft:compass 1', 'craft minecraft:glass_pane minecraft:glass_pane 16',
                    'craft minecraft:paper minecraft:paper 3', 'craft starbridge:navigation_slate starbridge:navigation_slate 1',
                    'Used owned navigation slate with', 'Clicked gui.starbridge.install.title', 'Clicked gui.done',
                    'Clicked slate.starbridge.guide.shipyard', 'Clicked slate.starbridge.guide.elevation',
                    'Clicked slate.starbridge.guide.plan', 'Clicked gui.done', 'store starbridge:navigation_slate']
        after = 0
        for trigger in timeline:
            after = operations.index(trigger, after) + len(trigger)
        server = history('last/server-events.log')
        for result in ('1 compass','16 glass_pane','3 paper','1 navigation_slate'):
            assert f'OrbitEngineer crafted {result}' in server, result
        chunk = unpack_chunk(bundle.read('last/camp.chunk'))
        assert (chunk['xPos'],chunk['zPos']) == (-6,-2)
        chest = next(tile for tile in chunk['block_entities'] if (tile['x'],tile['y'],tile['z']) == (-89,64,-18))
        slate = 'starbridge:navigation_slate'
        assert sum(i['Count'] for i in chest['Items'] if i['id'] == slate) == 1
        assert all(i['id'] != slate for i in saved['Inventory'])
        assert 'All dimensions are saved' in history('last/shutdown-extract.log')
        print(f"{len(manifest)} files verified. Survival workbench, crafted slate, {guide['useKey']}, installation check, both views and one stored terminal.")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    verify(parser.parse_args().archive)
