"""Run the original 100/200/300-block pad checks in one fresh, loopback-only dedicated world."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess

from run import prepare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for field in ('java', 'profile', 'mod', 'dock', 'out'):
        parser.add_argument('--' + field, type=Path, required=True)
    args = parser.parse_args()
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        args.port = probe.getsockname()[1]
    game = prepare(args, 'floor')
    (game / 'starbridge-original.fixture').touch()
    platform = 'win' if os.name == 'nt' else 'unix'
    forge = game / f'libraries/net/minecraftforge/forge/1.20.1-47.4.28/{platform}_args.txt'
    if '--launchTarget forgeserver' not in ' '.join(forge.read_text('utf8').split()):
        raise ValueError('Expected the installed dedicated Forge server arguments')
    java = str(args.java.resolve(strict=True))
    flags = [java, '-Xms384m', '-Xmx2300m', '-XX:ActiveProcessorCount=3',
             '-Dstarbridge.largeDockFixture=true', '@' + str(forge.resolve()), 'nogui']
    with (game / 'console.log').open('xb') as log:
        subprocess.run(flags, cwd=game, stdout=log, stderr=subprocess.STDOUT, check=True,
                       timeout=230, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    result = json.loads((game / 'starbridge-dock.json').read_bytes())
    if result.get('passed') is not True:
        raise ValueError(result)
    lengths = [100, 200, 300]
    if [r['length'] for r in result['floorRepairs']] != lengths or [r['upgraded'] for r in result['rows']] != lengths:
        raise ValueError('Expected the original machine to inspect all three upgraded pad lengths')
    for row in result['floorRepairs']:
        length = row['length']
        if (row['position'] != [length // 3 + 1, 190, length + 38] or row['refusal'] != 'pad_gap'
                or row['journalCreated'] is not False or row['repairedWith'] != 'advancedrocketry:tile.dockingpad'):
            raise ValueError(row)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
