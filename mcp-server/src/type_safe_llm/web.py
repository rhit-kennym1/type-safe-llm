"""Local web demo, the Python counterpart of the Kotlin demo-ui.

Run: python -m type_safe_llm.web   then open http://localhost:8765

Standard library only. Every validation goes through a real MCP stdio client/server
session. Binds to 127.0.0.1 and never sends API keys to the browser.
"""

import argparse
import asyncio
import inspect
import json
import sys
import threading
import webbrowser
from datetime import timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from .core import Issue
from .fixtures import SOURCES
from .live import run_live, run_tool_calling
from .providers import LiveConfig, chat_completion, chat_provider
from .schemas import SCHEMAS

STATIC_DIR = Path(__file__).parent / "static"
STATIC_FILES = {
    "/": ("index.html", "text/html; charset=utf-8"),
    "/index.html": ("index.html", "text/html; charset=utf-8"),
    "/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/styles.css": ("styles.css", "text/css; charset=utf-8"),
    "/vendor/pico.min.css": ("vendor/pico.min.css", "text/css; charset=utf-8"),
}
MAX_BODY_BYTES = 20_000
MAX_SOURCE_CHARS = 2_000
LIVE_MODES = ("host", "tools")
LIVE_MAX_RETRIES = 2

SCENARIOS = [
    ("event", "valid", "Right the first time", "The first answer fits, so it's used straight away."),
    ("event", "wrong_type", "Wrong type, then fixed", "The attendee count comes back as text. The error goes back to the model."),
    ("event", "missing_field", "Missing field, then fixed", "The title is missing. The error goes back to the model."),
    ("event", "malformed_json", "Broken JSON, then fixed", "The reply isn't even JSON. The model gets another go."),
    ("event", "domain_error", "Breaks a rule, then fixed", "A negative attendee count breaks a declared constraint."),
    ("event", "exhausted", "Never gets it right", "Every answer is wrong, so the program gets an error, not a half-filled object."),
    ("contract", "missing_field", "Nested contract, repaired", "A longer schema with nested clauses and a missing title."),
    ("contract", "exhausted", "Contract never fits", "Retries run out and no data is returned."),
]


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


# ---------- schema and error descriptions ----------

def _type_label(prop: dict[str, Any], defs: dict[str, Any]) -> str:
    if "$ref" in prop:
        return _type_label(defs[prop["$ref"].split("/")[-1]], defs)
    if "anyOf" in prop:
        labels = [_type_label(p, defs) for p in prop["anyOf"] if p.get("type") != "null"]
        return " or ".join(labels)
    if "enum" in prop:
        return "one of: " + ", ".join(map(str, prop["enum"]))
    if "const" in prop:
        return f"exactly {prop['const']}"
    kind = prop.get("type")
    if kind == "array":
        return "list of " + _type_label(prop.get("items", {}), defs)
    if kind == "object":
        return "group (" + ", ".join(prop.get("properties", {})) + ")"
    if kind == "string":
        return {"date-time": "date and time", "date": "date"}.get(prop.get("format", ""), "text")
    return {"integer": "whole number", "number": "number", "boolean": "true or false"}.get(str(kind), "any")


def describe_schema(name: str) -> dict[str, Any]:
    model = SCHEMAS[name]
    schema = model.model_json_schema()
    defs = schema.get("$defs", {})
    required = set(schema.get("required", []))
    return {
        "name": name,
        "title": model.__name__,
        "fields": [{"name": field, "type": _type_label(prop, defs), "required": field in required}
                   for field, prop in schema["properties"].items()],
        "source": SOURCES[name],
        "code": inspect.getsource(model),
        "json_schema": schema,
    }


_EXPLANATIONS = {
    "missing": lambda i: f"Missing {i.path}",
    "extra_forbidden": lambda i: f"Has a field we didn't ask for: {i.path}",
    "json_invalid": lambda i: "Not valid JSON",
    "empty_response": lambda i: "The answer was empty",
    "model_type": lambda i: "Wrong shape: expected a JSON object",
    "literal_error": lambda i: f"{i.path} isn't one of the allowed values",
    "string_too_short": lambda i: f"{i.path} is empty",
    "value_error": lambda i: i.message,
}
_RANGE_CODES = {"greater_than_equal", "greater_than", "less_than_equal", "less_than"}


