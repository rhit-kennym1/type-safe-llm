"""Local browser UI that talks to the demo through the real MCP server."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


WEB_ROOT = Path(__file__).resolve().parents[2] / "web"


async def mcp_call(tool_name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    params = StdioServerParameters(command=sys.executable, args=["-m", "type_safe_llm.server"])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=30)) as session:
            await session.initialize()
            result = await session.call_tool(tool_name, arguments)
            if result.isError or result.structuredContent is None:
                raise RuntimeError(f"MCP tool {tool_name} failed")
            return result.structuredContent


async def answer_question(question: str) -> dict[str, Any]:
    """Route supported demo questions to MCP tools and return a chat-friendly result."""
    text = question.strip()
    lowered = text.lower()
    if not text:
        return {"ok": False, "message": "Type a question first."}

    if any(word in lowered for word in ("schema", "what can", "capabilit", "help")):
        catalog = await mcp_call("list_schemas", {})
        names = ", ".join(catalog["schemas"])
        return {
            "ok": True,
            "message": f"The MCP server currently supports the {names} schemas. I can validate sample JSON or demonstrate bounded repair retries.",
            "data": {name: {"example": value["example"], "fields": list(value["json_schema"].get("properties", {}))} for name, value in catalog["schemas"].items()},
        }

    schema = "contract" if "contract" in lowered else "event"
    if "retry" in lowered or "recover" in lowered or "repair" in lowered:
        scenario = "exhausted" if any(word in lowered for word in ("exhaust", "fail", "never")) else "wrong_type"
        result = await mcp_call("run_retry_demo", {"schema_name": schema, "scenario": scenario, "max_retries": 2})
        return {"ok": result["ok"], "message": f"I ran the {scenario} retry scenario for the {schema} schema. The result is {result['status']}.", "data": result}

    if "valid" in lowered or "validate" in lowered or "check" in lowered:
        catalog = await mcp_call("list_schemas", {})
        example = catalog["schemas"][schema]["example"]
        if any(word in lowered for word in ("wrong", "invalid", "error", "bad")):
            field = "attendee_count" if schema == "event" else "payment_days"
            example[field] = "not a number"
        result = await mcp_call("validate_output", {"schema_name": schema, "content": json.dumps(example)})
        status = "validated successfully" if result["ok"] else "rejected with field-level errors"
        return {"ok": result["ok"], "message": f"The {schema} payload was {status}.", "data": result}

    return {
        "ok": True,
        "message": "I can use the MCP server to list schemas, validate an event or contract, and demonstrate retry recovery. Try one of the suggested questions below.",
        "suggestions": [
            "What schemas can you validate?",
            "Validate the event example",
            "Show me a wrong-type retry demo",
            "Show me an exhausted contract retry",
        ],
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, payload: dict[str, Any], content_type: str = "application/json") -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", f"{content_type}; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        if urlparse(self.path).path == "/api/health":
            self._send(200, {"ok": True, "service": "type-safe-llm-web-bridge"})
            return
        if urlparse(self.path).path not in ("/", "/index.html"):
            self._send(404, {"ok": False, "message": "Not found"})
            return
        body = (WEB_ROOT / "index.html").read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802
        if urlparse(self.path).path != "/api/ask":
            self._send(404, {"ok": False, "message": "Not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length))
            result = asyncio.run(answer_question(str(payload.get("question", ""))))
            self._send(200, result)
        except Exception as exc:  # Keep protocol errors readable for this local demo.
            self._send(500, {"ok": False, "message": f"The MCP request failed: {exc}"})

    def log_message(self, format: str, *args: Any) -> None:
        print(f"[web] {format % args}", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the local Type-Safe LLM MCP website")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Type-Safe LLM web demo: http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping web demo.", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
