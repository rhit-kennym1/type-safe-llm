"""Small, explicit retry policy for structured-output failures."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class RetryDecision:
    retry: bool
    attempts_remaining: int


def decide_retry(attempt_number: int, max_attempts: int) -> RetryDecision:
    """Decide whether another structured-output attempt is allowed."""

    remaining = max(max_attempts - attempt_number, 0)
    return RetryDecision(
        retry=remaining > 0,
        attempts_remaining=remaining,
    )
