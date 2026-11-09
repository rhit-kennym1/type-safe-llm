from typedllm.core.retry import decide_retry


def test_retry_when_attempts_remain():
    decision = decide_retry(
        attempt_number=1,
        max_attempts=3,
    )

    assert decision.retry is True
    assert decision.attempts_remaining == 2


def test_no_retry_on_final_attempt():
    decision = decide_retry(
        attempt_number=3,
        max_attempts=3,
    )

    assert decision.retry is False
    assert decision.attempts_remaining == 0


def test_attempts_remaining_never_goes_negative():
    decision = decide_retry(
        attempt_number=5,
        max_attempts=3,
    )

    assert decision.retry is False
    assert decision.attempts_remaining == 0
