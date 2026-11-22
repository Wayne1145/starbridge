"""Fourth hosted GUI scenario: use a paired slate on the original Moon and land beside an occupied home port."""
import argparse
import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import signal
import struct
import subprocess
import sys
import time
from urllib.request import Request, urlopen
from urllib.parse import urlsplit
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'qa/remote-ui'))
sys.path.insert(0, str(ROOT / 'qa/navigation-calibration'))
from package import MAIN
from runtime import PACK, command, hosted_only, sha
from smoke import png
from replay_radar import STATES as RADAR_FRAMES, verify as verify_radar
from hosted_originals import install as install_galacticraft
from upstream_runtime_region import chunks, read_compound

PORT = [-40.875, 220.25, 59.625]
OFFSET = [63, -38]
FRAMES = {'port': (True, False), 'alternate': (False, True), 'recenter': (True, False), 'reselect': (False, True),
          'refusal': (False, False), 'retry': (False, True)}
ACTIONS = ['use bound key on paired slate', 'route.starbridge.home', 'nav.starbridge.cruise', 'orbit.starbridge.landing',
           'click alternate port on map', 'click recorded port square', 'click alternate port on map',
           'orbit.starbridge.touchdown', 'orbit.starbridge.touchdown']
PHYSICS = {'Valkyrien Skies': ('V5ujR2yw', 'pOkdwuUh', 27752937), 'Kotlin for Forge': ('ordsPcFz', 'hmeyC41q', 7193768)}


def check(condition, message):
    if not condition:
        raise ValueError(message)


def verify_clicks(client, server, actions):
    check(client.get('pass') is True and server.get('pass') is True and client.get('language') == 'zh_cn' and client.get('boundUse'), 'Completed Chinese survival flight')
    check(actions.splitlines() == ACTIONS, 'Click order from terminal to the second landing confirmation')
    check(set(client['frames']) == set(FRAMES), 'Full set of rendered map states')
    alternate = server['offset']
    check(len(alternate) == 2 and all(type(v) is int for v in alternate) and math.dist(alternate, OFFSET) <= 6, 'Alternate near requested map point')
    clicks = client['mapClicks']
    check(len(clicks) == 3 and clicks[0] == clicks[2], 'Six frames from alternate, center and the same alternate pixel')
    for i, click in enumerate(clicks):
        px, py = click['pixel']; fw, fh = click['framebuffer']; gw, gh = click['gui']; left, top, side = click['map']
        check(type(px) is int and type(py) is int and 0 <= px < fw and 0 <= py < fh and side > 6, 'Real framebuffer coordinates')
        local = [px * gw / fw - left, py * gh / fh - top]
        if i == 1:
            delta = [v - math.floor((side-1)/2+.5) for v in local]
            check(all(abs(v) <= 3 for v in delta) and any(abs(v) >= 1 for v in delta), 'Click inside the recorded square away from its exact center')
        else:
            check([math.floor(v*1024/(side-1)-512+.5) for v in local] == alternate, 'Server offset agrees with physical map pixel')
    for name, (center, clear) in FRAMES.items():
        row = client['frames'][name]
        offset = [0, 0] if center else alternate
        absolute = [math.floor(PORT[0]) + offset[0], math.floor(PORT[2]) + offset[1]]
        check(row['offset'] == offset and row['center'] == absolute, f'{name}: home-relative selection')
        check(row['clearance'] == ('CLEAR' if clear else 'OCCUPIED') and row['buttonActive'] is clear, f'{name}: live landing permission')
        check(row['symbol'] == ('open green brackets' if clear else 'orange cross') and row['label'] == ('泊位净空' if clear else '泊位被占用'), f'{name}: rendered and localized state')
    check(server['source'].startswith('retrofit:legacy_dim_') and server['destination'] == 'minecraft:overworld', 'Return from the original world')
    check(server['confirmations'] == ['OCCUPIED', 'CLEAR'], 'Both actual confirmation surveys finished in order')
    check(server['fare'] == server['beforeFE'] - server['afterFE'] == 12000 and server['diamonds'] == 7, 'Fare and cargo')
    check(server['ship'] > 0 and all(server[k] is True for k in ('aboard','obstructionIntact','sourceCleared')), 'Same ship settled with pilot and obstruction')
    check(math.dist(server['nativePosition'], [PORT[0] + alternate[0], PORT[1], PORT[2] + alternate[1]]) < .05, 'Physical ship position')


