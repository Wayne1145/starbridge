#!/usr/bin/env python3
"""Starbridge's dependency-free MCP stdio bridge (protocol 2025-11-25).

Only fixed Starbridge routes on a loopback HTTP service are reachable. Game-side
authorization, navigation safety and job ownership remain the server's responsibility.
"""

from __future__ import annotations

import argparse
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import socket
import sys
from typing import Any, BinaryIO, Mapping
from urllib import error, parse, request
import uuid


PROTOCOL_VERSION = "2025-11-25"
SERVER_VERSION = "0.1.0"
DEFAULT_BASE_URL = "http://127.0.0.1:18765"
MAX_REQUEST_BYTES = 65_536
MAX_HTTP_RESPONSE_BYTES = 1_048_576
MAX_JSON_DEPTH = 64
MAX_TOKEN_BYTES = 4_096
UUID_PATTERN = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


class RpcError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class ToolError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _finite_float(text: str) -> float:
    value = float(text)
    if not math.isfinite(value):
        raise ValueError("JSON numbers must be finite")
    return value


def _reject_constant(_text: str) -> None:
    raise ValueError("Non-standard JSON numeric constant")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON object key")
        result[key] = value
    return result


def strict_json_loads(data: str | bytes) -> Any:
    result = json.loads(data, parse_float=_finite_float, parse_constant=_reject_constant,
                        object_pairs_hook=_unique_object)
    pending = [(result, 0)]
    while pending:
        value, depth = pending.pop()
        if depth > MAX_JSON_DEPTH:
            raise ValueError("JSON nesting limit exceeded")
        if isinstance(value, dict):
            pending.extend((child, depth + 1) for child in value.values())
        elif isinstance(value, list):
            pending.extend((child, depth + 1) for child in value)
    return result


def normalize_base_url(value: str) -> str:
    """Accept only literal loopback addresses; localhost is pinned to 127.0.0.1."""
    try:
        if not isinstance(value, str) or any(ord(char) < 33 or ord(char) > 126 for char in value):
            raise ValueError
        url = parse.urlsplit(value)
        if url.scheme not in ("http", "https") or not url.netloc:
            raise ValueError
        if url.username is not None or url.password is not None or url.query or url.fragment:
            raise ValueError
        if url.path not in ("", "/"):
            raise ValueError
        host = url.hostname
        if host == "localhost":
            host = "127.0.0.1"
        address = ipaddress.ip_address(host or "")
        if not address.is_loopback:
            raise ValueError
        port = url.port
        if port is not None and not 1 <= port <= 65_535:
            raise ValueError
        authority = f"[{address.compressed}]" if address.version == 6 else address.compressed
        if port is not None:
            authority += f":{port}"
        return f"{url.scheme}://{authority}"
    except (TypeError, ValueError):
        raise ValueError("API URL must be an http(s) loopback origin without credentials, path, query or fragment") from None


def validate_token(token: str) -> str:
    if not isinstance(token, str) or not token or len(token) > MAX_TOKEN_BYTES:
        raise ValueError("A non-empty API token is required")
    if any(ord(char) < 33 or ord(char) > 126 for char in token):
        raise ValueError("API token must contain printable ASCII without whitespace")
    return token


def load_token(environment: Mapping[str, str], token_file: str | None) -> str:
    if "STARBRIDGE_TOKEN" in environment:
        return validate_token(environment["STARBRIDGE_TOKEN"])
    selected_file = token_file or environment.get("STARBRIDGE_TOKEN_FILE")
    if not selected_file:
        raise ValueError("Set STARBRIDGE_TOKEN or supply --token-file / STARBRIDGE_TOKEN_FILE")
    try:
        with Path(selected_file).open("rb") as handle:
            raw = handle.read(MAX_TOKEN_BYTES + 4)
        if len(raw) > MAX_TOKEN_BYTES + 3:
            raise ValueError("Token file is too large")
        token = raw.decode("utf-8-sig").strip()
    except (OSError, UnicodeError):
        raise ValueError("Cannot read a UTF-8 API token file") from None
    return validate_token(token)


def _ship_id(value: Any) -> str:
    if not isinstance(value, str) or not UUID_PATTERN.fullmatch(value):
        raise ToolError("invalid_arguments", "ship_id must be a UUID with hyphens")
    return str(uuid.UUID(value))


