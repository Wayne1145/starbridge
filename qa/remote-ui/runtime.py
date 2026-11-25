"""Install and describe the pinned production Linux client from Mojang and Forge metadata."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
import platform
import socket
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
PINS = json.loads((HERE / 'runtime.lock.json').read_bytes())
PACK = json.loads((ROOT / 'pack/original-spaceflight.lock.json').read_bytes())
MC = PACK['minecraft']
FORGE = PACK['loader'].removeprefix('Forge ')
PROFILE = f'{MC}-forge-{FORGE}'
AUTHOR_HOSTS = {'piston-meta.mojang.com', 'piston-data.mojang.com', 'resources.download.minecraft.net',
                'libraries.minecraft.net', 'maven.minecraftforge.net'}


def hosted_only():
    expected = {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'RUNNER_OS': 'Linux'}
    if sys.platform != 'linux' or any(os.environ.get(k) != v for k, v in expected.items()) or (ROOT / '.tools/no-local-game-clients').exists():
        raise RuntimeError('Run this client on the GitHub-hosted Linux runner in the Remote GUI smoke workflow.')


def sha(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def check_url(url):
    parsed = urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in AUTHOR_HOSTS or parsed.username or parsed.password:
        raise ValueError('Unknown runtime download origin: ' + str(parsed.hostname))


def fetch(path, spec):
    url, size = spec['url'], spec['size']
    alg = 'sha256' if 'sha256' in spec else 'sha1'
    expected = spec[alg]
    check_url(url)
    if not 0 < size <= 256 * 1024**2 or not re.fullmatch('[0-9a-f]{' + str(hashlib.new(alg).digest_size * 2) + '}', expected):
        raise ValueError('Invalid byte count or digest in the pinned metadata')
    if path.is_file() and path.stat().st_size == size and sha(path, alg) == expected:
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.partial')
    for attempt in range(3):
        try:
            req = Request(url, headers={'User-Agent': 'Starbridge-Forge-QA/1'})
            with urlopen(req, timeout=30) as response, tmp.open('wb') as out:
                check_url(response.url)
                while block := response.read(128 * 1024):
                    if out.tell() + len(block) > size:
                        raise ValueError('Download exceeds the advertised size: ' + path.name)
                    out.write(block)
            if tmp.stat().st_size != size or sha(tmp, alg) != expected:
                raise ValueError('Download checksum mismatch: ' + path.name)
            tmp.replace(path)
            return path
        except OSError:
            if attempt == 2:
                raise
            time.sleep(attempt + 1)
        finally:
            tmp.unlink(missing_ok=True)


def linux_rules(rules, features):
    allowed = not rules
    for rule in rules:
        system = rule.get('os', {})
        if system.get('name', 'linux') != 'linux' or system.get('arch', 'x86_64') not in ('x86_64', 'amd64'):
            continue
        if all(features.get(key, False) == value for key, value in rule.get('features', {}).items()):
            allowed = rule['action'] == 'allow'
    return allowed


def libraries(vanilla, forge):
    selected = {}
    for info in (*vanilla['libraries'], *forge['libraries']):
        if not linux_rules(info.get('rules', []), {}):
            continue
        parts = info['name'].split(':')
        selected[tuple(parts[:2] + parts[3:])] = info['downloads']['artifact']
    return list(selected.values())


def relative(path):
    p = PurePosixPath(path)
    if p.is_absolute() or '..' in p.parts or '\\' in path or ':' in path:
        raise ValueError('Invalid library path')
    return Path(*p.parts)


def require_runtime_host(dedicated_host=None):
    if dedicated_host is None:
        hosted_only()
        return
    if (sys.platform != 'linux' or not dedicated_host or socket.gethostname() != dedicated_host
            or 'microsoft' in platform.release().lower() or os.environ.get('WSL_INTEROP') or os.environ.get('WSL_DISTRO_NAME')
            or os.environ.get('DISPLAY') or os.environ.get('WAYLAND_DISPLAY')
            or os.environ.get('RUNNER_ENVIRONMENT') == 'github-hosted'):
        raise RuntimeError('Run the endurance test from a headless shell on the named, dedicated Linux host.')
    # Worktrees and nested checkouts inherit the workstation marker.
    sys.path.insert(0, str(ROOT / 'tools'))
    try:
        from local_client_policy import require_local_client
        require_local_client(ROOT)
    finally:
        sys.path.pop(0)


def run_forge_installer(installer, destination, option):
    command = ['java', '-Djava.awt.headless=true', '-Xmx1536m', '-XX:ActiveProcessorCount=2',
               '-jar', str(installer), option, str(destination)]
    with (destination / 'forge-install.log').open('w+b') as output:
        try:
            subprocess.run(command, cwd=destination, check=True, timeout=480, stdout=output, stderr=subprocess.STDOUT)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            output.seek(0, os.SEEK_END)
            output.seek(max(0, output.tell() - 8192))
            print(output.read().decode('utf8', errors='replace'), file=sys.stderr)
            raise


def prepare(home, *, dedicated_host=None):
    require_runtime_host(dedicated_host)
    home.mkdir(parents=True, exist_ok=False)
    if shutil.disk_usage(home).free < 5 * 1024**3:
        raise RuntimeError('Forge preparation needs 5 GiB of free runner storage')
    version_dir = home / 'versions' / MC
    vanilla = json.loads(fetch(version_dir / f'{MC}.json', PINS['metadata']).read_bytes())
    if vanilla['id'] != MC:
        raise ValueError('Unexpected Minecraft metadata')
    client = fetch(version_dir / f'{MC}.jar', {**vanilla['downloads']['client'], 'sha256': PINS['clientSha256']})
    installer = fetch(home / 'forge-installer.jar', PINS['installer'])
    with ZipFile(installer) as archive:
        forge = json.loads(archive.read('version.json'))
        processors = json.loads(archive.read('install_profile.json'))
    if forge['id'] != PROFILE or forge['inheritsFrom'] != MC:
        raise ValueError('Installer does not match the flight pack')
    (home / 'launcher_profiles.json').write_text('{"profiles":{}}\n', 'utf8')
    run_forge_installer(installer, home, '--installClient')
    installed = home / 'versions' / PROFILE
    if json.loads((installed / f'{PROFILE}.json').read_bytes())['id'] != PROFILE:
        raise ValueError('The Forge profile was not installed')
    patched = home / f'libraries/net/minecraftforge/forge/{MC}-{FORGE}/forge-{MC}-{FORGE}-client.jar'
    if sha(patched, 'sha1') != processors['data']['PATCHED_SHA']['client'].strip("'"):
        raise ValueError('Patched production client differs from the Forge installer manifest')
    # Match the launcher profile name expected by Forge's classpath filter.
    profile_jar = installed / f'{PROFILE}.jar'
    if profile_jar.exists():
        if sha(profile_jar) != PINS['clientSha256']:
            raise ValueError('Unexpected JAR in the Forge launcher profile')
    else:
        os.link(client, profile_jar)
    files = [(home / 'libraries' / relative(lib['path']), lib) for lib in libraries(vanilla, forge)]
    assets = home / 'assets'
    index = fetch(assets / 'indexes' / (vanilla['assetIndex']['id'] + '.json'), {**vanilla['assetIndex'], 'sha256': PINS['assetIndexSha256']})
    objects = {v['hash']: v['size'] for v in json.loads(index.read_bytes())['objects'].values()}
    if len(objects) > 5500 or sum(objects.values()) > 900 * 1024**2:
        raise ValueError('Asset index exceeds the resource limit')
    for digest, size in objects.items():
        if not re.fullmatch('[a-f0-9]{40}', digest):
            raise ValueError('Invalid asset object ID')
        key = digest[:2] + '/' + digest
        files.append((assets / 'objects' / key, {'url': 'https://resources.download.minecraft.net/' + key, 'size': size, 'sha1': digest}))
    with ThreadPoolExecutor(max_workers=8) as pool:
        for _ in pool.map(lambda work: fetch(*work), files):
            pass
    return {'minecraft': MC, 'forge': FORGE, 'installerSha256': sha(installer), 'patchedSha1': sha(patched, 'sha1'),
            'clientSha256': sha(client), 'assetIndexSha256': sha(index), 'verifiedAssetObjects': len(objects)}


def command(home, game):
    vanilla = json.loads((home / 'versions' / MC / f'{MC}.json').read_bytes())
    forge = json.loads((home / 'versions' / PROFILE / f'{PROFILE}.json').read_bytes())
    libs = home / 'libraries'
    paths = [libs / relative(item['path']) for item in libraries(vanilla, forge)]
    paths.append(home / 'versions' / PROFILE / f'{PROFILE}.jar')
    if not all(p.is_file() for p in paths):
        raise ValueError('Incomplete production client classpath')
    natives = game / 'natives'
    natives.mkdir()
    features = {'has_custom_resolution': True}
    tokens = {'classpath': os.pathsep.join(map(str, paths)), 'classpath_separator': os.pathsep, 'library_directory': str(libs),
              'natives_directory': str(natives), 'version_name': PROFILE, 'version_type': 'release', 'game_directory': str(game),
              'assets_root': str(home / 'assets'), 'assets_index_name': vanilla['assetIndex']['id'], 'resolution_width': '960',
              'resolution_height': '600', 'launcher_name': 'StarbridgeRemoteQA', 'launcher_version': '1', 'auth_player_name': 'HabitatCaptain',
              'auth_uuid': '00000000000040008000000000004356', 'auth_access_token': '0', 'clientid': '0', 'auth_xuid': '0', 'user_type': 'legacy'}
    def arguments(key):
        result = []
        for arg in vanilla['arguments'][key] + forge['arguments'][key]:
            if isinstance(arg, dict):
                if not linux_rules(arg['rules'], features):
                    continue
                arg = arg['value']
            for part in arg if isinstance(arg, list) else [arg]:
                result.append(re.sub(r'\$\{([^}]+)\}', lambda m: tokens[m[1]], part))
        return result
    return ['java', '-Xms256m', '-Xmx2304m', '-XX:ActiveProcessorCount=3', '-Dfile.encoding=UTF-8',
            *arguments('jvm'), forge['mainClass'], *arguments('game')]
