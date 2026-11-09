from typedllm.evaluation.metrics import CaseResult, summarize


def test_summarize_evaluation_results():
    results = (
        CaseResult(
            name="first",
            success=True,
            expected_match=True,
            attempt_count=1,
            first_attempt_success=True,
            latency_ms=10.0,
            input_tokens=10,
            output_tokens=5,
            provider="fake",
            model="fake-model",
        ),
        CaseResult(
            name="second",
            success=True,
            expected_match=False,
            attempt_count=2,
            first_attempt_success=False,
            latency_ms=20.0,
            input_tokens=20,
            output_tokens=10,
            provider="fake",
            model="fake-model",
        ),
        CaseResult(
            name="third",
            success=False,
            expected_match=None,
            attempt_count=3,
            first_attempt_success=False,
            latency_ms=30.0,
            input_tokens=None,
            output_tokens=None,
            provider="fake",
            model="fake-model",
        ),
    )

    summary = summarize(results)

    assert summary.total_cases == 3
    assert summary.successful_cases == 2
    assert summary.failed_cases == 1
    assert summary.success_rate == 2 / 3
    assert summary.first_attempt_success_rate == 1 / 3
    assert summary.expectation_match_rate == 1 / 2
    assert summary.total_attempts == 6
    assert summary.total_retries == 3
    assert summary.average_attempts == 2
    assert summary.total_input_tokens == 30
    assert summary.total_output_tokens == 15
