"""Run a fresh original propellant factory and one rendered, non-op survival pilot on loopback."""
import argparse
import ctypes
from pathlib import Path
import hashlib
import importlib.util
import json
import re
import shutil
import socket
import sys
import time
import uuid
from zipfile import ZipFile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from local_client_policy import create_private_desktop, require_local_client
sys.path.insert(0, str(HERE.parent / 'concurrent-orbits'))
from soak_host import Child
from checkpoint import copy_checkpoint, copy_saved_dock

spec = importlib.util.spec_from_file_location('controls_host', HERE.parent / 'bridge-controls/run_windows.py')
controls = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controls)


class Memory(ctypes.Structure):
    _fields_ = [('size', ctypes.c_ulong), ('load', ctypes.c_ulong)] + [(name, ctypes.c_ulonglong) for name in ('physical', 'free', 'commit', 'free_commit', 'virtual', 'free_virtual', 'extra')]


def available():
    s = Memory(); s.size = ctypes.sizeof(s)
    if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(s)):
        raise OSError('Cannot check available memory')
    return round(s.free / 2**20), round(s.free_commit / 2**20), round(shutil.disk_usage(ROOT).free / 2**20)


def observe(path):
    try:
        return json.loads(path.read_bytes())
    except (OSError, ValueError):
        return None


def prepare(run, runtime, bundle, fixture, port):
    with ZipFile(bundle) as z:
        stamp = hashlib.sha256(bundle.read_bytes()).hexdigest()
        cache = ROOT / 'build/qa/native-fuel' / stamp
        cache.mkdir(exist_ok=True, parents=True)
        lock = json.loads(z.read('original-spaceflight.lock.json'))
        checks = {entry: digest for digest, entry in (line.split('  ',1) for line in z.read('SHA256SUMS.txt').decode().splitlines())}
        project = {}
        for row in lock['project']:
            name = row['file']; directory = row['directory']
            if Path(name).name != name or directory not in ('mods', 'retrofit/mods'):
                raise ValueError('Unexpected distribution path')
            entry = directory + '/' + name
            raw = z.read(entry)
            if hashlib.sha256(raw).hexdigest() != checks[entry]:
                raise ValueError('Package checksum mismatch: ' + name)
            (cache / name).write_bytes(raw); project[directory] = cache / name
    controls.prepare(run, runtime, project['mods'], fixture, project['retrofit/mods'], port, marker='starbridge-native-fuel.fixture')
    return {'bundleSha256':stamp, 'production':checks['mods/'+project['mods'].name], 'companion':checks['retrofit/mods/'+project['retrofit/mods'].name], 'qa':hashlib.sha256(fixture.read_bytes()).hexdigest()}


def supervise(run, java, report):
    require_local_client(ROOT)
    desk_name='StarbridgeFuel'+uuid.uuid4().hex[:12]
    desk=create_private_desktop(ROOT, desk_name)
    start=time.monotonic(); children=[]; server=run/'server'; pilot=run/'LunarTester'
    minimum=available(); last_output=None
    resumed = bool(report.get("restoredFrom"))
    try:
        children.append(Child(java,server,desk_name,['@launch.args']))
        print('Dedicated server PID',children[0].pid,flush=True)
        while time.monotonic()-start < 1400:
            if (run/'request-stop').exists(): raise RuntimeError('operator requested a saved checkpoint')
            sample=available(); minimum=tuple(min(a,b) for a,b in zip(minimum,sample))
            result=observe(server/'factory-result.json')
            if result:
                if not result.get('pass'): raise RuntimeError(str(result))
                report['pass']=True; return
            failure=observe(pilot/'factory-client-failure.json')
            if failure:raise RuntimeError(str(failure))
            if not all(p.alive() for p in children): raise RuntimeError('A game process exited before the factory completed')
            if sample[0] < 1050 or sample[1] < 2500 or sample[2] < 2200: raise RuntimeError('Resource guard '+str(sample))
            tank = observe(server/'factory-offline-tank.json') or {}
            ready = tank.get('fuel', 0) >= 1000 if resumed else (server/'factory-start.json').is_file()
            if len(children)==1 and ready and sample[0] > 3400 and sample[1] > 5000:
                children.append(Child(java,pilot,desk_name,['@launch.args'])); print('Rendered client PID',children[-1].pid,flush=True)
            new_order=observe(server/'factory-order.json')
            if new_order and new_order.get('seq') != last_output:
                last_output=new_order['seq']; print('Station',last_output,new_order['input'],'->',new_order['output'],flush=True)
            if len(children)==1 and time.monotonic()-start>(900 if resumed else 170): raise RuntimeError('Native machines failed to become ready in the available test slot')
            time.sleep(1)
        raise TimeoutError('Native processing did not finish during the test window')
    except Exception as error:
        report['error']=str(error); raise
    finally:
        for child in children: child.close()
        desk.close()
        report['elapsedSeconds']=round(time.monotonic()-start,3);report['minimumMiB']=minimum
        (run/'run-result.json').write_text(json.dumps(report,indent=2),'utf8')


if __name__=='__main__':
    require_local_client(ROOT)
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--runtime',type=Path,required=True);ap.add_argument('--bundle',type=Path,required=True);ap.add_argument('--java',type=Path,required=True);ap.add_argument('--label',required=True);ap.add_argument('--port',type=int,default=26885)
    ap.add_argument('--fixture',type=Path,default=ROOT/'build/qa/native-fuel/starbridge-native-fuel-qa-1.0.jar',help='Frozen test companion for the candidate under test')
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument('--player-build',action='store_true',help='Supply loose parts and form both machines through ordinary block placement')
    mode.add_argument('--inspect-from',type=Path,help='Reopen a stopped, refuelled original rocket using the candidate package')
    mode.add_argument('--resume-from', type=Path, help='Stopped factory checkpoint after loading oxygen and hydrogen; the original is kept intact')
    ap.add_argument('--wait-slot',type=int,default=0,help='Maximum seconds to wait for available RAM before copying the world')
    args=ap.parse_args()
    if not re.fullmatch('[a-z0-9-]+',args.label):ap.error('Choose a simple name for a new trial')
    deadline = time.monotonic() + max(0, args.wait_slot)
    while any(n < required for n,required in zip(available(), (7600,10000,4500))):
        if time.monotonic() >= deadline:
            raise SystemExit('Wait for free memory or disk before starting the factory')
        time.sleep(3)
    with socket.socket() as listener:listener.bind(('127.0.0.1',args.port))
    run=ROOT/('run-native-fuel-'+args.label)
    report=prepare(run,args.runtime.resolve(strict=True),args.bundle,args.fixture.resolve(strict=True),args.port)
    if args.resume_from:
        report['restoredFrom'] = copy_checkpoint(args.resume_from, run, report['production'])
    if args.player_build:
        (run/'server/assemble-workshop.fixture').touch()
    if args.inspect_from:
        report['inspectedFrom'] = copy_saved_dock(args.inspect_from, run)
    report['pass']=False;supervise(run,args.java.resolve(strict=True),report)
