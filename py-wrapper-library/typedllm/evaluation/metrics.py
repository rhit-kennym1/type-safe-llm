"""Evaluation result models and aggregate metrics."""

from __future__ import annotations

from dataclasses import dataclass
from statistics import mean


@dataclass(frozen=True, slots=True)
class CaseResult:
    """Observed result for one evaluation case."""

    name: str
    success: bool
    expected_match: bool | None
    attempt_count: int
    first_attempt_success: bool
    latency_ms: float
    input_tokens: int | None
    output_tokens: int | None
    provider: str | None
    model: str | None
    tags: tuple[str, ...] = ()
    error: str | None = None

    @property
    def retry_count(self) -> int:
        return max(self.attempt_count - 1, 0)


@dataclass(frozen=True, slots=True)
class EvaluationSummary:
    total_cases: int
    successful_cases: int
    failed_cases: int
    first_attempt_successes: int
    expectation_checks: int
    expectation_matches: int
    total_attempts: int
    total_retries: int
    total_latency_ms: float
    total_input_tokens: int | None
    total_output_tokens: int | None

    @property
    def success_rate(self) -> float:
        return _rate(self.successful_cases, self.total_cases)

    @property
    def first_attempt_success_rate(self) -> float:
        return _rate(self.first_attempt_successes, self.total_cases)

    @property
    def expectation_match_rate(self) -> float | None:
        if self.expectation_checks == 0:
            return None
        return _rate(self.expectation_matches, self.expectation_checks)

    @property
    def average_attempts(self) -> float:
        if self.total_cases == 0:
            return 0.0
        return self.total_attempts / self.total_cases

    @property
    def average_latency_ms(self) -> float:
        if self.total_cases == 0:
            return 0.0
        return self.total_latency_ms / self.total_cases


def _rate(numerator: int, denominator: int) -> float:
    if denominator == 0:
        return 0.0
    return numerator / denominator


def summarize(results: tuple[CaseResult, ...]) -> EvaluationSummary:
    """Aggregate case-level results."""

    input_counts = [r.input_tokens for r in results if r.input_tokens is not None]
    output_counts = [r.output_tokens for r in results if r.output_tokens is not None]

    expectation_results = [
        r.expected_match
        for r in results
        if r.expected_match is not None
    ]

    return EvaluationSummary(
        total_cases=len(results),
        successful_cases=sum(r.success for r in results),
        failed_cases=sum(not r.success for r in results),
        first_attempt_successes=sum(r.first_attempt_success for r in results),
        expectation_checks=len(expectation_results),
        expectation_matches=sum(value is True for value in expectation_results),
        total_attempts=sum(r.attempt_count for r in results),
        total_retries=sum(r.retry_count for r in results),
        total_latency_ms=sum(r.latency_ms for r in results),
        total_input_tokens=sum(input_counts) if input_counts else None,
        total_output_tokens=sum(output_counts) if output_counts else None,
    )
