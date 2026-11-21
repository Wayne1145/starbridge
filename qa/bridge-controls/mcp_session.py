"""Drive the shipped MCP stdio process during the isolated CC:Tweaked and cockpit scenario."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def run(server_dir):
    root = Path(__file__).resolve().parents[2]
    ticket = server_dir / 'controls-ticket.json'
    deadline = time.monotonic() + 300
    def wait(check, label):
        while time.monotonic() < deadline:
            if check():
                return
            if (server_dir / 'controls-result.json').is_file():
                raise RuntimeError('The scenario ended before ' + label)
            time.sleep(.35)
        raise TimeoutError(label)
    wait(ticket.is_file, 'computer setup')
    uuid = json.loads(ticket.read_bytes())['core']
    report = []
    exe = [sys.executable, '-B', str(root / 'tools/mcp_bridge.py'), '--token-file', str(server_dir / 'config/starbridge-token.txt')]
    env = {key: value for key, value in os.environ.items() if key not in {'STARBRIDGE_TOKEN', 'STARBRIDGE_API_URL', 'STARBRIDGE_TOKEN_FILE'}}
    with subprocess.Popen(exe, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, encoding='utf8', env=env) as proc:
        serial = 0
        def packet(method, params, expect=True):
            nonlocal serial
            serial += 1
            request = {'jsonrpc': '2.0', 'method': method, 'params': params}
            if expect: request['id'] = serial
            proc.stdin.write(json.dumps(request) + '\n'); proc.stdin.flush()
            if not expect: return None
            reply = json.loads(proc.stdout.readline()); assert reply['id'] == serial and 'error' not in reply, reply
            return reply['result']
        def tool(name, args):
            return packet('tools/call', {'name': name, 'arguments': args})
        def marker(name): (server_dir / (name + '.ok')).write_text('observed', encoding='ascii')
        def state(): return tool('get_ship_status', {'ship_id': uuid})['structuredContent'].get('orbit', {})
        try:
            initialized=packet('initialize', {'protocolVersion':'2025-11-25','capabilities':{},'clientInfo':{'name':'starbridge-qa','version':'1'}})
            packet('notifications/initialized', {}, False)
            listing=tool('list_ships',{})['structuredContent']['data'];assert not any(row['id']==uuid for row in listing)
            report.append({'stage':'not authorized','visible':False});marker('mcp-initial-hidden')
            def visible():return any(row['id']==uuid for row in tool('list_ships',{})['structuredContent']['data'])
            wait(visible,'owner GUI authorization')
            chart=tool('get_star_chart',{'ship_id':uuid})['structuredContent'];assert chart['routes']['moon']['available']
            report.append({'stage':'authorized chart','provider':chart['routes']['moon']['provider'],'world':chart['routes']['moon']['dimension']});marker('mcp-before-launch')
            wait(lambda: state().get('speed',0)>.55,'native Lua first course and acceleration')
            holding=tool('hold',{'ship_id':uuid});assert not holding['isError'],holding
            cruise=tool('set_course',{'ship_id':uuid,'destination':'moon'});assert not cruise['isError'],cruise
            report.append({'stage':'agent control','holdAccepted':True,'courseAccepted':True,'orbit':state()});marker('mcp-cruise')
            wait(lambda: state().get('manual') is True,'captain manual takeover')
            decline=tool('stop',{'ship_id':uuid});assert decline['isError'],decline
            report.append({'stage':'manual priority','error':decline['structuredContent']['error']});marker('mcp-denied')
            def lua_braked():
                live=server_dir/'controls-live.json'
                if not live.is_file(): return False
                try:return 'braked' in json.loads(live.read_bytes())
                except ValueError:return False
            wait(lua_braked,'CC:Tweaked regained controls after captain clicked cruise')
            flight=tool('set_course',{'ship_id':uuid,'destination':'moon'});assert not flight['isError'],flight
            report.append({'stage':'external control restored','courseAccepted':True,'orbit':state()});marker('mcp-complete')
        finally:
            proc.stdin.close()
            (server_dir/'mcp-results.json').write_text(json.dumps(report,indent=2),encoding='utf8')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('server',type=Path);args=parser.parse_args();run(args.server.resolve())
