import pytest

from typedllm.core.schema import InvalidSchemaError, validate_schema


def test_valid_schema_is_accepted():
    schema = {
        "type": "object",
        "properties": {
            "name": {"type": "string"},
            "age": {"type": "integer"},
        },
        "required": ["name", "age"],
        "additionalProperties": False,
    }

    validate_schema(schema)


def test_invalid_schema_is_rejected():
    schema = {
        "type": "not-a-real-json-schema-type",
    }

    with pytest.raises(InvalidSchemaError):
        validate_schema(schema)
