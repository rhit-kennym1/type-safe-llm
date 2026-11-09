"""Validate parsed model output against JSON Schema."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from jsonschema.exceptions import ValidationError

from .schema import validator_for_schema


@dataclass(frozen=True, slots=True)
class ValidationIssue:
    """One schema-validation problem."""

    path: str
    message: str
    rule: str | None = None

    def __str__(self) -> str:
        return f"{self.path}: {self.message}"


def _format_path(error: ValidationError) -> str:
    path = "$"

    for part in error.absolute_path:
        if isinstance(part, int):
            path += f"[{part}]"
        else:
            path += f".{part}"

    return path


def validate_output(
    data: Any,
    schema: dict[str, Any],
) -> tuple[ValidationIssue, ...]:
    """Return all detectable schema violations in deterministic order."""

    validator = validator_for_schema(schema)
    errors = sorted(
        validator.iter_errors(data),
        key=lambda error: tuple(str(part) for part in error.absolute_path),
    )

    return tuple(
        ValidationIssue(
            path=_format_path(error),
            message=error.message,
            rule=error.validator,
        )
        for error in errors
    )
