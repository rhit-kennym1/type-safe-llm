import http.client
import json
import threading

import pytest

from type_safe_llm.fixtures import EXAMPLES, SOURCES
from type_safe_llm.schemas import Event
from type_safe_llm.web import describe_schema, explain, make_server
from type_safe_llm.core import Issue

GOOD = json.dumps(EXAMPLES["event"])


@pytest.fixture
def web():
    server = make_server(0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield server.server_address[1]
    server.shutdown()
    server.server_close()


def call(port, method, path, body=None, headers=None):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=120)
    send = {"Content-Type": "application/json", **(headers or {})} if method == "POST" else dict(headers or {})
    connection.request(method, path, json.dumps(body) if body is not None else None, send)
    response = connection.getresponse()
    raw = response.read()
    connection.close()
    is_json = response.getheader("Content-Type", "").startswith("application/json")
    return response.status, json.loads(raw) if is_json else raw.decode(), response


def tool_call(arguments):
    return {"role": "assistant", "content": "", "tool_calls": [{
        "id": "c1", "type": "function",
        "function": {"name": "validate_output", "arguments": json.dumps(arguments)}}]}


def use_endpoint(monkeypatch, config, key="sekret-key"):
    monkeypatch.setenv("LIVE_BASE_URL", config.base_url)
    monkeypatch.setenv("LIVE_MODEL", "fake")
    monkeypatch.setenv("LIVE_API_KEY", key)


# ---------- static files and descriptions ----------

def test_serves_page_and_only_whitelisted_files(web):
    status, body, response = call(web, "GET", "/")
    assert status == 200 and "<title>Type-Safe LLM" in body
    assert response.getheader("X-Content-Type-Options") == "nosniff"
    assert call(web, "GET", "/app.js")[0] == 200
    assert call(web, "GET", "/vendor/pico.min.css")[0] == 200
    for path in ["/../pyproject.toml", "/..%2f..%2fpyproject.toml", "/web.py", "/.env", "/static/app.js"]:
        assert call(web, "GET", path)[0] == 404


def test_schema_description_is_plain_and_complete(web):
    status, schemas, _ = call(web, "GET", "/api/schemas")
    assert status == 200 and {s["name"] for s in schemas} == {"event", "contract"}
    fields = {f["name"]: f for f in describe_schema("event")["fields"]}
    assert fields["attendee_count"] == {"name": "attendee_count", "type": "whole number", "required": True}
    assert fields["category"]["type"] == "one of: meeting, workshop, conference"
    assert fields["starts_at"]["type"] == "date and time" and fields["notes"]["required"] is False
    assert fields["location"]["type"] == "group (name, city)" and fields["tags"]["type"] == "list of text"
    assert "class Event" in describe_schema("event")["code"] and Event.__name__ == "Event"


def test_explanations():
    issues = [Issue(path="attendee_count", code="int_type", message="x"), Issue(path="title", code="missing", message="x"),
              Issue(path="$", code="json_invalid", message="x"), Issue(path="mood", code="extra_forbidden", message="x")]
    text = explain(issues)
    assert "attendee_count has the wrong type" in text and "Missing title" in text
    assert "Not valid JSON" in text and "didn't ask for: mood" in text
    assert explain([]) is None


# ---------- security checks ----------

def test_rejects_foreign_host_and_wrong_content_type(web):
    assert call(web, "GET", "/api/schemas", headers={"Host": "evil.example"})[0] == 403
    assert call(web, "POST", "/api/live/run", {}, headers={"Host": "evil.example"})[0] == 403
    status, _, _ = call(web, "POST", "/api/live/run", {}, headers={"Content-Type": "text/plain"})
    assert status == 415


def test_input_validation(web):
    assert call(web, "POST", "/api/scenarios/nope/run", {})[0] == 404
    assert call(web, "POST", "/api/unknown", {})[0] == 404
    for body in [{"schema": "x", "mode": "host", "source": "s"}, {"schema": "event", "mode": "x", "source": "s"},
                 {"schema": "event", "mode": "host", "source": "  "},
                 {"schema": "event", "mode": "host", "source": "a" * 2001}, {}]:
        assert call(web, "POST", "/api/live/run", body)[0] == 400
    connection = http.client.HTTPConnection("127.0.0.1", web, timeout=10)
    connection.request("POST", "/api/live/run", "{not json", {"Content-Type": "application/json"})
    assert connection.getresponse().status == 400
    connection.close()


# ---------- examples through a real MCP session ----------

def test_example_recovers_after_wrong_type(web):
    status, view, _ = call(web, "POST", "/api/scenarios/event-wrong_type/run", {})
    assert status == 200 and view["mode"] == "examples"
    first, second = view["steps"]
    assert first["accepted"] is False and "attendee_count has the wrong type" in first["problem"]
    assert first["output"] and first["repair_prompt"] and second["accepted"] is True
    assert view["outcome"]["type"] == "success" and view["outcome"]["value"]["attendee_count"] == 30


def test_example_exhausted_returns_no_data(web):
    status, view, _ = call(web, "POST", "/api/scenarios/event-exhausted/run", {})
    assert status == 200 and view["outcome"]["type"] == "failure" and "value" not in view["outcome"]
    assert len(view["steps"]) == 3 and not any(s["accepted"] for s in view["steps"])


# ---------- live modes against a fake model ----------

def test_live_host_mode(web, fake_endpoint, monkeypatch):
    config, replies, _ = fake_endpoint
    use_endpoint(monkeypatch, config)
    replies += ['{"title": ', GOOD]
    status, view, _ = call(web, "POST", "/api/live/run", {"schema": "event", "mode": "host", "source": SOURCES["event"]})
    assert status == 200 and view["mode"] == "host" and view["outcome"]["type"] == "success"
    assert view["steps"][0]["problem"] == "Not valid JSON" and view["steps"][0]["output"] == '{"title": '
    assert view["meta"] is None


def test_live_tools_mode_reports_whether_the_model_validated(web, fake_endpoint, monkeypatch):
    config, replies, _ = fake_endpoint
    use_endpoint(monkeypatch, config)
    body = {"schema": "event", "mode": "tools", "source": SOURCES["event"]}
    replies += [tool_call({"schema_name": "event", "content": GOOD}), GOOD]
    _, view, _ = call(web, "POST", "/api/live/run", body)
    assert view["meta"] == {"model_called_validate": True, "model_calls": 2}
    assert [s["accepted"] for s in view["steps"]] == [True, True] and view["outcome"]["type"] == "success"

    replies += ["no thanks"]
    _, view, _ = call(web, "POST", "/api/live/run", body)
    assert view["meta"]["model_called_validate"] is False and view["outcome"]["type"] == "failure"
    assert view["steps"][-1]["problem"] == "Not valid JSON"


def test_provider_failure_is_a_clean_502_and_never_leaks_the_key(web, monkeypatch):
    monkeypatch.setenv("LIVE_BASE_URL", "http://127.0.0.1:1/v1")
    monkeypatch.setenv("LIVE_API_KEY", "sekret-key")
    monkeypatch.setenv("LIVE_MODEL", "fake")
    status, body, _ = call(web, "POST", "/api/live/run", {"schema": "event", "mode": "host", "source": "x"})
    assert status == 502 and "sekret" not in json.dumps(body) and "Could not get a response" in body["error"]
    config = call(web, "GET", "/api/live/config")[1]
    assert set(config) == {"model", "base_url"}
