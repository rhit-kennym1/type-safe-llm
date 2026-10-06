import asyncio
import json
import os

import pytest

from type_safe_llm import providers
from type_safe_llm.core import generate_validated
from type_safe_llm.fixtures import EXAMPLES, SOURCES
from type_safe_llm.live import clean_schema, run_live, run_tool_calling
from type_safe_llm.providers import LiveConfig, ProviderError, chat_completion, chat_provider


def test_config_defaults_and_overrides():
    assert LiveConfig.from_environment({}).base_url == "http://localhost:11434/v1"
    config = LiveConfig.from_environment({"LIVE_MODEL": "m", "LIVE_BASE_URL": "http://x/v1", "LIVE_API_KEY": "k"})
    assert (config.model, config.base_url, config.api_key) == ("m", "http://x/v1", "k")


def test_dotenv_loads_without_overriding_real_env(tmp_path, monkeypatch):
    env_file = tmp_path / ".env"
    env_file.write_text('# comment\nLIVE_MODEL="from-file"\nLIVE_API_KEY=file-key\n\nLIVE_BASE_URL = http://x/v1\n')
    monkeypatch.delenv("LIVE_MODEL", raising=False)
    monkeypatch.delenv("LIVE_BASE_URL", raising=False)
    monkeypatch.setenv("LIVE_API_KEY", "shell-key")
    providers.load_dotenv([env_file])
    config = LiveConfig.from_environment()
    assert (config.model, config.base_url, config.api_key) == ("from-file", "http://x/v1", "shell-key")
    for name in ("LIVE_MODEL", "LIVE_BASE_URL"):  # keep the test's changes from leaking
        monkeypatch.delenv(name, raising=False)


def test_provider_sends_openai_request(fake_endpoint):
    config, replies, seen = fake_endpoint
    replies.append("hello")
    assert chat_provider(config)("hi") == "hello"
    assert seen[0]["path"] == "/v1/chat/completions" and seen[0]["auth"] == "Bearer ollama"
    assert seen[0]["body"]["messages"] == [{"role": "user", "content": "hi"}]


def test_unreachable_provider_hides_details():
    generate = chat_provider(LiveConfig(base_url="http://127.0.0.1:1/v1", timeout_seconds=1))
    with pytest.raises(ProviderError):
        generate("hi")
    result = generate_validated("event", SOURCES["event"], generate, max_retries=0)
    assert result.status == "provider_error"


def test_host_driven_retry_over_real_mcp(fake_endpoint):
    config, replies, _ = fake_endpoint
    replies += ['{"title": ', json.dumps(EXAMPLES["event"])]
    result = asyncio.run(run_live("event", SOURCES["event"], chat_provider(config), max_retries=2))
    assert result.ok and result.status == "validated" and len(result.attempts) == 2
    assert result.attempts[0].result.errors[0].code == "json_invalid"
    assert result.attempts[0].repair_prompt and "VALIDATION ERRORS" in result.attempts[0].repair_prompt


def test_host_driven_retries_exhausted(fake_endpoint):
    config, replies, _ = fake_endpoint
    replies += ["nope", "still nope"]
    result = asyncio.run(run_live("event", SOURCES["event"], chat_provider(config), max_retries=1))
    assert result.status == "retries_exhausted" and result.data is None and len(result.attempts) == 2


def tool_call(arguments, name="validate_output", call_id="call_1"):
    return {"role": "assistant", "content": "", "tool_calls": [{
        "id": call_id, "type": "function",
        "function": {"name": name, "arguments": arguments if isinstance(arguments, str) else json.dumps(arguments)},
    }]}


def run_tools(config, **kwargs):
    chat = lambda messages, tools: chat_completion(config, messages, tools)  # noqa: E731
    return asyncio.run(run_tool_calling("event", SOURCES["event"], chat, **kwargs))


GOOD = json.dumps(EXAMPLES["event"])


