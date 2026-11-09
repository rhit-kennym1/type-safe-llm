"""Common interface for structured-output format adapters."""

from __future__ import annotations

from typing import Any, Protocol


class FormatAdapter(Protocol):
    """Convert model text into a Python value.

    Each adapter is responsible only for format-specific concerns.
    Schema validation remains in the core validation layer.
    """

    name: str

    def parse(
        self,
        raw_output: str,
        *,
        allow_markdown_fences: bool = True,
    ) -> Any:
        """Parse one complete model response."""

    def provider_schema(
        self,
        schema: dict[str, Any],
    ) -> dict[str, Any] | None:
        """Return the schema that may be passed to provider-native formatting.

        Return None when the provider should not be forced into JSON-schema
        structured output for this format.
        """
