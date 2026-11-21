"""Build and exercise the matching original-Forge companion using the local Retrofit library cache."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PINNED = {
    'launchwrapper-1.12.jar':'57f402b626d16cc2705bf2a37add7adbb074f0ca3b3102fa6e23aa303dae682f',
    'forge-1.7.10-10.13.4.1614-1.7.10.jar':'00d1ca02192c7efb87da95552ebf247021f1e1d642f86d0a03076314def11529',
    'asm-all-5.2.jar':'7fbffbc1db3422e2101689fd88df8384b15817b52b9b2b267b9f6d2511dc198d',
    'minecraft_server.1.7.10.jar':'c70870f00c4024d829e154f7e5f4e885b02dd87991726a3308d81f513972f3fc'
}

# Only used by an explicit build request. Upstream libraries never go in the distribution.
LIBRARY_URLS = {
    'launchwrapper-1.12.jar': 'https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar',
    'forge-1.7.10-10.13.4.1614-1.7.10.jar': 'https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar',
    'asm-all-5.2.jar': 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar',
    'minecraft_server.1.7.10.jar': 'https://piston-data.mojang.com/v1/objects/952438ac4e01b4d115c5fc38f891710c4941df29/server.jar',
}


def fetch_compile_libraries(directory):
    directory.mkdir(parents=True, exist_ok=True)
    for name, url in LIBRARY_URLS.items():
        destination = directory / name
        if destination.exists():
            checked(destination, PINNED[name])
            continue
        with urllib.request.urlopen(url, timeout=45) as response:
            jar = response.read()
        if hashlib.sha256(jar).hexdigest() != PINNED[name]:
            raise ValueError(f'Unexpected download digest for {name}')
        with destination.open('xb') as output:
            output.write(jar)


def checked(path,expected):
    with path.open('rb') as file:
        digest = hashlib.sha256()
        for part in iter(lambda:file.read(1024*1024),b''):
            digest.update(part)
    if digest.hexdigest()!=expected:raise ValueError(f'Unexpected artifact digest: {path}')
    return path.resolve()

def build(cache,rocketry,jdk):
    deps=[]
    for name,digest in PINNED.items():
        paths=list(cache.rglob(name))
        if len(paths)!=1:raise ValueError(f'Expected one {name} in {cache}, found {len(paths)}')
        deps.append(str(checked(paths[0],digest)))
    if rocketry:
        checked(rocketry,'76195e8342b507009961e18975abd7308b89d0c8619d963f5c272818c366b3ac')
    java = str((jdk/'bin'/'java').resolve()) if jdk else shutil.which('java')
    javac = str((jdk/'bin'/'javac').resolve()) if jdk else shutil.which('javac')
    source=ROOT/'legacy-dock/src'; build_dir=ROOT/'build/legacy-dock'; classes=build_dir/'classes'; classes.mkdir(parents=True,exist_ok=True)
    cp=os.pathsep.join(deps)
    subprocess.run([javac,'--release','8','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,(source/'main/java').rglob('*.java'))],check=True)
    if rocketry:
        tests=build_dir/'test-classes';tests.mkdir(exist_ok=True)
        subprocess.run([javac,'--release','8','-cp',os.pathsep.join([str(classes),cp]),'-d',str(tests),*map(str,(source/'test/java').rglob('*.java'))],check=True)
        subprocess.run([java,'-cp',os.pathsep.join([str(tests),str(classes),cp]),'dev.starbridge.legacydock.DockCompatibilityTest',str(rocketry)],check=True)
    version=next(line.split('=',1)[1] for line in (ROOT/'gradle.properties').read_text().splitlines() if line.startswith('mod_version='))
    target=ROOT/'build/libs'/f'starbridge-dock-1.7.10-{version}.jar';target.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as out:
        for entry,file in [('META-INF/MANIFEST.MF',source/'main/resources/META-INF/MANIFEST.MF'),('META-INF/LICENSE.starbridge',ROOT/'LICENSE')]+[(p.relative_to(classes).as_posix(),p) for p in sorted(classes.rglob('*.class'))]:
            info=zipfile.ZipInfo(entry,(2020,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;out.writestr(info,file.read_bytes())
    print(target)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('cache',type=Path,help='Retrofit .retrofit directory on an installed server');parser.add_argument('--rocketry',type=Path,help='original 1.7.10-1.4.0-32 JAR for format checks');parser.add_argument('--jdk',type=Path,help='JDK 17 home');parser.add_argument('--fetch-libraries', action='store_true', help='Download four pinned compile libraries into an isolated build cache');args=parser.parse_args()
    if args.fetch_libraries:
        fetch_compile_libraries(args.cache)
    build(args.cache,args.rocketry,args.jdk)
