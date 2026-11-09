"""Ollama provider adapter.

Connects the generic Type-Safe LLM provider interface to a local
or remotely hosted Ollama server.

The user chooses the model and owns/runs the Ollama instance.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any

from ollama import AsyncClient

from ..errors import ProviderError
from .base import Message, ProviderResponse


class OllamaProvider:
    """Provider adapter for Ollama.

    Example:
        provider = OllamaProvider(
            model="llama3.2",
        )

    A custom Ollama server can also be used:

        provider = OllamaProvider(
            model="llama3.2",
            host="http://localhost:11434",
        )
    """

    def __init__(
        self,
        model: str,
        *,
        host: str = "http://localhost:11434",
        options: Mapping[str, Any] | None = None,
    ) -> None:
        if not model.strip():
            raise ValueError("model must not be empty")

        if not host.strip():
            raise ValueError("host must not be empty")

        self.model = model
        self.host = host
        self.options = dict(options or {})
        self._client = AsyncClient(host=host)

    async def complete(
        self,
        messages: Sequence[Message],
        *,
        response_schema: dict[str, Any] | None = None,
    ) -> ProviderResponse:
        """Send one completion request to Ollama.

        If a JSON Schema is supplied, it is passed to Ollama's native
        structured-output ``format`` parameter.

        The Type-Safe LLM core still validates the returned response
        independently after the provider call.

        Ollama SDK, connection, and response-conversion failures are wrapped in
        :class:`ProviderError` so callers do not need to depend on Ollama's
        exception types.
        """

        ollama_messages = [
            {
                "role": message.role,
                "content": message.content,
            }
            for message in messages
        ]

        request: dict[str, Any] = {
            "model": self.model,
            "messages": ollama_messages,
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
