"""Copy a stopped native-fuel test world and identify its real machines from their saved tile data."""
from pathlib import Path
import gzip
import hashlib
import json
import shutil
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from upstream_runtime_region import chunks, read_compound


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fixture_checkpoint(previous, production):
    previous = previous.resolve(strict=True)
    if previous.parent != ROOT or not previous.name.startswith('run-native-fuel-'):
        raise ValueError('Expected an isolated native factory inside this workspace')
    server = previous / 'server'
    outcome = json.loads((previous / 'run-result.json').read_bytes())
    steps = [previous / 'LunarTester' / f'factory-done-{step}.json' for step in range(9)]
    if outcome['production'] != production or not all(path.is_file() for path in steps):
        raise ValueError('Expected the same production release and nine completed player actions')
    if (previous / 'LunarTester/factory-done-9.json').exists() or 'All dimensions are saved' not in (server / 'logs/latest.log').read_text('utf8', errors='replace'):
        raise ValueError('This checkpoint requires a cleanly saved world before collecting the fuel bucket')
    if json.loads((server / 'ops.json').read_bytes()):
        raise ValueError('The source server must have no operators')
    world = server / 'world'
    region = world / 'region/r.0.0.mca'
    tiles = [tile['legacy'] for chunk in chunks(region) if (chunk['xPos'], chunk['zPos']) in {(1, 2), (2, 2)}
             for tile in chunk['block_entities'] if 'legacy' in tile]

    def only(matches, label):
        rows = list(matches)
        if len(rows) != 1:
            raise ValueError(f'Expected exactly one {label}, found {len(rows)}')
        return rows[0]

    def pos(tile):
        return tuple(tile[axis] for axis in 'xyz')

    def pack(tile):
        x, y, z = pos(tile)
        return ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff)

    def station(controller_id, fluids):
        controller = only((t for t in tiles if t['id'] == controller_id), controller_id)
        owner = pos(controller)
        hatches = [t for t in tiles if t['id'] == 'ARFluidHatch' and tuple(t['master' + a] for a in 'XYZ') == owner]
        sorted_hatches = [only((t for t in hatches if (t.get('FluidName'), t.get('outputOnly')) == key), str(key)) for key in fluids]
        generator = only((t for t in tiles if t['id'] == 'VulpesCoalGenerator' and sum(abs(a-b) for a,b in zip(pos(t), owner)) <= 4), 'nearby generator')
        return [pack(t) for t in [controller, *sorted_hatches, generator]], sorted_hatches

    split, _ = station('ARElectrolyser', [('water', 0), ('oxygen', 1), ('hydrogen', 1)])
    mix, tanks = station('ARChemicalReactor', [('oxygen', 0), ('hydrogen', 0), ('rocketfuel', 1)])
    pilot = only((world/'playerdata').glob('*.dat'), 'saved player')
    data = read_compound(gzip.decompress(pilot.read_bytes()))
    inventory = data['Inventory']
    if data['playerGameType'] != 0 or not inventory or any(i['id'] != 'minecraft:bucket' for i in inventory):
        raise ValueError('Expected only empty buckets on the same survival player')
    if not (0 < tanks[2]['Amount'] < 1000 and all(t['Amount'] > 0 for t in tanks[:2])):
        raise ValueError('Expected partially processed native rocket fuel with both gas inputs')
    files = [region, pilot, world/'level.dat', server/'factory-progress.json', server/'logs/latest.log', previous/'run-result.json', *steps]
    evidence = {'source': previous.name, 'sha256': {p.relative_to(previous).as_posix(): digest(p) for p in files},
                'savedTanks': {t['FluidName']: t['Amount'] for t in tanks}, 'sourceQA': outcome['qa']}
    mark = {'pilot': pilot.stem, 'emptyBuckets': sum(i['Count'] for i in inventory), 'fuel': tanks[2]['Amount'], 'splitter': split, 'reactor': mix}
    return world, mark, evidence


def copy_checkpoint(previous, destination, production):
    world, mark, evidence = fixture_checkpoint(previous, production)
    target = destination / 'server/world'
    shutil.copytree(world, target, ignore=shutil.ignore_patterns('session.lock'))
    for path in world.rglob('*'):
        if path.is_file() and path.name != 'session.lock' and digest(path) != digest(target / path.relative_to(world)):
            raise ValueError(f'Saved world copy mismatch at {path.relative_to(world)}')
    (destination / 'server/factory-resume.json').write_text(json.dumps(mark, indent=2), 'utf8')
    (destination / 'checkpoint-source.json').write_text(json.dumps(evidence, indent=2), 'utf8')
    return evidence['source']


def copy_saved_dock(previous, destination):
    previous = previous.resolve(strict=True)
    if previous.parent != ROOT or not previous.name.startswith('run-native-fuel-'):
        raise ValueError('Expected a disposable native fuel server inside the workspace')
    server = previous / 'server'
    receipt = json.loads((server/'factory-result.json').read_bytes())
    if not receipt.get('pass') or receipt.get('originalRocketFuel') != 1000 or 'All dimensions are saved' not in (server/'logs/latest.log').read_text('utf8', errors='replace'):
        raise ValueError('Expected the completed, stopped production and refuelling trial')
    if json.loads((server/'ops.json').read_bytes()):
        raise ValueError('Expected an ordinary pilot')
    source = server/'world'
    stats = [e['legacy']['rocketStats'] for region in (source/'entities').glob('*.mca') for c in chunks(region)
             for e in c['Entities'] if e['id'] == 'advancedrocketry:rocket']
    if len(stats) != 1 or stats[0]['fuelLiquid'] != 1000:
        raise ValueError('Missing refuelled original rocket in stopped source world')
    target = destination/'server/world'
    shutil.copytree(source, target, ignore=shutil.ignore_patterns('session.lock'))
    critical = [*source.glob('entities/*.mca'), *source.glob('playerdata/*.dat'), source/'data/starbridge_original_shipyard.dat']
    checked = {p.relative_to(source).as_posix(): digest(p) for p in critical}
    if not all(digest(target/path) == stamp for path, stamp in checked.items()):
        raise ValueError('Saved dock did not copy intact')
    (destination/'server/inspect-saved-rocket.fixture').touch()
    (destination/'dock-source.json').write_text(json.dumps({'source':previous.name, 'files':checked, 'rocket': stats[0]},indent=2),'utf8')
    return previous.name
