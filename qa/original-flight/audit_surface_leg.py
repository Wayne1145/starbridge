"""Audit one saved lunar walking leg from player, client and integrated-server evidence."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
from zipfile import ZIP_DEFLATED, ZipFile

from audit_lunar_walk import MOON, read_compound, saved_player

ROLE = 'OrbitEngineer'


def read(path):
    return json.loads(path.read_bytes())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(run):
    # The saved player's personal inventory is sufficient. No chunk files are used in this audit.
    data = read_compound(gzip.decompress((run / ROLE / 'saves/LunarTrail/level.dat').read_bytes()))['Data']
    player = data['Player']
    return player['UUID'], player['foodLevel'], {s['Slot']: s for s in player['Inventory']}


def audit(before, run):
    dst = run / ROLE
    launch = read(run / 'launch-result.json')
    assert launch['mode'] == 'search' and launch['source'] == before.name and launch['normalExit']
    assert launch['sourceLevelSha256'] == digest(before / ROLE / 'saves/LunarTrail/level.dat')
    assert 'All dimensions are saved' in (dst / 'logs/latest.log').read_text('utf8', errors='replace')
    assert all(launch[field] == digest(dst / 'mods' / name) for field, name in (
        ('production', 'starbridge-1.20.1-0.3.0-dev.jar'), ('qa', 'starbridge-flight-fixture-1.0.0.jar')))
    assert not (dst / 'lunar-trail-result.json').exists() or read(dst / 'lunar-trail-result.json').get('pass')
    start, saved = saved_player(before), saved_player(run)
    earlier = read(before / ROLE / 'lunar-trail-live.json')
    live = read(dst / 'lunar-trail-live.json')
    chart = read(dst / 'lunar-walk-live.json')
    oldid, food0, inventory0 = inventory(before)
    newid, food1, inventory1 = inventory(run)
    assert oldid == newid and start['marks']['SHIP'] == saved['marks']['SHIP']
    assert inventory0[0]['id'] == inventory1[0]['id'] == 'starbridge:navigation_slate'
    assert inventory0[0]['tag']['StarbridgeSurveyId'] == inventory1[0]['tag']['StarbridgeSurveyId']
    assert live['continuedFromSurfaceSurvey'] and live['onlineAtResume'] == 1
    assert live['rocket'] == earlier['rocket'] and live['core'] == earlier['core']
    assert not live['preparedSurfaceRations']['issued'] and live['preparedSurfaceRations']['quantity'] == inventory0.get(2, {}).get('Count', 0)
    assert inventory1.get(2, {}).get('Count', 0) <= inventory0.get(2, {}).get('Count', 0)
    assert 0 < live['largestTickStep'] < 10 and live['distance'] > 0 and saved['health'] > 0
    inputs = (dst / 'flight-input.log').read_text('utf8'); zoom = read(dst / 'field-zoom-check.json')
    assert 'slate.starbridge.field.zoom_out' in inputs and 'slate.starbridge.field.zoom_in' in inputs
    assert 256 in zoom['spans'] and 32 in zoom['spans'] and chart['samples'] > 0
    sight = dst / 'observed-entrance.json'; entrance = read(sight) if sight.exists() else None
    if entrance:
        assert entrance['capturedFacingTheMasonry'] and entrance['identifiedInFieldGuide']
        assert 0 < entrance['sightDistance'] <= 64 and 'meta=14' in entrance['state']
        assert all((dst / f'screenshots/{n}.png').is_file() for n in ('field-visible-dungeon-brick', 'field-compared-dungeon-brick'))
    selected = re.findall(r'Survey contour suggests an open depression at (BlockPos\{[^}]+\})', inputs)
    rejected = [line for line in inputs.splitlines() if 'without seeing original masonry' in line or 'Could not reach the candidate safely' in line]
    files = ['launch-result.json', 'prepared.json', f'{ROLE}/flight-input.log', f'{ROLE}/lunar-trail-live.json',
             f'{ROLE}/lunar-walk-live.json', f'{ROLE}/field-zoom-check.json', f'{ROLE}/lunar-search-checkpoint.json']
    photo_names = {'field-ship-position', 'field-one-block-scale', 'field-moon-crater-guide'}
    photo_names.update(p.stem for p in (dst / 'screenshots').glob('field-depression-*.png'))
    cave = run / 'visible-cave-detour.json'
    detour = read(cave) if cave.is_file() else None
    if detour:
        files.append(cave.name)
        for name in detour['playerScreens']:
            assert re.fullmatch(r'field-eye-\d+-\d+', name)
            files.append(f'{ROLE}/{name}.json')
            photo_names.add(name)
    if entrance:
        photo_names.update(('field-visible-dungeon-brick', 'field-compared-dungeon-brick'))
    files.extend(f'{ROLE}/screenshots/{name}.png' for name in sorted(photo_names))
    if entrance:
        files.append(f'{ROLE}/observed-entrance.json')
    panorama = run / 'player-rim-photos.json'
    if panorama.is_file():
        files.append(panorama.name)
        for rim in read(panorama):
            for face in rim['firstVisibleFaces']:
                name = face['record']
                if (dst / name).is_file():
                    files.append(f'{ROLE}/{name}')
    return {'source': before.name, 'run': run.name, 'version': '0.3.0-dev', 'minecraft': '1.20.1', 'forge': '47.4.28',
            'client': 'One scripted Chinese survival client on an integrated server; no cheats',
            'world': MOON, 'originalShip': live['rocket'], 'savedSourceFeet': start['feet'], 'savedFeet': saved['feet'],
            'savedShipMark': saved['marks']['SHIP'], 'foodStartEnd': [food0, food1],
            'rationsStartEnd': [inventory0.get(2, {}).get('Count', 0), inventory1.get(2, {}).get('Count', 0)],
            'healthStartEnd': [start['health'], saved['health']], 'surveySamplesThisLeg': chart['samples'],
            'candidateCellsFromVisibleMapThisLeg': selected, 'dryOrInaccessibleRimsThisLeg': rejected,
            'visuallyApproachedCave': detour['observedRockFact'] if detour else None,
            'walkedIncludingDetours': live['distance'], 'horizontalNetMovement': math.dist(start['feet'][::2], saved['feet'][::2]),
            'largestObservedTickMovement': live['largestTickStep'], 'entranceWitness': entrance, 'previousSupplyAndShipWerePrepared': True,
            'reasonForSave': launch['reason'], 'jarHashes': {k: launch[k] for k in ('production', 'qa')},
            'levelDatSha256': digest(dst / 'saves/LunarTrail/level.dat'), 'logSha256': digest(dst / 'logs/latest.log'),
            'evidence': {p: digest(run / p) for p in files}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('previous', type=Path)
    parser.add_argument('run', type=Path)
    parser.add_argument('--archive', type=Path)
    opts = parser.parse_args()
    result = audit(opts.previous, opts.run)
    text = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if opts.archive:
        with ZipFile(opts.archive, 'x', compression=ZIP_DEFLATED) as archive:
            archive.writestr('audit.json', text)
            for path in result['evidence']:
                archive.write(opts.run / path, path)
    print(text)
