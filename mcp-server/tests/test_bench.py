"""The experiment harness: shared corpus, benchmark schemas, both Python tracks, and the result format."""

import asyncio
import json
import sys
from datetime import timedelta
from types import SimpleNamespace

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from type_safe_llm.bench.runner import TOOL_ERROR_PREFIX, Generator, load_cases, main, run_core_case, run_mcp_case
from type_safe_llm.bench.schemas import BENCH_SCHEMAS
from type_safe_llm.core import generate_validated

CORPUS = load_cases()
CASES = {c["id"]: c for c in CORPUS["cases"]}
MAX = CORPUS["max_attempts"]


def core_results() -> dict[str, dict]:
    return {cid: run_core_case(c, Generator(responses=c["responses"]), MAX, "scripted") for cid, c in CASES.items()}


def test_every_case_targets_a_benchmark_schema():
    assert {c["target"] for c in CASES.values()} == set(BENCH_SCHEMAS)


def test_detectable_bad_output_is_never_returned_as_valid():
    results = core_results()
    leaked = {cid for cid, c in CASES.items() if c["ideal"] == "reject" and c["detectable"] and results[cid]["ok"]}
    # Pydantic, like kotlinx-serialization, keeps the last value of a duplicated key. Documented in REPORT.md.
    assert leaked == {"event-duplicate-key"}


def test_good_output_is_accepted_except_code_fences():
    results = core_results()
    rejected = {cid for cid, c in CASES.items() if c["ideal"] == "accept" and not results[cid]["ok"]}
    # The validator deliberately does no markdown stripping (see validate_output's docstring); the report shows
    # this is the main behavioral difference from the Kotlin library, which strips fences.
    assert rejected == {"event-code-fence"}


def test_wrong_but_well_formed_output_passes_structural_validation():
    results = core_results()
    assert all(results[cid]["ok"] for cid, c in CASES.items() if not c["detectable"])


def test_stall_stops_at_the_attempt_limit_with_no_data():
    rec = core_results()["event-stall"]
    assert rec["status"] == "retries_exhausted" and len(rec["attempts"]) == MAX and rec["data"] is None


def test_repair_prompts_are_rebuilt_not_accumulated():
    case = CASES["event-two-mistakes"]
    prompts: list[str] = []
    responses = iter(case["responses"])

    def generate(prompt: str) -> str:
        prompts.append(prompt)
        return next(responses)

    generate_validated("event", case["source"], generate, max_retries=MAX - 1, model=BENCH_SCHEMAS["event"])
    assert len(prompts) == 3
    original = prompts[0]
    assert all(p.startswith(original) for p in prompts[1:])
    # The third prompt carries only the second answer, not the first: unlike the Kotlin library, which
    # resends the whole conversation, retry prompts here don't grow with every attempt.
    assert case["responses"][0] not in prompts[2]
    assert case["responses"][1] in prompts[2]


def test_mcp_track_matches_in_process_validation():
    ids = ["event-valid", "order-missing-discriminator-then-fix", "event-code-fence", "thread-deep-60", "event-stall"]

    async def run() -> dict[str, dict]:
        params = StdioServerParameters(command=sys.executable, args=["-m", "type_safe_llm.bench.server"])
        async with stdio_client(params) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=60)) as session:
                await session.initialize()
                return {cid: await run_mcp_case(session, CASES[cid], Generator(responses=CASES[cid]["responses"]), MAX, "scripted")
                        for cid in ids}

    over_mcp = asyncio.run(run())
    in_process = core_results()
    for cid in ids:
        if over_mcp[cid]["status"] == "tool_error":
            # Known protocol difference, not a validation difference: pydantic-core's serialization depth limit is
            # lower on Windows, so the server validates the 60-level thread but can't send the result back.
            assert cid == "thread-deep-60" and "depth" in over_mcp[cid]["error"], over_mcp[cid]["error"]
            assert in_process[cid]["status"] == "validated"
            continue
        assert over_mcp[cid]["status"] == in_process[cid]["status"], cid
        assert len(over_mcp[cid]["attempts"]) == len(in_process[cid]["attempts"]), cid
        assert over_mcp[cid]["data"] == in_process[cid]["data"], cid


def test_a_server_failure_is_recorded_as_the_outcome_not_a_crash():
    class FailingSession:
        async def get_prompt(self, name, arguments):
            return SimpleNamespace(messages=[SimpleNamespace(content=SimpleNamespace(text="extract"))])

        async def call_tool(self, name, arguments):
            text = "Error executing tool validate_output: Circular reference detected (depth exceeded)"
            return SimpleNamespace(isError=True, structuredContent=None, content=[SimpleNamespace(text=text)])

    case = CASES["thread-deep-60"]
    rec = asyncio.run(run_mcp_case(FailingSession(), case, Generator(responses=case["responses"]), MAX, "scripted"))
    assert rec["status"] == "tool_error" and not rec["ok"] and rec["data"] is None
    assert "depth exceeded" in rec["error"]
    # One attempt: retrying can't help when the server, not the model output, is what failed.
    assert len(rec["attempts"]) == 1 and rec["attempts"][0]["error"].startswith(TOOL_ERROR_PREFIX)


def test_live_generator_records_usage_when_reported(fake_endpoint):
    config, replies, seen = fake_endpoint
    replies.append("{}")
    gen = Generator(config=config)
    assert gen("extract") == "{}"
    assert gen.prompt_chars == [len("extract")] and gen.usage == [(None, None)]
    assert seen[0]["body"]["temperature"] == 0


def test_runner_writes_the_shared_record_format(tmp_path):
    out = tmp_path / "out.jsonl"
    main(["--track", "python-core", "--only", "event-valid,event-stall", "--timing-runs", "2", "--out", str(out)])
    records = [json.loads(line) for line in out.read_text().splitlines()]
    assert [r["case_id"] for r in records] == ["event-valid", "event-stall"]
    expected = {"track", "mode", "provider", "case_id", "target", "category", "repeat", "ok", "status",
                "attempts", "loop_ms", "overhead_samples_ms", "data", "error"}
    assert expected <= set(records[0])
    assert {"n", "ok", "error", "prompt_chars", "response_chars", "input_tokens", "output_tokens", "latency_ms"} \
        == set(records[1]["attempts"][0])
    assert len(records[0]["overhead_samples_ms"]) == 2
