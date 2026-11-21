"""Cross-check the two endpoints of the short, three-ship orbital benchmark."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def audit(root: Path) -> dict:
    server, client = root / 'server', root / 'LunarTester'
    result_path = server / 'fleet-result.json'
    peers_path = client / 'peer-counts.json'
    result = json.loads(result_path.read_text(encoding='utf-8'))
    peers = json.loads(peers_path.read_text(encoding='utf-8'))
    log_path = server / 'console.log'
    log = log_path.read_text(encoding='utf-8', errors='replace')
    tiers, hulls = result['tiers'], result['ships']
    snapshots = [tier['orbits'] for tier in tiers]
    join_ids = {int(name) for name in re.findall(r'\bFleetQA(\d{2}) joined the game', log)}
    backlogs = [int(n) for n in re.findall(r'Running (\d+)ms or \d+ ticks behind', log)]
    crash_files = [str(p.relative_to(root)) for p in (server / 'crash-reports').glob('*.txt')]
    artifacts = [result_path, peers_path, log_path, client / 'console.log', server / 'server.properties']
    jar_pairs = {}
    for pattern in ('starbridge-1.*.jar', 'starbridge-connected-fleet-*.jar'):
        first, = (server / 'mods').glob(pattern)
        second, = (client / 'mods').glob(pattern)
        jar_pairs[first.name] = [sha256(first), sha256(second)]
    expected = set(range(1, 31))
    healthy_at_each_checkpoint = all(
        len(snapshot) == 3 and all(
            ship['active'] and ship['cruise'] and not ship['paused']
            and ship['speed'] > .1 and ship['target'] == 'moon'
            for ship in snapshot
        )
        for snapshot in snapshots
    )
    advancing = all(
        all(after['distance'] < before['distance'] for before, after in zip(prev, curr))
        for prev, curr in zip(snapshots, snapshots[1:])
    )
    every_peer_saw_play = all(
        p['joined'] and p['positioned'] and p['alive']
        and all(p[key] > 0 for key in ('movement', 'chunks', 'keepalives'))
        for p in peers
    )
    checks = {
        'serverPassed': result['pass'] is True,
        'hullLengths': [h['length'] for h in hulls] == [100, 200, 300],
        'distinctHulls': len({h['id'] for h in hulls}) == 3,
        'threeCohorts': [t['connected'] for t in tiers] == [10, 20, 30],
        'activeMovementDuringEachWindow': all(t['ticks'] == 1200 and t['uniqueMoving'] == t['connected'] for t in tiers),
        'threeCruisingAtCheckpointAndApproachingBetweenCheckpoints': healthy_at_each_checkpoint and advancing,
        'smeltingDuringEveryWindow': all(t['furnaceOutput'] > 0 for t in tiers),
        'thirtyIndependentServerLogins': join_ids == expected,
        'thirtyLivePeersReceivedWorldData': len(peers) == 30 and {p['name'] for p in peers} == expected and every_peer_saw_play,
        'identicalJarsOnBothEndpoints': all(a == b for a, b in jar_pairs.values()),
        'noServerCrashReport': not crash_files and 'Exception in server tick loop' not in log and 'ServerHangWatchdog' not in log,
        'serverSavedOnShutdown': 'Stopping server' in log and 'All dimensions are saved' in log,
    }
    counters = {key: {'minimum': min(p[key] for p in peers), 'maximum': max(p[key] for p in peers), 'total': sum(p[key] for p in peers)} for key in ('chunks', 'keepalives', 'movement', 'packets')}
    return {
        'pass': all(checks.values()), 'checks': checks, 'hulls': hulls, 'tiers': tiers,
        'peerCountersAtLastClientSnapshot': counters, 'serverJoinNames': len(join_ids),
        'serverBacklogsMsIncludingStartup': backlogs, 'serverCrashReports': crash_files,
        'installedServerAndClientSha256': jar_pairs,
        'evidenceSha256': {p.relative_to(root).as_posix(): sha256(p) for p in artifacts},
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('instance', type=Path, help='finished instance with server/ and LunarTester/')
    args = parser.parse_args()
    report = audit(args.instance)
    print(json.dumps(report, indent=2, ensure_ascii=False))
    raise SystemExit(0 if report['pass'] else 1)
