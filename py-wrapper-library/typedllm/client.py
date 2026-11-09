"""Public developer-facing entry point.

This should contain the TypedLLM class.

Responsibilities:
- accept a prompt + expected JSON Schema
- accept user configuration
- call the core pipeline
- return a validated structured result

Keep this file small.
The implementation details belong in core/.
"""

from __future__ import annotations

from typing import Any

from .config import GenerationConfig
from .core.pipeline import GenerationPipeline
from .models.request import GenerateRequest
from .models.result import GenerateResult
from .providers.base import Provider


class TypedLLM:
    """Generate JSON that is validated against a caller-supplied JSON Schema."""

    def __init__(
        self,
        provider: Provider,
        *,
        config: GenerationConfig | None = None,
    ) -> None:
        self._config = config or GenerationConfig()
        self._pipeline = GenerationPipeline(provider)

    async def generate(
        self,
        prompt: str,
        schema: dict[str, Any],
        *,
        config: GenerationConfig | None = None,
    ) -> GenerateResult:
        """Generate and validate one structured response."""

        request = GenerateRequest(
            prompt=prompt,
            schema=schema,
            config=config or self._config,
        )
        return await self._pipeline.run(request)
