import pytest

from typedllm import FakeProvider, TypedLLM


SCHEMA = {
    "type": "object",
    "properties": {
        "name": {"type": "string"},
        "age": {"type": "integer"},
    },
    "required": ["name", "age"],
    "additionalProperties": False,
}


@pytest.mark.asyncio
async def test_invalid_first_response_retries_and_recovers():
    provider = FakeProvider(
        '{"name":"Alice","age":"21"}',
        '{"name":"Alice","age":21}',
    )

    llm = TypedLLM(provider)

    result = await llm.generate(
        prompt="Extract the person's name and age.",
        schema=SCHEMA,
    )

    assert result.data == {
        "name": "Alice",
        "age": 21,
    }

    assert result.attempt_count == 2

    first_attempt = result.attempts[0]
    second_attempt = result.attempts[1]

    assert first_attempt.succeeded is False
    assert any("$.age" in error for error in first_attempt.errors)

    assert second_attempt.succeeded is True
    assert second_attempt.errors == ()

    assert len(provider.received_messages) == 2

    second_request_messages = provider.received_messages[1]

    assert second_request_messages[-2].role == "assistant"
    assert second_request_messages[-2].content == '{"name":"Alice","age":"21"}'

    assert second_request_messages[-1].role == "user"
    assert "previous response was rejected" in second_request_messages[-1].content.lower()
    assert "$.age" in second_request_messages[-1].content


@pytest.mark.asyncio
async def test_schema_is_passed_to_provider():
    provider = FakeProvider(
        '{"name":"Alice","age":21}',
    )

    llm = TypedLLM(provider)

    await llm.generate(
        prompt="Extract the person's name and age.",
        schema=SCHEMA,
    )

    assert provider.received_schemas == [SCHEMA]
