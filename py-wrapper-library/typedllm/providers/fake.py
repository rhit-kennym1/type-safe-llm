"""Deterministic fake provider for tests.

Should return scripted outputs so retry/validation behavior can be tested
without calling a real model.
"""
from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from .base import Message, ProviderResponse


class FakeProvider:
    """Return scripted responses in order, with no real LLM call."""

    def __init__(self, *responses: str | ProviderResponse) -> None:
        if not responses:
            raise ValueError("FakeProvider requires at least one response")

        self._responses = list(responses)
        self.received_messages: list[tuple[Message, ...]] = []
        self.received_schemas: list[dict[str, Any] | None] = []

    async def complete(
        self,
        messages: Sequence[Message],
        *,
        response_schema: dict[str, Any] | None = None,
    ) -> ProviderResponse:
        self.received_messages.append(tuple(messages))
        self.received_schemas.append(response_schema)

        if not self._responses:
            raise RuntimeError("FakeProvider ran out of scripted responses")

        response = self._responses.pop(0)

        if isinstance(response, ProviderResponse):
            return response

        return ProviderResponse(
            text=response,
            provider="fake",
            model="fake-model",
        )
