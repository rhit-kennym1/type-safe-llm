"""Prompt construction for structured output and correction attempts."""

from __future__ import annotations

import json
from typing import Any, Iterable


def structured_output_prompt(
    schema: dict[str, Any],
    *,
    output_format: str = "json",
) -> str:
    """Build the system instruction for the first attempt."""

    schema_text = json.dumps(schema, indent=2, sort_keys=True)
    format_name = output_format.upper()

    return (
        f"Return exactly one {format_name} value that satisfies the JSON Schema below.\n"
        "Do not include explanations or surrounding prose.\n"
        "Do not invent factual values that are not supported by the user's input.\n\n"
        f"JSON Schema:\n{schema_text}"
    )


def correction_prompt(
    errors: Iterable[str],
    *,
    output_format: str = "json",
) -> str:
    """Tell the model why the previous structured response was rejected."""

    error_text = "\n".join(f"- {error}" for error in errors)
    format_name = output_format.upper()

    return (
        "The previous response was rejected for these reasons:\n"
        f"{error_text}\n\n"
        f"Return the corrected {format_name} only.\n"
        "Correct schema/formatting problems, but do not invent missing facts."
    )
