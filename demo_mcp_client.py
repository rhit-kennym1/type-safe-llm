"""Interactive terminal client for the demo MCP server.

Run with:
    python demo_mcp_client.py
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any


PROJECT_ROOT = Path(__file__).resolve().parent
SERVER_PATH = PROJECT_ROOT / "demo_mcp_server.py"


def frame(message: dict[str, Any]) -> bytes:
    body = json.dumps(message).encode("utf-8")
    return b"Content-Length: " + str(len(body)).encode("ascii") + b"\r\n\r\n" + body


def read_message(stream: Any) -> dict[str, Any]:
    headers: dict[str, str] = {}
    while True:
        line = stream.readline()
        if not line:
            raise RuntimeError("MCP server closed the connection")
        if line in (b"\r\n", b"\n"):
            break
        name, value = line.decode("ascii").split(":", 1)
        headers[name.lower()] = value.strip()

    length = int(headers["content-length"])
    return json.loads(stream.read(length))


class DemoMCPClient:
    def __init__(self) -> None:
        self.process = subprocess.Popen(
            [sys.executable, str(SERVER_PATH)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        assert self.process.stdin is not None
        assert self.process.stdout is not None
        self.stdin = self.process.stdin
        self.stdout = self.process.stdout

    def request(self, request_id: int, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        message: dict[str, Any] = {"jsonrpc": "2.0", "id": request_id, "method": method}
        if params is not None:
            message["params"] = params
        self.stdin.write(frame(message))
        self.stdin.flush()
        return read_message(self.stdout)

    def close(self) -> None:
        self.stdin.close()
        self.process.wait(timeout=2)
        self.stdout.close()
        if self.process.stderr is not None:
            self.process.stderr.close()


def print_result(result: dict[str, Any]) -> None:
    if "error" in result:
        print(f"[error] {result['error']['message']}")
        return
    if result.get("result", {}).get("isError"):
        print(f"[tool error] {result['result']['content'][0]['text']}")
        return
    text = result.get("result", {}).get("content", [{}])[0].get("text")
    print(f"[server] {text if text is not None else result.get('result', {})}")


def main() -> None:
    print("Starting demo MCP server...", flush=True)
    try:
        client = DemoMCPClient()
    except OSError as exc:
        print(f"Could not start demo MCP server: {exc}", flush=True)
        raise SystemExit(1) from exc
    try:
        initialized = client.request(
            1,
            "initialize",
            {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "demo-client", "version": "0.1.0"}},
        )
        print(f"Connected to {initialized['result']['serverInfo']['name']}.", flush=True)
        tools = client.request(2, "tools/list")["result"]["tools"]
        print("Available commands: greet <name>, add <number> <number>, help, quit", flush=True)
        print("Tools:", ", ".join(tool["name"] for tool in tools), flush=True)

        request_id = 3
        while True:
            try:
                command = input("mcp> ").strip()
            except EOFError:
                break
            if not command:
                continue
            if command.lower() in {"quit", "exit"}:
                break
            if command.lower() == "help":
                print("Type 'greet Ada' or 'add 2 3'. Type 'quit' to exit.")
                continue

            parts = command.split(maxsplit=2)
            if parts[0].lower() == "greet" and len(parts) == 2:
                params = {"name": parts[1]}
                tool = "greet"
            elif parts[0].lower() == "add" and len(parts) == 3:
                try:
                    a, b = float(parts[1]), float(parts[2])
                except ValueError:
                    print("Usage: add <number> <number>")
                    continue
                params = {"a": a, "b": b}
                tool = "add"
            else:
                print("Unknown command. Try: greet <name>, add <number> <number>, help, quit")
                continue

            print_result(client.request(request_id, "tools/call", {"name": tool, "arguments": params}))
            request_id += 1
    finally:
        client.close()


if __name__ == "__main__":
    main()
