import asyncio

import pytest

from type_safe_llm.demo import exercise_server
from type_safe_llm.server import run_retry_demo, validate_output


def test_real_stdio_mcp_round_trip():
    results = asyncio.run(exercise_server())
    assert set(results["tools"]) == {"list_schemas", "validate_output", "run_retry_demo"}
    assert all(results["output_schemas"].values())
    assert results["valid"]["ok"] is True
    assert results["invalid"]["ok"] is False and results["invalid"]["data"] is None
    assert results["invalid"]["errors"][0]["path"] == "attendee_count"
    assert results["recovered"]["ok"] and len(results["recovered"]["attempts"]) == 2
    assert results["contract"]["ok"]
    assert results["exhausted"]["status"] == "retries_exhausted"
    assert results["exhausted"]["data"] is None and len(results["exhausted"]["attempts"]) == 2
    assert results["guide_available"] and results["prompt_available"]


@pytest.mark.parametrize("scenario", ["valid", "wrong_type", "missing_field", "malformed_json", "domain_error"])
def test_each_retry_scenario_has_an_explainable_result(scenario):
    result = run_retry_demo(schema_name="event", scenario=scenario, max_retries=2)
    assert result.status == "validated"
    assert result.ok is True
    assert result.data is not None
    assert result.attempts
    if scenario == "valid":
        assert len(result.attempts) == 1
    else:
        assert len(result.attempts) == 2
        assert result.attempts[0].result.ok is False
        assert result.attempts[0].repair_prompt is not None


def test_validate_output_exposes_field_level_error_for_mcp_users():
    result = validate_output(
        "event",
        '{"title":"Workshop","starts_at":"2026-10-05T14:00:00Z",'
        '"attendee_count":"30","category":"workshop",'
        '"location":{"name":"Hall","city":"Indianapolis"}}',
    )
    assert result.ok is False
    assert result.data is None
    assert [(error.path, error.code) for error in result.errors] == [("attendee_count", "int_type")]


def test_exhausted_retry_never_exposes_invalid_data():
    result = run_retry_demo(schema_name="contract", scenario="exhausted", max_retries=1)
    assert result.status == "retries_exhausted"
    assert result.ok is False
    assert result.data is None
    assert len(result.attempts) == 2
    assert all(attempt.result.ok is False for attempt in result.attempts)
