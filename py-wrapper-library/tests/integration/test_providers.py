from __future__ import annotations

from types import SimpleNamespace

import pytest

pytest.importorskip("ollama")

from typedllm.errors import ProviderError
from typedllm.providers.ollama import OllamaProvider


@pytest.mark.asyncio
async def test_ollama_wraps_sdk_failure_as_provider_error(monkeypatch):
    provider = OllamaProvider(model="test-model")

    async def fail(**kwargs):
        raise RuntimeError("connection failed")

    monkeypatch.setattr(provider._client, "chat", fail)

    with pytest.raises(ProviderError, match="Ollama request failed") as exc_info:
        await provider.complete([])

    assert isinstance(exc_info.value.__cause__, RuntimeError)


@pytest.mark.asyncio
async def test_ollama_lists_available_models(monkeypatch):
    provider = OllamaProvider(model="test-model")

    async def list_models():
        return SimpleNamespace(
            models=[
                SimpleNamespace(model="qwen3:latest"),
                SimpleNamespace(model="llama3.2:latest"),
                SimpleNamespace(model=None),
            ]
        )

    monkeypatch.setattr(provider._client, "list", list_models)

    assert await provider.list_models() == (
        "llama3.2:latest",
        "qwen3:latest",
    )


@pytest.mark.asyncio
async def test_ollama_wraps_list_failure(monkeypatch):
    provider = OllamaProvider(model="test-model")

    async def fail():
        raise RuntimeError("server unavailable")

    monkeypatch.setattr(provider._client, "list", fail)

    with pytest.raises(ProviderError, match="Could not list Ollama models"):
        await provider.list_models()
