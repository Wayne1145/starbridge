from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from audit import NAMES, WORLDS, audit_window
from prepare import distribution_url, PACK
from run import run


class FleetAuditTest(unittest.TestCase):
    def setUp(self):
        self.fixture = {'pid': 78, 'ships': [{'length': n, 'height': 4, 'width': 8, 'id': str(n)} for n in (100, 200, 300)],
                        'furnaces': 30, 'diamonds': [3, 5, 7]}
        self.witness = {'pid': 78, 'identities': NAMES.copy(), 'moves': dict.fromkeys(NAMES, 20),
                        'elapsedSeconds': 75.01, 'firstTick': 1000, 'lastTick': 2500, 'originalWorlds': WORLDS.copy()}
        self.window = {'pid': 78, 'serverTick': 2200, 'windowWallSeconds': 60, 'windowTicks': 1200, 'tps': 20,
                       'cargo': [3, 5, 7], 'target': 10, 'online': 10, 'minimumOnline': 10, 'maximumOnline': 10,
                       'observedMoving': 10, 'stoneThisWindow': 60, 'totalQuorumDeficitTicks': 0, 'tickP95CeilMs': 20,
                       'peakTickMs': 44.2, 'ships': [{'active': True, 'cruise': True, 'paused': False, 'speed': 17,
                       'source': 'home', 'target': 'moon'} for _ in range(3)]}
        self.peers = [{'name': i, 'joined': True, 'alive': True, 'positioned': True, 'movement': 12, 'chunks': 93,
                       'keepalives': 1, 'packets': 2500} for i in range(1, 11)]

    def audit(self):
        return audit_window(78, self.fixture, self.witness, self.window, self.peers)

    def test_simultaneous_interval(self):
        self.assertEqual(self.audit()['identities'], 10)
        self.assertEqual(NAMES['FleetQA01'], '0b7bd537-b147-34a5-8585-5995d2e9221a')

    def test_rejects_nonoverlapping_different_pid_or_changed_dimension(self):
        for key, value in [('firstTick', 2300), ('elapsedSeconds', 74), ('pid', 79), ('originalWorlds', {'moon': 'example'})]:
            with self.subTest(key=key), patch.dict(self.witness, {key: value}), self.assertRaises(ValueError):
                self.audit()

    def test_temporarily_missing_player_or_inactive_route_fails(self):
        for key, value in [('minimumOnline', 9), ('totalQuorumDeficitTicks', 1), ('stoneThisWindow', 0),
                           ('ships', [dict(s, active=False) for s in self.window['ships']]), ('cargo', [3, 5, 6])]:
            with self.subTest(key=key), patch.dict(self.window, {key: value}), self.assertRaises(ValueError):
                self.audit()

    def test_connected_count_alone_does_not_establish_individual_motion_or_handshake(self):
        for key, value in [('name', 2), ('chunks', 0), ('alive', False), ('keepalives', 0), ('movement', 1)]:
            with self.subTest(key=key), patch.dict(self.peers[0], {key: value}), self.assertRaises(ValueError):
                self.audit()
        with patch.dict(self.witness['moves'], {'FleetQA10': 0}), self.assertRaises(ValueError):
            self.audit()

    def test_locked_dependencies_have_author_urls_and_sizes(self):
        self.assertEqual(len(PACK['dependencies']), 9)
        for dep in PACK['dependencies']:
            url, size = distribution_url(dep)
            self.assertTrue(url.endswith('/' + dep['file']))
            self.assertGreater(size, 0)

    def test_no_local_launch_before_host_gate(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict('os.environ', {'GITHUB_ACTIONS': ''}), patch('run.launch') as launch:
            path = Path(folder)
            with self.assertRaises(RuntimeError):
                run(path / 'runtime', path / 'evidence', path / 'fixture.jar')
            launch.assert_not_called()
            self.assertFalse((path / 'runtime').exists())
            self.assertFalse((path / 'evidence').exists())


if __name__ == '__main__':
    unittest.main()
