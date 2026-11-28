"""Verify the stopped Overworld-to-lunar expedition copies, their player and terminal."""
import gzip
import hashlib
import json
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from upstream_runtime_region import read_compound

MOON = 'retrofit:legacy_dim_-28'


def read(path):
    return json.loads(path.read_bytes())


def saved_player(run):
    data = read_compound(gzip.decompress((run / 'OrbitEngineer/saves/LunarTrail/level.dat').read_bytes()))['Data']
    assert data['allowCommands'] == 0
    player = data['Player']
    assert player['Dimension'] == MOON and player['playerGameType'] == 0
    chart, = (s for s in player['Inventory'] if s['id'] == 'starbridge:navigation_slate')
    cobble = sum(s['Count'] for s in player['Inventory'] if s['id'] == 'minecraft:cobblestone')
    return {'feet': player['Pos'], 'health': player['Health'], 'ground': bool(player['OnGround']), 'marks': chart['tag']['StarbridgeFieldNotes'][MOON], 'cobblestone': cobble}


def verify(dedicated, outward, returning):
    launched = read(dedicated / 'server/lunar-trail-live.json')
    assert launched['crewLanded'] and (dedicated / 'server/shipyard-gui-server.txt').read_text().startswith('PASS two real non-OP')
    assert len(launched['crewEquipment']) == 2 and all(s.get('radiationSuit') and len(s['recipes']) == 4 for s in launched['crewEquipment'])
    for run in [outward, returning]:
        install = read(run / 'launch-result.json')
        for name, key in [('starbridge-1.20.1-0.3.0-dev.jar', 'production'), ('starbridge-flight-fixture-1.0.0.jar', 'qa')]:
            assert hashlib.sha256((run / 'OrbitEngineer/mods' / name).read_bytes()).hexdigest() == install[key]
    away = read(outward / 'OrbitEngineer/lunar-trail-live.json')
    chart = read(outward / 'OrbitEngineer/lunar-walk-live.json')
    controls = read(outward / 'OrbitEngineer/walking-input-live.json')
    assert away['resumedOnExistingDeck'] and away['onlineAtResume'] == 1 and away['distance'] > 100 and chart['samples'] > 1
    assert controls['forwardKey'] and controls['forwardImpulse'] == 1 and not controls['passenger']
    win = read(returning / 'OrbitEngineer/lunar-trail-deck-return.json')
    assert read(returning / 'launch-result.json')['reason'] == 'server confirmed walked back to deck'
    assert win['fieldReturnOnly'] and win['walkedBackAboard'] and win['onlineAtResume'] == 1 and win['stableTicks'] >= 10
    assert win['core'] == away['core'] == launched['core'] and win['rocket'] == win['returnedRocket'] == away['rocket'] == launched['rocket']
    assert max(away['largestTickStep'], win['largestTickStep']) < 2
    before, after = saved_player(outward), saved_player(returning)
    assert before['marks']['SHIP'] == after['marks']['SHIP'] and 'SITE' not in before['marks'] and after['marks']['SITE'] != after['marks']['SHIP']
    log = (returning / 'OrbitEngineer/flight-input.log').read_text(encoding='utf-8')
    distances = [int(m) for m in re.findall(r'Follow selected ship marker.*horizontal distance (\d+)', log)]
    assert 'clicked slate.starbridge.field.ship' in log and distances == sorted(distances, reverse=True) and len(distances) > 1
    uses = log.count('Placed boarding step on ')
    assert uses > 0 and before['cobblestone'] - after['cobblestone'] == uses
    foot = win['footing']
    physical = [float(v.strip()) for v in foot['worldFeet'].strip('()').split(',')]
    assert after['ground'] and after['health'] == 20 and math.dist(physical, after['feet']) < .08 and abs(foot['verticalGap']) <= .075
    assert 'advancedrocketry:' in foot['support']
    return {'rocket': win['rocket'], 'core': win['core'], 'outwardTravelAtLastObservation': away['distance'], 'surfaceMapSamplesAtLastObservation': chart['samples'], 'departureFromField': before, 'arrivedOnShip': after, 'returnMapDistances': distances, 'returnDistanceTraveled': win['walkingDistance'], 'consecutiveContactTicks': win['stableTicks'], 'support': foot['support'], 'measuredVerticalGap': foot['verticalGap'], 'boardingBlocks': uses, 'passSurfaceReturn': True}


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('usage: audit_lunar_walk.py <two-client landing> <solo outward> <solo return>')
    print(json.dumps(verify(*(Path(arg) for arg in sys.argv[1:])), indent=2))
