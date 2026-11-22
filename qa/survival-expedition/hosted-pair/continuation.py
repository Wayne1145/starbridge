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
            executed = [line.strip() for line in previous if line.strip() and not line.lstrip().startswith('#')]
            requested = [line.strip() for line in orders[name] if line.strip() and not line.lstrip().startswith('#')]
            cursor = name + '/expedition-cursor.txt'
            count = int(z.read(cursor).decode('ascii')) if cursor in z.namelist() else 0
            if not 0 <= count <= len(executed): raise ValueError('Saved cursor outside prior orders')
            if not previous or requested[:count] != executed[:count]:
                raise ValueError('Already executed orders must be a prefix of the new orders')
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
CAMP_RUN = '44993373934'
ARCHIVES = {BASELINE_RUN:(BASELINE,BASELINE_DIGEST), CAMP_RUN:(BASELINE.with_name('44993373934-1.zip'),
            'sha256:8e1eeba030122cbbec45d8d67564cc213b3e21c3dff2eacf80f1199f4dee40f3')}


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


def choose_yard_parent():
    """Only previously closed sessions of this PR and this camp may carry the working world forward."""
    hosted_only()
    repo = os.environ['GITHUB_REPOSITORY']
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_bytes())
    pr = event['pull_request']; branch = pr['head']['ref']; current = int(os.environ['GITHUB_RUN_ID'])
    from urllib.parse import quote
    runs = api(repo, 'actions/workflows/survival-pair.yml/runs?branch='+quote(branch,safe='')+'&event=pull_request&per_page=60')['workflow_runs']
    for run in sorted(runs,key=lambda row:row['id'],reverse=True):
        if not (int(CAMP_RUN)<run['id']<current and run['status']=='completed' and
                (run.get('head_repository') or {}).get('full_name','').lower()==repo.lower() and
                any(link['number']==pr['number'] for link in run.get('pull_requests',[]))): continue
        artifacts = api(repo, f'actions/runs/{run["id"]}/artifacts')['artifacts']
        label=f'original-yard-resume-{run["id"]}-{run["run_attempt"]}'
        receipts=[item for item in artifacts if item['name']==label]
        if not receipts: continue
        receipt,=receipts
        if receipt['expired']: raise ValueError('The most recent yard checkpoint expired; supply its archived world to continue')
        if not 0<receipt['size_in_bytes']<32*1024: raise ValueError('Invalid continuation receipt size')
        blob=subprocess.check_output(['gh','api',f'repos/{repo}/actions/artifacts/{receipt["id"]}/zip'],timeout=45)
        if 'sha256:'+hashlib.sha256(blob).hexdigest()!=receipt['digest']: raise ValueError('Continuation receipt digest differs')
        with ZipFile(io.BytesIO(blob)) as small:
            info,=small.infolist()
            if info.filename!='yard-resume.json' or not 0<info.file_size<=4096: raise ValueError('Invalid continuation receipt')
            pin=json.loads(small.read(info))
        if (pin['run']!=run['id'] or str(pin['camp'])!=CAMP_RUN or not re.fullmatch('[0-9a-f]{64}',pin['worldSha256'])
                or set(pin['cursors'])!=set(ROLES) or pin['cursors']['OrbitCaptain']<35 or pin['cursors']['OrbitEngineer']<14):
            raise ValueError('Unexpected lineage or participants in the latest continuation receipt')
        return str(run['id']),pin
    return CAMP_RUN,None


def fetch_checkpoint(run_id, root, evidence, orders):
    hosted_only()
    if not str(run_id).isascii() or not str(run_id).isdigit(): raise ValueError('Expected numeric pair run ID')
    if str(run_id) in ARCHIVES:
        path, digest = ARCHIVES[str(run_id)]
        return restore_archive(path, run_id, digest, root, orders)
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
