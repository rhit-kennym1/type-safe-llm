"""Input/request models.

Models may represent:
- generation request
- prompt/messages
- schema
- provider options
- safeguard settings
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..config import GenerationConfig


@dataclass(frozen=True, slots=True)
class GenerateRequest:
    """Everything the core pipeline needs for one generation."""

    prompt: str
    schema: dict[str, Any]
    config: GenerationConfig = field(default_factory=GenerationConfig)

    def __post_init__(self) -> None:
        if not self.prompt.strip():
            raise ValueError("prompt must not be empty")
        if not isinstance(self.schema, dict):
            raise TypeError("schema must be a dictionary containing JSON Schema")
