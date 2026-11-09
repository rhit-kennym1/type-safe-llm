"""Provider interface/protocol.

Every provider adapter should implement the same small contract.

The core should not know whether the model is:
- OpenAI
- Gemini
- Ollama
- Anthropic
- another provider
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal, Protocol, Sequence


Role = Literal["system", "user", "assistant"]


@dataclass(frozen=True, slots=True)
class Message:
    role: Role
    content: str


@dataclass(frozen=True, slots=True)
class ProviderResponse:
    """Provider-independent model response."""

    text: str

    provider: str | None = None
    model: str | None = None
    input_tokens: int | None = None
    output_tokens: int | None = None


class Provider(Protocol):
    """Contract every provider adapter must implement."""

    async def complete(
        self,
        messages: Sequence[Message],
        *,
        response_schema: dict[str, Any] | None = None,
    ) -> ProviderResponse:
        """Return one model completion.

        Provider-specific adapters may use response_schema for native structured
        output when the selected model supports it.
        """
        ...
