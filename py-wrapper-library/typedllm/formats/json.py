"""Strict JSON format adapter."""

from __future__ import annotations

import json
import re
from typing import Any

from ..errors import DuplicateJsonKeyError, OutputParsingError


_FENCE_RE = re.compile(
    r"^\s*```(?:json)?\s*(.*?)\s*```\s*$",
    re.IGNORECASE | re.DOTALL,
)


def _remove_single_markdown_fence(text: str) -> str:
    match = _FENCE_RE.match(text)
    return match.group(1) if match else text


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}

    for key, value in pairs:
        if key in result:
            raise DuplicateJsonKeyError(f"Duplicate JSON key: {key!r}")
        result[key] = value

    return result


def _reject_non_standard_number(value: str) -> None:
    raise OutputParsingError(f"Non-standard JSON number is not allowed: {value}")


class JsonFormat:
    """JSON implementation of the format adapter contract."""

    name = "json"

    def parse(
        self,
        raw_output: str,
        *,
        allow_markdown_fences: bool = True,
    ) -> Any:
        text = (
            _remove_single_markdown_fence(raw_output)
            if allow_markdown_fences
            else raw_output
        )

        try:
            return json.loads(
                text,
                object_pairs_hook=_reject_duplicate_keys,
                parse_constant=_reject_non_standard_number,
            )
        except DuplicateJsonKeyError:
            raise
        except OutputParsingError:
            raise
        except json.JSONDecodeError as exc:
            raise OutputParsingError(
                f"Invalid JSON at line {exc.lineno}, column {exc.colno}: {exc.msg}"
            ) from exc

    def provider_schema(
        self,
        schema: dict[str, Any],
    ) -> dict[str, Any]:
        """JSON providers may receive the same JSON Schema natively."""
        return schema


JSON = JsonFormat()
