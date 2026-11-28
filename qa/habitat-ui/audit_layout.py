"""Match the two rendered controller diagrams to the already productive, stopped factory."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from upstream_runtime_region import chunks, read_compound


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit(game, factory, publish):
    client = game/'HabitatCaptain'
    world = factory/'server/world'
    observed = json.loads((client/'machine-layout-result.json').read_bytes())
    build = json.loads((game/'launch-result.json').read_bytes())
    if not observed['pass'] or not build['pass'] or 'All dimensions are saved' not in (client/'logs/latest.log').read_text('utf8', errors='replace'):
        raise ValueError('Incomplete rendered survival test')
    region = world/'region/r.0.0.mca'
    registry = world/'retrofit/fml-ids.dat'
    blocks = {row['V']: row['K'][1:].lower() for row in read_compound(registry.read_bytes())['ItemData'] if row['K'].startswith(chr(1))}
    tiles = {(t['x'], t['y'], t['z']): t['legacy'] for c in chunks(region) if (c['xPos'],c['zPos']) in ((1,2),(2,2)) for t in c['block_entities'] if 'legacy' in t}
    roles = {'FLUID_IN':'ARFluidHatch', 'FLUID_OUT':'ARFluidHatch', 'ITEM_IN':'ARinputHatch', 'ITEM_OUT':'ARoutputHatch', 'POWER':'ARrfBattery', 'FRAME':'ARplaceHolder'}
    checked = {}
    for name, controller in [('reactor','ARChemicalReactor'), ('electrolyser','ARElectrolyser')]:
        origin, = (pos for pos,t in tiles.items() if t['id']==controller)
        cells = observed[name]
        anchor, = (cell for cell in cells if cell['role']=='CONTROLLER')
        matches = []
        for cell in cells:
            # This served factory was built with the controller facing north. A player looking at its face has east on the left.
            p=(origin[0]-(cell['x']-anchor['x']),origin[1]+cell['layer']-anchor['layer'],origin[2]+cell['z']-anchor['z'])
            t=tiles[p]; role=cell['role']; expected=controller if role=='CONTROLLER' else roles[role]
            if t['id']!=expected:
                raise ValueError(f'{name}: GUI {role} at {p} is actually {t["id"]}')
            if role=='FRAME' and (blocks[t['ID']]!=cell['item'] or t['damage']!=cell['metadata']):
                raise ValueError(f'{name}: joined structural material differs at {p}')
            if role in ('FLUID_IN','FLUID_OUT') and (bool(t['outputOnly'])!=(role=='FLUID_OUT') or cell['metadata']!=(3 if t['outputOnly'] else 2)):
                raise ValueError(f'{name}: incorrect fluid hatch direction or metadata at {p}')
            matches.append({'position':p,'role':role,'item':cell['item'],'metadata':cell['metadata'],'originalTile':t['id']})
        checked[name]=matches
    print('Original controllers and GUI match at',sum(map(len,checked.values())),'positions')
    if not publish:
        return
    photos={}
    for suffix in ('reactor-upper','reactor-lower','electrolyser-upper','electrolyser-lower','projector-recipe'):
        p=client/'screenshots'/('assembly-'+suffix+'.png');dest=ROOT/'docs/images/fuel'/p.name;dest.write_bytes(p.read_bytes());photos[dest.relative_to(ROOT).as_posix()]=sha(dest)
    raw=[game/'launch-result.json',client/'machine-layout-result.json',client/'propellant-guide-result.json',client/'habitat-actions.log']
    archive=ROOT/'docs/evidence/native-fuel/assembly-plans.zip'
    with ZipFile(archive,'w') as z:
        for p in raw:
            z.writestr(ZipInfo(p.relative_to(game).as_posix(),(2026,11,22,0,0,0)),p.read_bytes(),compress_type=ZIP_DEFLATED)
    result={'date':'2026-11-22','minecraft':'1.20.1','forge':'47.4.28','game':game.name,'build':build,'comparisonWorld':factory.name,
            'scenario':'One rendered Chinese client, original mods and both expansions, cheats off. From the fuel guide the pilot selected both original controllers, visited both layers, picked the reactor fluid outlet, opened its recipe and the original LibVulpes projector recipe and returned.',
            'comparison':'All required positions, roles and fluid hatch variants are matched to the actual previously operated factory. Components hidden by an assembled machine are identified from its persisted original block ID, its metadata and the saved Forge registry. Empty template cells are unconstrained.',
            'scope':'The two controllers supply their own structures and one accepted block per position. The recipes were opened in the guide. This trial did not operate an in-world projector or build either machine by hand.',
            'cells':checked,'rawArchive':{archive.relative_to(ROOT).as_posix():sha(archive)},'files':{p.relative_to(ROOT).as_posix():sha(p) for p in [region,registry,*raw]},'screenshots':photos}
    (ROOT/'docs/machine-layout-verification.json').write_text(json.dumps(result,indent=2)+'\n','utf8')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('game',type=Path);p.add_argument('factory',type=Path);p.add_argument('--publish',action='store_true');args=p.parse_args()
    audit(args.game.resolve(),args.factory.resolve(),args.publish)
