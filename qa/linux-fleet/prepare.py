"""Install role-specific production Forge/Retrofit runtimes and the complete pinned Starbridge pack."""
from __future__ import annotations

import hashlib
import io
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'qa/remote-ui'))
sys.path.insert(0, str(ROOT/'tools'))
from package import BUNDLE, MAIN, member
from runtime import FORGE, MC, PACK, PINS, fetch, hosted_only, require_runtime_host, sha
from upstream_runtime_cache import prepare as prepare_legacy

CF_FILES = {
    'Advanced Rocketry': (2985057, 13350781), 'LibVulpes': (2812784, 385062),
    'Galacticraft Legacy': (4501439, 12583586), 'ExtraPlanets': (3214411, 8029092),
    'GalaxySpace': (3806537, 19916959),
}
ORIGINS = {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com',
           'cdn.modrinth.com', 'mediafilez.forgecdn.net', 'maven.squiddev.cc'}


def distribution_url(row):
    name=row['name']; file=row['file']
    if name in CF_FILES:
        release,size=CF_FILES[name];return f'https://mediafilez.forgecdn.net/files/{release//1000}/{release%1000}/{file}',size
    if name=='Retrofit':return 'https://github.com/Astryxx/Retrofit/releases/download/BETA-0.6.2/'+file,1164891
    if name=='Valkyrien Skies':return 'https://cdn.modrinth.com/data/V5ujR2yw/versions/pOkdwuUh/'+file,27752937
    if name=='Kotlin for Forge':return 'https://cdn.modrinth.com/data/ordsPcFz/versions/hmeyC41q/'+file,7193768
    if name=='CC:Tweaked':return row['source'],3075392
    raise ValueError('Unknown dependency '+name)


def install_dep(row,cache):
    url,size=distribution_url(row);target=cache/row['file']
    if target.is_file() and target.stat().st_size==size and sha(target)==row['sha256']:return target
    request=Request(url,headers={'User-Agent':'Starbridge-Forge-QA/1'})
    for attempt in range(3):
        try:
            with urlopen(request,timeout=50) as response:
                final=urlsplit(response.url)
                if final.scheme!='https' or final.hostname not in ORIGINS:raise ValueError('Unexpected dependency origin')
                data=response.read(size+1)
            if len(data)!=size or hashlib.sha256(data).hexdigest()!=row['sha256']:raise ValueError('Author release does not match manifest: '+row['name'])
            target.write_bytes(data);return target
        except OSError:
            if attempt==2:raise
            time.sleep(2**attempt)


def project_files(package,cache):
    source=json.loads((package/'source.json').read_bytes());outer=package/'actions-artifact.zip'
    if 'sha256:'+sha(outer)!=source['artifactDigest']:raise ValueError('CI artifact digest mismatch')
    with ZipFile(outer) as z:bundle=member(z,BUNDLE,64*1024**2)
    if hashlib.sha256(bundle).hexdigest()!=source['packageSha256']:raise ValueError('Pack digest mismatch')
    with ZipFile(io.BytesIO(bundle)) as z:
        sums=member(z,'SHA256SUMS.txt',128*1024).decode().splitlines();installed={}
        for row in PACK['project']:
            name=row['directory']+'/'+row['file'];target=cache/row['file'];raw=member(z,name,16*1024**2)
            expected,=[line.split('  ',1)[0] for line in sums if line.endswith('  '+name)]
            if hashlib.sha256(raw).hexdigest()!=expected:raise ValueError('Published component digest mismatch')
            if row['file']==MAIN and expected!=source['mainJarSha256']:raise ValueError('Published main JAR digest mismatch')
            target.write_bytes(raw);installed[name]=target
        config=member(z,'config-examples/ExtraPlanets.cfg',2*1024**2)
        if config!=(ROOT/'pack/config/ExtraPlanets.cfg').read_bytes():raise ValueError('Expansion coexistence config differs from this revision')
    return installed,config,source


