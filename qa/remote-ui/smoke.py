"""Run a graphical inspection on Xvfb and collect the corresponding QA evidence."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import struct
import subprocess
import sys
import time

from package import MAIN
from runtime import command, hosted_only, prepare, sha

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'navigation-calibration'))
from hosted_originals import install as install_originals, saved_calibration

REQUIRED = {'starbridge': 'loaded', 'retrofit': 'missing', 'dock': 'waiting', 'advancedrocketry': 'waiting',
            'libvulpes': 'waiting', 'galacticraft': 'waiting', 'valkyrienskies': 'missing', 'kotlinforforge': 'missing'}
OPTIONAL = {'extraplanets': 'waiting', 'galaxyspace': 'waiting', 'computercraft': 'missing'}
PICTURES = {'main-only': ['setup-required', 'setup-original-waiting', 'setup-runtime', 'setup-file', 'setup-optional'],
            'slate-only': ['setup-slate-entry', 'setup-yard-entry', 'yard-plan-pad', 'yard-elevation-tower',
                           'yard-elevation-assembler', 'yard-advanced-rocketry-file'],
            'forbidden-dock': ['dock-forbidden-lower', 'dock-forbidden-chest', 'dock-forbidden-rotated', 'dock-forbidden-next'],
            'pad-floor': ['dock-floor-gap', 'dock-floor-rotated', 'dock-floor-repaired'],
            'calibration': ['navigation-proof-options', 'navigation-proof-learned'],
            'machine-recipes': ['machine-guidance-stock', 'machine-tracking-circuit', 'machine-precision-assembly',
                                'machine-titanium-plate', 'machine-rolling-layout',
                                'machine-titanium-ingot', 'machine-rutile-pickaxe', 'machine-arc-layout', 'machine-tower-batches', 'machine-workshop-arc-recipe', 'machine-arc-coal-power',
                                'machine-arc-brick-batches', 'machine-fire-resistance-brew', 'machine-awkward-brew',
                                'machine-strength-brew', 'machine-bottle-water', 'machine-gold-ore-harvest', 'machine-hand-press-gold-input', 'machine-hand-press-gold-output',
                                'machine-hand-press-copper-input', 'machine-hand-press-copper-output'],
            'garden': ['garden-grown', 'garden-paper', 'garden-terminal', 'garden-plan', 'garden-elevation']}
JOURNEY = {'main-only': ['Clicked fml.menu.mods', 'Clicked fml.menu.mods.config', 'Clicked gui.starbridge.install.runtime',
                         'Clicked gui.starbridge.install.back_to', 'Clicked gui.starbridge.install.source',
                         'Clicked gui.starbridge.install.back', 'Clicked gui.starbridge.install.optional', 'Clicked gui.done'],
           'slate-only': ['CraftingScreen', 'Unlocked vanilla recipe book entry: navigation_slate; selected recipe in the crafting table',
                          'Unlocked vanilla recipe book entry: ship_core; selected recipe in the crafting table',
                          'FirstFlightScreen', 'Clicked gui.starbridge.install.title', 'FlightInstallScreen', 'Clicked gui.done',
                          'FirstFlightScreen', 'Clicked slate.starbridge.guide.shipyard', 'Clicked gui.starbridge.install.title',
                          'FlightInstallScreen', 'Clicked gui.done', 'FirstFlightScreen', 'Clicked slate.starbridge.guide.elevation'],
           'calibration': ['Clicked route.starbridge.mars', 'Clicked nav.starbridge.proof.title',
                           'Clicked navigation rocket (absent)', 'Clicked navigation schematic (apply)', 'Clicked nav.starbridge.proof.back'],
           'machine-recipes': ['Clicked slate.starbridge.guide.shipyard', 'Clicked slate.starbridge.materials.title',
                               'Selected the navigation computer recipe', 'Guidance workbench showed three stocked slots of nine',
                               'Selected the tracking circuit ingredient',
                               'Saw the original precision assembler and circuit ingredients',
                               'Clicked slate.starbridge.propellant.assembly', 'Clicked slate.starbridge.machine.back_recipe',
                               'Clicked slate.starbridge.materials.back_ingredient', 'Selected the titanium plate ingredient',
                               'Saw the original rolling machine and titanium ingot',
                               'Clicked slate.starbridge.propellant.assembly', 'Clicked slate.starbridge.machine.back_recipe',
                               'Selected the titanium ingot ingredient', 'Clicked slate.starbridge.materials.machine_list',
                               'Opened the diamond pickaxe from the original rutile ore',
                               'Clicked slate.starbridge.propellant.assembly', 'Clicked slate.starbridge.machine.back_recipe',
                               'Clicked slate.starbridge.materials.back_ingredient', 'Clicked slate.starbridge.materials.back_ingredient',
                               'Clicked slate.starbridge.materials.workshop', 'Clicked slate.starbridge.propellant.assembly',
                               'Original arc furnace brick recipe showed four remaining batches',
                               'Followed the water bottle to the glass bottle recipe',
                               'Opened the fire resistance, awkward and strength brewing recipes from the blast bricks',
                               'Selected the top arc furnace power input', 'Clicked slate.starbridge.machine.back_recipe',
                               'Clicked slate.starbridge.materials.press_setup', 'Opened an enchanting table recipe from the gold ore harvest guide',
                               'Clicked slate.starbridge.materials.press_power',
                               'Clicked slate.starbridge.materials.press_previous_feed', 'Clicked slate.starbridge.materials.press_next_feed'],
           'forbidden-dock': ['Used powered original assembler with a configured chest aboard', 'Clicked shipyard.starbridge.scan',
                              'Moved one deck below the configured chest and saw Show block', 'Clicked shipyard.starbridge.locate_block',
                              'Clicked shipyard.starbridge.rotate', 'Chopped the marked chest through the survival attack action',
                              'Reopened original assembler after chopping the marked chest', 'Clicked shipyard.starbridge.scan',
                              'The next original scan offers the missing navigation part'],
           'pad-floor': ['Used original assembler beside the incomplete pad', 'Saw disabled scan on the launchpad floor',
                         'Clicked shipyard.starbridge.rotate', 'Placed the carried launchpad in the marked cell',
                         'Reopened original assembler after placing the spare pad', 'Clicked shipyard.starbridge.down',
                         'Saw the repaired floor and active scan'],
           'garden': ['Saw a naturally grown upper cane at the rooftop garden',
                      'Collected at least three cane from the two planted stems', 'ContainerScreen', 'CraftingScreen',
                      'Took three paper from the workbench output', 'Took the crafted navigation terminal from the workbench output',
                      'Opened the self-crafted terminal with Use', 'Clicked slate.starbridge.guide.shipyard',
                      'Clicked slate.starbridge.guide.elevation', 'Clicked slate.starbridge.guide.plan', 'Clicked gui.done',
                      'Stored the terminal in the camp chest']}


def validate(mode, result, actions):
    if result.get('pass') is not True:
        raise ValueError(f'{mode}: fixture returned {result}')
    if mode == 'main-only':
        expected = {'scenario': mode, 'inWorld': False, 'required': REQUIRED, 'optional': OPTIONAL,
                    'detailOpened': 'galacticraft', 'followedRuntimeAndReturned': True, 'returnedToMods': True}
    elif mode == 'machine-recipes':
        expected = {'gameType': 'survival', 'cheats': False,
                    'activeParts': ['trackingCircuit', 'titaniumPlate', 'titaniumIngot'],
                    'returnedToGuidance': True, 'layers': [3, 2, 5], 'guidanceStock': [3, 9], 'towerBatches': 4, 'workshopArcFurnace': True, 'arcPowerDiagram': True, 'arcBrickBatches': 4, 'arcBrewing': ['fire_resistance', 'awkward', 'strength'],
                    'goldDustPerOre': 2, 'brewingWaterBottle': True, 'galacticraftCopperStock': 3,
                    'copperDustPerOre': 2, 'cycledPressInputs': True, 'rutilePickaxeRecipe': True, 'goldOreSilkTouchGuide': True}
    elif mode == 'forbidden-dock':
        expected = {'mode': 'survival', 'states': ['lower', 'chest', 'rotated', 'next']}
    elif mode == 'pad-floor':
        expected = {'states': ['gap', 'rotated', 'repaired'], 'scanBefore': False, 'scanAfter': True, 'spentPad': True}
    elif mode == 'garden':
        if type(result.get('collectedCane')) is not int or not 3 <= result['collectedCane'] <= 6 or not result.get('useKey'):
            raise ValueError('The garden needs at least three harvested cane and the bound Use action')
        expected = {'views': ['plan', 'elevation', 'plan'], 'mode': 'survival'}
    elif mode == 'calibration':
        expected = {'keptSchematic': True, 'returnedToSameChart': True,
                    'clicked': ['Mars', 'calibration', 'absent rocket', 'carried tier 2 schematic', 'back']}
    else:
        expected = {'gameType': 'survival', 'commands': False, 'heldCraftedSlate': True,
                    'openedFromUnlinkedSlateAndMissingYard': True, 'returnedToYard': True}
    for key, value in expected.items():
        if type(result.get(key)) is not type(value) or result[key] != value:
            raise ValueError(f'{mode}: expected {key} = {value}, received {result.get(key)}')
    cursor = iter(actions.splitlines())
    for step in JOURNEY[mode]:
        if not any(line == step for line in cursor):
            raise ValueError(f'{mode}: action missing or out of order: {step}')

    if mode == 'slate-only':
        verify_blueprint(result.get('blueprintInteractions'), cursor)


def verify_blueprint(visits, actions):
    routes = [('plan', 'grid', 'pad'), ('elevation', 'grid', 'tower'), ('elevation', 'keyboard', 'assembler')]
    if not isinstance(visits, list) or len(visits) != len(routes):
        raise ValueError('Missing three shipyard blueprint interactions')
    for visit, (view, method, part) in zip(visits, routes):
        label = f'{view} {method} {part}'
        expected = {'view': view, 'input': 'Tab+Enter' if method == 'keyboard' else 'grid-click',
                    'part': part, 'component': 'advancedrocketry', 'directFromPart': True}
        if not isinstance(visit, dict) or any(visit.get(key) != value for key, value in expected.items()) or visit.get('returnedToSameGuide') is not True:
            raise ValueError('Wrong blueprint part, component or return view: ' + label)
        events = []
        if method == 'keyboard':
            presses = visit.get('tabPresses')
            if type(presses) is not int or not 1 <= presses <= 64:
                raise ValueError('Missing keyboard traversal of the material legend')
            events.append('Tabbed to assembler material')
        events.extend([f'Activated {label}', 'FlightInstallScreen', f'Opened component directly for {label}',
                       'Clicked gui.starbridge.install.runtime', 'Clicked gui.starbridge.install.back_to',
                       'Clicked gui.starbridge.install.back_to_yard', 'FirstFlightScreen',
                       f'Returned to {view} after {label}'])
        for event in events:
            if not any(line == event for line in actions):
                raise ValueError('Blueprint action missing or out of order: ' + event)


def png(path):
    if not path.is_file() or not 80 <= path.stat().st_size <= 5 * 1024**2:
        raise ValueError(f'No bounded screenshot for {path.name}')
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR' or b'IEND' != data[-8:-4]:
        raise ValueError('Incomplete PNG: ' + path.name)
    w, h = struct.unpack('>II', data[16:24])
    if not 640 <= w <= 1920 or not 360 <= h <= 1080:
        raise ValueError(f'Unexpected framebuffer {w} x {h}')
    return {'sha256': hashlib.sha256(data).hexdigest(), 'width': w, 'height': h}


def run_scenario(temp, evidence, fixture, mode):
    hosted_only()
    if not os.environ.get('DISPLAY', '').startswith(':'):
        raise RuntimeError('Expected the workflow Xvfb display')
    game = temp / mode
    (game / 'mods').mkdir(parents=True, exist_ok=False)
    (game / 'config').mkdir()
    (game / 'remote-ui.fixture').touch()
    (game / 'options.txt').write_text('version:3465\nlang:zh_cn\nonboardAccessibility:false\ntutorialStep:none\nfullscreen:false\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:5\nguiScale:2\nmaxFps:30\nmipmapLevels:0\n', 'utf8')
    (game / 'config/forge-client.toml').write_text('[client]\nshowLoadWarnings=false\n', 'utf8')
    originals = {}
    if mode == 'main-only':
        (game / 'starbridge-setup.fixture').write_text('main-only', 'utf8')
        filename = 'flight-setup-result.json'
    elif mode in ('pad-floor', 'forbidden-dock'):
        originals = install_originals(game, ('Retrofit', 'Advanced Rocketry', 'LibVulpes'))
        (game / 'starbridge-habitat.host').touch()
        if mode == 'pad-floor':
            (game / 'starbridge-pad-floor.fixture').touch()
            filename = 'dock-floor-client.json'
        else:
            from forbidden_dock import configure
            configure(game)
            filename = 'forbidden-dock-client.json'
    elif mode == 'machine-recipes':
        originals = install_originals(game, ('Retrofit', 'Advanced Rocketry', 'LibVulpes', 'Galacticraft Legacy'))
        (game / 'starbridge-habitat.host').touch()
        (game / 'starbridge-machine-recipes.fixture').touch()
        filename = 'machine-recipes-client.json'
    elif mode == 'garden':
        (game / 'starbridge-habitat.host').touch()
        from garden import original_inputs
        (game / 'starbridge-garden.fixture').write_text(json.dumps(original_inputs()), 'utf8')
        filename = 'garden-client.json'
    elif mode == 'calibration':
        originals = install_originals(game)
        for name in ('starbridge-habitat.host', 'starbridge-starter.fixture', 'starbridge-navigation-proof.fixture'):
            (game / name).touch()
        filename = 'navigation-proof-ui.json'
    else:
        for name in ('starbridge-setup-slate.fixture', 'starbridge-habitat.host', 'starbridge-starter.fixture'):
            (game / name).touch()
        filename = 'flight-slate-result.json'
    release = temp / 'package' / MAIN
    for jar in (release, fixture):
        shutil.copy2(jar, game / 'mods' / jar.name)
    source = json.loads((temp / 'package/source.json').read_bytes())
    expected = {'starbridge': source['mainJarSha256'], 'starbridgehabitatqa': sha(fixture), 'language': 'zh_cn'}
    out = evidence / mode
    out.mkdir(parents=True)
    log = game / 'java.log'
    with log.open('xb') as stream:
        launch = command(temp / 'launcher', game)
        if mode in ('calibration', 'pad-floor', 'forbidden-dock', 'machine-recipes'):
            launch.insert(1, '-Dretrofit.minecraftHome=' + str(game / 'legacy-launcher'))
        process = subprocess.Popen(launch, cwd=game, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + {'main-only': 210, 'garden': 22*60}.get(mode, 360)
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError(f'{mode} exited with {process.returncode} before completing')
            if shutil.disk_usage(temp).free < 1536 * 1024**2:
                raise RuntimeError('Runner free space below 1.5 GiB')
            if (game / filename).is_file():
                try:
                    result = json.loads((game / filename).read_bytes())
                except json.JSONDecodeError:
                    time.sleep(.3)
                    continue
                actions = (game / 'habitat-actions.log').read_text('utf8')
                validate(mode, result, actions)
                if all((game / 'screenshots' / (p + '.png')).is_file() for p in PICTURES[mode]):
                    break
            time.sleep(.5)
        else:
            raise TimeoutError(f'{mode} did not finish its menu and screenshot checks')
        loaded = json.loads((game / 'remote-ui-loaded.json').read_bytes())
        if loaded != expected:
            raise ValueError('Loaded Forge containers do not match the downloaded product and QA fixture')
        (game / 'remote-ui.stop').touch()
        if process.wait(timeout=65) != 0:
            raise RuntimeError('Minecraft did not shut down cleanly')
        if mode in ('slate-only', 'calibration', 'garden', 'pad-floor', 'forbidden-dock', 'machine-recipes') and (not (game / 'saves/Habitat/level.dat').is_file() or
                                    'All dimensions are saved' not in (game / 'logs/latest.log').read_text('utf8', errors='replace')):
            raise RuntimeError('Integrated survival world did not finish saving')
        if mode == 'forbidden-dock':
            from forbidden_dock import saved_cargo
            (out / 'saved.json').write_text(json.dumps(saved_cargo(game), indent=2) + '\n', 'utf8')
        if mode == 'pad-floor':
            from dock_floor import saved_floor
            (out / 'saved.json').write_text(json.dumps(saved_floor(game), indent=2) + '\n', 'utf8')
        if mode == 'garden':
            from garden import saved_garden
            (out / 'saved.json').write_text(json.dumps(saved_garden(game), indent=2) + '\n', 'utf8')
        if mode == 'calibration':
            (out / 'saved.json').write_text(json.dumps(saved_calibration(game), indent=2) + '\n', 'utf8')
        images = {}
        for name in PICTURES[mode]:
            screenshot = game / 'screenshots' / (name + '.png')
            images[name] = png(screenshot)
            shutil.copyfile(screenshot, out / screenshot.name)
        if len({p['sha256'] for p in images.values()}) < 2:
            raise ValueError('Screens all have the same framebuffer')
        if mode == 'machine-recipes':
            for metal in ('gold', 'copper'):
                if images[f'machine-hand-press-{metal}-input']['sha256'] == images[f'machine-hand-press-{metal}-output']['sha256']:
                    raise ValueError(f'The {metal} hand press input and powered output require distinct renders')
        shutil.copyfile(game / filename, out / 'result.json')
        shutil.copyfile(game / 'remote-ui-loaded.json', out / 'loaded.json')
        (out / 'actions.txt').write_text(actions, 'utf8')
        (out / 'evidence.json').write_text(json.dumps({'scenario': mode, 'screenshots': images, 'source': source, 'originals': originals, 'cleanExit': True}, indent=2) + '\n', 'utf8')
    except Exception as error:
        (out / 'failure.json').write_text(json.dumps({'scenario': mode, 'error': str(error)[:1800]}, indent=2) + '\n', 'utf8')
        if mode == 'garden':
            for name in ('garden-start', 'garden-growth', 'garden-server', 'garden-progress', 'garden-client'):
                diagnostic = game / (name + '.json')
                if diagnostic.is_file():
                    print(diagnostic.read_text('utf8')[:1500])
        for diagnostic in (log, game / 'habitat-actions.log'):
            if diagnostic.is_file():
                print('\n'.join(diagnostic.read_text('utf8', errors='replace').splitlines()[-25:]))
        raise
    finally:
        if process.poll() is None:
            (game / 'remote-ui.stop').touch()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=8)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('step', choices=('prepare', *PICTURES))
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    parser.add_argument('--fixture', type=Path)
    args = parser.parse_args()
    hosted_only()
    temp, evidence = args.root.resolve(), args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    if args.step == 'prepare':
        machine = prepare(temp / 'launcher')
        (evidence / 'runtime.json').write_text(json.dumps(machine, indent=2) + '\n', 'utf8')
    else:
        if args.fixture is None:
            parser.error('--fixture is required for a GUI scenario')
        run_scenario(temp, evidence, args.fixture.resolve(strict=True), args.step)
