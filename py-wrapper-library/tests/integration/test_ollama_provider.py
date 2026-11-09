"""Live integration tests for the Ollama provider.

Requirements:
- install the Ollama extra: pip install -e ".[ollama,dev]"
- run Ollama
- pull a model
- set RUN_OLLAMA_TESTS=1

Windows PowerShell:

    $env:RUN_OLLAMA_TESTS="1"
    $env:OLLAMA_TEST_MODEL="llama3.2"
    pytest tests/integration/test_ollama_provider.py -v
"""

from __future__ import annotations

import os

import pytest

pytest.importorskip("ollama")

from typedllm import TypedLLM
from typedllm.providers.ollama import OllamaProvider


RUN_OLLAMA_TESTS = os.getenv("RUN_OLLAMA_TESTS") == "1"
MODEL = os.getenv("OLLAMA_TEST_MODEL", "llama3.2")


pytestmark = pytest.mark.skipif(
    not RUN_OLLAMA_TESTS,
    reason="Set RUN_OLLAMA_TESTS=1 to run live Ollama tests.",
)


PERSON_SCHEMA = {
    "type": "object",
    "properties": {
        "name": {"type": "string"},
        "age": {"type": "integer"},
    },
    "required": ["name", "age"],
    "additionalProperties": False,
}


@pytest.mark.asyncio
async def test_ollama_can_list_models():
    provider = OllamaProvider(model=MODEL)

    models = await provider.list_models()

    assert models
    assert any(MODEL in model or model in MODEL for model in models)


@pytest.mark.asyncio
async def test_ollama_provider_generates_valid_structured_output():
    provider = OllamaProvider(
        model=MODEL,
        options={
            "temperature": 0,
        },
    )

    llm = TypedLLM(provider)

    result = await llm.generate(
        prompt=(
            "Extract the person's information from this sentence:\n"
            "Alice is 24 years old."
        ),
        schema=PERSON_SCHEMA,
    )

    assert result.data == {
        "name": "Alice",
        "age": 24,
    }

    assert result.attempt_count >= 1
    assert result.final_attempt.succeeded is True
    assert result.final_attempt.provider == "ollama"
    assert result.final_attempt.model is not None
    assert result.final_attempt.input_tokens is not None
    assert result.final_attempt.output_tokens is not None
