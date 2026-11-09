"""Focused tests for provider adapter behavior."""

from __future__ import annotations

import pytest

pytest.importorskip("ollama")

from typedllm import ProviderError
from typedllm.providers.base import Message
from typedllm.providers.ollama import OllamaProvider


class _FailingOllamaClient:
    async def chat(self, **_kwargs):
        raise RuntimeError("connection refused")


@pytest.mark.asyncio
async def test_ollama_wraps_sdk_failures_in_provider_error():
    provider = OllamaProvider(model="test-model")
    provider._client = _FailingOllamaClient()

    with pytest.raises(ProviderError) as exc_info:
        await provider.complete(
            [Message(role="user", content="Hello")],
        )

    assert "Ollama request failed" in str(exc_info.value)
    assert "test-model" in str(exc_info.value)
    assert "connection refused" in str(exc_info.value)
    assert isinstance(exc_info.value.__cause__, RuntimeError)
