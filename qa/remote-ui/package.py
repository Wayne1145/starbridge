"""Select the public package built by this repository's Build and test workflow."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import time
from zipfile import ZipFile

from runtime import PACK, hosted_only, sha

ARTIFACT = 'starbridge-0.3-dev'
BUNDLE = f'starbridge-{PACK["version"]}.zip'
MAIN = next(item['file'] for item in PACK['project'] if item['directory'] == 'mods')


def api(repo, suffix):
    return json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/{suffix}'], timeout=40))


def select_run(repo, commit, requested, head_repo, event, pull_request=None, base_commit=None):
    def acceptable(run):
        if (run['head_sha'] != commit or run['path'] != '.github/workflows/ci.yml'
                or (run.get('head_repository') or {}).get('full_name', '').lower() != head_repo.lower()
                or run['conclusion'] != 'success' or (event and run['event'] != event)):
            return False
        if pull_request is None:
            return True
        return any(str(pr['number']) == pull_request and pr['base']['sha'] == base_commit
                   for pr in run.get('pull_requests', []))
    if requested:
        if not requested.isdecimal():
            raise ValueError('The build run ID must be numeric')
        run = api(repo, 'actions/runs/' + requested)
        if not acceptable(run):
            raise ValueError('Requested run must be a successful Build and test for the requested source revision')
        return run
    deadline = time.monotonic() + 12 * 60
    print('Waiting for Build and test for ' + commit, flush=True)
    while time.monotonic() < deadline:
        runs = api(repo, f'actions/workflows/ci.yml/runs?head_sha={commit}&per_page=35')['workflow_runs']
        for run in runs:
            if acceptable(run):
                return run
        time.sleep(12)
    raise TimeoutError('No successful public package from Build and test for ' + commit)


def member(archive, name, limit):
    matched = [e for e in archive.infolist() if e.filename == name]
    if len(matched) != 1 or not 0 < matched[0].file_size <= limit:
        raise ValueError('Missing, repeated or oversized archive member: ' + name)
    return archive.read(matched[0])


def extract(bundle_bytes, target):
    with ZipFile(io.BytesIO(bundle_bytes)) as bundle:
        lock = member(bundle, 'original-spaceflight.lock.json', 128 * 1024)
        if json.loads(lock) != PACK:
            raise ValueError('Package and checkout have different flight manifests')
        sums = member(bundle, 'SHA256SUMS.txt', 128 * 1024).decode().splitlines()
        name = 'mods/' + MAIN
        expected, = [line.split('  ', 1)[0] for line in sums if line.endswith('  ' + name)]
        jar = member(bundle, name, 16 * 1024**2)
        if hashlib.sha256(jar).hexdigest() != expected:
            raise ValueError('Published main JAR checksum mismatch')
    with ZipFile(io.BytesIO(jar)) as release:
        if member(release, 'META-INF/starbridge/original-spaceflight.lock.json', 128 * 1024) != lock:
            raise ValueError('Installed JAR does not contain the published manifest')
    target.write_bytes(jar)
    return hashlib.sha256(bundle_bytes).hexdigest()


def fetch_package(folder, repo, commit, requested, head_repo, event=None, pull_request=None, base_commit=None):
    run = select_run(repo, commit, requested, head_repo, event, pull_request, base_commit)
    artifact, = [a for a in api(repo, f'actions/runs/{run["id"]}/artifacts')['artifacts'] if a['name'] == ARTIFACT and not a['expired']]
    if not 0 < artifact['size_in_bytes'] < 64 * 1024**2 or not re.fullmatch('sha256:[a-f0-9]{64}', artifact.get('digest', '') or ''):
        raise ValueError('Missing artifact digest or archive outside the size limit')
    folder.mkdir(parents=True, exist_ok=False)
    outer = folder / 'actions-artifact.zip'
    with outer.open('xb') as stream:
        subprocess.run(['gh', 'api', f'repos/{repo}/actions/artifacts/{artifact["id"]}/zip'], stdout=stream, check=True, timeout=150)
    if outer.stat().st_size != artifact['size_in_bytes'] or 'sha256:' + sha(outer) != artifact['digest']:
        raise ValueError('Archive differs from the GitHub Actions artifact digest')
    with ZipFile(outer) as archive:
        bundle_hash = extract(member(archive, BUNDLE, 64 * 1024**2), folder / MAIN)
    source = {'repository': repo, 'sourceRepository': head_repo, 'buildRun': run['id'], 'sourceCommit': commit,
              'artifactID': artifact['id'], 'artifactDigest': artifact['digest'], 'packageSha256': bundle_hash, 'mainJarSha256': sha(folder / MAIN)}
    if pull_request is not None:
        source.update(pullRequest=int(pull_request), baseCommit=base_commit)
    (folder / 'source.json').write_text(json.dumps(source, indent=2) + '\n', 'utf8')
    return source


if __name__ == '__main__':
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument('--directory', required=True, type=Path)
    args = cli.parse_args()
    hosted_only()
    repo = os.environ['GITHUB_REPOSITORY']
    commit = os.environ['SOURCE_COMMIT']
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Expected a Git commit SHA')
    event = 'pull_request' if os.environ['GITHUB_EVENT_NAME'] == 'pull_request' else None
    source = fetch_package(args.directory.resolve(), repo, commit, os.environ.get('REQUESTED_RUN', ''),
                           os.environ['SOURCE_REPOSITORY'], event, os.environ['SOURCE_PR'] if event else None,
                           os.environ['SOURCE_BASE'] if event else None)
    print(json.dumps(source))
