"""Check the missing-cell marker against the actual before, rotated and repaired screenshots."""
import argparse
from hashlib import sha256
import io
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image
from smoke import PICTURES, validate


def painted_tiles(image):
    # This run uses a 960×600 framebuffer and GUI scale 2. Check the 3×3 floor with one
    # pixel sample per GUI pixel, so the rotated view cannot pass with its old marker.
    if image.size != (960, 600):
        raise ValueError('This replay expects the hosted 960×600 client')
    w, h = 409, 208
    left, top = (480 - w) // 2, (300 - h) // 2
    pane, cell = w * 49 // 100, 21
    ox = left + 6 + (pane - 10 - 3 * cell) // 2
    oz = top + 33 + (h - 82 - 3 * cell) // 2
    result = []
    for row in range(3):
        for col in range(3):
            mask = {(x, y) for y in range(cell) for x in range(cell)
                    if image.getpixel((2 * (ox + col * cell + x) + 1, 2 * (oz + row * cell + y) + 1))[:3] == (255, 196, 101)}
            cross = {(x, y) for x in range(cell) for y in (x, cell - 1 - x)}
            if mask == cross:
                result.append([col, row])
    return result


def replay(archive):
    with ZipFile(archive) as z:
        result, saved, evidence, loaded = (json.loads(z.read(n + '.json')) for n in ('result', 'saved', 'evidence', 'loaded'))
        validate('pad-floor', result, z.read('actions.txt').decode('utf8'))
        if (loaded['language'] != 'zh_cn' or loaded['starbridge'] != evidence['source']['mainJarSha256']
                or not evidence['cleanExit'] or saved != {'survival': True, 'cheats': False, 'repairedCell': [63, 148, 61],
                'originalLaunchpads': 8, 'originalLandingPads': 1, 'remainingSparePads': 0}):
            raise ValueError('Unexpected loaded package or saved floor')
        observed = {}
        for name, expected in zip(PICTURES['pad-floor'], ([[2, 1]], [[1, 2]], []), strict=True):
            data = z.read(name + '.png')
            if sha256(data).hexdigest() != evidence['screenshots'][name]['sha256']:
                raise ValueError('Screenshot digest differs from hosted receipt: ' + name)
            with Image.open(io.BytesIO(data)) as bitmap:
                found = painted_tiles(bitmap.convert('RGB'))
            if found != expected:
                raise ValueError(f'{name}: expected complete crosses {expected}, saw {found}')
            observed[name] = found
        return {'source': evidence['source'], 'saved': saved, 'crossTilesColumnRow': observed,
                'client': result, 'fixtureSha256': loaded['starbridgehabitatqa']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    print(json.dumps(replay(parser.parse_args().archive), indent=2))