def explain(errors: list[Issue]) -> str | None:
    """Plain-language summary of validation issues. The exact errors are still shown alongside."""
    parts: list[str] = []
    for issue in errors:
        if issue.code in _EXPLANATIONS:
            text = _EXPLANATIONS[issue.code](issue)
        elif issue.code.endswith("_type"):
            text = f"{issue.path} has the wrong type"
        elif issue.code in _RANGE_CODES:
            text = f"{issue.path} is out of range"
        elif "parsing" in issue.code or issue.code == "timezone_aware":
            text = f"{issue.path} isn't a valid date and time"
        else:
            text = f"{issue.path}: {issue.message}"
        if text not in parts:
            parts.append(text)
    return "; ".join(parts) or None


def _exact(errors: list[Issue]) -> str:
    return "\n".join(f"{e.path}: {e.code}: {e.message}" for e in errors)


# ---------- turning results into the view the page draws ----------

def _issues(raw: list[dict[str, Any]]) -> list[Issue]:
    return [Issue.model_validate(e) for e in raw]


def retry_view(result: dict[str, Any], mode: str) -> dict[str, Any]:
    """View of a RetryResult dict (simulated examples and the host-driven live loop)."""
    steps = []
    for attempt in result["attempts"]:
        errors = _issues(attempt["result"]["errors"])
        steps.append({
            "label": f"Try {attempt['number']}", "output": attempt.get("content") or "",
            "accepted": attempt["result"]["ok"], "problem": explain(errors), "error": _exact(errors) or None,
            "repair_prompt": attempt.get("repair_prompt"),
        })
    if result["ok"]:
        outcome: dict[str, Any] = {"type": "success", "value": result["data"]}
    else:
        outcome = {"type": "failure",
                   "message": f"No usable answer after {len(result['attempts'])} tries."}
    return {"mode": mode, "provider": result["provider"], "steps": steps, "outcome": outcome, "meta": None}


def tool_calling_view(result: dict[str, Any]) -> dict[str, Any]:
    steps = []
    for number, record in enumerate(result["tool_calls"], start=1):
        errors = _issues(record["errors"])
        if record["name"] != "validate_output":
            problem: str | None = f"The model called a tool we don't offer: {record['name']}"
        elif not record["arguments_valid"]:
            problem = "The tool call was malformed or rejected by the server"
        else:
            problem = explain(errors)
        steps.append({
            "label": f"Model called {record['name']} ({number})", "output": record.get("content") or "",
            "accepted": bool(record["validation_ok"]), "problem": problem, "error": _exact(errors) or None,
            "repair_prompt": None,
        })
    final_errors = _issues(result["errors"])
    steps.append({
        "label": "Final answer, checked by the program", "output": result.get("final_text") or "",
        "accepted": result["ok"], "problem": explain(final_errors), "error": _exact(final_errors) or None,
        "repair_prompt": None,
    })
    if result["ok"]:
        outcome: dict[str, Any] = {"type": "success", "value": result["data"]}
    elif result["status"] == "step_limit":
        outcome = {"type": "failure", "message": "The model kept calling tools and never gave a final answer."}
    else:
        outcome = {"type": "failure", "message": "The model's final answer did not fit the fields."}
    return {
        "mode": "tools", "provider": result["provider"], "steps": steps, "outcome": outcome,
        "meta": {"model_called_validate": result["model_called_validate"], "model_calls": result["model_calls"]},
    }


# ---------- running things ----------

def _server_params() -> StdioServerParameters:
    return StdioServerParameters(command=sys.executable, args=["-m", "type_safe_llm.server"])


async def _call_tool(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    async with stdio_client(_server_params()) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=30)) as session:
            await session.initialize()
            result = await session.call_tool(name, arguments)
            if result.isError or result.structuredContent is None:
                raise ApiError(502, f"MCP tool {name} failed.")
            return result.structuredContent


def run_scenario(scenario_id: str) -> dict[str, Any]:
    for schema_name, scenario, _, _ in SCENARIOS:
        if f"{schema_name}-{scenario}" == scenario_id:
            result = asyncio.run(_call_tool("run_retry_demo", {"schema_name": schema_name, "scenario": scenario}))
            return retry_view(result, "examples")
    raise ApiError(404, "Unknown scenario.")


