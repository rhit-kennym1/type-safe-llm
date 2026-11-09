import pytest

from typedllm import FakeProvider, GenerationConfig, TypedLLM
from typedllm.evaluation import EvaluationCase, EvaluationHarness


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
async def test_harness_records_success_retry_and_expected_output():
    provider = FakeProvider(
        '{"name":"Alice","age":"wrong"}',
        '{"name":"Alice","age":21}',
    )

    llm = TypedLLM(
        provider,
        config=GenerationConfig(max_attempts=2),
    )

    harness = EvaluationHarness(llm)

    run = await harness.run(
        [
            EvaluationCase(
                name="person",
                prompt="Extract Alice, age 21.",
                schema=PERSON_SCHEMA,
                expected_data={
                    "name": "Alice",
                    "age": 21,
                },
                tags=("type", "retry"),
            )
        ]
    )

    result = run.results[0]

    assert result.success is True
    assert result.expected_match is True
    assert result.attempt_count == 2
    assert result.first_attempt_success is False
    assert result.retry_count == 1
    assert result.provider == "fake"

    assert run.summary.total_cases == 1
    assert run.summary.success_rate == 1.0
    assert run.summary.average_attempts == 2.0
