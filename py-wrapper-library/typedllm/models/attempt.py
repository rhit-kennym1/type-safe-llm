"""Per-attempt information.

Useful for:
- debugging
- demo UI
- evaluation harness
- token/cost analysis

Possible fields:
- attempt number
- raw model output
- parsed value
- validation errors
- latency
- token counts
- retry reason
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True, slots=True)
class Attempt:
    """What happened during one provider call."""

    number: int
    raw_output: str
    parsed_output: Any | None
    errors: tuple[str, ...]
    duration_ms: float

    provider: str | None = None
    model: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None

    @property
    def succeeded(self) -> bool:
        return not self.errors
