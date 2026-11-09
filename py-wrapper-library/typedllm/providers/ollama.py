"""Ollama provider adapter.

The user owns the Ollama installation, chooses the model, and may point this
adapter at a local or remote Ollama-compatible server.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from ollama import AsyncClient

from ..errors import ProviderError
from .base import Message, ProviderResponse


class OllamaProvider:
    """Provider adapter for Ollama."""

    def __init__(
        self,
        model: str,
        *,
        host: str = "http://localhost:11434",
        options: Mapping[str, Any] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> None:
        if not model.strip():
            raise ValueError("model must not be empty")

        if not host.strip():
            raise ValueError("host must not be empty")

        self.model = model
        self.host = host
        self.options = dict(options or {})
        self._client = AsyncClient(
            host=host,
            headers=dict(headers or {}),
        )

    async def complete(
        self,
        messages: Sequence[Message],
        *,
        response_schema: dict[str, Any] | None = None,
    ) -> ProviderResponse:
        """Send one completion request to Ollama.

        For JSON generation, ``response_schema`` is passed through Ollama's
        native ``format`` parameter. The Type-Safe LLM core still parses and
        validates the returned value independently.
        """

        request: dict[str, Any] = {
            "model": self.model,
            "messages": [
                {
                    "role": message.role,
                    "content": message.content,
                }
                for message in messages
            ],
            "stream": False,
        }

        if response_schema is not None:
            request["format"] = response_schema

        if self.options:
            request["options"] = self.options

        try:
            response = await self._client.chat(**request)

            return ProviderResponse(
                text=response.message.content or "",
                provider="ollama",
                model=response.model or self.model,
                input_tokens=response.prompt_eval_count,
                output_tokens=response.eval_count,
            )
        except ProviderError:
            raise
        except Exception as exc:
            raise ProviderError(
                f"Ollama request failed for model {self.model!r}: {exc}"
            ) from exc

    async def list_models(self) -> tuple[str, ...]:
        """Return model names available from the configured Ollama server.

        This is an optional provider-specific convenience method intended for
        future UI/model-selection workflows. It is not part of the base
        Provider protocol.
        """

        try:
            response = await self._client.list()

            names = {
                model.model
                for model in response.models
                if getattr(model, "model", None)
            }

            return tuple(sorted(names))
        except Exception as exc:
            raise ProviderError(
                f"Could not list Ollama models from {self.host!r}: {exc}"
            ) from exc
