"""Negative regressions for the hosted home-map evidence. All data here are synthetic."""
import copy
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import remote


class HomeMapTest(unittest.TestCase):
    def setUp(self):
        frames = {}
        for name, offset, center, clear in (
                ('port', [0,0], [-41,59], False), ('alternate', [63,-37], [22,22], True),
                ('recenter', [0,0], [-41,59], False), ('reselect', [63,-37], [22,22], True),
                ('refusal', [63,-37], [22,22], False), ('retry', [63,-37], [22,22], True)):
            frames[name] = {'offset':offset, 'center':center, 'clearance':'CLEAR' if clear else 'OCCUPIED', 'buttonActive':clear,
                            'symbol':'open green brackets' if clear else 'orange cross', 'label':'泊位净空' if clear else '泊位被占用'}
        layout = {'framebuffer':[960,600],'gui':[480,300],'map':[30,42,180]}
        pixels = [layout | {'pixel':p} for p in ([261,250],[244,260],[261,250])]
        self.client = {'mapClicks':pixels,'pass':True, 'language':'zh_cn','boundUse':'key.mouse.right','frames':frames}
        self.server = {'pass':True,'source':'retrofit:legacy_dim_-28', 'destination':'minecraft:overworld','fare':12000,
                       'beforeFE':88000,'afterFE':76000,'ship':8,'diamonds':7,'offset':[63,-37],'aboard':True,'obstructionIntact':True,'sourceCleared':True,
                       'confirmations':['OCCUPIED','CLEAR'],'nativePosition':[22.125,220.25,22.625]}
        self.actions = '\n'.join(remote.ACTIONS)

    def test_two_player_confirmations_require_two_actual_fresh_surveys(self):
        remote.verify_clicks(self.client, self.server, self.actions)
        for observed in ([], ['CLEAR'], ['CLEAR','OCCUPIED'], ['OCCUPIED','CLEAR','CLEAR']):
            with self.subTest(observed=observed), self.assertRaisesRegex(ValueError,'confirmation surveys'):
                remote.verify_clicks(self.client, self.server | {'confirmations':observed}, self.actions)
        with self.assertRaisesRegex(ValueError, 'Click order'):
            remote.verify_clicks(self.client, self.server, self.actions.replace('click recorded port square\n',''))

    def test_center_snap_and_same_alternate_are_required_across_all_four_changes(self):
        for name, update in [('recenter',{'offset':[2,-2]}),('refusal',{'center':[-41,59]}),('refusal',{'buttonActive':True}),
                             ('retry',{'label':'泊位被占用'}),('port',{'symbol':'open green brackets'})]:
            with self.subTest(name=name, update=update), self.assertRaisesRegex(ValueError,name):
                client = copy.deepcopy(self.client)
                client['frames'][name].update(update)
                remote.verify_clicks(client,self.server,self.actions)

    def test_ship_must_arrive_at_selected_subblock_position_and_charge_once(self):
        for updates in ({'nativePosition':[22,220,21]}, {'nativePosition':[-40.875,220.25,59.625]}, {'afterFE':64000}, {'sourceCleared':False}):
            with self.subTest(updates=updates), self.assertRaises(ValueError):
                remote.verify_clicks(self.client, self.server | updates, self.actions)

    def test_workstation_guard_precedes_game_directory_or_process(self):
        with TemporaryDirectory() as temp, patch.dict('os.environ',{'RUNNER_ENVIRONMENT':'self-hosted'},clear=True), patch('subprocess.Popen') as launch:
            root = Path(temp)
            with self.assertRaisesRegex(RuntimeError,'GitHub-hosted Linux'):
                remote.run(root,root/'evidence',root/'fake.jar')
            self.assertEqual([],list(root.iterdir()))
            launch.assert_not_called()


if __name__ == '__main__':
    unittest.main()
