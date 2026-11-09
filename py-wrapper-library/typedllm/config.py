"""Configuration for type-safe generation."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class GenerationConfig:
    """Generic Type-Safe LLM settings.

    Provider credentials, model selection, and provider-specific options belong
    to the provider adapter rather than this configuration object.
    """

    max_attempts: int = 3
    output_format: str = "json"
    allow_markdown_fences: bool = True

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("max_attempts must be at least 1")

        if not self.output_format.strip():
            raise ValueError("output_format must not be empty")
