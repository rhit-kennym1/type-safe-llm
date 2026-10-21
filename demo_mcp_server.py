"""A tiny dependency-free MCP server for demos.

Run it over stdio:
    python demo_mcp_server.py

The server implements the small MCP surface needed by most clients:
initialize, notifications/initialized, tools/list, tools/call, and ping.
"""

from __future__ import annotations

import json
import sys
from typing import Any

from pydantic import BaseModel, Field, field_validator


SERVER_INFO = {"name": "demo-mcp-server", "version": "0.1.0"}


class GreetArguments(BaseModel):
    name: str = Field(description="Name to greet")

    @field_validator("name")
    @classmethod
    def name_must_not_be_blank(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError("name must be a non-empty string")
        return value


class AddArguments(BaseModel):
    a: float = Field(description="First number")
    b: float = Field(description="Second number")


TOOLS = [
    {
        "name": "greet",
        "description": "Return a friendly greeting for a person.",
        "inputSchema": GreetArguments.model_json_schema(),
    },
    {
        "name": "add",
        "description": "Add two numbers together.",
        "inputSchema": AddArguments.model_json_schema(),
    },
]


def _write_message(message: dict[str, Any]) -> None:
    payload = json.dumps(message).encode("utf-8")
    header = f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii")
    # MCP framing is byte-based. Using the binary stream avoids newline
    # translation on Windows, which would corrupt the required CRLF header.
    sys.stdout.buffer.write(header + payload)
    sys.stdout.buffer.flush()


def response(request_id: Any, result: dict[str, Any]) -> None:
    _write_message({"jsonrpc": "2.0", "id": request_id, "result": result})


def error(request_id: Any, code: int, message: str) -> None:
    _write_message({"jsonrpc": "2.0", "id": request_id, "error": {"code": code, "message": message}})


def call_tool(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    if name == "greet":
        person = GreetArguments.model_validate(arguments).name
        text = f"Hello, {person}! Welcome to the demo MCP server."
    elif name == "add":
        values = AddArguments.model_validate(arguments)
        a, b = values.a, values.b
        text = f"{a} + {b} = {a + b}"
    else:
        raise LookupError(f"Unknown tool: {name}")
    return {"content": [{"type": "text", "text": text}]}


def handle(message: dict[str, Any]) -> None:
    method = message.get("method")
    request_id = message.get("id")
    if request_id is None:
        return  # Notifications do not receive a response.
    if method == "initialize":
        response(
            request_id,
            {
                "protocolVersion": message.get("params", {}).get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {}},
                "serverInfo": SERVER_INFO,
            },
        )
    elif method == "ping":
        response(request_id, {})
    elif method == "tools/list":
        response(request_id, {"tools": TOOLS})
    elif method == "tools/call":
        params = message.get("params", {})
        try:
            response(request_id, call_tool(params.get("name", ""), params.get("arguments", {})))
        except LookupError as exc:
            error(request_id, -32601, str(exc))
        except (TypeError, ValueError) as exc:
            response(request_id, {"isError": True, "content": [{"type": "text", "text": str(exc)}]})
    else:
        error(request_id, -32601, f"Method not found: {method}")


def main() -> None:
    while True:
        line = sys.stdin.buffer.readline()
        if not line:
            break
        if not line.strip():
            continue
        headers = line.decode("ascii").strip().split(":", 1)
        if len(headers) != 2 or headers[0].lower() != "content-length":
            continue
        length = int(headers[1].strip())
        # Consume the blank line separating headers and the JSON body.
        sys.stdin.buffer.readline()
        body = sys.stdin.buffer.read(length)
        handle(json.loads(body))


if __name__ == "__main__":
    main()
