import copy
import json

import pytest

from type_safe_llm.core import generate_validated, validate_json
from type_safe_llm.fixtures import EXAMPLES


@pytest.mark.parametrize("schema_name", ["event", "contract"])
def test_valid_nested_schema(schema_name):
    result = validate_json(schema_name, json.dumps(EXAMPLES[schema_name]))
    assert result.ok and result.data is not None and result.errors == []


@pytest.mark.parametrize("field,value,code", [
    ("attendee_count", "30", "int_type"),
    ("attendee_count", True, "int_type"),
    ("attendee_count", -1, "greater_than_equal"),
    ("category", "party", "literal_error"),
    ("starts_at", "not-a-date", "datetime_parsing"),
    ("starts_at", "2026-10-05T14:00:00", "timezone_aware"),
    ("tags", [42], "string_type"),
    ("unexpected", 42, "extra_forbidden"),
    ("title", "   ", "string_too_short"),
])
def test_invalid_fields_never_return_data(field, value, code):
    payload = {**EXAMPLES["event"], field: value}
    result = validate_json("event", json.dumps(payload))
    assert not result.ok and result.data is None
    assert code in [error.code for error in result.errors]


@pytest.mark.parametrize("content,code", [
    ("", "empty_response"), ("   ", "empty_response"),
    ('{"title":', "json_invalid"), ('```json\n{}\n```', "json_invalid"),
    ("{}", "missing"), ("null", "model_type"),
])
def test_parse_and_missing_errors(content, code):
    result = validate_json("event", content)
    assert not result.ok and result.data is None
    assert code in [error.code for error in result.errors]


def test_nested_error_path_and_contract_date_order():
    event = copy.deepcopy(EXAMPLES["event"])
    event["location"]["city"] = 123
    assert validate_json("event", json.dumps(event)).errors[0].path == "location.city"
    contract = {**EXAMPLES["contract"], "expiration_date": "2025-01-01"}
    assert validate_json("contract", json.dumps(contract)).errors[0].code == "value_error"


def test_retry_passes_errors_and_schema_to_provider():
    prompts = []

    def provider(prompt):
        prompts.append(prompt)
        return '{}' if len(prompts) == 1 else json.dumps(EXAMPLES["event"])

    result = generate_validated("event", "Example source", provider, max_retries=1)
    assert result.ok and result.status == "validated" and len(result.attempts) == 2
    assert "VALIDATION ERRORS" in prompts[1] and '"title"' in prompts[1]
    assert "Example source" in prompts[1] and "SCHEMA:" in prompts[1]


@pytest.mark.parametrize("retries", [0, 1, 5])
def test_retry_budget_and_no_invalid_data(retries):
    calls = []

    def provider(prompt):
        calls.append(prompt)
        return '{}'

    result = generate_validated("event", "source", provider, max_retries=retries)
    assert not result.ok and result.data is None and result.status == "retries_exhausted"
    assert len(calls) == retries + 1
    assert result.attempts[-1].repair_prompt is None


def test_provider_failure_is_distinct_and_redacted():
    def provider(prompt):
        raise TimeoutError("secret-api-key")

    result = generate_validated("event", "source", provider)
    assert result.status == "provider_error" and not result.ok
    assert "secret-api-key" not in result.model_dump_json()


@pytest.mark.parametrize("retries", [-1, 6, True, 1.5])
def test_invalid_retry_budget(retries):
    with pytest.raises(ValueError):
        generate_validated("event", "source", lambda _: '{}', max_retries=retries)


def test_unknown_schema():
    with pytest.raises(ValueError, match="Unknown schema"):
        validate_json("unknown", '{}')