def stored(game, server):
    save = game / 'saves/HomePort'
    world = read_compound(gzip.decompress((save / 'level.dat').read_bytes()))['Data']
    player = world.get('Player')
    if player is None:
        player = read_compound(gzip.decompress((save / 'playerdata' / (server['pilot'] + '.dat')).read_bytes()))
    def identity(raw):
        return str(uuid.UUID(bytes=struct.pack('>4i', *raw)))
    check(world['allowCommands'] == 0 and player['playerGameType'] == 0 and player['Health'] > 0, 'Saved living survival player, cheats disabled')
    check(player['Dimension'] == 'minecraft:overworld' and identity(player['UUID']) == server['pilot'], 'Returned captain in player save')
    navigation = read_compound(gzip.decompress((save / 'data/starbridge_navigation.dat').read_bytes()))['data']
    entry, = [port for port in navigation['Ports'] if identity(port['Core']) == server['core']]
    check([entry[k] for k in 'XYZ'] == PORT, 'Recorded home preserved after landing nearby')
    journal = read_compound((save / 'data/starbridge_passages.dat').read_bytes())
    check(journal['Format'] == 1 and journal['Legs'] == [], 'No unsettled transit in world save')
    x,y,z = server['storageCore']
    positions = {(x,y,z), (x+1,y,z)}
    regions = {save / 'region' / f'r.{a//512}.{c//512}.mca' for a,_,c in positions}
    found = {(t['x'],t['y'],t['z']):t for reg in regions for chunk in chunks(reg) for t in chunk.get('block_entities', []) if (t['x'],t['y'],t['z']) in positions}
    helm = found[x,y,z]
    check(helm['id'] == 'starbridge:ship_core' and helm['Energy'] == server['afterFE'], 'Landed helm and energy saved')
    for n, key in [('CoreId','core'),('OriginalRocket','rocket'),('Owner','pilot'),('PaidVoyage','receipt')]:
        check(identity(helm[n]) == server[key], 'Saved ' + n)
    barrel = found[x+1,y,z]; item, = barrel['Items']
    check(barrel['id'] == 'minecraft:barrel' and item['id'] == 'minecraft:diamond' and item['Count'] == server['diamonds'], 'Cargo saved in ship storage')
    return {'pilot': server['pilot'], 'recordedHome': PORT, 'paidReceipt': server['receipt'], 'remainingFE': helm['Energy'],
            'diamonds': item['Count'], 'settled': True, 'survival': True, 'cheats': False}


def install_physics(game):
    lock = {part['name']: part for part in PACK['dependencies']}
    installed = {}
    for name, (project, version, size) in PHYSICS.items():
        part = lock[name]; file = part['file']
        req = Request(f'https://cdn.modrinth.com/data/{project}/versions/{version}/{file}', headers={'User-Agent':'Starbridge-Forge-QA/1'})
        with urlopen(req, timeout=60) as response:
            host = urlsplit(response.url)
            check(host.scheme == 'https' and host.hostname == 'cdn.modrinth.com', 'Unexpected mod distribution host')
            data = response.read(size + 1)
        check(len(data) == size and hashlib.sha256(data).hexdigest() == part['sha256'], name + ' does not match the project lock')
        (game / 'mods' / file).write_bytes(data)
        installed[file] = part['sha256']
    return installed


