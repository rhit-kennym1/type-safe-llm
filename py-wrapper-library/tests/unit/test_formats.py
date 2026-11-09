import pytest

from typedllm.errors import DuplicateJsonKeyError, OutputParsingError
from typedllm.formats import JSON, get_format


def test_json_is_registered():
    assert get_format("json") is JSON
    assert get_format("JSON") is JSON


def test_unknown_format_is_rejected():
    with pytest.raises(ValueError, match="Unsupported output format"):
        get_format("yaml")


def test_json_format_rejects_duplicate_keys():
    with pytest.raises(DuplicateJsonKeyError):
        JSON.parse('{"name":"Alice","name":"Bob"}')


def test_json_format_rejects_malformed_json():
    with pytest.raises(OutputParsingError):
        JSON.parse('{"name":"Alice"')


def test_json_format_passes_schema_to_native_provider():
    schema = {"type": "object"}

    assert JSON.provider_schema(schema) is schema