def prepare_installation(root, fixture, clients, *, dedicated_host=None):
    """Install one dedicated server and an independent full runtime for each client role."""
    require_runtime_host(dedicated_host)
    server, cache = root / 'server', root / 'fleet-cache'
    for folder in (server, *clients, cache):
        folder.mkdir(exist_ok=False)
    files,config,source=project_files(root/'package',cache)
    for dep in PACK['dependencies']:files[dep['directory']+'/'+dep['file']]=install_dep(dep,cache)
    files['mods/'+fixture.name]=fixture
    installer=root/'launcher/forge-installer.jar'
    if sha(installer)!=PINS['installer']['sha256']:raise ValueError('Unverified Forge installer')
    metadata=json.loads((root/'launcher/versions'/MC/(MC+'.json')).read_bytes());vanilla=metadata['downloads']['server']
    fetch(server/'minecraft_server.1.20.1.jar',vanilla)
    command = ['java', '-Djava.awt.headless=true', '-Xmx1536m', '-XX:ActiveProcessorCount=2',
               '-jar', str(installer), '--installServer', str(server)]
    with (server / 'forge-install.log').open('w+b') as output:
        try:
            subprocess.run(command, cwd=server, check=True, timeout=480, stdout=output, stderr=subprocess.STDOUT)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            output.seek(0, io.SEEK_END)
            output.seek(max(0, output.tell() - 8192))
            print(output.read().decode('utf8', errors='replace'), file=sys.stderr)
            raise
    with ZipFile(installer) as z:profile=json.loads(z.read('install_profile.json'))
    forge_path=server/f'libraries/net/minecraftforge/forge/{MC}-{FORGE}'
    if sha(forge_path/f'forge-{MC}-{FORGE}-server.jar','sha1')!=profile['data']['PATCHED_SHA']['server'].strip("'"):raise ValueError('Patched Forge server failed installer checksum')
    locked={name:sha(path) for name,path in files.items()}
    for role in (server, *clients):
        for name,path in files.items():
            dest=role/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(path,dest)
        (role/'config').mkdir(exist_ok=True);(role/'config/ExtraPlanets.cfg').write_bytes(config)
        (role/'retrofit/config').mkdir(exist_ok=True);(role/'retrofit/config/ExtraPlanets.cfg').write_bytes(config)
        prepare_legacy(role,client=(role != server));(role/'retrofit/retrofit.properties').write_text('download-files=never\n','utf8')
        found={f.relative_to(role).as_posix():sha(f) for d in ('mods','retrofit/mods') for f in (role/d).glob('*.jar')}
        if found!=locked:raise ValueError('Participant jars differ from the locked complete set')
    (server/'eula.txt').write_text('eula=true\n','utf8')
    return {'source':source,'installed':locked,'forgeDedicatedPatchSha1':profile['data']['PATCHED_SHA']['server'].strip("'")}


def prepare(root, fixture, *, dedicated_host=None):
    server, hub = root / 'server', root / 'Peers'
    installation = prepare_installation(root, fixture, [hub], dedicated_host=dedicated_host)
    (server/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=26750\nonline-mode=false\nenforce-secure-profile=false\nmax-players=30\nspawn-protection=0\nallow-flight=true\ngamemode=survival\ndifficulty=peaceful\nview-distance=3\nsimulation-distance=3\nlevel-seed=576618501\nenable-query=false\nenable-rcon=false\n','utf8')
    for file in ['starbridge-orbit-soak.fixture','linux-fleet.fixture']:(server/file).touch()
    (hub/'starbridge-soak-peers.fixture').touch()
    (hub/'options.txt').write_text('version:3465\nlang:en_us\nonboardAccessibility:false\nfullscreen:false\npauseOnLostFocus:false\nrenderDistance:3\nsimulationDistance:3\nmaxFps:30\nmipmapLevels:0\ntutorialStep:none\n','utf8')
    (hub/'config/forge-client.toml').write_text('[client]\nshowLoadWarnings=false\n','utf8')
    return installation
