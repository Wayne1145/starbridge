"""Replay the grid crosshair and the actual survival repair from a hosted client artifact."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image
from smoke import PICTURES, validate


def targets(image):
    if image.size != (960, 600):
        raise ValueError('Expected the hosted client framebuffer at GUI scale 2')
    w, h = 409, 208
    left, top = (480-w)//2, (300-h)//2
    pane, cell = w*49//100, 21
    ox = left + 6 + (pane - 10 - 3*cell)//2
    oz = top + 33 + (h - 82 - 3*cell)//2
    border = {0,cell-1}
    wanted = {(x,y) for y in range(cell) for x in range(cell)
              if x in border or y in border or x == cell//2 or y == cell//2}
    result = []
    for row in range(3):
        for col in range(3):
            mask = {(x,y) for y in range(cell) for x in range(cell)
                    if image.getpixel((2*(ox+col*cell+x)+1,2*(oz+row*cell+y)+1)) == (255,196,101)}
            if wanted <= mask:
                result.append([col,row])
    return result


def replay(path):
    with ZipFile(path) as archive:
        files = set(archive.namelist())
        prefix = 'forbidden-dock/' if 'forbidden-dock/evidence.json' in files else ''
        def content(name):
            return archive.read(prefix + name)
        metadata = json.loads(content('evidence.json'))
        result = json.loads(content('result.json'))
        validate('forbidden-dock', result, content('actions.txt').decode('utf8'))
        saved = json.loads(content('saved.json'))
        if saved != {'survival': True, 'cheats': False, 'cleared': [63,151,60], 'barrel': [61,149,60], 'diamonds': 7}:
            raise ValueError('Stored player mode, repaired cell or barrel inventory changed')
        observed = {}
        for name, expected in zip(PICTURES['forbidden-dock'], ([], [[2,0]], [[2,2]], []), strict=True):
            data = content(name + '.png')
            if hashlib.sha256(data).hexdigest() != metadata['screenshots'][name]['sha256']:
                raise ValueError('Screenshot hash differs: ' + name)
            with Image.open(io.BytesIO(data)) as image:
                marks = targets(image.convert('RGB'))
            if marks != expected:
                raise ValueError(f'{name}: expected {expected}, found {marks}')
            observed[name] = marks
        return {'source': metadata['source'], 'saved': saved, 'crosshairs': observed}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    print(json.dumps(replay(parser.parse_args().archive), indent=2))
