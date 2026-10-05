import asyncio

from type_safe_llm.demo import exercise_server


def test_real_stdio_mcp_round_trip():
    results = asyncio.run(exercise_server())
    assert set(results["tools"]) == {"list_schemas", "validate_output", "run_retry_demo"}
    assert all(results["output_schemas"].values())
    assert results["valid"]["ok"] is True
    assert results["invalid"]["ok"] is False and results["invalid"]["data"] is None
    assert results["invalid"]["errors"][0]["path"] == "attendee_count"
    assert results["recovered"]["ok"] and len(results["recovered"]["attempts"]) == 2
    assert results["contract"]["ok"]
    assert results["exhausted"]["status"] == "retries_exhausted"
    assert results["exhausted"]["data"] is None and len(results["exhausted"]["attempts"]) == 2
    assert results["guide_available"] and results["prompt_available"]
