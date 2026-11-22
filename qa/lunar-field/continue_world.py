"""Resume this repository's own saved lunar expedition on a fresh hosted client."""
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'remote-ui'))
from package import api
from runtime import hosted_only, sha

WORLD_SUFFIXES = {'.dat', '.dat_old', '.mca', '.mcc', '.json', '.toml'}
EVENTS = {'field-start', 'field-vessel', 'field-identified-face', 'field-native-interaction', 'field-boss', 'field-key', 'field-chest-open', 'field-reward', 'field-cargo', 'field-boarded'}


def checked(condition, message):
    if not condition:
        raise ValueError(message)


def fetch_archive(repository, metadata, destination, limit):
    checked(0 < metadata['size_in_bytes'] <= limit and re.fullmatch('sha256:[a-f0-9]{64}', metadata.get('digest', '')),
            'Expected a bounded artifact with GitHub digest')
    with destination.open('xb') as stream:
        subprocess.run(['gh', 'api', f"repos/{repository}/actions/artifacts/{metadata['id']}/zip"], stdout=stream, check=True, timeout=180)
    checked(destination.stat().st_size == metadata['size_in_bytes'] and 'sha256:' + sha(destination) == metadata['digest'],
            'Downloaded checkpoint disagrees with GitHub')


def restore(world_zip, observation_zip, game, run_id):
    with ZipFile(observation_zip) as observations:
        raw_audit = observations.read('field-audit.json')
        audit = json.loads(raw_audit)
        progress = observations.read('field-progress.json')
        result = observations.read('field-result.json')
        checked(audit['cleanExit'] and audit['saved']['survival'] and not audit['saved']['cheats'] and not audit['saved'].get('returnedShip'),
                'Prior world must be a cleanly saved, unfinished lunar survival leg')
        receipt = audit['checkpoint']
        checked(world_zip.stat().st_size == receipt['bytes'] and sha(world_zip) == receipt['sha256'], 'World matches prior offline audit')
        images = {}
        for name, facts in audit['screenshots'].items():
            checked(re.fullmatch(r'[a-z-]{1,50}', name) and 1 <= facts['width'] <= 4096 and 1 <= facts['height'] <= 4096, 'Screen record')
            data = observations.read(name + '.png')
            checked(len(data) <= 16 * 1024**2 and hashlib.sha256(data).hexdigest() == facts['sha256'], 'Previous rendered frame')
            images[name] = data
        old_signal = observations.read('field-map-signal.json') if 'field-map-signal.json' in observations.namelist() else None
    with ZipFile(world_zip) as blocks:
        entries = blocks.infolist()
        checked(len(entries) <= 16000 and sum(e.file_size for e in entries) <= 420 * 1024**2, 'World archive size')
        for e in entries:
            name = e.filename; path = PurePosixPath(name)
            checked(path.as_posix() == name and not path.is_absolute() and '..' not in path.parts and ':' not in name and chr(92) not in name
                    and len(path.parts) >= 2 and not e.is_dir() and not stat.S_ISLNK(e.external_attr >> 16), 'Regular relative world file')
            checked((path.parts[0] == 'world' and path.suffix in WORLD_SUFFIXES)
                    or (path.parts[0] == 'expedition' and len(path.parts)==2 and path.suffix=='.json'), 'Only saved world or witness data')
        names = {e.filename for e in entries}
        checked(len(names)==len(entries) and {'world/level.dat','expedition/field-start.json','expedition/field-vessel.json'} <= names, 'One original registered ship')
        checked(blocks.read('expedition/field-progress.json') == progress and blocks.read('expedition/field-result.json') == result,
                'World and observation archives recorded the same leg')
        save = game/'saves/FieldWalk';save.mkdir(parents=True,exist_ok=False)
        for e in entries:
            path = PurePosixPath(e.filename)
            if path.parts[0] == 'world':
                out = save.joinpath(*path.parts[1:]);out.parent.mkdir(parents=True,exist_ok=True)
            elif path.stem in EVENTS:
                out = game/path.name
            else:
                continue
            with blocks.open(e) as inp, out.open('xb') as dest:
                shutil.copyfileobj(inp,dest)
        old_course = blocks.read('expedition/field-course.json') if 'expedition/field-course.json' in names else None
    (game/'previous-field-progress.json').write_bytes(progress)
    (game/'previous-field-result.json').write_bytes(result)
    if old_course: (game/'previous-field-course.json').write_bytes(old_course)
    if old_signal: (game/'field-map-signal.json').write_bytes(old_signal)
    (game/'screenshots').mkdir(exist_ok=True)
    for stem, data in images.items(): (game/'screenshots'/(stem+'.png')).write_bytes(data)
    lineage = {'run':int(run_id),'head':audit['source']['sourceCommit'],'worldSha256':receipt['sha256'],
               'playerFeet':audit['saved']['feet'],'world':audit['saved']['world'],'copiedFrames':list(images),
               'priorProfiles':len(json.loads(progress)['profiles'])}
    (game/'field-lineage.json').write_text(json.dumps(lineage,indent=2)+'\n', encoding='utf8')
    return lineage


def previous(run_id, root, game):
    hosted_only()
    checked(re.fullmatch('[0-9]+',str(run_id)), 'Numeric hosted lunar run')
    project = Path(__file__).resolve().parents[2]
    lock = json.loads((project/'qa/lunar-field/checkpoint.lock.json').read_bytes())
    if str(run_id) == str(lock['run']):
        preserve = project/lock['world']; seen = project/lock['observations']
        checked(sha(preserve)==lock['worldSha256'] and sha(seen)==lock['observationDigest'], 'Pinned original lunar expedition files')
        return restore(preserve,seen,game,run_id)
    repo = os.environ['GITHUB_REPOSITORY']; run = api(repo,'actions/runs/'+str(run_id))
    checked(run['path'] == '.github/workflows/lunar-field.yml' and run['status']=='completed'
            and run['head_repository']['full_name'].lower()==repo.lower(), 'Completed lunar workflow from this repository')
    suffix = f"-{run_id}-{run['run_attempt']}"
    available = {a['name']:a for a in api(repo,'actions/runs/'+str(run_id)+'/artifacts')['artifacts'] if not a['expired']}
    world_outer, observations = root/'previous-world-artifact.zip',root/'previous-observations.zip'
    fetch_archive(repo, available['lunar-world'+suffix],world_outer,420*1024**2)
    fetch_archive(repo, available['lunar-field-walk'+suffix],observations,20*1024**2)
    packed = root/'previous-field-world.zip'
    with ZipFile(world_outer) as archive:
        checked(archive.namelist()==['field-world.zip'] and 0<archive.getinfo('field-world.zip').file_size<=400*1024**2,'One bounded world archive')
        with archive.open('field-world.zip') as inp, packed.open('xb') as out: shutil.copyfileobj(inp,out)
    return restore(packed, observations, game, run_id)

if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--previous', default='')
    args = parser.parse_args()
    run_id = args.previous or json.loads(Path(__file__).with_name('checkpoint.lock.json').read_bytes())['run']
    print(json.dumps(previous(run_id,args.root,args.root/'lunar-field'),indent=2))
