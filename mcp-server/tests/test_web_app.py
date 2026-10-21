import asyncio

from type_safe_llm.web_app import answer_question


def test_web_question_discovers_real_mcp_schemas():
    result = asyncio.run(answer_question("What schemas can you validate?"))
    assert result["ok"] is True
    assert set(result["data"]) == {"event", "contract"}
    assert "event" in result["message"]


def test_web_question_surfaces_validation_failure_without_data():
    result = asyncio.run(answer_question("Validate an invalid event"))
    assert result["ok"] is False
    assert result["data"]["data"] is None
    assert result["data"]["errors"][0]["path"] == "attendee_count"


def test_web_question_exposes_retry_timeline():
    result = asyncio.run(answer_question("Show me a wrong-type retry demo"))
    assert result["ok"] is True
    assert result["data"]["status"] == "validated"
    assert len(result["data"]["attempts"]) == 2


def test_web_question_explains_current_boundary_for_unknown_requests():
    result = asyncio.run(answer_question("Who is the president of France?"))
    assert result["ok"] is True
    assert result["suggestions"]
    assert "live LLM" not in result["message"]