def run_live_request(body: dict[str, Any]) -> dict[str, Any]:
    schema_name, mode, source = body.get("schema"), body.get("mode"), body.get("source")
    if schema_name not in SCHEMAS or mode not in LIVE_MODES:
        raise ApiError(400, "Choose a schema and a mode.")
    if not isinstance(source, str) or not source.strip() or len(source) > MAX_SOURCE_CHARS:
        raise ApiError(400, f"Enter source text under {MAX_SOURCE_CHARS} characters.")
    config = LiveConfig.from_environment()
    if mode == "host":
        result = asyncio.run(run_live(schema_name, source, chat_provider(config),
                                      max_retries=LIVE_MAX_RETRIES, provider=config.model)).model_dump()
        if result["status"] == "provider_error":
            raise ApiError(502, result["error"])
        return retry_view(result, "host")
    tool_result = asyncio.run(run_tool_calling(
        schema_name, source, lambda m, t: chat_completion(config, m, t),
        max_retries=LIVE_MAX_RETRIES, provider=config.model)).model_dump()
    if tool_result["status"] == "provider_error":
        raise ApiError(502, tool_result["error"])
    return tool_calling_view(tool_result)


# ---------- HTTP ----------

class Handler(BaseHTTPRequestHandler):
    server_version = "TypeSafeLlmDemo"

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; style-src 'self' https://fonts.googleapis.com; "
                         "font-src https://fonts.gstatic.com; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(body)

    def _json(self, status: int, payload: Any) -> None:
        self._send(status, json.dumps(payload).encode(), "application/json")

    def _host_allowed(self) -> bool:
        # Blocks DNS-rebinding: a hostile site resolving to 127.0.0.1 would carry its own Host.
        port = self.server.server_address[1]
        return self.headers.get("Host", "") in {f"localhost:{port}", f"127.0.0.1:{port}"}

    def do_GET(self) -> None:  # noqa: N802
        if not self._host_allowed():
            return self._json(403, {"error": "Forbidden host."})
        path = self.path.split("?", 1)[0]
        if path in STATIC_FILES:
            name, content_type = STATIC_FILES[path]
            return self._send(200, (STATIC_DIR / name).read_bytes(), content_type)
        try:
            if path == "/api/schemas":
                return self._json(200, [describe_schema(name) for name in SCHEMAS])
            if path == "/api/scenarios":
                return self._json(200, [
                    {"id": f"{s}-{sc}", "schema": s, "title": title, "description": description}
                    for s, sc, title, description in SCENARIOS])
            if path == "/api/live/config":
                config = LiveConfig.from_environment()
                return self._json(200, {"model": config.model, "base_url": config.base_url})
        except ApiError as error:
            return self._json(error.status, {"error": error.message})
        self._json(404, {"error": "Not found."})

    def do_POST(self) -> None:  # noqa: N802
        if not self._host_allowed():
            return self._json(403, {"error": "Forbidden host."})
        # JSON content type forces a CORS preflight from other origins, which this server never answers.
        if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
            return self._json(415, {"error": "Content-Type must be application/json."})
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= MAX_BODY_BYTES:
                raise ApiError(413, "Request too large.")
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                raise ApiError(400, "Body must be JSON.") from None
            if not isinstance(body, dict):
                raise ApiError(400, "Body must be a JSON object.")
            path = self.path.split("?", 1)[0]
            if path.startswith("/api/scenarios/") and path.endswith("/run"):
                return self._json(200, run_scenario(path[len("/api/scenarios/"):-len("/run")]))
            if path == "/api/live/run":
                return self._json(200, run_live_request(body))
            raise ApiError(404, "Not found.")
        except ApiError as error:
            self._json(error.status, {"error": error.message})
        except Exception:  # noqa: BLE001 - never leak internals or tracebacks to the browser
            self.log_error("Unhandled error in %s", self.path)
            self._json(500, {"error": "Something went wrong running that. See the server console."})


def make_server(port: int) -> ThreadingHTTPServer:
    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def main() -> None:
    parser = argparse.ArgumentParser(description="Local web demo for the Type-Safe LLM MCP server.")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-open", action="store_true", help="Don't open a browser tab")
    args = parser.parse_args()
    server = make_server(args.port)
    url = f"http://localhost:{args.port}"
    print(f"Type-Safe LLM web demo: {url}   (Ctrl+C to stop)", flush=True)
    if not args.no_open:
        threading.Timer(0.5, webbrowser.open, args=(url,)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