def run(root, evidence, fixture, *, wide=False):
    hosted_only()
    check(os.environ.get('DISPLAY','').startswith(':'), 'Expected isolated Xvfb display')
    game = root / 'home-port'
    (game / 'mods').mkdir(parents=True, exist_ok=False)
    (game / 'config').mkdir(); evidence.mkdir(parents=True, exist_ok=True)
    process = None
    log = game / 'java.log'
    try:
        (game / 'remote-home.fixture').touch()
        if wide:
            (game / 'remote-home-wide.fixture').touch()
        (game / 'options.txt').write_text('version:3465\nlang:zh_cn\nonboardAccessibility:false\nfullscreen:false\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:5\nguiScale:2\nmaxFps:30\nmipmapLevels:0\n','utf8')
        (game / 'config/forge-client.toml').write_text('[client]\nshowLoadWarnings=false\n','utf8')
        originals = install_galacticraft(game) | install_physics(game)
        for file in (root / 'package' / MAIN, fixture):
            shutil.copy2(file, game / 'mods' / file.name)
        source = json.loads((root / 'package/source.json').read_bytes())
        def locked(name):
            return next(i['sha256'] for i in PACK['dependencies'] if i['name'] == name)
        expected = {'starbridge':source['mainJarSha256'], 'starbridgeportqa':sha(fixture), 'retrofit':locked('Retrofit'), 'valkyrienskies':locked('Valkyrien Skies')}
        launch = command(root / 'launcher', game)
        heap, = [arg for arg in launch if arg.startswith('-Xmx')]
        launch[launch.index(heap)] = '-Xmx4096m'
        launch.insert(1, '-Dretrofit.minecraftHome=' + str(game / 'legacy-launcher'))
        with log.open('xb') as stream:
            process = subprocess.Popen(launch, cwd=game, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
        deadline = time.monotonic() + 600
        results = {}
        while time.monotonic() < deadline and len(results) != 2:
            check(process.poll() is None, 'Rendered client stopped before completing')
            check(shutil.disk_usage(root).free > 1536 * 1024**2, 'Low runner disk space')
            for kind in ('client','server'):
                file = game / f'home-{kind}.json'
                if kind not in results and file.exists():
                    results[kind] = json.loads(file.read_bytes())
                    check(results[kind].get('pass') is True, f'{kind}: {results[kind]}')
            if len(results) != 2:
                time.sleep(.5)
        check(len(results) == 2, 'Timed out during the home map journey')
        verify_clicks(results['client'],results['server'],(game/'home-actions.txt').read_text('utf8'))
        check(json.loads((game/'home-loaded.json').read_bytes()) == expected, 'Forge loaded the published main JAR and intended fixture and runtimes')
        (game/'remote-home.stop').touch()
        check(process.wait(timeout=75) == 0 and 'All dimensions are saved' in (game/'logs/latest.log').read_text('utf8',errors='replace'), 'Integrated server saved and shut down')
        captured = {name:png(game/'screenshots'/f'{name}.png') for name in FRAMES}
        check(captured['port']['sha256'] != captured['alternate']['sha256'] and captured['refusal']['sha256'] != captured['retry']['sha256'], 'Blocked and clear frames differ')
        radar = verify_radar(json.loads((game/'radar-client.json').read_bytes()),json.loads((game/'radar-server.json').read_bytes()),results['server'])
        radar_images = {name:png(game/'screenshots'/f'{name}.png') for name in RADAR_FRAMES}
        landing_bar = []
        if wide:
            observed = results['client']['landingBars']
            check(len(observed) == 2 and observed[0]['progress'] < observed[1]['progress'],
                  'The ship preparation gauge advanced on the rendered client')
            landing_bar = [bar | png(game / 'screenshots' / (bar['frame'] + '.png')) for bar in observed]
            check(landing_bar[0]['sha256'] != landing_bar[1]['sha256'], 'Two distinct progress frames')
        report = {'source':source,'originals':originals,'screenshots':captured,'cleanExit':True,'save':stored(game,results['server']),
                  'radar':radar,'radarScreenshots':radar_images,'landingProgress':landing_bar}
        (evidence/'evidence.json').write_text(json.dumps(report,indent=2)+'\n','utf8')
    except Exception as err:
        (evidence/'failure.json').write_text(json.dumps({'error':str(err)[:1500]},indent=2)+'\n','utf8')
        if log.exists(): print('\n'.join(log.read_text('utf8',errors='replace').splitlines()[-45:]),flush=True)
        raise
    finally:
        if process is not None and process.poll() is None:
            (game/'remote-home.stop').touch()
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGKILL); process.wait(timeout=10)
        for file in [*(game/f'home-{name}.json' for name in ('loaded','server','client')), game/'home-actions.txt', game/'landing-profile.json', game/'radar-client.json', game/'radar-server.json', *(game/'screenshots'/f'{name}.png' for name in (*FRAMES,*RADAR_FRAMES, 'landing-progress-1', 'landing-progress-2'))]:
            if file.is_file(): shutil.copyfile(file,evidence/file.name)


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--root',required=True,type=Path); cli.add_argument('--evidence',required=True,type=Path); cli.add_argument('--fixture',required=True,type=Path)
    cli.add_argument("--wide", action="store_true")
    args=cli.parse_args()
    run(args.root.resolve(),args.evidence.resolve(),args.fixture.resolve(strict=True),wide=args.wide)
