"""Run the full-pack ten-identity smoke on a GitHub-hosted Linux machine."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import time

from audit import audit_save, audit_two_hours, audit_window, require
from prepare import FORGE, MC, ROOT, hosted_only, prepare
from runtime import command

SERVER_FILES = ['linux-witness.json', 'soak-start.json', 'soak-fixture.json', 'soak-windows.jsonl',
                'soak-events.jsonl', 'soak-result.json', 'ops.json', 'ten-cohort-complete.json']
PEER_FILES = ['peer-counts.json', 'native-peer-retry.log', 'fleet-peer-error']


def write_json(path, obj):
    path.write_text(json.dumps(obj, indent=2) + '\n', 'utf8')


def copy_evidence(game, evidence, files):
    for name in files:
        source = game / name
        if source.is_file():
            shutil.copyfile(source, evidence / name)
    # Keep a bounded diagnostic tail, including early failures before Forge creates latest.log.
    for name in ['java.log', 'logs/latest.log']:
        path = game / name
        if path.is_file():
            with path.open('rb') as stream:
                stream.seek(max(0, path.stat().st_size - 128 * 1024))
                (evidence / (game.name + '-' + path.name)).write_bytes(stream.read())


def launch(args, directory):
    with (directory / 'java.log').open('xb') as out:
        return subprocess.Popen(args, cwd=directory, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT,
                                start_new_session=True, text=True, encoding='utf8')


def stop_server(server):
    if server is not None and server.poll() is None:
        try:
            print('stop', file=server.stdin, flush=True)
        except BrokenPipeError:
            pass


def stop_own_process(process):
    if process is not None and process.poll() is None:
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=10)


def verify_live(process, role):
    require(process.poll() is None, role + ' exited before the sample finished (status ' + str(process.returncode) + ')')


def wait_for(root, target, seconds, server, peers=None):
    deadline = time.monotonic() + seconds
    next_log = 0
    while time.monotonic() < deadline:
        verify_live(server, 'Dedicated server')
        if peers:
            verify_live(peers, 'Forge connection client')
        for name in ['server/soak-result.json', 'Peers/fleet-peer-error']:
            failure = root / name
            if failure.is_file():
                raise RuntimeError(f'{name}: {failure.read_text("utf8")[:1400]}')
        if target.is_file():
            return
        require(shutil.disk_usage(root).free > 1536 * 1024**2, 'Runner free space fell below 1.5 GiB')
        if time.monotonic() >= next_log:
            print(f'Waiting for {target.name}. Dedicated server PID {server.pid}.', flush=True)
            next_log = time.monotonic() + 40
        time.sleep(1)
    raise TimeoutError('Timed out waiting for ' + target.name)


def run(root, evidence, fixture, *, two_hours=False):
    hosted_only()
    require(re.fullmatch(r':\d+(\.\d+)?', os.environ.get('DISPLAY', '')), 'Expected the runner閳ユ獨 local Xvfb display')
    evidence.mkdir(parents=True, exist_ok=True)
    dedicated, hub = root / 'server', root / 'Peers'
    server = peers = None
    try:
        installation = prepare(root, fixture)
        # The short hosted run also exercises the endurance fixture's first real cohort.
        (dedicated / 'full-cohort-hours.fixture').touch()
        if two_hours:
            (dedicated / 'ten-cohort-hours.fixture').touch()
        write_json(evidence / 'installed.json', installation)
        options = ['-Dio.netty.eventLoopThreads=12', '-Dfile.encoding=UTF-8', '-XX:ActiveProcessorCount=2']
        args = f'@libraries/net/minecraftforge/forge/{MC}-{FORGE}/unix_args.txt'
        require((dedicated / args[1:]).is_file(), 'Missing installed production server argument file')
        server = launch(['java', *options, '-Djava.awt.headless=true', '-Xms384m', '-Xmx3072m',
                         '-Dretrofit.minecraftHome=' + str(dedicated / 'legacy-launcher'), args, 'nogui'], dedicated)
        wait_for(root, dedicated / 'soak-peers.txt', 450, server)
        client = command(root / 'launcher', hub)
        client = ['-Xmx3072m' if arg.startswith('-Xmx') else arg for arg in client]
        client[1:1] = ['-Dio.netty.eventLoopThreads=12', '-Dretrofit.minecraftHome=' + str(hub / 'legacy-launcher')]
        peers = launch(client, hub)
        wait_for(root, dedicated / 'linux-witness.json', 780, server, peers)
        minute = json.loads((dedicated / 'soak-windows.jsonl').read_bytes().splitlines()[0])
        expected = [f'FleetQA{i:02}' for i in range(1, 11)]
        require(minute.get('members') == minute.get('movingMembers') == expected, 'Missing full-cohort identity sample')
        result = audit_window(server.pid, json.loads((dedicated / 'soak-fixture.json').read_bytes()),
                              json.loads((dedicated / 'linux-witness.json').read_bytes()),
                              minute,
                              json.loads((hub / 'peer-counts.json').read_bytes()))
        # Preserve the live client counters; a final disconnect during shutdown is expected.
        shutil.copyfile(hub / 'peer-counts.json', evidence / 'measured-peers.json')
        write_json(evidence / 'measured-window.json', result)
        extended = None
        if two_hours:
            finish = dedicated / 'ten-cohort-complete.json'
            wait_for(root, finish, 130 * 60, server, peers)
            rows = [json.loads(line) for line in (dedicated / 'soak-windows.jsonl').read_bytes().splitlines()]
            extended = audit_two_hours(json.loads((dedicated / 'soak-fixture.json').read_bytes()), rows,
                                       json.loads(finish.read_bytes()))
            shutil.copyfile(hub / 'peer-counts.json', evidence / 'final-measured-peers.json')
        stop_server(server)
        require(server.wait(timeout=90) == 0, 'Server did not exit cleanly')
        (hub / 'linux-fleet.stop').touch()
        require(peers.wait(timeout=45) == 0, 'Client did not exit cleanly')
        require('All dimensions are saved' in (dedicated / 'logs/latest.log').read_text('utf8', errors='replace'),
                'The server did not finish writing all dimensions')
        require(not any(p.is_file() for side in (dedicated, hub) for p in (side / 'crash-reports').glob('*.txt')),
                'A runtime crash report exists')
        save = audit_save(dedicated)
        write_json(evidence / 'evidence.json', {'pass': True, 'mode': 'ten-identity two-hour diagnostic' if two_hours else 'ten-identity smoke',
                   'source': installation['source'], 'continuousTen': extended, 'measured': result, 'save': save, 'serverExit': server.returncode, 'clientExit': peers.returncode})
        print(json.dumps(result), flush=True)
    except Exception as err:
        write_json(evidence / 'failure.json', {'error': str(err)[:1800]})
        raise
    finally:
        stop_server(server)
        if hub.is_dir():
            (hub / 'linux-fleet.stop').touch(exist_ok=True)
        for process in [server, peers]:
            stop_own_process(process)
        copy_evidence(dedicated, evidence, SERVER_FILES)
        copy_evidence(hub, evidence, PEER_FILES)


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--root', required=True, type=Path)
    cli.add_argument('--evidence', required=True, type=Path)
    cli.add_argument('--fixture', required=True, type=Path)
    cli.add_argument('--two-hours', action='store_true')
    opts = cli.parse_args()
    run(opts.root.resolve(), opts.evidence.resolve(), opts.fixture.resolve(strict=True), two_hours=opts.two_hours)
