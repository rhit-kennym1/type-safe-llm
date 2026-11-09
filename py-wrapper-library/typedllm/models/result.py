"""Final result models.

Should represent:
- validated structured data
- success/failure state
- attempts
- validation information
- timing/token metadata
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .attempt import Attempt


@dataclass(frozen=True, slots=True)
class GenerateResult:
    """Validated structured data plus the attempts that produced it."""

    data: Any
    attempts: tuple[Attempt, ...]

    @property
    def attempt_count(self) -> int:
        return len(self.attempts)

    @property
    def final_attempt(self) -> Attempt:
        return self.attempts[-1]