"""Structured-output format registry.

Only JSON is implemented today. Additional formats can be added by creating an
adapter and registering it here without changing the generation pipeline.
"""

from __future__ import annotations

from .base import FormatAdapter
from .json import JSON, JsonFormat


_FORMATS: dict[str, FormatAdapter] = {
    JSON.name: JSON,
}


def get_format(name: str) -> FormatAdapter:
    """Return a registered format adapter by name."""

    normalized = name.strip().lower()

    try:
        return _FORMATS[normalized]
    except KeyError as exc:
        supported = ", ".join(sorted(_FORMATS))
        raise ValueError(
            f"Unsupported output format {name!r}. Supported formats: {supported}"
        ) from exc


__all__ = [
    "FormatAdapter",
    "JSON",
    "JsonFormat",
    "get_format",
]
