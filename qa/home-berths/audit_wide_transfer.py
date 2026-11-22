"""Compare the actual source and Moon region files around the moved ship after a normal shutdown."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from upstream_runtime_region import chunks
sys.path.insert(0, str(ROOT / 'qa/original-flight'))
from inspect_deck import nbt

MOON = Path('dimensions/retrofit/legacy_dim_-28/region')
HOME = Path('region')


def canonical(value):
    def encode(byte_array):
        if isinstance(byte_array, bytes):
            return {'NBTByteArray': byte_array.hex()}
        raise TypeError(type(byte_array))
    return json.dumps(value, default=encode, sort_keys=True, separators=(',', ':'))


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def collect(world, directory, bounds):
    x0, y0, z0, x1, y1, z1 = bounds
    blocks, machines, inventories, checksums = {}, {}, {}, {}
    for rx in range(x0 // 512, x1 // 512 + 1):
        for rz in range(z0 // 512, z1 // 512 + 1):
            relative = directory / f'r.{rx}.{rz}.mca'
            region = world / relative.as_posix()
            if not region.exists():
                continue
            checksums[relative.as_posix()] = digest(region)
            for chunk in chunks(region):
                left, north = chunk['xPos'] * 16, chunk['zPos'] * 16
                if left > x1 or north > z1 or left + 15 < x0 or north + 15 < z0:
                    continue
                for section in chunk['sections']:
                    bottom = section['Y'] * 16
                    states = section.get('block_states')
                    if not states or bottom + 15 < y0 or bottom > y1:
                        continue
                    palette = states['palette']
                    names = [canonical(p) if p['Name'] not in ('minecraft:air','minecraft:cave_air','minecraft:void_air') else None for p in palette]
                    if not any(names):
                        continue
                    bits = max(4, (len(names) - 1).bit_length())
                    per_word = 64 // bits
                    packed = states.get('data')
                    for y in range(max(y0,bottom),min(y1,bottom+15)+1):
                        for z in range(max(z0,north),min(z1,north+15)+1):
                            for x in range(max(x0,left),min(x1,left+15)+1):
                                index = ((y & 15) * 16 + (z & 15)) * 16 + (x & 15)
                                entry = 0 if len(names)==1 else (packed[index//per_word] >> (index%per_word*bits)) & ((1<<bits)-1)
                                if names[entry] is not None:
                                    blocks[x,y,z] = names[entry]
                for tile in chunk['block_entities']:
                    x, y, z = (tile[a] for a in 'xyz')
                    if x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1:
                        machines[x,y,z] = tile['id']
                        if 'Items' in tile:
                            inventories[x,y,z] = tile['Items']
    return blocks, machines, inventories, checksums


def summary(sample):
    blocks, machines, inventories, checksums = sample
    items = Counter()
    for slots in inventories.values():
        for stack in slots:
            items[stack['id']] += stack['Count']
    def fingerprint(mapping):
        rows = [[*pos, mapping[pos]] for pos in sorted(mapping)]
        return hashlib.sha256(canonical(rows).encode('utf8')).hexdigest()
    return {'occupiedBlocks':len(blocks), 'machinesById':dict(sorted(Counter(machines.values()).items())),
            'machines':len(machines), 'itemsById':dict(sorted(items.items())), 'listedInventories':len(inventories),
            'occupiedStateSha256':fingerprint(blocks), 'machinePositionSha256':fingerprint(machines),
            'slottedInventorySha256':fingerprint(inventories), 'regionSha256':checksums}


def audit(source, result):
    log = result / 'logs/latest.log'
    if 'All dimensions are saved' not in log.read_text('utf8', errors='replace'):
        raise ValueError('The destination server did not complete its world save')
    return inspect_saved_worlds(source, result, digest(log))


def replay(archive):
    with zipfile.ZipFile(archive) as file:
        root = zipfile.Path(file)
        receipt = json.loads((root / 'shutdown.json').read_bytes())
        if 'All dimensions are saved' not in receipt['line']:
            raise ValueError('The shutdown record is missing')
        return inspect_saved_worlds(root / 'before', root / 'after', receipt['fullLogSha256'])


def inspect_saved_worlds(source, result, log_digest):
    live = json.loads((result / 'home-berth-result.json').read_bytes())
    if live.get('pass') is not True or live['players'] != 0:
        raise ValueError('Expected the successful zero-player passage')
    ship = live['wideTransfer']
    samples = [collect(path/'world',dimension,ship['bounds']) for path,dimension in (
        (source,HOME),(source,MOON),(result,HOME),(result,MOON))]
    before, empty_moon, empty_home, arrival = samples
    if any(empty_moon[:3]) or any(empty_home[:3]):
        raise ValueError('Unexpected hull or cargo at one of the empty endpoints')
    if before[:3] != arrival[:3]:
        raise ValueError('Some saved block states, machine positions or slotted inventories changed')
    totals=summary(arrival)
    items=totals['itemsById']
    if (totals['occupiedBlocks'],totals['machines'],totals['machinesById'].get('retrofit:legacy_tile'),
            items.get('minecraft:diamond',0),items.get('minecraft:iron_ore',0)+items.get('minecraft:iron_ingot',0)) != (19522,4530,4501,221,104):
        raise ValueError('The archived ship did not have the expected full 300-block manifest')
    records = []
    for path,dimension in ((source,'minecraft:overworld'),(result,'retrofit:legacy_dim_-28')):
        file = path/'world/data/vs_ship_data.dat'
        ships = json.loads(nbt(file)['data']['vs_pipeline'])['ships']
        matching = [s for s in ships if s['id'] == ship['shipId']]
        if len(matching) != 1 or matching[0]['chunkClaimDimension'] != 'minecraft:dimension:' + dimension:
            raise ValueError('The same physical ship must have precisely one owning dimension in each save')
        records.append({'dimension':dimension,'id':ship['shipId'],'storageSha256':digest(file)})
    return {'beforeHome':summary(before), 'beforeMoon':summary(empty_moon), 'afterHome':summary(empty_home),
            'afterMoon':totals, 'nativeShipRecords':records, 'serverResultSha256':digest(result/'home-berth-result.json'),
            'serverLogSha256':log_digest}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path,help='Original closed server directory or a published evidence ZIP')
    parser.add_argument('result',type=Path,nargs='?',help='Closed server directory after the lunar transfer')
    args = parser.parse_args()
    proof = audit(args.source,args.result) if args.result is not None else replay(args.source)
    print(json.dumps(proof,indent=2))
