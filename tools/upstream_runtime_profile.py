"""Start an isolated Forge server and check that the expected legacy mods and world are present."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time


DIMENSION = re.compile(r'Legacy dimension (-?\d+) \(([^)]*)\) is (retrofit:legacy_dim_-?\d+)')
AVAILABLE = re.compile(r'^\s*([\w.-]+) -> Available\s*$', re.MULTILINE)
OMITTED = re.compile(r'1\.7\.10 mod (?:left out: |not loaded, the others are: )([^:\r\n]+)', re.IGNORECASE)


def select_world(worlds, requested):
    matches = {key for name, key in worlds if requested.casefold() in (key.casefold(), name.casefold())}
    if len(matches) != 1:
        raise ValueError(f'Expected one registered world named {requested!r}, found {sorted(matches)}')
    return matches.pop()


def legacy_mods(text, expected):
    available = sorted(set(AVAILABLE.findall(text)), key=str.casefold)
    omitted = sorted(set(OMITTED.findall(text)), key=str.casefold)
    missing = sorted(set(expected) - {mod.casefold() for mod in available})
    return available, omitted, missing


def run_profile(instance, java, search, expected):
    output = instance / 'original-probe-console.log'
    summary_path = instance / 'original-probe.json'
    if output.exists() or summary_path.exists():
        raise FileExistsError('Use a new test profile so the original evidence is kept.')
    args = [str(java), '-Xms384m', '-Xmx2400m', '-XX:ActiveProcessorCount=4', '-Djava.awt.headless=true',
            '-Dfile.encoding=UTF-8', '-Dretrofit.minecraftHome=' + str(instance / 'legacy-launcher'), '@launch.args', 'nogui']
    expected = {mod.casefold() for mod in expected}
    ready = flushed = loaded = False
    key = error = None
    with output.open('xb') as log:
        proc = subprocess.Popen(args, cwd=instance, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))

        def command(text):
            proc.stdin.write((text + '\n').encode('utf8'))
            proc.stdin.flush()

        def console():
            return output.read_bytes().decode('utf8', 'replace')

        def wait_for(pattern, timeout):
            until = time.monotonic() + timeout
            while time.monotonic() < until:
                text = console()
                if re.search(pattern, text):
                    return text
                if proc.poll() is not None:
                    raise RuntimeError('Server exited while waiting for ' + pattern)
                time.sleep(.7)
            raise TimeoutError('Server timed out waiting for ' + pattern)

        try:
            text = wait_for(r'Done \([\d.,]+s\)', 220)
            ready = True
            available, omitted, missing = legacy_mods(text, expected)
            if not available or missing or omitted:
                raise ValueError(f'Missing legacy mods {missing}, excluded {omitted}, available {available}')
            key = select_world({(name, body) for _, name, body in DIMENSION.findall(text)}, search)
            command(f'execute in {key} run forceload add 0 0')
            for _ in range(40):
                command(f'execute in {key} if loaded 0 64 0 run say StarbridgeChunkReady')
                try:
                    wait_for(r'\[Server\] StarbridgeChunkReady', 2)
                    loaded = True
                    break
                except TimeoutError:
                    continue
            if not loaded:
                raise TimeoutError('Target chunk never became available')
            command('save-all flush')
            wait_for(r'Saved the game', 80)
            flushed = True
        except (ValueError, OSError, RuntimeError) as exc:
            error = str(exc)
        finally:
            if proc.poll() is None:
                try:
                    if key:
                        command(f'execute in {key} run forceload remove 0 0')
                    command('stop')
                except BrokenPipeError:
                    pass
                try:
                    proc.wait(timeout=70)
                except subprocess.TimeoutExpired:
                    proc.terminate()
                    proc.wait(timeout=12)
            proc.stdin.close()
    text = output.read_bytes().decode('utf8', 'replace')
    available, omitted, missing = legacy_mods(text, expected)
    result = dict(profile=instance.name, ready=ready, available=available, omitted=omitted, missing=missing,
                  technically_registered=sorted(set((name, body) for _, name, body in DIMENSION.findall(text))),
                  selected=key, chunk_loaded=loaded, flushed=flushed, exit=proc.returncode, error=error,
                  passed=ready and loaded and flushed and not error and not missing and not omitted and proc.returncode == 0)
    summary_path.write_text(json.dumps(result, indent=2), encoding='utf8')
    print(summary_path, 'PASS' if result['passed'] else f'FAIL {error}', flush=True)
    return result['passed']


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('instance', type=Path)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--body', required=True, help='Exact upstream display name or full registered world key')
    parser.add_argument('--expect-mod', action='append', required=True, help='Expected legacy mod ID, repeat for each mod in the profile')
    args = parser.parse_args()
    raise SystemExit(0 if run_profile(args.instance.resolve(), args.java.resolve(), args.body, args.expect_mod) else 1)
