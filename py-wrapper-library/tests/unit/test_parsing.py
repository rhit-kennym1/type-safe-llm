import pytest

from typedllm.core.parsing import (
    DuplicateJsonKeyError,
    OutputParsingError,
    parse_json,
)


def test_parse_valid_json_object():
    raw = '{"name":"Alice","age":21}'

    result = parse_json(raw)

    assert result == {
        "name": "Alice",
        "age": 21,
    }


def test_parse_markdown_fenced_json():
    raw = """```json
    {"name":"Alice","age":21}
    ```"""

    result = parse_json(raw)

    assert result == {
        "name": "Alice",
        "age": 21,
    }


def test_markdown_fence_can_be_disabled():
    raw = """```json
    {"name":"Alice"}
    ```"""

    with pytest.raises(OutputParsingError):
        parse_json(raw, allow_markdown_fences=False)


def test_rejects_malformed_json():
    raw = '{"name":"Alice","age":21'

    with pytest.raises(OutputParsingError):
        parse_json(raw)


def test_rejects_duplicate_keys():
    raw = '{"name":"Alice","name":"Bob"}'

    with pytest.raises(DuplicateJsonKeyError):
        parse_json(raw)


def test_rejects_non_standard_json_numbers():
    raw = '{"score": NaN}'

    with pytest.raises(OutputParsingError):
        parse_json(raw)
