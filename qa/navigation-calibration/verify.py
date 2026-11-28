"""Recheck a navigation calibration run against its actual saved helm block entities."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
import sys
import uuid
from zipfile import ZIP_DEFLATED, ZipFile
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from upstream_runtime_region import read_compound


def extract_chunk(path):
    data = path.read_bytes()
    offset = int.from_bytes(data[:3], 'big') * 4096  # Both helms are in overworld chunk (0, 0).
    assert offset > 0
    size = int.from_bytes(data[offset:offset + 4], 'big')
    assert 1 <= size <= len(data) - offset - 4
    return data[offset + 4:offset + 4 + size]


def seal(folder, archive):
    members = {name: (folder / name).read_bytes() for name in ('calibration-result.json', 'reopen-result.json', 'installed.json', 'ops.json')}
    members['overworld.chunk'] = extract_chunk(folder/'world/region/r.0.0.mca')
    for phase in ('calibration', 'reopen'):
        lines = (folder / (phase+'.log')).read_text('utf8', errors='replace').splitlines()
        members[phase+'-shutdown.log'] = ('\n'.join(line for line in lines if any(marker in line for marker in ('Starting minecraft server version', 'Saving players', 'All dimensions are saved'))) + '\n').encode('utf8')
    checksums = {name: hashlib.sha256(data).hexdigest() for name, data in members.items()}
    archive.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(archive, 'w', compression=ZIP_DEFLATED) as bundle:
        for name, data in members.items(): bundle.writestr(name, data)
        bundle.writestr('sha256.json', json.dumps(checksums, indent=2))
    verify(archive)


def verify(path):
    def identity(value):
        return str(uuid.UUID(bytes=struct.pack('>4i', *value)))
    with ZipFile(path) as bundle:
        hashes = json.loads(bundle.read('sha256.json'))
        for name, expected in hashes.items():
            assert hashlib.sha256(bundle.read(name)).hexdigest() == expected, name
        first = json.loads(bundle.read('calibration-result.json'))
        reopened = json.loads(bundle.read('reopen-result.json'))
        assert first['pass'] and reopened['pass'] and first['core'] == reopened['core']
        assert json.loads(bundle.read('ops.json')) == []
        assert (first['oldSaveWithTier'], first['oldSaveWithoutTier']) == (7, 1)
        assert first['marsBefore'] == 'TECHNOLOGY' and first['marsAfter'] == 'FUEL'
        assert first['originalStackUnchanged'] and first['testedRocketVariant'] == 3
        names = {(r['item'], r['metadata'], r['tier']) for r in first['catalogue']}
        assert {('galacticraftcore:item.schematic', 1, 2), ('galacticraftmars:item.schematic', 0, 3), ('extraplanets:schematictier4', 0, 4), ('extraplanets:schematictier10', 0, 10)} <= names
        assert all('All dimensions are saved' in bundle.read(p+'-shutdown.log').decode() for p in ('calibration','reopen'))
        record = bundle.read('overworld.chunk')
        unpack = {1: gzip.decompress, 2: zlib.decompress, 3: lambda value: value}[record[0]]
        chunk = read_compound(unpack(record[1:]))
        assert (chunk['xPos'], chunk['zPos']) == (0, 0)
        cores = {identity(tile['CoreId']): tile for tile in chunk['block_entities'] if tile['id'] == 'starbridge:ship_core'}
        primary, secondary = cores[first['core']], cores[first['secondHelm']]
        assert identity(primary['Owner']) == reopened['owner'] == first['owner'] and primary['NavigationTier'] == reopened['tier'] == 10
        assert identity(secondary['Owner']) == first['visitor'] and secondary['NavigationTier'] == first['rocketOwnerTier'] == 4
        print(f"{len(hashes)} files verified: {len(names)} real original item types, both owners and saved tiers 10 and 4.")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--seal', type=Path, help='Build the archive from a stopped dedicated server')
    args = parser.parse_args()
    if args.seal:
        seal(args.seal, args.archive)
    else:
        verify(args.archive)
