"""Backward-compatible parsing helpers.

Format-specific parsing now lives in ``typedllm.formats``.
New core code should use the selected format adapter instead of importing this
module directly.
"""

from __future__ import annotations

from typing import Any

from ..errors import DuplicateJsonKeyError, OutputParsingError
from ..formats.json import JSON


def parse_json(
    raw_output: str,
    *,
    allow_markdown_fences: bool = True,
) -> Any:
    """Parse strict JSON using the JSON format adapter."""

    return JSON.parse(
        raw_output,
        allow_markdown_fences=allow_markdown_fences,
    )


__all__ = [
    "DuplicateJsonKeyError",
    "OutputParsingError",
    "parse_json",
]
