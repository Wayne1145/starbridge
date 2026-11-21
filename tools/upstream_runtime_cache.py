"""Preload Retrofit's author-specified legacy runtime in an isolated game directory.

An alternative for Java HTTP clients which do not inherit the launcher's HTTPS
proxy. It never installs files into the user's Minecraft installation.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import re
import urllib.request
import json
import os
import sys
import zipfile

MOJANG = "https://libraries.minecraft.net/"
FORGE = "https://maven.minecraftforge.net/"
CENTRAL = "https://repo.maven.apache.org/maven2/"
FORGE_VERSION = "1.7.10-10.13.4.1614-1.7.10"


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Starbridge-Original-Runtime/1"}), timeout=28) as response:
        return response.read()


def verified_file(destination, url, hashes=()):
    current = destination.read_bytes() if destination.is_file() else None
    data = current if current is not None else fetch(url)
    actual = hashlib.sha1(data).hexdigest()
    if actual not in hashes:
        signature = fetch(url + ".sha1").decode('ascii').strip().split()[0].lower()
        if not re.fullmatch(r'[0-9a-f]{40}', signature) or actual != signature:
            raise ValueError(f"bad SHA-1 {actual} for {url}")
    if current is None:
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + '.upstream-part')
        temporary.write_bytes(data)
        os.replace(temporary, destination)
    return destination


def maven_relative(group, artifact, version):
    return f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"


def library_info(entry):
    group, artifact, version = entry['name'].split(':')[:3]
    path = maven_relative(group, artifact, version)
    base = entry.get('url', '').replace('http://files.minecraftforge.net/maven/', FORGE)
    if base.startswith('http://'):
        base = 'https://' + base[7:]
    official_repositories = (FORGE, MOJANG, CENTRAL, 'https://repo1.maven.org/maven2/')
    urls = list(dict.fromkeys(url.rstrip('/') + '/' + path for url in (base, MOJANG, CENTRAL, FORGE) if url in official_repositories))
    hashes = set(entry.get('checksums', []))
    artifact_meta = entry.get('downloads', {}).get('artifact', {})
    if artifact_meta.get('sha1'):
        hashes.add(artifact_meta['sha1'])
    return path, urls, hashes


def prepare(run, client=False):
    cache = run / 'retrofit' / '.retrofit'
    if not (run / 'retrofit' / 'mods').is_dir():
        raise ValueError('target must already contain retrofit/mods')
    forge_path = maven_relative('net.minecraftforge', 'forge', FORGE_VERSION)
    forge = verified_file(cache / 'libraries' / forge_path, FORGE + forge_path[:-4] + '-universal.jar')
    manifest = json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'))
    version = next(x for x in manifest['versions'] if x['id'] == '1.7.10')
    version_file = verified_file(cache / 'versions/1.7.10.json', version['url'], {version['sha1']})
    vanilla = json.loads(version_file.read_bytes())
    side = 'client' if client else 'server'
    game = vanilla['downloads'][side]
    game_file = '1.7.10.jar' if client else 'minecraft_server.1.7.10.jar'
    verified_file(cache / 'versions' / game_file, game['url'], {game['sha1']})
    with zipfile.ZipFile(forge) as z:
        entries = json.loads(z.read('version.json'))['libraries']
    replacements = ('org.apache.logging.log4j:log4j-api:2.19.0', 'org.apache.logging.log4j:log4j-to-slf4j:2.19.0', 'org.ow2.asm:asm-all:5.2', 'org.apache.commons:commons-compress:1.21')
    specs = {name: dict(name=name) for name in replacements}
    if client:
        entries.extend(vanilla['libraries'])
    for entry in entries:
        group, artifact = entry['name'].split(':')[:2]
        key = group + ':' + artifact
        if group == 'org.apache.logging.log4j' or key == 'net.minecraftforge:forge' or entry.get('natives') or (not client and not entry.get('serverreq', True)):
            continue
        if entry.get('rules'):
            platform = 'windows' if os.name == 'nt' else 'osx' if sys.platform == 'darwin' else 'linux'
            matching = [r for r in entry['rules'] if not r.get('os') or r['os'].get('name') == platform]
            if not matching or matching[-1]['action'] != 'allow':
                continue
        # Retrofit also checks the client manifest for exact versions, including
        # commons-compress 1.8.1 alongside its own 1.21. Keep distinct files.
        specs.setdefault(entry['name'], entry)

    def download(entry):
        path, urls, hashes = library_info(entry)
        errors = []
        for url in urls:
            try:
                verified_file(cache / 'libraries' / path, url, hashes)
                return path
            except (ValueError, OSError) as error:
                errors.append(f'{url}: {error}')
        raise RuntimeError('\n'.join(errors))

    with ThreadPoolExecutor(max_workers=6) as pool:
        for path in pool.map(download, specs.values()):
            print(path, flush=True)


if __name__ == '__main__':
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('instance', type=Path)
    args.add_argument('--client', action='store_true')
    opts = args.parse_args()
    prepare(opts.instance.resolve(), opts.client)
