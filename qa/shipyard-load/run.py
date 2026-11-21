"""Run actual AR/Starbridge loaded ship assembly and check it after restarting the server."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def copy_static(source, target):
    if Path(source).suffix == '.jar':
        try:
            os.link(source, target)
            return target
        except OSError:
            pass
    return shutil.copy2(source, target)


def prepare(args, length):
    game = args.out / str(length)
    game.mkdir(parents=True, exist_ok=False)
    shutil.copytree(args.profile / 'libraries', game / 'libraries', copy_function=copy_static)
    shutil.copytree(args.profile / 'retrofit' / '.retrofit', game / 'retrofit' / '.retrofit')
    shutil.copytree(args.profile / 'retrofit' / 'config', game / 'retrofit' / 'config')
    shutil.copytree(args.profile / 'config', game / 'config')
    (game / 'retrofit' / 'mods').mkdir()
    (game / 'mods').mkdir()
    for pattern in ['AdvancedRocketry-1.7.10-1.4.0-32.jar', 'LibVulpes-1.7.10-0.2.8-37-universal.jar']:
        shutil.copy2(args.profile / 'retrofit' / 'mods' / pattern, game / 'retrofit' / 'mods')
    shutil.copy2(args.dock, game / 'retrofit' / 'mods')
    shutil.copy2(args.profile / 'mods' / 'retrofit-forge-0.6.2-beta.jar', game / 'mods')
    shutil.copy2(args.mod, game / 'mods')
    shutil.copy2(args.profile / 'eula.txt', game)
    cfg = game / 'retrofit/config/advRocketry/advancedRocketry.cfg'
    text, replacements = re.subn(r'(?m)^(\s*D:buildSpeedMultiplier=).*$', r'\g<1>0.01', cfg.read_text(encoding='utf-8'))
    if replacements != 1:
        raise ValueError(f'Missing native build speed setting in {cfg}')
    cfg.write_text(text, encoding='utf-8')
    (game / 'starbridge-disposable-shipyard.fixture').touch()
    (game / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nlevel-name=loaded-{length}\nlevel-seed=576618501\nmax-players=2\nspawn-protection=0\nview-distance=3\nsimulation-distance=3\nallow-flight=true\ngamemode=survival\n', encoding='utf-8')
    return game


def run(args, game, length, restart=False):
    name = 'restart' if restart else 'result'
    platform = 'win' if os.name == 'nt' else 'unix'
    forge_args = next((game / 'libraries/net/minecraftforge/forge').glob(f'1.20.1-47.4.28/{platform}_args.txt'))
    command = [str(args.java), '-Xms384m', '-Xmx2800m', '-XX:ActiveProcessorCount=4', f'-Dstarbridge.loadedDockLength={length}']
    if restart:
        command.append('-Dstarbridge.loadedDockCheckSave=true')
    command.extend(['@' + str(forge_args.resolve()), 'nogui'])
    with (game / f'{name}-console.log').open('wb') as log:
        completed = subprocess.run(command, cwd=game, stdout=log, stderr=subprocess.STDOUT, timeout=360, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    raw = game / f'loaded-dock-{name}.json'
    if (game / 'logs/latest.log').is_file():
        shutil.copy2(game / 'logs/latest.log', game / f'{name}-latest.log')
    if completed.returncode or not raw.is_file():
        raise RuntimeError(f'{game.name} {name}: see {game / (name + "-console.log")}')
    observed = json.loads(raw.read_text(encoding='utf-8'))
    print(f'{length} {name} {observed}', flush=True)
    if not observed['passed']:
        raise RuntimeError(str(raw))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ['profile', 'java', 'mod', 'dock', 'out']:
        parser.add_argument('--' + option, type=Path, required=True)
    parser.add_argument('--port', type=int, default=25704)
    parser.add_argument('--length', nargs='+', type=int, choices=(100, 200, 300), default=[100, 200, 300])
    parser.add_argument('--skip-restart', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('Invalid server port')
    # A concurrent Gradle build may replace build/libs between test branches. Test one immutable pair.
    snapshots = args.out / 'artifacts'
    snapshots.mkdir(parents=True, exist_ok=False)
    for name in ('mod', 'dock'):
        original = getattr(args, name).resolve()
        target = snapshots / original.name
        shutil.copy2(original, target)
        with zipfile.ZipFile(target) as archive:
            if name == 'mod' and b'CREATIVE_MODE_TAB' in archive.read('dev/starbridge/Starbridge.class'):
                raise ValueError('Expected a reobfuscated production mod. Wait for Gradle reobfJar to finish.')
        setattr(args, name, target)
    for length in args.length:
        instance = prepare(args, length)
        run(args, instance, length)
        if not args.skip_restart:
            run(args, instance, length, restart=True)
