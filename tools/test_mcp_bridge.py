"""No game connection is made: HTTP tests use an ephemeral loopback fake server."""

import io
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

import mcp_bridge as bridge


TOKEN = "starbridge-test-token-never-log"
SHIP_ID = "44e0e152-c606-45d6-bca8-72c1b1fb9a42"


class FakeGameServer:
    def __init__(self):
        self.calls = []
        self.status = 200
        self.body = {"ships": [{"id": SHIP_ID, "name": "Test ship"}]}
        self.raw_body = None
        self.delay = 0
        self.headers = {}
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.respond()

            def do_POST(self):
                self.respond()

            def respond(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                owner.calls.append({"method": self.command, "path": self.path,
                                    "authorization": self.headers.get("Authorization"),
                                    "body": json.loads(raw) if raw else None})
                if owner.delay:
                    time.sleep(owner.delay)
                response = owner.raw_body if owner.raw_body is not None else json.dumps(owner.body).encode()
                try:
                    self.send_response(owner.status)
                    self.send_header("Content-Type", "application/json")
                    for key, value in owner.headers.items():
                        self.send_header(key, value)
                    self.send_header("Content-Length", str(len(response)))
                    self.end_headers()
                    self.wfile.write(response)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                    pass

            def log_message(self, *_args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)


def initialize(server):
    response = server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": bridge.PROTOCOL_VERSION, "capabilities": {},
        "clientInfo": {"name": "unit-test", "version": "1"},
    }})
    server.handle({"jsonrpc": "2.0", "method": "notifications/initialized"})
    return response


def call(server, name, arguments):
    return server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
        "name": name, "arguments": arguments,
    }})


