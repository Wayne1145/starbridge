"""Replay the hosted field artifact against its recorded inputs, screenshots and saved player."""
import argparse
import json
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory
from zipfile import Path as ArchivePath, ZipFile
from run import JSON_FILES, PICTURES, check, png, saved_player, sha, verify


def review(directory, world=None):
    def read(stem):
        return json.loads((directory / (stem + '.json')).read_bytes())
    audit = read('field-audit')
    progress = read('field-progress')
    result = read('field-result')
    saved = audit['saved']
    start = read('field-start')
    loaded = read('field-loaded')
    check(audit['cleanExit'] and saved['survival'] and not saved['cheats'], 'Clean survival checkpoint')
    check(not start['cheats'] and start['ordinarySurvival'] and saved['seed'] == start['seed'], 'Same starting survival world')
    check(loaded['starbridge'] == audit['source']['mainJarSha256'], 'Published game mod loaded')
    check(audit['result'] == result, 'Saved audit agrees with recorded observations')
    if world is not None:
        receipt = audit['checkpoint']
        check(world.stat().st_size == receipt['bytes'] and sha(world) == receipt['sha256'],
              'Closed world archive matches the checkpoint digest')
        with ZipFile(world) as checkpoint:
            world_files = [item for item in checkpoint.infolist() if item.filename.startswith('world/')]
            check(len(world_files) == receipt['worldFiles'] and len({item.filename for item in world_files}) == len(world_files)
                  and sum(item.file_size for item in world_files) <= 420 * 1024**2, 'Bounded world archive with unique entries')
            actual = saved_player(directory, ArchivePath(checkpoint, 'world/'))
        check(actual == saved, 'Archived player, original world, return receipt and cargo match the report')
        saved = actual
    verify(result, progress, saved, directory)
    shots = audit['screenshots']
    check(set(shots) == set(progress['photos']), 'Every reported photo archived')
    for name, metadata in shots.items():
        check(name in PICTURES and png(directory / (name + '.png')) == metadata, 'Exact frame: ' + name)
    if 'surface-masonry' in shots:
        from PIL import Image
        signal = read('field-map-signal')
        with Image.open(directory / 'surface-masonry.png') as image:
            check(list(image.size) == signal['framebuffer'],'Same visible map frame')
            red, green, blue = image.convert('RGB').getpixel(tuple(signal['pixel']))
        check((red << 16 | green << 8 | blue) == signal['rgb'] in (0xdc99e4,0x733463), 'Violet marker at the recorded map cell')
    if result['naturalRockFace']:
        check('natural-face' in shots,'Observed natural rock in a rendered frame')
    if result['masonry']:
        check({'identified-brick','exposed-brick'} <= set(shots), 'Observed the native face in the world and the guide')
    return {'head':audit['source']['sourceCommit'], 'ticks':result['elapsedTicks'],
            'profileCount':len(progress['profiles']), 'frameCount':len(shots), 'naturalRock':result['naturalRockFace'],
            'physicalRimInspections':progress.get('closeRims'), 'furthestFromShip':progress.get('furthestFromShip'),
            'masonry':result['masonry'], 'originalInteraction':result['nativeInteraction'], 'boss':result['boss'],
            'key':result['key'], 'treasureOpened':(directory/'field-chest-open.json').is_file(),
            'originalReward':read('field-reward')['item'] if (directory/'field-reward.json').is_file() else None,
            'rewardLoaded':(directory/'field-cargo.json').is_file(), 'savedKeys':saved['originalKeyCount'],
            'returnedShip':saved.get('returnedShip'), 'worldArchiveVerified':world is not None}


def replay(archive, world=None):
    if archive.is_dir():
        return review(archive, world)
    names = {'field-audit.json', *(stem + '.json' for stem in JSON_FILES), *(stem + '.png' for stem in PICTURES)}
    with TemporaryDirectory() as folder, ZipFile(archive) as contents:
        root = Path(folder)
        for entry in contents.infolist():
            if entry.filename in names and entry.file_size <= 16 * 1024 ** 2:
                (root / PurePosixPath(entry.filename).name).write_bytes(contents.read(entry))
        return review(root, world)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--world', type=Path, help='Read actual player and field tiles from field-world.zip')
    args = parser.parse_args()
    print(json.dumps(replay(args.archive, args.world), indent=2))
