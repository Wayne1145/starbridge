"""Check a two-session native fuel journey against both saves, the UI inputs and the pinned package."""
import argparse
import gzip
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
from checkpoint import ROOT, fixture_checkpoint, digest, read_compound, chunks


def read(path):
    return json.loads(path.read_bytes())


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def inspect(first, second):
    initial, final = read(first / 'run-result.json'), read(second / 'run-result.json')
    require(final['pass'] and initial['bundleSha256'] == final['bundleSha256'] and final['restoredFrom'] == first.name, 'Release changed or wrong continuation')
    require(initial['production'] == final['production'] and initial['companion'] == final['companion'], 'Installed production mods changed')
    original, mark, source = fixture_checkpoint(first, final['production'])
    require(read(second/'checkpoint-source.json') == source and read(second/'server/factory-resume.json') == mark, 'Continuation provenance changed')
    sources = {}
    for side, run in [('first', first), ('resumed', second)]:
        for folder in ['server','LunarTester']:
            for filename in ['mods/starbridge-1.20.1-0.3.0-dev.jar','retrofit/mods/starbridge-dock-1.7.10-0.3.0-dev.jar','mods/starbridge-native-fuel-qa-1.0.jar']:
                key = 'qa' if 'native-fuel-qa' in filename else 'companion' if 'starbridge-dock' in filename else 'production'
                require(digest(run/folder/filename) == (initial if side == 'first' else final)[key], f'Wrong {folder} {key}')
        for relative in ['run-result.json','server/factory-start.json','server/factory-progress.json','server/ops.json','LunarTester/factory-input.log']:
            sources[f'{side}/{relative}'] = (run/relative).read_bytes()
        require(read(run/'server/ops.json') == [], 'Unexpected operator')
        for p in (run/'LunarTester').glob('factory-done-*.json'):
            sources[f'{side}/actions/{p.name}'] = p.read_bytes()
        log = (run/'server/logs/latest.log').read_text('utf8', errors='replace')
        require('All dimensions are saved' in log, 'Missing clean shutdown')
        sources[f'{side}/saved.log'] = ('\n'.join(line for line in log.splitlines() if 'All dimensions are saved' in line or 'logged in with entity id' in line)+'\n').encode()
    expected = [('machine','minecraft:coal'),('machine','minecraft:coal'),('machine','minecraft:water_bucket'),('switch',''),('switch',''),
                ('machine','minecraft:bucket'),('machine','minecraft:bucket'),('machine','advancedrocketry:item.bucketoxygen'),('machine','advancedrocketry:item.buckethydrogen'),('machine','minecraft:bucket'),('rocket','advancedrocketry:item.bucketrocketfuel')]
    for i, (_, item) in enumerate(expected):
        run = first if i < 9 else second
        action = read(run/'LunarTester'/f'factory-done-{i}.json')
        require(action['input'] == item and action['action'], f'Missing player action {i}')
    beginning = read(first/'server/factory-progress.json')[0]
    require(all(beginning[key] == 0 for key in ('water','splitA','splitB','mixA','mixB','fuel','fuelBuckets')), 'Factory tanks or player started with product')
    for i in (3,4): require(read(first/'server'/f'factory-enabled-{i}.json')['enabled'], 'Power switch was not operated')
    result = read(second/'server/factory-result.json'); zero = read(second/'server/empty-original-rocket.json')
    require(result['pass'] and result['ordinarySurvival'] and zero['fuel'] == 0 and result['originalRocketFuel'] == 1000 and result['fuelAtDockBeforeAssembly'] == 1, 'Refuelling measurement failed')
    world = second/'server/world'; player = world/'playerdata'/(mark['pilot']+'.dat'); raw_player = player.read_bytes(); nbt = read_compound(gzip.decompress(raw_player))
    require(nbt['playerGameType'] == 0 and all(i['id'] == 'minecraft:bucket' for i in nbt['Inventory']) and sum(i['Count'] for i in nbt['Inventory']) == mark['emptyBuckets'], 'Wrong final player or inventory')
    rockets = [(region,e) for region in (world/'entities').glob('*.mca') for chunk in chunks(region) for e in chunk['Entities'] if e['id']=='advancedrocketry:rocket']
    require(len(rockets) == 1, 'Expected one original rocket in the stopped world')
    region, rocket = rockets[0]; stats = rocket['legacy']['rocketStats']; require(stats['fuelLiquid']==1000 and stats['fuelCapacityLiquid']>=1000,'Fuel did not persist')
    for name in ['checkpoint-source.json','server/factory-resume.json','server/empty-original-rocket.json','server/factory-result.json','server/factory-offline-tank.json']:
        sources['resumed/'+name] = (second/name).read_bytes()
    sources['first/water-and-gas-player.dat']=(original/'playerdata'/player.name).read_bytes()
    sources['resumed/refuelled-player.dat']=raw_player
    sources['resumed/entity-region.mca']=region.read_bytes()
    images = [('power-switch', first, 'factory-controller-off-4'),('gas-bucket', first, 'factory-output-6'),('fuel-bucket', second, 'factory-output-9'),('fuelled-rocket', second, 'factory-fuel-in-original-rocket')]
    return {'version': '0.3.0-dev', 'date':'2026-11-22', 'platform':'Minecraft 1.20.1 / Forge 47.4.28 / Java 17, dedicated server on loopback with both original expansions and one rendered non-operator survival player.',
            'firstSession': first.name, 'continuation':second.name, 'packageSHA256':final['bundleSha256'], 'mainSHA256':final['production'], 'companionSHA256':final['companion'],
            'setup':'Prepared original multiblock machines, generators, reach positions, a water bucket, empty buckets, coal, and later a powered original launchpad and unassembled test hull.',
            'originalInputs':source['savedTanks'], 'restoredRocketFuel':result['restoredTank'], 'tankBeforeFillingBucket':result['fuelAvailableBeforeBucket'],
            'fuelBeforeShipyardAction':zero['fuel'],'fuelInSavedOriginalRocket':stats['fuelLiquid'],'savedRocketCapacity':stats['fuelCapacityLiquid'],'savedEmptyBuckets':mark['emptyBuckets'],
            'inputs':'The pilot put coal and water in their real slots, pressed each original controller power switch, filled native oxygen and hydrogen buckets, supplied separate reactor inputs, picked up one original propellant bucket, then clicked scan, assemble and refuel.',
            'interruptions':'The first two trials stopped at the host memory guard. Factory2 saved 240 mB in the original output. The successful continuation copied the full stopped world and restored the same player, machines, inventories and tank. Both machine chunks were kept loaded.',
            'limits':'Equipment and input materials were prepared. Mining, building both machines from raw resources and a continuous two-player campaign are tracked separately.',
            'snapshotsSHA256':{**source['sha256'], 'resumedRocketEntities':digest(region),'resumedPlayer':digest(player)}}, sources, images


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('first',type=Path);p.add_argument('second',type=Path);p.add_argument('--publish', action='store_true'); args=p.parse_args()
    report, sources, images = inspect(args.first.resolve(strict=True), args.second.resolve(strict=True))
    if args.publish:
        evidence = ROOT/'docs/evidence/native-fuel/factory.zip'; evidence.parent.mkdir(exist_ok=True,parents=True)
        # Fixed timestamps make the evidence reproducible from the same save.
        with ZipFile(evidence, 'w') as archive:
            for name, contents in sorted(sources.items()):
                entry=ZipInfo(name,(2026,11,22,0,0,0));archive.writestr(entry,contents,compress_type=ZIP_DEFLATED)
        report['rawArchive']={'path':evidence.relative_to(ROOT).as_posix(),'sha256':digest(evidence)}
        image_dir=ROOT/'docs/images/fuel';image_dir.mkdir(exist_ok=True,parents=True);report['photos']={}
        for title, run, original in images:
            image=image_dir/(title+'.png');image.write_bytes((run/'LunarTester/screenshots'/(original+'.png')).read_bytes());report['photos'][image.relative_to(ROOT).as_posix()]=digest(image)
        (ROOT/'docs/native-propellant-verification.json').write_text(json.dumps(report,indent=2)+'\n','utf8')
    print('Saved original rocket',report['fuelInSavedOriginalRocket'],'mB /',report['savedRocketCapacity'],'mB. Empty buckets in the same player save:',report['savedEmptyBuckets'])


if __name__=='__main__':
    main()
