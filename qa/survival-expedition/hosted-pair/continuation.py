"""Restore only the generated world and order progress from this repository's own pair workflow."""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
from zipfile import ZipFile

from inspect_checkpoint import ROLES
from package import api
from runtime import hosted_only, sha


def check_member(entry, allowed_roles):
    name = entry.filename
    path = PurePosixPath(name)
    if ('\\' in name or ':' in name or path.is_absolute() or '..' in path.parts or path.as_posix() != name
            or not path.parts or stat.S_ISLNK(entry.external_attr >> 16) or entry.is_dir()):
        raise ValueError('Invalid generated checkpoint member')
    if path.parts[0] == 'world' and len(path.parts) >= 2:
        return 'server/' + name
    if len(path.parts) == 2 and path.parts[0] in allowed_roles and path.name in ('expedition-orders.txt', 'expedition-cursor.txt'):
        return name
    raise ValueError('Checkpoint contains a file outside its generated world and player orders')


def restore_world(raw, root, expected, orders):
    if hashlib.sha256(raw).hexdigest() != expected['sha256'] or len(raw) != expected['bytes']:
        raise ValueError('Closed-world checksum or size mismatch')
    with ZipFile(io.BytesIO(raw)) as z:
        entries = z.infolist()
        targets = [check_member(e, ROLES) for e in entries]
        if len(set(targets)) != len(entries) or sum(e.file_size for e in entries) > 2 * 1024**3:
            raise ValueError('Invalid size or duplicate checkpoint paths')
        if not {'server/world/level.dat', *('server/world/playerdata/' + uid + '.dat' for uid in ROLES.values())} <= set(targets):
            raise ValueError('Both saved characters and the world are required for continuation')
        for name in ROLES:
            previous = z.read(name + '/expedition-orders.txt').decode('utf8').splitlines()
            if not previous or orders[name][:len(previous)] != previous:
                raise ValueError('The executed order list must be a prefix of the new orders')
            cursor = name + '/expedition-cursor.txt'
            if cursor in z.namelist():
                n = int(z.read(cursor).decode('ascii'))
                count = sum(bool(line.strip()) and not line.lstrip().startswith('#') for line in previous)
                if not 0 <= n <= count: raise ValueError('Saved cursor outside prior orders')
        if any((root / target).exists() for target in targets):
            raise ValueError('Continuation destination already contains save data')
        for entry, relative in zip(entries, targets):
            dest = root / relative
            dest.parent.mkdir(parents=True, exist_ok=True)
            with z.open(entry) as src, dest.open('xb') as out:
                shutil.copyfileobj(src, out)


BASELINE_RUN = '44992119186'
BASELINE = Path(__file__).resolve().parents[3] / 'docs/evidence/original-survival-pair/44992119186-1.zip'
BASELINE_DIGEST = 'sha256:ed1202fb2735e997ba45a7c4945f085900ed61bd9191af383b75ae15f29b1205'


def restore_archive(archive, run_id, digest, root, orders):
    if 'sha256:' + sha(archive) != digest:
        raise ValueError('Pair archive differs from its GitHub digest')
    with ZipFile(archive) as z:
        source = json.loads(z.read('pair-result.json'))
        for name in ROLES:
            first = source['firstJoin'][name]
            if first['name'] != name or first['items'] or first['creative'] or first['op'] or first['allowCommands']:
                raise ValueError('Previous world lacks a recorded empty survival start for ' + name)
        receipt = source['checkpoint']
        if receipt['archive'] != 'cloud-world.zip' or not 0 < receipt['bytes'] <= 512 * 1024**2:
            raise ValueError('Unexpected previous world member')
        matching = [e for e in z.infolist() if e.filename == receipt['archive']]
        if len(matching) != 1 or matching[0].file_size != receipt['bytes']: raise ValueError('Invalid world member size')
        restore_world(z.read(matching[0]),root,receipt,orders)
    return {'run': int(run_id), 'artifactDigest': digest, 'worldSha256': receipt['sha256'], 'firstJoin': source['firstJoin']}


def fetch_checkpoint(run_id, root, evidence, orders):
    hosted_only()
    if not str(run_id).isascii() or not str(run_id).isdigit(): raise ValueError('Expected numeric pair run ID')
    if str(run_id) == BASELINE_RUN:
        return restore_archive(BASELINE, run_id, BASELINE_DIGEST, root, orders)
    repo = os.environ['GITHUB_REPOSITORY']
    run = api(repo, 'actions/runs/' + str(run_id))
    if (run['path'] != '.github/workflows/survival-pair.yml' or run['status'] != 'completed'
            or run['head_repository']['full_name'].lower() != repo.lower()):
        raise ValueError('Expected a completed original-survival-pair run from this repository')
    name = f'original-survival-pair-{run_id}-{run["run_attempt"]}'
    artifact, = [a for a in api(repo, f'actions/runs/{run_id}/artifacts')['artifacts'] if a['name'] == name and not a['expired']]
    if not 0 < artifact['size_in_bytes'] <= 512 * 1024**2 or not re.fullmatch('sha256:[a-f0-9]{64}', artifact.get('digest','')):
        raise ValueError('Invalid hosted artifact metadata')
    archive = root / 'previous-pair.zip'
    with archive.open('xb') as out:
        subprocess.run(['gh','api',f'repos/{repo}/actions/artifacts/{artifact["id"]}/zip'],stdout=out,check=True,timeout=180)
    if archive.stat().st_size != artifact['size_in_bytes']:
        raise ValueError('Pair archive differs from its GitHub digest')
    return restore_archive(archive, run_id, artifact['digest'], root, orders)