class ConfigurationTests(unittest.TestCase):
    def test_only_loopback_origins_are_accepted(self):
        self.assertEqual("http://127.0.0.1:18765", bridge.normalize_base_url("http://localhost:18765/"))
        self.assertEqual("http://[::1]:18765", bridge.normalize_base_url("http://[::1]:18765"))
        for invalid in ("http://example.com", "http://127.0.0.1.evil", "http://192.168.1.2",
                        "file:///etc/passwd", "http://token@127.0.0.1", "http://127.0.0.1/api",
                        "http://127.0.0.1?token=secret", "http://127.0.0.1#fragment",
                        "http://127.1", "http://2130706433", "http://127.0.0.1:0",
                        "http://127.0.0.1:65536", "http://127.0.0.1\n"):
            with self.subTest(invalid=invalid):
                with self.assertRaises(ValueError):
                    bridge.normalize_base_url(invalid)

    def test_token_precedence_and_file_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "api.token"
            path.write_text("\ufefffile-token\n", encoding="utf-8")
            self.assertEqual("file-token", bridge.load_token({}, str(path)))
            self.assertEqual(TOKEN, bridge.load_token({"STARBRIDGE_TOKEN": TOKEN}, str(path)))
            self.assertEqual("file-token", bridge.load_token({"STARBRIDGE_TOKEN_FILE": str(path)}, None))
            with self.assertRaises(ValueError):
                bridge.load_token({"STARBRIDGE_TOKEN": ""}, str(path))
            path.write_bytes(b"x" * (bridge.MAX_TOKEN_BYTES + 4))
            with self.assertRaises(ValueError):
                bridge.load_token({}, str(path))
        with self.assertRaises(ValueError):
            bridge.load_token({}, None)
        with self.assertRaises(ValueError):
            bridge.validate_token("token\r\nX-Injected: yes")

    def test_http_timeout_must_be_bounded_and_finite(self):
        for timeout in (0, -1, float("nan"), float("inf"), 31, True):
            with self.subTest(timeout=timeout), self.assertRaises(ValueError):
                bridge.StarbridgeClient(bridge.DEFAULT_BASE_URL, TOKEN, timeout)

    def test_mistaken_secret_argument_is_not_echoed_by_the_cli(self):
        process = subprocess.run(
            [sys.executable, str(Path(bridge.__file__).resolve()), "--unsupported-secret", TOKEN],
            text=True, capture_output=True, timeout=5,
        )
        self.assertEqual(2, process.returncode)
        self.assertEqual("", process.stdout)
        self.assertNotIn(TOKEN, process.stderr)


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        self.client = mock.Mock(spec=bridge.StarbridgeClient)
        self.client.call_tool.return_value = {"accepted": True}
        self.server = bridge.McpBridge(self.client)

    def test_initialization_negotiates_the_declared_protocol_and_has_no_http_side_effect(self):
        response = initialize(self.server)
        self.assertEqual(bridge.PROTOCOL_VERSION, response["result"]["protocolVersion"])
        self.assertEqual({"tools": {"listChanged": False}}, response["result"]["capabilities"])
        self.assertTrue(self.server.initialized)
        self.client.call_tool.assert_not_called()

    def test_unknown_requested_protocol_returns_supported_version(self):
        result = self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2099-01-01", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"},
        }})
        self.assertEqual("2025-11-25", result["result"]["protocolVersion"])

    def test_tools_cannot_execute_before_initialized_notification(self):
        response = call(self.server, "stop", {"ship_id": SHIP_ID})
        self.assertEqual(-32002, response["error"]["code"])
        self.client.call_tool.assert_not_called()

    def test_tools_list_and_ping(self):
        self.assertEqual({}, self.server.handle({"jsonrpc": "2.0", "id": 1, "method": "ping"})["result"])
        initialize(self.server)
        response = self.server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        self.assertEqual(bridge.TOOL_NAMES, {item["name"] for item in response["result"]["tools"]})
        self.assertTrue(all(not item["inputSchema"]["additionalProperties"] for item in response["result"]["tools"]))

    def test_notifications_never_execute_mutations_or_receive_responses(self):
        initialize(self.server)
        self.assertIsNone(self.server.handle({"jsonrpc": "2.0", "method": "tools/call", "params": {
            "name": "stop", "arguments": {"ship_id": SHIP_ID},
        }}))
        self.assertIsNone(self.server.handle({"jsonrpc": "2.0", "method": "unknown/notification"}))
        self.client.call_tool.assert_not_called()

    def test_unknown_tool_is_a_protocol_error_and_execution_failure_is_a_tool_error(self):
        initialize(self.server)
        self.assertEqual(-32602, call(self.server, "arbitrary_request", {})["error"]["code"])
        self.client.call_tool.side_effect = bridge.ToolError("timeout", "Request timed out")
        response = call(self.server, "list_ships", {})
        self.assertTrue(response["result"]["isError"])
        self.assertEqual("timeout", response["result"]["structuredContent"]["error"]["code"])

    def test_invalid_ids_and_batches_are_rejected(self):
        for bad_id in (None, True, 1.5, [], {}):
            response = self.server.handle({"jsonrpc": "2.0", "id": bad_id, "method": "ping"})
            self.assertEqual(-32600, response["error"]["code"])
            self.assertIsNone(response["id"])
        self.assertEqual(-32600, self.server.handle([])["error"]["code"])

    def test_stdio_uses_newline_json_and_emits_no_notification_response(self):
        lines = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                "protocolVersion": bridge.PROTOCOL_VERSION, "capabilities": {},
                "clientInfo": {"name": "test", "version": "1"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized"},
            {"jsonrpc": "2.0", "id": 2, "method": "ping"},
        ]
        source = io.BytesIO(b"".join(json.dumps(line).encode() + b"\n" for line in lines))
        destination = io.BytesIO()
        self.assertEqual(0, bridge.serve(self.server, source, destination))
        responses = [json.loads(line) for line in destination.getvalue().splitlines()]
        self.assertEqual([1, 2], [item["id"] for item in responses])

    def test_parse_errors_do_not_corrupt_the_next_message(self):
        source = io.BytesIO(b'{"jsonrpc":"2.0","id":1,"id":2,"method":"ping"}\n'
                            b'{"jsonrpc":"2.0","id":NaN,"method":"ping"}\n'
                            b'{"jsonrpc":"2.0","id":3,"method":"ping"}\n')
        destination = io.BytesIO()
        bridge.serve(self.server, source, destination)
        replies = [json.loads(line) for line in destination.getvalue().splitlines()]
        self.assertEqual([-32700, -32700], [item["error"]["code"] for item in replies[:2]])
        self.assertEqual({}, replies[2]["result"])

    def test_oversize_request_closes_transport_without_executing_a_tool(self):
        source = io.BytesIO(b" " * (bridge.MAX_REQUEST_BYTES + 1) + b"\n")
        destination = io.BytesIO()
        self.assertEqual(2, bridge.serve(self.server, source, destination))
        self.assertEqual(-32600, json.loads(destination.getvalue())["error"]["code"])
        self.client.call_tool.assert_not_called()

    def test_unexpected_exceptions_are_sanitized(self):
        initialize(self.server)
        self.client.call_tool.side_effect = RuntimeError("Do not expose " + TOKEN)
        response = call(self.server, "list_ships", {})
        self.assertEqual(-32603, response["error"]["code"])
        self.assertNotIn(TOKEN, json.dumps(response))


