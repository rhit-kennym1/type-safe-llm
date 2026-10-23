"""Runs the shared case corpus through the Python tracks; output matches the Kotlin harness record for record.

Tracks
  python-core   the Pydantic validator used in-process, the way a library would use it (core.generate_validated)
  mcp-server    the same validator behind real stdio MCP: the server supplies the prompt (prompts/get) and every
                attempt is a tools/call to validate_output, with the host driving retries, as in live.py
Modes
  scripted      the case's scripted responses stand in for the model (default; deterministic; no network)
  live          a real OpenAI-compatible endpoint from LIVE_BASE_URL / LIVE_API_KEY / LIVE_MODEL, the same
                variables the Kotlin harness reads, so both tracks can hit the identical model

Usage (from mcp-server/, with the venv active):
  python -m type_safe_llm.bench.runner --track python-core
  python -m type_safe_llm.bench.runner --track mcp-server
  python -m type_safe_llm.bench.runner --track python-core --provider live --repeat 3
"""

import argparse
import asyncio
import json
import sys
import time
from collections.abc import Callable
from datetime import timedelta
from pathlib import Path
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from ..core import Issue, ValidationResult, generate_validated, repair_prompt
from ..providers import LiveConfig, ProviderError, chat_completion_full
from .schemas import BENCH_SCHEMAS

REPO = Path(__file__).resolve().parents[4]
DEFAULT_CASES = REPO / "experiments" / "cases" / "cases.json"
TRACKS = ("python-core", "mcp-server")
TOOL_ERROR_PREFIX = "server error: "  # marks attempts where validate_output itself failed; compare.py keys on it


def load_cases(path: Path = DEFAULT_CASES) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


class Generator:
    """Callable `generate(prompt) -> text` that also records what each call sent and cost."""

    def __init__(self, responses: list[str] | None = None, config: LiveConfig | None = None):
        if (responses is None) == (config is None):
            raise ValueError("give scripted responses or a live config, not both")
        self.responses, self.config = responses, config
        self.index = 0
        self.prompt_chars: list[int] = []
        self.usage: list[tuple[int | None, int | None]] = []
        self.latency_ms: list[float] = []

    @property
    def provider(self) -> str:
        return "scripted" if self.config is None else f"live:{self.config.model}"

    def __call__(self, prompt: str) -> str:
        self.prompt_chars.append(len(prompt))
        started = time.perf_counter()
        if self.responses is not None:
            text = self.responses[min(self.index, len(self.responses) - 1)]
            self.index += 1
            self.usage.append((None, None))
        else:
            data = chat_completion_full(self.config, [{"role": "user", "content": prompt}])
            usage = data.get("usage") or {}
            self.usage.append((usage.get("prompt_tokens"), usage.get("completion_tokens")))
            content = data["choices"][0]["message"].get("content")
            if not isinstance(content, str):
                raise ProviderError("Model returned no text content.")
            text = content
        self.latency_ms.append((time.perf_counter() - started) * 1000)
        return text


def describe(errors: list[Issue]) -> str | None:
    return "; ".join(f"{e.path}: {e.code}: {e.message}" for e in errors) or None


def attempt_records(gen: Generator, results: list[tuple[bool, str | None, str]]) -> list[dict[str, Any]]:
    out = []
    for i, (ok, error, content) in enumerate(results):
        tokens_in, tokens_out = gen.usage[i] if i < len(gen.usage) else (None, None)
        out.append({
            "n": i + 1, "ok": ok, "error": error,
            "prompt_chars": gen.prompt_chars[i] if i < len(gen.prompt_chars) else 0,
            "response_chars": len(content),
            "input_tokens": tokens_in, "output_tokens": tokens_out,
            "latency_ms": round(gen.latency_ms[i], 3) if i < len(gen.latency_ms) else 0.0,
        })
    return out


def record(track: str, mode: str, gen: Generator, case: dict, repeat: int, status: str,
           attempts: list[dict], loop_ms: float, data: Any, error: str | None, **extra: Any) -> dict[str, Any]:
    return {
        "track": track, "mode": mode, "provider": gen.provider, "case_id": case["id"], "target": case["target"],
        "category": case["category"], "repeat": repeat, "ok": status == "validated", "status": status,
        "attempts": attempts, "loop_ms": loop_ms, "overhead_samples_ms": [], "data": data, "error": error, **extra,
    }


def run_core_case(case: dict, gen: Generator, max_attempts: int, mode: str, repeat: int = 0) -> dict[str, Any]:
    started = time.perf_counter()
    result = generate_validated(case["target"], case["source"], gen, max_retries=max_attempts - 1,
                                provider=gen.provider, model=BENCH_SCHEMAS[case["target"]])
    loop_ms = (time.perf_counter() - started) * 1000
    attempts = attempt_records(gen, [(a.result.ok, describe(a.result.errors), a.content or "") for a in result.attempts])
    return record("python-core", mode, gen, case, repeat, result.status, attempts, loop_ms, result.data, result.error)


