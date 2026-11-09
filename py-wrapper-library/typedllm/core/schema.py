"""JSON Schema validation and construction helpers."""

from __future__ import annotations

from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError

from ..errors import InvalidSchemaError


def validator_for_schema(schema: dict[str, Any]) -> Draft202012Validator:
    """Validate a schema and return a reusable Draft 2020-12 validator."""

    try:
        Draft202012Validator.check_schema(schema)
    except SchemaError as exc:
        raise InvalidSchemaError(f"Invalid JSON Schema: {exc.message}") from exc

    return Draft202012Validator(schema)


def validate_schema(schema: dict[str, Any]) -> None:
    """Fail fast before an LLM call if the schema itself is invalid."""

    validator_for_schema(schema)