def _coordinate(value: Any, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ToolError("invalid_arguments", f"{name} must be a finite number")
    try:
        converted = float(value)
    except (ValueError, OverflowError):
        raise ToolError("invalid_arguments", f"{name} must be a finite number") from None
    if not math.isfinite(converted):
        raise ToolError("invalid_arguments", f"{name} must be a finite number")
    return converted


def _arguments(arguments: dict[str, Any], required: set[str]) -> None:
    if set(arguments) != required:
        raise ToolError("invalid_arguments", "Arguments must contain exactly: " + (", ".join(sorted(required)) or "no fields"))


_SHIP_SCHEMA = {"type": "string", "format": "uuid", "description": "Ship core UUID from list_ships.id; do not use the physical backend's shipId."}


def _tool(name: str, description: str, properties: dict[str, Any], read_only: bool) -> dict[str, Any]:
    return {
        "name": name,
        "description": description,
        "inputSchema": {
            "type": "object", "properties": properties,
            "required": list(properties), "additionalProperties": False,
        },
        "annotations": {
            "readOnlyHint": read_only, "destructiveHint": not read_only,
            "idempotentHint": read_only, "openWorldHint": False,
        },
    }


_DESTINATION = re.compile(r"[a-z0-9_]+(?:[.][a-z0-9_]+)*")


TOOLS = [
    _tool("list_ships", "List ships visible to the authenticated local Starbridge service.", {}, True),
    _tool("get_ship_status", "Read a ship's telemetry and navigation status.", {"ship_id": _SHIP_SCHEMA}, True),
    _tool("get_star_chart", "Read the owner’s chart, route costs and return reserves.", {"ship_id": _SHIP_SCHEMA}, True),
    _tool("set_course", "Take the crew on a charted expedition with the owner aboard. Verify arrival separately.", {
        "ship_id": _SHIP_SCHEMA, "destination": {"type": "string", "pattern": "^" + _DESTINATION.pattern + "$", "maxLength": 96, "description": "Destination ID from get_star_chart"},
    }, False),
    _tool("navigate", "Request navigation to a world position in the ship's current dimension. Acceptance does not mean arrival.", {
        "ship_id": _SHIP_SCHEMA,
        "x": {"type": "number", "description": "World X coordinate in blocks."},
        "y": {"type": "number", "description": "World Y coordinate in blocks."},
        "z": {"type": "number", "description": "World Z coordinate in blocks."},
    }, False),
    _tool("hold", "Request position hold through the authorized ship controller.", {"ship_id": _SHIP_SCHEMA}, False),
    _tool("stop", "Request emergency braking through the authorized ship controller; this does not teleport the ship.", {"ship_id": _SHIP_SCHEMA}, False),
]
TOOL_NAMES = frozenset(tool["name"] for tool in TOOLS)


class _NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        return None


class StarbridgeClient:
    def __init__(self, base_url: str, token: str, timeout: float = 5.0):
        self.base_url = normalize_base_url(base_url)
        self._token = validate_token(token)
        if isinstance(timeout, bool) or not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or not 0 < timeout <= 30:
            raise ValueError("Timeout must be greater than zero and at most 30 seconds")
        self.timeout = float(timeout)
        # Local bearer credentials must never be sent through an environment-configured proxy.
        self._opener = request.build_opener(request.ProxyHandler({}), _NoRedirect())

    def call_tool(self, name: str, arguments: dict[str, Any]) -> Any:
        if name == "list_ships":
            _arguments(arguments, set())
            return self._request("GET", "/v1/ships")
        if name == "get_ship_status":
            _arguments(arguments, {"ship_id"})
            return self._request("GET", "/v1/ships/" + _ship_id(arguments["ship_id"]))
        if name == "get_star_chart":
            _arguments(arguments, {"ship_id"})
            return self._request("GET", f"/v1/ships/{_ship_id(arguments['ship_id'])}/chart")
        if name == "set_course":
            _arguments(arguments, {"ship_id", "destination"})
            destination = arguments["destination"]
            if not isinstance(destination, str) or len(destination) > 96 or not _DESTINATION.fullmatch(destination):
                raise ToolError("invalid_arguments", "Unknown expedition destination")
            return self._request("POST", f"/v1/ships/{_ship_id(arguments['ship_id'])}/voyage/{destination}", {})
        if name == "navigate":
            _arguments(arguments, {"ship_id", "x", "y", "z"})
            ship = _ship_id(arguments["ship_id"])
            coordinates = {axis: _coordinate(arguments[axis], axis) for axis in ("x", "y", "z")}
            return self._request("POST", f"/v1/ships/{ship}/navigate", coordinates)
        if name in ("hold", "stop"):
            _arguments(arguments, {"ship_id"})
            return self._request("POST", f"/v1/ships/{_ship_id(arguments['ship_id'])}/{name}", {})
        raise ToolError("unknown_tool", "Unknown Starbridge tool")

    def _request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        headers = {"Accept": "application/json", "Authorization": "Bearer " + self._token,
                   "User-Agent": "Starbridge-MCP/" + SERVER_VERSION}
        encoded = None
        if payload is not None:
            encoded = json.dumps(payload, allow_nan=False, separators=(",", ":")).encode("utf-8")
            headers["Content-Type"] = "application/json"
        outbound = request.Request(self.base_url + path, data=encoded, headers=headers, method=method)
        try:
            with self._opener.open(outbound, timeout=self.timeout) as response:
                data = response.read(MAX_HTTP_RESPONSE_BYTES + 1)
                if len(data) > MAX_HTTP_RESPONSE_BYTES:
                    raise ToolError("response_too_large", "Game API response exceeded the size limit")
                if not data and response.status == 204:
                    return {"accepted": True}
                try:
                    return self._redact(strict_json_loads(data.decode("utf-8")))
                except (ValueError, UnicodeError, RecursionError):
                    raise ToolError("invalid_response", "Game API returned invalid JSON") from None
        except error.HTTPError as failure:
            # Deliberately omit upstream bodies, URLs and exception strings: they may contain secrets.
            code = failure.code
            failure.close()
            if code in (401, 403):
                raise ToolError("authorization_failed", f"Game API returned HTTP {code}; check the configured token and game-side permissions") from None
            if 300 <= code < 400:
                raise ToolError("redirect_blocked", "Game API redirect was blocked") from None
            raise ToolError("upstream_http_error", f"Game API returned HTTP {code}") from None
        except (TimeoutError, socket.timeout):
            raise ToolError("timeout", "Game API request timed out; check ship status before retrying a control command") from None
        except error.URLError as failure:
            if isinstance(failure.reason, (TimeoutError, socket.timeout)):
                raise ToolError("timeout", "Game API request timed out; check ship status before retrying a control command") from None
            raise ToolError("connection_failed", "Cannot connect to the local Starbridge game API") from None
        except http.client.HTTPException:
            raise ToolError("invalid_response", "Game API returned an incomplete or invalid HTTP response") from None
        except OSError:
            raise ToolError("connection_failed", "Local Starbridge connection failed") from None

    def _redact(self, value: Any) -> Any:
        if isinstance(value, str):
            return value.replace(self._token, "[REDACTED]")
        if isinstance(value, list):
            return [self._redact(child) for child in value]
        if isinstance(value, dict):
            return {self._redact(key): self._redact(child) for key, child in value.items()}
        return value


class McpBridge:
    def __init__(self, client: StarbridgeClient):
        self.client = client
        self.initialize_sent = False
        self.initialized = False

    @staticmethod
    def error(identifier: Any, code: int, message: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": identifier, "error": {"code": code, "message": message}}

    def handle(self, message: Any) -> dict[str, Any] | None:
        identifier = None
        notification = False
        try:
            if not isinstance(message, dict) or message.get("jsonrpc") != "2.0":
                raise RpcError(-32600, "Invalid JSON-RPC request")
            method = message.get("method")
            if not isinstance(method, str) or not method or len(method) > 128:
                raise RpcError(-32600, "Invalid JSON-RPC method")
            notification = "id" not in message
            if not notification:
                candidate = message["id"]
                if isinstance(candidate, bool) or not isinstance(candidate, (str, int)):
                    raise RpcError(-32600, "Request id must be a string or integer")
                identifier = candidate
            params = message.get("params", {})
            if not isinstance(params, dict):
                raise RpcError(-32602, "params must be an object")

            if notification:
                if method == "notifications/initialized" and self.initialize_sent:
                    self.initialized = True
                # Notifications never execute tools or receive a response.
                return None
            if method == "ping":
                result: Any = {}
            elif method == "initialize":
                if self.initialize_sent:
                    raise RpcError(-32600, "This connection has already been initialized")
                info = params.get("clientInfo")
                if (not isinstance(params.get("protocolVersion"), str)
                        or not isinstance(params.get("capabilities"), dict)
                        or not isinstance(info, dict)
                        or not isinstance(info.get("name"), str)
                        or not isinstance(info.get("version"), str)):
                    raise RpcError(-32602, "initialize requires protocolVersion, capabilities and clientInfo")
                self.initialize_sent = True
                result = {
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": {"tools": {"listChanged": False}},
                    "serverInfo": {"name": "starbridge", "version": SERVER_VERSION},
                    "instructions": "Use only ships authorized by the game service. Navigation is asynchronous; query status for arrival. Check status after timeouts before retrying commands.",
                }
            elif not self.initialized:
                raise RpcError(-32002, "Server initialization is not complete")
            elif method == "tools/list":
                if params.get("cursor") is not None:
                    raise RpcError(-32602, "This server does not issue pagination cursors")
                result = {"tools": TOOLS}
            elif method == "tools/call":
                name = params.get("name")
                if not isinstance(name, str) or name not in TOOL_NAMES:
                    raise RpcError(-32602, "Unknown tool")
                arguments = params.get("arguments", {})
                if not isinstance(arguments, dict):
                    raise RpcError(-32602, "Tool arguments must be an object")
                try:
                    data = self.client.call_tool(name, arguments)
                    structured = data if isinstance(data, dict) else {"data": data}
                    result = {"content": [{"type": "text", "text": json.dumps(structured, allow_nan=False)}],
                              "structuredContent": structured, "isError": False}
                except ToolError as failure:
                    result = {"content": [{"type": "text", "text": failure.message}], "isError": True,
                              "structuredContent": {"error": {"code": failure.code, "message": failure.message}}}
            else:
                raise RpcError(-32601, "Method not found")
            return {"jsonrpc": "2.0", "id": identifier, "result": result}
        except RpcError as failure:
            return None if notification else self.error(identifier, failure.code, failure.message)
        except Exception:
            # Do not leak backend or token-bearing exception details onto the protocol stream.
            return None if notification else self.error(identifier, -32603, "Internal bridge error")


def serve(bridge: McpBridge, source: BinaryIO, destination: BinaryIO) -> int:
    def send(message: dict[str, Any]) -> None:
        destination.write(json.dumps(message, allow_nan=False, separators=(",", ":")).encode("utf-8") + b"\n")
        destination.flush()

    while True:
        line = source.readline(MAX_REQUEST_BYTES + 1)
        if not line:
            return 0
        if len(line) > MAX_REQUEST_BYTES:
            send(bridge.error(None, -32600, "JSON-RPC request exceeds the size limit"))
            return 2
        if not line.strip():
            continue
        try:
            message = strict_json_loads(line.decode("utf-8"))
        except (ValueError, UnicodeError, RecursionError):
            send(bridge.error(None, -32700, "Parse error"))
            continue
        response = bridge.handle(message)
        if response is not None:
            send(response)


class _SafeArgumentParser(argparse.ArgumentParser):
    def error(self, _message: str) -> None:
        # argparse normally echoes invalid argument values, which may accidentally be credentials.
        self.print_usage(sys.stderr)
        self.exit(2, "Starbridge MCP: invalid command-line arguments; run --help.\n")


def main(argv: list[str] | None = None) -> int:
    parser = _SafeArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("STARBRIDGE_API_URL", DEFAULT_BASE_URL))
    parser.add_argument("--token-file", help="UTF-8 token file; STARBRIDGE_TOKEN takes precedence")
    parser.add_argument("--timeout", type=float, default=5.0, help="HTTP socket timeout in seconds, greater than 0 and at most 30")
    arguments = parser.parse_args(argv)
    try:
        token = load_token(os.environ, arguments.token_file)
        client = StarbridgeClient(arguments.base_url, token, arguments.timeout)
    except ValueError as failure:
        print(f"Starbridge MCP configuration error: {failure}", file=sys.stderr)
        return 2
    try:
        return serve(McpBridge(client), sys.stdin.buffer, sys.stdout.buffer)
    except (BrokenPipeError, KeyboardInterrupt):
        return 0
    except OSError:
        print("Starbridge MCP transport failed", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
