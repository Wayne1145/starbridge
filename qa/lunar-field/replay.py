"""Replay the hosted field artifact against its recorded inputs, screenshots and saved player."""
import argparse
import json
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory
from zipfile import ZipFile
from run import JSON_FILES, PICTURES, check, png, verify


def review(directory):
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
            'key':result['key'], 'savedKeys':saved['originalKeyCount'], 'returnedShip':saved.get('returnedShip')}


def replay(archive):
    if archive.is_dir():
        return review(archive)
    names = {'field-audit.json', *(stem + '.json' for stem in JSON_FILES), *(stem + '.png' for stem in PICTURES)}
    with TemporaryDirectory() as folder, ZipFile(archive) as contents:
        root = Path(folder)
        for entry in contents.infolist():
            if entry.filename in names and entry.file_size <= 16 * 1024 ** 2:
                (root / PurePosixPath(entry.filename).name).write_bytes(contents.read(entry))
        return review(root)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    print(json.dumps(replay(parser.parse_args().archive), indent=2))
