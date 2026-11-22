"""Compare departure capture on a private copy of the finished wide 300-block dedicated test world."""
import argparse
import hashlib
import json
import re
import shutil
import socket
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FORGE_ARGS = Path('libraries/net/minecraftforge/forge/1.20.1-47.4.28/win_args.txt')


def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(source, folder, modern, fixture, java, *, transfer=False, preload=None, approach=False, parallel=False, parallel_survey=False):
    audit = json.loads((source / 'large-flight-audit.json').read_bytes())
    if not (audit['pass'] and audit['checks']['cleanShutdown'] and audit['length'] == 300
            and audit['nativeBlockCount'] == 19522 and audit['nativeBlockEntities'] == 4530):
        raise ValueError('Expected the completed wide 300-block dedicated-server trial')
    world_files = {Path(key).relative_to('server'): value for key, value in audit['rawSha256'].items()
                   if key.startswith(('server/world/region/', 'server/world/dimensions/retrofit/legacy_dim_-28/region/',
                                      'server/world/data/vs_ship_data.dat', 'server/world/level.dat'))}
    if not world_files or not all(checksum(source / p) == value for p, value in world_files.items()):
        raise ValueError('Saved ship or world differs from the audited shutdown')
    forge = (source / FORGE_ARGS).read_text('utf8')
    if '--launchTarget forgeserver' not in forge.splitlines() or 'forgeclient' in forge:
        raise ValueError('Expected dedicated Forge server arguments')
    props = (source / 'server.properties').read_text('utf8')
    if 'eula=true' not in (source / 'eula.txt').read_text('utf8').splitlines() or 'server-ip=127.0.0.1' not in props.splitlines():
        raise ValueError('Expected an accepted EULA and a loopback-only test server')
    lock = json.loads((ROOT / 'pack/original-spaceflight.lock.json').read_bytes())
    for dependency in lock['dependencies']:
        if checksum(source / dependency['directory'] / dependency['file']) != dependency['sha256']:
            raise ValueError('Installed mod differs from the pinned file: ' + dependency['file'])
    folder.mkdir(exist_ok=False)
    for name in ('libraries', 'world', 'retrofit', 'config', 'defaultconfigs'):
        shutil.copytree(source / name, folder / name)
    (folder / 'mods').mkdir()
    for mod in lock['dependencies']:
        if mod['directory'] == 'mods':
            shutil.copy2(source / 'mods' / mod['file'], folder / 'mods' / mod['file'])
    for jar in (modern, fixture):
        shutil.copy2(jar, folder / 'mods' / jar.name)
    shutil.copy2(source / 'eula.txt', folder / 'eula.txt')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    props, count = re.subn(r'(?m)^server-port=\d+$', 'server-port=' + str(port), props)
    if count != 1:
        raise ValueError('Expected one server-port setting')
    (folder / 'server.properties').write_text(props, 'utf8')
    marker = ('starbridge-wide-approach.fixture' if approach else 'starbridge-wide-transfer.fixture' if transfer else 'starbridge-wide-capture.fixture')
    (folder / marker).write_text(str(audit['flight']['home']['shipId']), 'utf8')
    if parallel:
        (folder / 'starbridge-parallel-approach.fixture').touch()
    if parallel_survey:
        (folder / 'starbridge-parallel-survey.fixture').touch()
    if preload is not None:
        (folder / 'starbridge-wide-preload.fixture').write_text(preload, 'utf8')
    cmd = [str(java), '-Xms512m', '-Xmx3000m', '-XX:ActiveProcessorCount=3', '-Djava.awt.headless=true',
           '-Dfile.encoding=UTF-8', '@' + FORGE_ARGS.as_posix(), 'nogui']
    with (folder / 'console.log').open('xb') as log, subprocess.Popen(cmd, cwd=folder, stdin=subprocess.PIPE,
            stdout=log, stderr=subprocess.STDOUT, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0)) as server:
        print('Dedicated benchmark server PID', server.pid, flush=True)
        try:
            server.wait(timeout=360)
        except subprocess.TimeoutExpired:
            server.stdin.write(b'stop\n'); server.stdin.flush()
            try:
                server.wait(timeout=45)
            except subprocess.TimeoutExpired:
                server.kill()
            raise
    result = json.loads((folder / 'home-berth-result.json').read_bytes())
    if server.returncode or not result['pass'] or result['players'] != (2 if parallel else 1 if approach else 0):
        raise RuntimeError('Dedicated ship trial failed: ' + str(result))
    if not all(checksum(source / p) == value for p, value in world_files.items()):
        raise ValueError('Source world changed during comparison')
    print(json.dumps(result, indent=2), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('label')
    parser.add_argument('source', type=Path, help='Closed server directory containing large-flight-audit.json')
    parser.add_argument('modern', type=Path)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--transfer', action='store_true', help='Time a native passage to the original Moon on this copy')
    parser.add_argument('--preload-source', choices=('keep','edit','chunks','wait'), help='Study idle-tick preparation, a late inventory edit, chunk-only preparation or an equally aged cold server')
    parser.add_argument('--approach', action='store_true', help='Use a simulated captain and the normal confirmed landing entry')
    parser.add_argument('--parallel-approach', action='store_true', help='Confirm two identical wide hulls for different lunar berths in one server tick')
    parser.add_argument('--parallel-survey', action='store_true', help='Use both normal survey and landing controls for the two wide hulls')
    args = parser.parse_args()
    args.parallel_approach |= args.parallel_survey
    args.approach |= args.parallel_approach
    if args.approach and (args.transfer or args.preload_source):
        parser.error('Choose a normal approach or a direct portal study')
    if args.preload_source and not args.transfer:
        parser.error('--preload-source requires --transfer')
    if not re.fullmatch(r'[a-z0-9-]+', args.label):
        parser.error('Expected a new local trial name')
    run(args.source.resolve(strict=True), ROOT / ('run-home-berth-' + args.label), args.modern.resolve(strict=True),
        args.fixture.resolve(strict=True), args.java.resolve(strict=True), transfer=args.transfer, preload=args.preload_source, approach=args.approach, parallel=args.parallel_approach, parallel_survey=args.parallel_survey)
