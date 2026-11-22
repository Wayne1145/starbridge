"""Compare the original 300-block ship and the confirmed landing after both worlds saved."""
import argparse
import json
import zipfile
from pathlib import Path
from uuid import UUID

from audit_wide_transfer import HOME, MOON, collect, digest, nbt, summary


def require(value, message):
    if not value:
        raise ValueError(message)


def relative(table, origin):
    return {tuple(n - offset for n, offset in zip(pos, origin)): value for pos, value in table.items()}


def audit(source, result, shutdown=None):
    trial = json.loads((result / 'home-berth-result.json').read_bytes())
    initial = json.loads((source / 'large-flight-audit.json').read_bytes())
    require(trial['pass'] and trial['clientProcesses'] == 0 and trial['simulatedPilots'] == 1
            and trial['serverActions'] == ['SURVEY', 'CLEAR', 'LAND'], 'Expected confirmed simulated flight')
    require(trial['rocket'] == initial['original'] and trial['core'] == initial['core'], 'Original rocket or bridge changed')
    before, after = source / 'world', result / 'world'
    moon = 'retrofit:legacy_dim_-28'

    def body(world, identity, dim):
        data = nbt(world / 'data/vs_ship_data.dat')['data']['vs_pipeline']
        ship, = (s for s in json.loads(data)['ships'] if s['id'] == identity)
        require(ship['chunkClaimDimension'] == 'minecraft:dimension:' + dim, 'Native body in wrong dimension')
        return [ship['shipAABB'][prefix + axis] for prefix in ('min', 'max') for axis in 'XYZ']

    old = body(before, initial['flight']['home']['shipId'], 'minecraft:overworld')
    new = body(after, trial['ship'], moon)
    require(new == trial['nativeBounds'], 'Saved ship bounds changed')
    a, b = collect(before, HOME, old), collect(after, MOON, new)
    for name, left, right in zip(('block states', 'machine types'), a[:2], b[:2]):
        require(relative(left, old[:3]) == relative(right, new[:3]), 'Changed ' + name)
    slots = relative(a[2], old[:3]); arrival = relative(b[2], new[:3])
    chest = tuple(n - offset for n, offset in zip(trial['arrivalCargo'], new[:3]))
    stack, = (stack for stack in slots[chest] if stack['Slot'] == 0)
    require(stack['id'] == 'minecraft:diamond' and stack['Count'] == 17
            and all(i['Slot'] != 26 for i in slots[chest]), 'Missing source diamonds')
    slots[chest] = sorted([dict(i, Slot=26) if i['Slot'] == 0 else i for i in slots[chest]], key=lambda i: i['Slot'])
    require(slots == arrival, 'Saved inventory differs from final departure load')
    for world, dim, box in ((after, HOME, old), (before, MOON, new)):
        require(not any(collect(world, dim, box)[:3]), 'Unexpected blocks or cargo at empty endpoint')
    pilot_file = after / 'playerdata' / (trial['pilot'] + '.dat')
    pilot = nbt(pilot_file)
    require(pilot['Dimension'] == moon and pilot['playerGameType'] == 0 and pilot['Health'] > 0, 'Survival captain missing on Moon')
    require(nbt(after / 'data/starbridge_passages.dat')['Legs'] == [], 'A passage is still pending')
    from upstream_runtime_region import chunks
    pos = trial['destinationCore']
    region = after / MOON.as_posix() / f'r.{pos[0]//512}.{pos[2]//512}.mca'
    core, = (tile for chunk in chunks(region) for tile in chunk['block_entities'] if [tile[k] for k in 'xyz'] == pos)

    def uid(key):
        return str(UUID(bytes=b''.join((v & 0xffffffff).to_bytes(4, 'big') for v in core[key])))

    require(uid('PaidVoyage') == trial['receipt'] and uid('OriginalRocket') == trial['rocket'], 'Saved receipt or rocket differs')
    require(core['Energy'] == trial['postFare'] and trial['preFare'] - core['Energy'] == 12000, 'Saved fare differs')
    if shutdown is None:
        log = result / 'logs/latest.log'
        shutdown = {'line': log.read_text('utf8', errors='replace'), 'fullLogSha256': digest(log)}
    require('All dimensions are saved' in shutdown['line'], 'Incomplete shutdown')
    return {'sourceHull': summary(a), 'destinationHull': summary(b), 'cargoMove': {'from': 0, 'to': 26, 'diamonds': 17},
            'pilotDimension': pilot['Dimension'], 'pilotPosition': pilot['Pos'], 'savedEnergy': core['Energy'], 'receipt': uid('PaidVoyage'),
            'playerFileSha256': digest(pilot_file), 'serverLogSha256': shutdown['fullLogSha256'], 'sourceBounds': old, 'destinationBounds': new}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('result', nargs='?', type=Path)
    args = parser.parse_args()
    if args.result is None:
        with zipfile.ZipFile(args.source) as archive:
            root = zipfile.Path(archive)
            evidence = audit(root / 'before', root / 'after', json.loads((root / 'shutdown.json').read_bytes()))
    else:
        evidence = audit(args.source, args.result)
    print(json.dumps(evidence, indent=2))
