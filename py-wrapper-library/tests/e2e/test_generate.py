import pytest

from typedllm import (
    FakeProvider,
    GenerationConfig,
    TypedLLM,
    TypedOutputError,
)


SCHEMA = {
    "type": "object",
    "properties": {
        "company": {"type": "string"},
        "value": {"type": "number"},
    },
    "required": ["company", "value"],
    "additionalProperties": False,
}


@pytest.mark.asyncio
async def test_generate_succeeds_on_first_attempt():
    provider = FakeProvider(
        '{"company":"Acme","value":125000.0}',
    )

    llm = TypedLLM(provider)

    result = await llm.generate(
        prompt="Extract the company and value.",
        schema=SCHEMA,
    )

    assert result.data == {
        "company": "Acme",
        "value": 125000.0,
    }

    assert result.attempt_count == 1
    assert result.final_attempt.succeeded is True
    assert result.final_attempt.provider == "fake"
    assert result.final_attempt.model == "fake-model"


@pytest.mark.asyncio
async def test_generate_raises_after_retry_exhaustion():
    provider = FakeProvider(
        '{"company":"Acme","value":"wrong"}',
        '{"company":"Acme","value":"still wrong"}',
    )

    llm = TypedLLM(
        provider,
        config=GenerationConfig(max_attempts=2),
    )

    with pytest.raises(TypedOutputError) as exc_info:
        await llm.generate(
            prompt="Extract the company and value.",
            schema=SCHEMA,
        )

    error = exc_info.value

    assert len(error.attempts) == 2
    assert all(attempt.succeeded is False for attempt in error.attempts)
    assert all(
        any("$.value" in validation_error for validation_error in attempt.errors)
        for attempt in error.attempts
    )


@pytest.mark.asyncio
async def test_generate_can_override_default_config_for_one_call():
    provider = FakeProvider(
        '{"company":"Acme","value":"wrong"}',
    )

    llm = TypedLLM(
        provider,
        config=GenerationConfig(max_attempts=3),
    )

    with pytest.raises(TypedOutputError) as exc_info:
        await llm.generate(
            prompt="Extract the company and value.",
            schema=SCHEMA,
            config=GenerationConfig(max_attempts=1),
        )

    assert len(exc_info.value.attempts) == 1