def test_tool_calling_model_validates_then_answers(fake_endpoint):
    config, replies, seen = fake_endpoint
    replies += [tool_call({"schema_name": "event", "content": GOOD}), GOOD]
    result = run_tools(config)
    assert result.status == "validated" and result.model_called_validate
    assert [(r.name, r.validation_ok) for r in result.tool_calls] == [("validate_output", True)]
    sent_tools = seen[0]["body"]["tools"]
    assert [t["function"]["name"] for t in sent_tools] == ["validate_output"]
    assert "title" not in json.dumps(sent_tools[0]["function"]["parameters"]["properties"]["content"])
    follow_up = seen[1]["body"]["messages"]
    assert follow_up[-1]["role"] == "tool" and follow_up[-1]["tool_call_id"] == "call_1"
    assert json.loads(follow_up[-1]["content"])["ok"] is True


def test_tool_calling_recovers_after_failed_validation(fake_endpoint):
    config, replies, _ = fake_endpoint
    bad = json.dumps({**EXAMPLES["event"], "attendee_count": "30"})
    replies += [tool_call({"schema_name": "event", "content": bad}),
                tool_call({"schema_name": "event", "content": GOOD}, call_id="call_2"), GOOD]
    result = run_tools(config)
    assert result.status == "validated"
    assert [r.validation_ok for r in result.tool_calls] == [False, True]


def test_tool_calling_model_skips_validation_but_host_still_checks(fake_endpoint):
    config, replies, _ = fake_endpoint
    replies += [GOOD]
    result = run_tools(config)
    assert result.status == "validated" and result.model_called_validate is False

    replies += ["I could not extract that."]
    result = run_tools(config)
    assert result.status == "invalid_final" and not result.ok and result.errors


def test_tool_calling_survives_bad_arguments_and_unknown_tools(fake_endpoint):
    config, replies, seen = fake_endpoint
    replies += [tool_call("{not json"), tool_call({}, name="nonexistent", call_id="call_2"),
                tool_call({"schema_name": "nope", "content": GOOD}, call_id="call_3"), GOOD]
    result = run_tools(config, max_retries=2)
    assert [r.arguments_valid for r in result.tool_calls] == [False, True, False]
    assert result.status == "validated"


def test_tool_calling_step_limit(fake_endpoint):
    config, replies, _ = fake_endpoint
    replies += [tool_call({"schema_name": "event", "content": "x"}, call_id=f"c{i}") for i in range(2)]
    result = run_tools(config, max_retries=0)
    assert result.status == "step_limit" and result.model_calls == 2 and not result.ok


def test_rate_limit_is_retried_and_errors_are_explained(fake_endpoint, monkeypatch):
    config, replies, _ = fake_endpoint
    monkeypatch.setattr(providers, "_sleep", lambda seconds: None)
    replies += [429, "ok"]
    assert chat_provider(config)("hi") == "ok"
    replies += [429, 429, 429]
    with pytest.raises(ProviderError, match="rate limited"):
        chat_provider(config)("hi")
    replies += [400]
    with pytest.raises(ProviderError, match="may not support tool calling"):
        chat_completion(config, [{"role": "user", "content": "hi"}], [{"type": "function"}])


def test_clean_schema_removes_titles_only():
    assert clean_schema({"title": "T", "properties": {"title": {"title": "x", "type": "string"}}}) == {
        "properties": {"title": {"type": "string"}}}


@pytest.mark.skipif(os.environ.get("TYPED_LLM_LIVE") != "1", reason="set TYPED_LLM_LIVE=1 with a model running")
@pytest.mark.parametrize("schema_name", ["event", "contract"])
def test_live_model(schema_name):
    config = LiveConfig.from_environment()
    result = asyncio.run(run_live(schema_name, SOURCES[schema_name], chat_provider(config), max_retries=3))
    print(result.status, [a.result.errors for a in result.attempts])
    assert result.status in {"validated", "retries_exhausted"}  # a small model may legitimately fail
