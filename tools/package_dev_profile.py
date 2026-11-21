"""Package the two Starbridge mods for one clean instance. The lock lists upstream downloads."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]


def package(jars: Path, destination: Path) -> None:
    lock = ROOT / 'pack/original-spaceflight.lock.json'
    manifest = json.loads(lock.read_bytes())
    files = {
        'original-spaceflight.lock.json': lock,
        'config-examples/ExtraPlanets.cfg': ROOT / 'pack/config/ExtraPlanets.cfg',
        'INSTALL.md': ROOT / 'docs/install-original.md',
        'tools/mcp_bridge.py': ROOT / 'tools/mcp_bridge.py',
        'LICENSE': ROOT / 'LICENSE',
    }
    for mod in manifest['project']:
        files[f"{mod['directory']}/{mod['file']}"] = jars / mod['file']
    modern = next(path for name, path in files.items() if name.startswith('mods/'))
    with ZipFile(modern) as jar:
        if b'f_279569_' not in jar.read('dev/starbridge/Starbridge.class'):
            raise ValueError('The modern mod must be built with Forge runtime mappings')
    legacy = next(path for name, path in files.items() if name.startswith('retrofit/mods/'))
    with ZipFile(legacy) as jar:
        if 'dev/starbridge/legacydock/DockTransformer.class' not in jar.namelist():
            raise ValueError('The legacy dock companion is missing')
    payload = {name: path.read_bytes() for name, path in files.items()}
    # Links in the bundled installation guide are usable outside a clone of this repository.
    guide = payload['INSTALL.md'].decode('utf8').replace('../pack/original-spaceflight.lock.json','original-spaceflight.lock.json').replace('../pack/config/ExtraPlanets.cfg','config-examples/ExtraPlanets.cfg')
    payload['INSTALL.md'] = guide.encode('utf8')
    for image in ('first-flight-shipyard.png', 'shipyard/02-native-scan.png'):
        payload[f'images/{image}'] = (ROOT / 'docs/images' / image).read_bytes()
    payload['SHA256SUMS.txt'] = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(payload.items())).encode('utf8')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(destination, 'w') as output:
        for name, data in sorted(payload.items()):
            entry = ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            entry.compress_type = ZIP_DEFLATED
            output.writestr(entry, data)
    print(destination)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jars', type=Path, default=ROOT / 'build/libs')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/distributions/starbridge-0.3.0-dev.zip')
    args = parser.parse_args()
    package(args.jars, args.output)
