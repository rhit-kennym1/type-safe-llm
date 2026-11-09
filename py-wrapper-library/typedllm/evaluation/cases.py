"""Evaluation-case definitions.

Cases describe inputs and expectations. They do not know how the library is
implemented, so the same cases can later be reused across providers and
configurations.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Mapping

from ..config import GenerationConfig


OutputCheck = Callable[[Any], bool]


@dataclass(frozen=True, slots=True)
class EvaluationCase:
    """One benchmark/evaluation input."""

    name: str
    prompt: str
    schema: dict[str, Any]

    expected_data: Any | None = None
    output_check: OutputCheck | None = None
    config: GenerationConfig | None = None
    tags: tuple[str, ...] = ()
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.name.strip():
            raise ValueError("evaluation case name must not be empty")
        if not self.prompt.strip():
            raise ValueError("evaluation case prompt must not be empty")

    def matches_expected(self, actual: Any) -> bool | None:
        """Evaluate semantic expectations when the case defines one.

        None means the case only measures structural/type-safe success.
        """

        if self.output_check is not None:
            return bool(self.output_check(actual))

        if self.expected_data is not None:
            return actual == self.expected_data

        return None
