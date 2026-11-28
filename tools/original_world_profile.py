"""Verify or install author-provided original planetary JARs in a Forge 1.20.1 instance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile

LOCK = Path(__file__).resolve().parents[1] / 'docs/original-world-profile.json'
SETTINGS = {
    'mod compatibility support': ('Enable New Galaxy Space Compatibility (v1.2.3 and above)', 'true'),
    'space stations': ('Mars SpaceStation', 'false'),
}


def digest(path):
    with path.open('rb') as data:
        checksum = hashlib.sha256()
        for block in iter(lambda: data.read(1024 * 1024), b''):
            checksum.update(block)
        return checksum.hexdigest()


def coexistence(current):
    """Change only the two named booleans in their original Forge categories."""
    newline = '\r\n' if '\r\n' in current else '\n'
    lines = current.splitlines(keepends=True)
    for section, (setting, expected) in SETTINGS.items():
        header = re.compile(r'^\s*"' + re.escape(section) + r'"\s*\{\s*$')
        starts = [index for index, text in enumerate(lines) if header.match(text)]
        if len(starts) > 1:
            raise ValueError(f'Ambiguous ExtraPlanets category {section!r}')
        if not starts:
            if lines and not lines[-1].endswith(('\n', '\r')):
                lines[-1] += newline
            lines.extend([f'"{section}" {{{newline}', f'    B:"{setting}"={expected}{newline}', f'}}{newline}'])
            continue
        begin = starts[0] + 1
        end = next((idx for idx in range(begin, len(lines)) if lines[idx].strip() == '}'), None)
        if end is None:
            raise ValueError(f'Unclosed ExtraPlanets category {section!r}')
        key = re.compile(r'^(\s*B:"' + re.escape(setting) + r'"\s*=\s*)(\S+?)(\s*)$')
        matches = [(idx, key.match(lines[idx])) for idx in range(begin, end) if key.match(lines[idx])]
        if len(matches) > 1:
            raise ValueError(f'Duplicate ExtraPlanets setting {setting!r}')
        if matches:
            index, match = matches[0]
            if match.group(2).lower() not in ('true', 'false'):
                raise ValueError(f'Invalid ExtraPlanets boolean {setting!r}')
            lines[index] = match.group(1) + expected + match.group(3)
        else:
            lines.insert(end, f'    B:"{setting}"={expected}{newline}')
    return ''.join(lines)


def atomic_write(destination, content=None, source=None):
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.starbridge-', delete=False) as temp:
        path = Path(temp.name)
        try:
            if source is None:
                temp.write(content)
            else:
                with source.open('rb') as data:
                    shutil.copyfileobj(data, temp)
        except BaseException:
            temp.close()
            path.unlink(missing_ok=True)
            raise
    try:
        os.replace(path, destination)
    finally:
        path.unlink(missing_ok=True)


def configure(instance, sources, install):
    subset = json.loads(LOCK.read_bytes())
    manifest = json.loads((LOCK.parent / subset['lock']).read_bytes())
    names = set(subset['members'])
    rows = [row for row in manifest['dependencies'] if row['name'] in names]
    if len(rows) != len(names):
        raise ValueError('Incomplete original-world dependency selection in the pack lock')
    source_dirs = [] if sources is None else [sources, sources / 'mods', sources / 'retrofit/mods']
    targets = [instance / 'mods', instance / 'retrofit/mods']
    expected = {row['sha256']: instance / row['directory'] / row['file'] for row in rows}
    for directory in targets:
        for jar in directory.glob('*.jar'):
            correct = expected.get(digest(jar))
            if correct and jar != correct:
                raise ValueError(f'{jar} already contains an original profile JAR. Expected {correct}; check for duplicates.')
    additions = []
    for row in rows:
        destination = expected[row['sha256']]
        if destination.exists():
            if digest(destination) != row['sha256']:
                raise ValueError(f'Existing {destination} differs from the locked release. Get the specified file from {row["source"]}')
            continue
        if not install:
            raise ValueError(f'Missing {destination}; author release: {row["source"]}')
        candidate = next((directory / row['file'] for directory in source_dirs if (directory / row['file']).is_file()), None)
        if not candidate or digest(candidate) != row['sha256']:
            raise ValueError(f'Provide the verified {row["file"]} from {row["source"]}')
        additions.append((candidate, destination))
    path = instance / 'config/ExtraPlanets.cfg'
    original = path.read_bytes() if path.exists() else b''
    bom = b'\xef\xbb\xbf' if original.startswith(b'\xef\xbb\xbf') else b''
    text = original[len(bom):].decode('utf8')
    revised = bom + coexistence(text).encode('utf8')
    if revised != original and not install:
        raise ValueError(f'{path} needs the two documented GalaxySpace coexistence settings.')
    for source, destination in additions:
        atomic_write(destination, source=source)
    if revised != original:
        atomic_write(path, content=revised)
    print(f'Verified {len(expected)} original runtime JARs and ExtraPlanets coexistence settings in {instance}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', type=Path, required=True, help='Explicit Forge game or server directory')
    parser.add_argument('--sources', type=Path, help='Directory of locally obtained author releases')
    parser.add_argument('--install', action='store_true', help='Install missing verified JARs and the two coexistence settings')
    args = parser.parse_args()
    try:
        configure(args.instance.resolve(), args.sources.resolve() if args.sources else None, args.install)
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        raise SystemExit(1)
