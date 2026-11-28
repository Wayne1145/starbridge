"""Launch two private JVMs in sequence, preserving the real on-disk passage between them."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time


def wait_for_marker(process, marker, timeout):
    deadline = time.monotonic() + timeout
    while not marker.exists():
        if process.poll() is not None:
            raise RuntimeError(f"Server exited ({process.returncode}) before {marker.name}")
        if marker.with_name('flight-result.json').exists():
            raise RuntimeError(f"Scenario finished before {marker.name}")
        if time.monotonic() >= deadline:
            raise TimeoutError(f"Server did not reach {marker.name}")
        time.sleep(.1)


def before_restart(server, marker, evidence):
    from inspect_restart import hull_columns, read_compound, region_files

    mark = json.loads(marker.read_bytes())
    if mark['source'] != 'retrofit:legacy_dim_-28' or mark['destination'] != 'minecraft:overworld':
        raise ValueError('Expected a return from the original Moon to the Overworld')
    world = server / 'world'
    journal = read_compound((world / 'data/starbridge_passages.dat').read_bytes())
    if len(journal['Legs']) != 1:
        raise ValueError('Expected one pending passage before restarting the dedicated server')
    regions = sorted(region_files(hull_columns(journal['Legs'][0]['Bounds'])))
    files = [world / 'data' / name for name in ('starbridge_passages.dat', 'starbridge_navigation.dat',
             'starbridge_physical_approaches.dat', 'starbridge_orbits.dat', 'vs_ship_data.dat')]
    for directory in (world / 'region', world / 'dimensions/retrofit/legacy_dim_-28/region'):
        files.extend(directory / name for name in regions)
    files.extend(sorted((world / 'playerdata').glob('*.dat')))
    files.extend((server / 'logs/latest.log', marker, server / 'flight-live.json'))
    digests, missing = {}, []
    for original in files:
        relative = original.relative_to(server)
        if not original.is_file():
            missing.append(relative.as_posix())
            continue
        target = evidence / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(original, target)
        digests[relative.as_posix()] = hashlib.sha256(target.read_bytes()).hexdigest()
    (evidence / 'sha256.json').write_text(json.dumps(digests, indent=2) + '\n', encoding='utf-8')
    coverage = {'regionsExpectedInEachWorld': regions, 'missingFiles': missing}
    (evidence / 'capture.json').write_text(json.dumps(coverage, indent=2) + '\n', encoding='utf-8')


def run(server, java):
    server = server.resolve(strict=True)
    armed = server / 'starbridge-crash-restart.fixture'
    marker = server / 'restart-cut.json'
    if not armed.is_file() or marker.exists() or (server / 'flight-result.json').exists():
        raise ValueError('Expected an armed, unused server instance')
    evidence = server / 'restart-evidence'
    evidence.mkdir(exist_ok=False)
    args = (server / 'launch.args').read_text(encoding='utf-8')
    resume = '"-Dstarbridge.resumeQa=true"\n' + args.replace('shipyardGuiFixture=true', 'shipyardGuiFixture=false')
    (server / 'restart.args').write_text(resume, encoding='utf-8')
    start = time.monotonic()
    with (server / 'before-restart.log').open('wb') as first_log:
        initial = subprocess.Popen([str(java), '@launch.args'], cwd=server, stdin=subprocess.PIPE, stdout=first_log, stderr=subprocess.STDOUT)
        try:
            wait_for_marker(initial, marker, 900)
            mark = json.loads(marker.read_bytes())
            if mark['process'] != initial.pid or mark['committed'] or mark['delivered'] or mark['fare'] != 12000:
                raise ValueError('Server marker does not identify the expected durable passage')
            initial.kill()
            initial.wait(timeout=15)
        finally:
            if initial.poll() is None:
                initial.stdin.write(b'stop\n'); initial.stdin.flush()
                try: initial.wait(timeout=15)
                except subprocess.TimeoutExpired: initial.kill(); initial.wait(timeout=10)
            initial.stdin.close()
    before_restart(server, marker, evidence)
    with (server / 'after-restart.log').open('wb') as second_log:
        restored = subprocess.Popen([str(java), '@restart.args'], cwd=server, stdin=subprocess.PIPE, stdout=second_log, stderr=subprocess.STDOUT)
        try:
            code = restored.wait(timeout=400)
        finally:
            if restored.poll() is None:
                restored.stdin.write(b'stop\n'); restored.stdin.flush()
                try: restored.wait(timeout=15)
                except subprocess.TimeoutExpired: restored.kill(); restored.wait(timeout=10)
            restored.stdin.close()
    (evidence / 'processes.json').write_text(json.dumps({'firstPID':initial.pid, 'firstExit':initial.returncode, 'secondPID':restored.pid, 'secondExit':code, 'elapsedSeconds':round(time.monotonic()-start,1), 'forcedAt':'chunk durability acknowledged, before journal commit and crew delivery'},indent=2)+'\n',encoding='utf8')
    if code or not (server / 'recovery-result.json').is_file():
        raise RuntimeError('No completed recovery observation')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', required=True, type=Path)
    parser.add_argument('--java', required=True, type=Path)
    opts=parser.parse_args()
    run(opts.server, opts.java.resolve(strict=True))
