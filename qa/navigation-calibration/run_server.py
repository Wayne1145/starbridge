"""Run the calibration fixture twice on a private, loopback-only Forge dedicated server."""
import argparse
from hashlib import sha256
import json
from pathlib import Path
import shutil
import socket
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    return sha256(path.read_bytes()).hexdigest()


def prepare(folder, template, jar, fixture):
    folder.mkdir(parents=True, exist_ok=False)
    for sub in ('mods', 'retrofit/mods', 'config', 'tmp', 'qa-home'):
        (folder/sub).mkdir(parents=True, exist_ok=True)
    lock=json.loads((ROOT/'pack/original-spaceflight.lock.json').read_bytes())
    entries=lock['dependencies']
    locked={}
    for entry in entries:
        name=entry['file']
        for sub in ('mods','retrofit/mods'):
            source=template/sub/name
            if source.is_file() and not name.startswith('starbridge-'):
                assert digest(source)==entry['sha256'],name
                shutil.copy2(source,folder/sub/name)
                locked[name]=entry['sha256']
    dock=next((template/'retrofit/mods').glob('starbridge-dock-*.jar'))
    shutil.copy2(dock,folder/'retrofit/mods'/dock.name)
    for file in (jar,fixture):shutil.copy2(file,folder/'mods'/file.name)
    shutil.copytree(template/'retrofit/.retrofit',folder/'retrofit/.retrofit')
    (folder/'retrofit/retrofit.properties').write_text('download-files=never\n')
    shutil.copy2(ROOT/'pack/config/ExtraPlanets.cfg',folder/'config/ExtraPlanets.cfg')
    assert 'eula=true' in (template/'eula.txt').read_text().lower()
    shutil.copy2(template/'eula.txt',folder/'eula.txt')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    (folder/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nspawn-protection=0\nlevel-seed=576618501\nview-distance=3\nsimulation-distance=3\ngamemode=survival\nenable-command-block=false\n')
    lines=(template/'launch.args').read_text('utf8').splitlines()
    assert any('--launchTarget forgeserver' in line for line in lines) and 'nogui' in lines
    args='\n'.join(line for line in lines if '-Dstarbridge.' not in line).replace(template.as_posix(),folder.as_posix()).replace(str(template),str(folder))+'\n'
    (folder/'launch.args').write_text(args,'utf8')
    (folder/'navigation-calibration.fixture').touch()
    (folder/'installed.json').write_text(json.dumps({'upstream':locked,'dock':digest(dock),'production':digest(jar),'fixture':digest(fixture)},indent=2),'utf8')


def run(folder,java,pass_name):
    with (folder/(pass_name+'.log')).open('wb') as log:
        child=subprocess.Popen([str(java), '@launch.args'],cwd=folder,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,
                               creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
        try:
            code=child.wait(timeout=200)
        except subprocess.TimeoutExpired:
            child.stdin.write(b'stop\n');child.stdin.flush();child.wait(timeout=45);raise
    record=json.loads((folder/(pass_name+'-result.json')).read_bytes())
    if code or not record['pass']:raise RuntimeError(record.get('error',code))
    assert 'All dimensions are saved' in (folder/(pass_name+'.log')).read_text('utf8',errors='replace')
    return record


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('directory','template','jar','fixture','java'):parser.add_argument(name,type=Path)
    args=parser.parse_args(); folder=args.directory.resolve()
    prepare(folder,args.template.resolve(strict=True),args.jar,args.fixture)
    first=run(folder,args.java,'calibration');(folder/'navigation-reopen.fixture').touch()
    second=run(folder,args.java,'reopen');assert first['core']==second['core']
    print('Passed two dedicated-server runs on', first['core'], 'with',len(first['catalogue']),'registered proofs and saved tier', second['tier'])
