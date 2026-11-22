"""Run the original AR blacklist and a 300-block shipyard locator in a new, loopback-only nogui save."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess

from run import prepare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java', 'profile', 'mod', 'dock', 'fixture', 'out'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        args.port = probe.getsockname()[1]
    game = prepare(args, 'forbidden')
    cfg = game / 'retrofit/config/advRocketry/advancedRocketry.cfg'
    text, count = re.subn(r'(?m)^(\s*S:rocketBlockBlackList <)\s*^\s*>',
                          r'\g<1>\n        minecraft:chest\n        advancedRocketry:fuelTank\n     >', cfg.read_text('utf8'))
    if count != 1:
        raise ValueError('Expected the empty original rocketBlockBlackList in this disposable profile')
    cfg.write_text(text, 'utf8')
    shutil.copy2(args.fixture, game / 'mods' / args.fixture.name)
    (game / 'starbridge-forbidden-dock.fixture').touch()
    argsfile = game / 'libraries/net/minecraftforge/forge/1.20.1-47.4.28' / ('win_args.txt' if os.name == 'nt' else 'unix_args.txt')
    if '--launchTarget forgeserver' not in ' '.join(argsfile.read_text('utf8').split()):
        raise ValueError('Expected dedicated Forge arguments')
    flags = [str(args.java.resolve(strict=True)), '-Xms384m', '-Xmx2300m', '-XX:ActiveProcessorCount=3',
             '-Djava.awt.headless=true', '@' + str(argsfile.resolve()), 'nogui']
    with (game / 'console.log').open('xb') as log:
        subprocess.run(flags, cwd=game, stdout=log, stderr=subprocess.STDOUT, timeout=280, check=True,
                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    result = json.loads((game / 'forbidden-dock-result.json').read_bytes())
    if result.get('pass') is not True or result.get('connectedPlayers') != 0:
        raise ValueError(result)
    if {r['block'] for r in result['markers']} != {'minecraft:chest', 'advancedrocketry:fueltank'}:
        raise ValueError('Original and vanilla exclusions must both produce markers')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
