"""Exercise occupied and alternate home berths in a fresh, zero-client dedicated Forge server."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def copy(source, target):
    if not source.is_file():
        raise FileNotFoundError(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('label')
    parser.add_argument('runtime', type=Path, help='Existing server with a self-contained Forge launcher argument file')
    parser.add_argument('modern', type=Path)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('--java', type=Path, required=True)
    args=parser.parse_args()
    if not re.fullmatch(r'[a-z0-9-]+', args.label):
        parser.error('Use a new local trial name')
    template=args.runtime.resolve(strict=True)
    launch=(template/'launch.args').read_text('utf8')
    if '--launchTarget forgeserver' not in launch or 'forgeclient' in launch or 'nogui' not in launch:
        parser.error('Expected dedicated-server startup with nogui')
    directory=ROOT/('run-home-berth-'+args.label)
    directory.mkdir(exist_ok=False)
    (directory/'launch.args').write_text(launch.replace(str(template),str(directory)).replace(template.as_posix(),directory.as_posix()),'utf8')
    copy(template/'eula.txt', directory/'eula.txt')
    if 'eula=true' not in (directory/'eula.txt').read_text('utf8'):
        raise ValueError('Expected a previously accepted test server template')
    lock=json.loads((ROOT/'pack/original-spaceflight.lock.json').read_bytes())
    for part in lock['dependencies']:
        if part['name'] in ('Valkyrien Skies','Kotlin for Forge'):
            jar=template/'mods'/part['file']
            if hashlib.sha256(jar.read_bytes()).hexdigest()!=part['sha256']:
                raise ValueError('Physics runtime differs from the project lock')
            copy(jar,directory/'mods'/jar.name)
    for file in (args.modern,args.fixture):
        copy(file,directory/'mods'/file.name)
    props=(template/'server.properties').read_text('utf8')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
    props, count = re.subn(r'(?m)^server-port=\d+$','server-port='+str(port),props)
    if count!=1 or 'server-ip=127.0.0.1' not in props.splitlines():
        raise ValueError('Expected a loopback test server')
    (directory/'server.properties').write_text(props,'utf8')
    (directory/'starbridge-home-berth.fixture').touch()
    with (directory/'console.log').open('xb') as log:
        with subprocess.Popen([str(args.java.resolve(strict=True)),'-Djava.awt.headless=true','@launch.args'],cwd=directory,
                stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0)) as server:
            print('Dedicated server PID',server.pid,flush=True)
            try:
                server.wait(timeout=260)
            except subprocess.TimeoutExpired:
                server.stdin.write(b'stop\n');server.stdin.flush()
                try:
                    server.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    server.kill()
                raise
    result=json.loads((directory/'home-berth-result.json').read_bytes())
    print(json.dumps(result),flush=True)
    if server.returncode or result.get('pass') is not True:
        raise SystemExit('Dedicated berth check failed')


if __name__ == '__main__':
    main()
