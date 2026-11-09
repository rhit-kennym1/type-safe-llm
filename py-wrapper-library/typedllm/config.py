"""Library configuration models.

Settings may include:
- max retry attempts
- provider/model selection
- output format
- validation strictness
- safeguard options
- logging/observability options
- timeout/concurrency settings
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class GenerationConfig:
    max_attempts: int = 3
    allow_markdown_fences: bool = True

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")
