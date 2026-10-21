import json
import subprocess
import sys
import unittest
from pathlib import Path

import demo_mcp_server as server
from demo_mcp_client import DemoMCPClient


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SERVER_PATH = PROJECT_ROOT / "demo_mcp_server.py"


def decode_frames(data: bytes) -> list[dict]:
    """Decode Content-Length framed JSON-RPC messages."""
    messages = []
    offset = 0
    while offset < len(data):
        header_end = data.index(b"\r\n\r\n", offset)
        header = data[offset:header_end].decode("ascii")
        length = int(header.split(":", 1)[1].strip())
        body_start = header_end + 4
        body_end = body_start + length
        messages.append(json.loads(data[body_start:body_end]))
        offset = body_end
    return messages


def frame(message: dict) -> bytes:
    body = json.dumps(message).encode("utf-8")
    return b"Content-Length: " + str(len(body)).encode("ascii") + b"\r\n\r\n" + body


class DemoToolTests(unittest.TestCase):
    def test_greet_strips_whitespace(self):
        result = server.call_tool("greet", {"name": "  Ada  "})
        self.assertEqual(
            result["content"][0]["text"],
            "Hello, Ada! Welcome to the demo MCP server.",
        )

    def test_add_returns_sum(self):
        result = server.call_tool("add", {"a": 2.5, "b": 4})
        self.assertEqual(result["content"][0]["text"], "2.5 + 4.0 = 6.5")

    def test_blank_greeting_name_is_rejected(self):
        with self.assertRaises(ValueError):
            server.call_tool("greet", {"name": "   "})

    def test_unknown_tool_is_rejected(self):
        with self.assertRaises(LookupError):
            server.call_tool("missing", {})


class DemoProtocolTests(unittest.TestCase):
    def test_real_process_handles_initialize_and_tool_call(self):
        requests = b"".join(
            [
                frame(
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "initialize",
                        "params": {"protocolVersion": "2024-11-05"},
                    }
                ),
                frame(
                    {
                        "jsonrpc": "2.0",
                        "id": 2,
                        "method": "tools/call",
                        "params": {"name": "add", "arguments": {"a": 7, "b": 5}},
                    }
                ),
            ]
        )

        completed = subprocess.run(
            [sys.executable, str(SERVER_PATH)],
            input=requests,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=True,
        )

        messages = decode_frames(completed.stdout)
        self.assertEqual(messages[0]["result"]["serverInfo"], server.SERVER_INFO)
        self.assertEqual(messages[1]["id"], 2)
        self.assertEqual(messages[1]["result"]["content"][0]["text"], "7.0 + 5.0 = 12.0")

    def test_interactive_client_can_call_greet(self):
        client = DemoMCPClient()
        try:
            client.request(1, "initialize", {"protocolVersion": "2024-11-05"})
            result = client.request(
                2,
                "tools/call",
                {"name": "greet", "arguments": {"name": "Grace"}},
            )
            self.assertEqual(
                result["result"]["content"][0]["text"],
                "Hello, Grace! Welcome to the demo MCP server.",
            )
        finally:
            client.close()


if __name__ == "__main__":
    unittest.main()
