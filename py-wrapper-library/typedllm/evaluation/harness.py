"""Evaluation harness for running shared cases through TypedLLM."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Iterable as TypingIterable, TypeVar

from ..client import TypedLLM
from ..errors import TypedLLMError, TypedOutputError
from .cases import EvaluationCase
from .metrics import CaseResult, EvaluationSummary, summarize


T = TypeVar("T")


@dataclass(frozen=True, slots=True)
class EvaluationRun:
    """Case-level results plus aggregate metrics."""

    results: tuple[CaseResult, ...]
    summary: EvaluationSummary


class EvaluationHarness:
    """Run cases consistently through one configured TypedLLM instance."""

    def __init__(self, llm: TypedLLM) -> None:
        self._llm = llm

    async def run_case(self, case: EvaluationCase) -> CaseResult:
        """Run one case and capture observable metrics."""

        try:
            result = await self._llm.generate(
                prompt=case.prompt,
                schema=case.schema,
                config=case.config,
            )
        except TypedOutputError as exc:
            attempts = exc.attempts
            return CaseResult(
                name=case.name,
                success=False,
                expected_match=(
                    False
                    if case.expected_data is not None or case.output_check
                    else None
                ),
                attempt_count=len(attempts),
                first_attempt_success=False,
                latency_ms=sum(attempt.duration_ms for attempt in attempts),
                input_tokens=_sum_optional(
                    attempt.input_tokens for attempt in attempts
                ),
                output_tokens=_sum_optional(
                    attempt.output_tokens for attempt in attempts
                ),
                provider=_last_non_none(
                    attempt.provider for attempt in attempts
                ),
                model=_last_non_none(
                    attempt.model for attempt in attempts
                ),
                tags=case.tags,
                error=str(exc),
            )
        except TypedLLMError as exc:
            return CaseResult(
                name=case.name,
                success=False,
                expected_match=(
                    False
                    if case.expected_data is not None or case.output_check
                    else None
                ),
                attempt_count=0,
                first_attempt_success=False,
                latency_ms=0.0,
                input_tokens=None,
                output_tokens=None,
                provider=None,
                model=None,
                tags=case.tags,
                error=str(exc),
            )

        attempts = result.attempts

        return CaseResult(
            name=case.name,
            success=True,
            expected_match=case.matches_expected(result.data),
            attempt_count=result.attempt_count,
            first_attempt_success=result.attempt_count == 1,
            latency_ms=sum(attempt.duration_ms for attempt in attempts),
            input_tokens=_sum_optional(
                attempt.input_tokens for attempt in attempts
            ),
            output_tokens=_sum_optional(
                attempt.output_tokens for attempt in attempts
            ),
            provider=_last_non_none(
                attempt.provider for attempt in attempts
            ),
            model=_last_non_none(
                attempt.model for attempt in attempts
            ),
            tags=case.tags,
            error=None,
        )

    async def run(
        self,
        cases: Iterable[EvaluationCase],
    ) -> EvaluationRun:
        """Run all cases sequentially.

        Sequential execution is intentional in the first implementation.
        Concurrency/batching can be added later without changing case definitions
        or reporting.
        """

        results_list: list[CaseResult] = []

        for case in cases:
            results_list.append(await self.run_case(case))

        results = tuple(results_list)

        return EvaluationRun(
            results=results,
            summary=summarize(results),
        )


def _sum_optional(values: Iterable[int | None]) -> int | None:
    concrete = [value for value in values if value is not None]
    return sum(concrete) if concrete else None


def _last_non_none(values: Iterable[T | None]) -> T | None:
    last: T | None = None

    for value in values:
        if value is not None:
            last = value

    return last