class LocalHttpTests(unittest.TestCase):
    def test_real_stdio_process_round_trip_uses_only_the_fake_game_service(self):
        with FakeGameServer() as game:
            game.body = [{"id": SHIP_ID, "name": "Fake only"}]
            messages = [
                {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                    "protocolVersion": bridge.PROTOCOL_VERSION, "capabilities": {},
                    "clientInfo": {"name": "subprocess-test", "version": "1"}}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
                    "name": "list_ships", "arguments": {}}},
            ]
            environment = dict(os.environ, STARBRIDGE_TOKEN=TOKEN, PYTHONDONTWRITEBYTECODE="1")
            process = subprocess.run(
                [sys.executable, str(Path(bridge.__file__).resolve()), "--base-url", game.base_url],
                input="".join(json.dumps(message) + "\n" for message in messages),
                text=True, capture_output=True, env=environment, timeout=5,
            )
            self.assertEqual(0, process.returncode, process.stderr)
            self.assertEqual("", process.stderr)
            self.assertNotIn(TOKEN, process.stdout + process.stderr)
            responses = [json.loads(line) for line in process.stdout.splitlines()]
            self.assertEqual([1, 2], [item["id"] for item in responses])
            self.assertEqual(SHIP_ID, responses[1]["result"]["structuredContent"]["data"][0]["id"])
            self.assertEqual(1, len(game.calls))

    def test_all_five_tools_use_only_the_expected_authenticated_routes(self):
        with FakeGameServer() as game:
            client = bridge.StarbridgeClient(game.base_url, TOKEN)
            client.call_tool("list_ships", {})
            client.call_tool("get_ship_status", {"ship_id": SHIP_ID.upper()})
            client.call_tool("navigate", {"ship_id": SHIP_ID, "x": 1, "y": 90.5, "z": -20})
            client.call_tool("hold", {"ship_id": SHIP_ID})
            client.call_tool("stop", {"ship_id": SHIP_ID})
            self.assertEqual(["GET", "GET", "POST", "POST", "POST"], [item["method"] for item in game.calls])
            self.assertEqual(["/v1/ships", f"/v1/ships/{SHIP_ID}", f"/v1/ships/{SHIP_ID}/navigate",
                              f"/v1/ships/{SHIP_ID}/hold", f"/v1/ships/{SHIP_ID}/stop"],
                             [item["path"] for item in game.calls])
            self.assertTrue(all(item["authorization"] == "Bearer " + TOKEN for item in game.calls))
            self.assertEqual({"x": 1.0, "y": 90.5, "z": -20.0}, game.calls[2]["body"])
            self.assertEqual({}, game.calls[3]["body"])

    def test_interplanetary_routes_and_finite_destination_set(self):
        with FakeGameServer() as game:
            client = bridge.StarbridgeClient(game.base_url, TOKEN)
            client.call_tool("get_star_chart", {"ship_id": SHIP_ID})
            client.call_tool("set_course", {"ship_id": SHIP_ID, "destination": "moon"})
            for value in ("../stop", "moon/../../ships", "MARS", False, None):
                with self.assertRaises(bridge.ToolError):
                    client.call_tool("set_course", {"ship_id": SHIP_ID, "destination": value})
            self.assertEqual([(r["method"], r["path"]) for r in game.calls], [
                ("GET", f"/v1/ships/{SHIP_ID}/chart"), ("POST", f"/v1/ships/{SHIP_ID}/voyage/moon")])

    def test_invalid_coordinates_ids_or_extra_fields_make_no_http_request(self):
        with FakeGameServer() as game:
            client = bridge.StarbridgeClient(game.base_url, TOKEN)
            for value in (float("nan"), float("inf"), True, "1", None, 10 ** 400):
                with self.subTest(value=repr(value)[:20]), self.assertRaises(bridge.ToolError):
                    client.call_tool("navigate", {"ship_id": SHIP_ID, "x": value, "y": 0, "z": 0})
            for value in ("../../stop", SHIP_ID + "?admin=true", "", None, "{" + SHIP_ID + "}"):
                with self.assertRaises(bridge.ToolError):
                    client.call_tool("stop", {"ship_id": value})
            with self.assertRaises(bridge.ToolError):
                client.call_tool("navigate", {"ship_id": SHIP_ID, "x": 1, "y": 2, "z": 3, "path": "/admin"})
            with self.assertRaises(bridge.ToolError):
                client.call_tool("list_ships", {"url": "http://example.com"})
            self.assertEqual([], game.calls)

    def test_http_failure_body_cannot_leak_credentials(self):
        with FakeGameServer() as game:
            game.status = 401
            game.body = {"error": "Bearer " + TOKEN}
            with self.assertRaises(bridge.ToolError) as raised:
                bridge.StarbridgeClient(game.base_url, TOKEN).call_tool("list_ships", {})
            self.assertEqual("authorization_failed", raised.exception.code)
            self.assertNotIn(TOKEN, str(raised.exception))

    def test_redirects_are_not_followed_even_to_another_loopback_path(self):
        with FakeGameServer() as game:
            game.status = 302
            game.headers = {"Location": game.base_url + "/steal-token"}
            with self.assertRaises(bridge.ToolError) as raised:
                bridge.StarbridgeClient(game.base_url, TOKEN).call_tool("list_ships", {})
            self.assertEqual("redirect_blocked", raised.exception.code)
            self.assertEqual(1, len(game.calls))

    def test_socket_timeout_returns_a_tool_failure_without_retrying(self):
        with FakeGameServer() as game:
            game.delay = 0.25
            with self.assertRaises(bridge.ToolError) as raised:
                bridge.StarbridgeClient(game.base_url, TOKEN, timeout=0.05).call_tool("stop", {"ship_id": SHIP_ID})
            self.assertEqual("timeout", raised.exception.code)
            self.assertEqual(1, len(game.calls))

    def test_invalid_or_oversized_upstream_json_is_rejected(self):
        with FakeGameServer() as game:
            client = bridge.StarbridgeClient(game.base_url, TOKEN)
            for raw in (b"not JSON", b'{"value":NaN}', b'{"value":1e9999}', b"\xff"):
                game.raw_body = raw
                with self.assertRaises(bridge.ToolError) as raised:
                    client.call_tool("list_ships", {})
                self.assertEqual("invalid_response", raised.exception.code)
            game.raw_body = b"x" * (bridge.MAX_HTTP_RESPONSE_BYTES + 1)
            with self.assertRaises(bridge.ToolError) as raised:
                client.call_tool("list_ships", {})
            self.assertEqual("response_too_large", raised.exception.code)

    def test_success_payload_is_scrubbed_if_upstream_accidentally_echoes_the_token(self):
        with FakeGameServer() as game:
            game.body = {"diagnostic": ["Bearer " + TOKEN], TOKEN: "value"}
            data = bridge.StarbridgeClient(game.base_url, TOKEN).call_tool("list_ships", {})
            self.assertNotIn(TOKEN, json.dumps(data))


if __name__ == "__main__":
    unittest.main()