async def run_mcp_case(session: ClientSession, case: dict, gen: Generator, max_attempts: int, mode: str,
                       repeat: int = 0) -> dict[str, Any]:
    """The host loop: prompt from the server, model call, validate_output over MCP, repair prompt, repeat."""
    started = time.perf_counter()
    target = case["target"]
    prompt_result = await session.get_prompt("extract_typed_json", {"schema_name": target, "source": case["source"]})
    original = getattr(prompt_result.messages[0].content, "text", "")
    prompt, results, status, data, error = original, [], "retries_exhausted", None, None
    for number in range(1, max_attempts + 1):
        try:
            content = gen(prompt) if gen.config is None else await asyncio.to_thread(gen, prompt)
        except ProviderError as exc:
            status, error = "provider_error", str(exc)
            break
        call = await session.call_tool("validate_output", {"schema_name": target, "content": content})
        if call.isError or call.structuredContent is None:
            # The server failed to answer (e.g. it couldn't serialize a deeply nested result), so the host never
            # learns whether the output was valid. Recorded as this case's outcome rather than aborting the run;
            # retrying wouldn't help, because the same output would hit the same failure.
            text = " ".join(getattr(c, "text", "") for c in call.content).strip() or "no structured result"
            status, error = "tool_error", text
            results.append((False, f"{TOOL_ERROR_PREFIX}{text}", content))
            break
        result = ValidationResult.model_validate(call.structuredContent)
        results.append((result.ok, describe(result.errors), content))
        if result.ok:
            status, data = "validated", result.data
            break
        if number < max_attempts:
            prompt = repair_prompt(original, content, result.errors)
    loop_ms = (time.perf_counter() - started) * 1000
    return record("mcp-server", mode, gen, case, repeat, status, attempt_records(gen, results), loop_ms, data, error)


def make_gen(case: dict, provider: str) -> Generator:
    return Generator(responses=case["responses"]) if provider == "scripted" else Generator(config=LiveConfig.from_environment())


def dump_schemas(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for name, model in BENCH_SCHEMAS.items():
        (directory / f"{name}.json").write_text(json.dumps(model.model_json_schema(), indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(BENCH_SCHEMAS)} schemas to {directory}")


async def run_track(track: str, cases: list[dict], max_attempts: int, provider: str, repeat: int,
                    timing_runs: int, emit: Callable[[dict], None]) -> None:
    mode = "scripted" if provider == "scripted" else "live"
    timed = mode == "scripted" and timing_runs > 0

    if track == "python-core":
        if timed:
            for case in cases:  # warm-up pass so timings reflect steady state
                run_core_case(case, make_gen(case, provider), max_attempts, mode)
        for case in cases:
            for r in range(repeat):
                rec = run_core_case(case, make_gen(case, provider), max_attempts, mode, r)
                if timed:
                    rec["overhead_samples_ms"] = [round(run_core_case(case, make_gen(case, provider), max_attempts, mode)["loop_ms"], 3)
                                                  for _ in range(timing_runs)]
                emit(rec)
        return

    params = StdioServerParameters(command=sys.executable, args=["-m", "type_safe_llm.bench.server"])
    setup_started = time.perf_counter()
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=60)) as session:
            await session.initialize()
            setup_ms = round((time.perf_counter() - setup_started) * 1000, 1)
            print(f"MCP session ready in {setup_ms} ms (server process start + initialize handshake)")
            if timed:
                for case in cases:
                    await run_mcp_case(session, case, make_gen(case, provider), max_attempts, mode)
            for case in cases:
                for r in range(repeat):
                    rec = await run_mcp_case(session, case, make_gen(case, provider), max_attempts, mode, r)
                    if timed:
                        samples = []
                        for _ in range(timing_runs):
                            samples.append(round((await run_mcp_case(session, case, make_gen(case, provider), max_attempts, mode))["loop_ms"], 3))
                        rec["overhead_samples_ms"] = samples
                    rec["session_setup_ms"] = setup_ms
                    emit(rec)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Run the shared experiment corpus through a Python track.")
    parser.add_argument("--track", choices=TRACKS, required=True)
    parser.add_argument("--provider", choices=["scripted", "live"], default="scripted")
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--timing-runs", type=int, default=None, help="scripted mode: timed re-runs per case (default 50)")
    parser.add_argument("--schemas-out", type=Path)
    parser.add_argument("--only", help="comma-separated case ids")
    args = parser.parse_args(argv)

    file = load_cases(args.cases)
    cases = [c for c in file["cases"] if not args.only or c["id"] in args.only.split(",")]
    timing_runs = args.timing_runs if args.timing_runs is not None else (50 if args.provider == "scripted" else 0)
    if args.schemas_out:
        dump_schemas(args.schemas_out)
    if args.provider == "live":
        config = LiveConfig.from_environment()
        print(f"Live run | model {config.model} @ {config.base_url}")
        name = f"{args.track}-live-{config.model}".replace(":", "_").replace("/", "_")
    else:
        name = f"{args.track}-scripted"
    out = args.out or REPO / "experiments" / "results" / f"{name}.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)

    with out.open("w", encoding="utf-8") as handle:
        def emit(rec: dict) -> None:
            handle.write(json.dumps(rec) + "\n")
            mark = "ok " if rec["ok"] else "-- "
            print(f"{mark} {rec['case_id']:<40} {rec['status']:<18} attempts={len(rec['attempts'])}")

        asyncio.run(run_track(args.track, cases, file["max_attempts"], args.provider, args.repeat, timing_runs, emit))
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
