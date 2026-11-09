"""Exception hierarchy for Type-Safe LLM.

All library-defined exceptions live here so callers have one predictable place
to import errors from.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from .models.attempt import Attempt


class TypedLLMError(Exception):
    """Base class for all Type-Safe LLM exceptions."""


class InvalidSchemaError(TypedLLMError, ValueError):
    """Raised when the caller supplies an invalid JSON Schema."""


class OutputParsingError(TypedLLMError, ValueError):
    """Raised when model output cannot be parsed in the expected format."""


class DuplicateJsonKeyError(OutputParsingError):
    """Raised when a JSON object contains the same key more than once."""


class ProviderError(TypedLLMError, RuntimeError):
    """Raised when a provider request fails before structured output is produced."""


class TypedOutputError(TypedLLMError, RuntimeError):
    """Raised when every allowed model attempt fails validation."""

    def __init__(self, attempts: tuple[Attempt, ...]) -> None:
        self.attempts = attempts
        super().__init__(
            "Could not produce valid structured output after "
            f"{len(attempts)} attempt(s)"
        )


__all__ = [
    "DuplicateJsonKeyError",
    "InvalidSchemaError",
    "OutputParsingError",
    "ProviderError",
    "TypedLLMError",
    "TypedOutputError",
]
