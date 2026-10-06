"""Live run where the host drives validation and retries through a real MCP client.

Unlike `run_retry_demo`, the model here is real and the server only supplies the
prompt and the validator. The loop below plays the role of the MCP host.
Run: python -m type_safe_llm.live   (needs Ollama, or LIVE_BASE_URL/LIVE_API_KEY/LIVE_MODEL)
"""

import argparse
import asyncio
import json
import sys
from collections.abc import Callable
from datetime import timedelta
from typing import Any, Literal

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from pydantic import BaseModel, Field

from .core import Attempt, Issue, RetryResult, ValidationResult, repair_prompt
from .fixtures import SOURCES
from .providers import LiveConfig, ProviderError, chat_completion, chat_provider


async def run_live(
    schema_name: str, source: str, generate: Callable[[str], str],
    *, max_retries: int = 2, provider: str = "custom",
) -> RetryResult:
    if type(max_retries) is not int or not 0 <= max_retries <= 5:
        raise ValueError("max_retries must be an integer from 0 to 5")
    params = StdioServerParameters(command=sys.executable, args=["-m", "type_safe_llm.server"])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=30)) as session:
            await session.initialize()
            prompt_result = await session.get_prompt(
                "extract_typed_json", arguments={"schema_name": schema_name, "source": source})
            # Message 0 is the extraction task; later messages instruct tool-calling hosts,
            # which this loop is not (the model only produces text).
            original_prompt = getattr(prompt_result.messages[0].content, "text", "")
            prompt = original_prompt
            attempts: list[Attempt] = []
            for number in range(1, max_retries + 2):
                try:
                    content = await asyncio.to_thread(generate, prompt)
                except ProviderError as exc:
                    return RetryResult(ok=False, schema_name=schema_name, provider=provider,
                                       status="provider_error", attempts=attempts, error=str(exc))
                call = await session.call_tool(
                    "validate_output", {"schema_name": schema_name, "content": content})
                if call.isError or call.structuredContent is None:
                    raise RuntimeError(f"validate_output failed: {call.content}")
                result = ValidationResult.model_validate(call.structuredContent)
                attempt = Attempt(number=number, result=result, content=content)
                attempts.append(attempt)
                if result.ok:
                    return RetryResult(ok=True, schema_name=schema_name, provider=provider,
                                       status="validated", data=result.data, attempts=attempts)
                if number <= max_retries:
                    prompt = repair_prompt(original_prompt, content, result.errors)
                    attempt.repair_prompt = prompt
            return RetryResult(ok=False, schema_name=schema_name, provider=provider,
                               status="retries_exhausted", attempts=attempts)


class ToolCallRecord(BaseModel):
    name: str
    arguments_valid: bool
    validation_ok: bool | None = None  # only for validate_output calls the server accepted
    content: str | None = None  # the text the model asked to have validated
    errors: list[Issue] = Field(default_factory=list)


class ToolCallingResult(BaseModel):
    ok: bool
    schema_name: str
    provider: str
    status: Literal["validated", "invalid_final", "step_limit", "provider_error"]
    model_called_validate: bool = False
    model_calls: int = 0
    tool_calls: list[ToolCallRecord] = Field(default_factory=list)
    final_text: str | None = None
    data: dict[str, Any] | None = None
    errors: list[Issue] = Field(default_factory=list)
    error: str | None = None


Chat = Callable[[list[dict[str, Any]], list[dict[str, Any]] | None], dict[str, Any]]


def clean_schema(node: Any) -> Any:
    """Drop `title` keys (pure documentation) that some providers' tool-schema parsers reject."""
    if isinstance(node, dict):
        return {k: clean_schema(v) for k, v in node.items() if not (k == "title" and isinstance(v, str))}
    if isinstance(node, list):
        return [clean_schema(v) for v in node]
    return node


async def run_tool_calling(
    schema_name: str, source: str, chat: Chat,
    *, max_retries: int = 2, provider: str = "custom", tool_names: tuple[str, ...] = ("validate_output",),
) -> ToolCallingResult:
    """The model chooses whether to call `validate_output`; the host still checks the final text.

    `max_retries` bounds model round trips to 2 * (max_retries + 1): about one tool call
    plus one reply per attempt. A passing validate_output call does not make the final
    text valid, so the host validates that text itself.
    """
    if type(max_retries) is not int or not 0 <= max_retries <= 5:
        raise ValueError("max_retries must be an integer from 0 to 5")
    params = StdioServerParameters(command=sys.executable, args=["-m", "type_safe_llm.server"])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=30)) as session:
            await session.initialize()
            listed = await session.list_tools()
            tools = [{"type": "function", "function": {
                "name": t.name, "description": t.description or "",
                "parameters": clean_schema(t.inputSchema),
            }} for t in listed.tools if t.name in tool_names]
            prompt_result = await session.get_prompt(
                "extract_typed_json", arguments={"schema_name": schema_name, "source": source})
            # Both messages: the second tells a tool-calling host to validate first.
            text = "\n\n".join(getattr(m.content, "text", "") for m in prompt_result.messages)
            messages: list[dict[str, Any]] = [{"role": "user", "content": text}]
            records: list[ToolCallRecord] = []
            model_calls = 0
            final_text: str | None = None

            def result(status: str, **extra: Any) -> ToolCallingResult:
                return ToolCallingResult(
                    ok=status == "validated", schema_name=schema_name, provider=provider,
                    status=status,  # type: ignore[arg-type]
                    model_called_validate=any(r.name == "validate_output" for r in records),
                    model_calls=model_calls, tool_calls=records, final_text=final_text, **extra)

            for _ in range(2 * (max_retries + 1)):
                try:
                    message = await asyncio.to_thread(chat, messages, tools)
                except ProviderError as exc:
                    return result("provider_error", error=str(exc))
                model_calls += 1
                messages.append(message)
                calls = message.get("tool_calls") or []
                if not calls:
                    content = message.get("content")
                    final_text = content if isinstance(content, str) else ""
                    break
                for call in calls:
                    function = call.get("function", {})
                    name = str(function.get("name", ""))
                    raw = function.get("arguments", {})
                    try:
                        arguments = json.loads(raw) if isinstance(raw, str) else raw
                        if not isinstance(arguments, dict):
                            raise ValueError
                        arguments_valid = True
                    except ValueError:
                        arguments, arguments_valid = {}, False
                    record = ToolCallRecord(name=name, arguments_valid=arguments_valid)
                    records.append(record)
                    if name not in tool_names:
                        output = f"Unknown tool {name!r}. Available: {', '.join(tool_names)}"
                    elif not arguments_valid:
                        output = "Tool arguments were not a valid JSON object."
                    else:
                        called = await session.call_tool(name, arguments)
                        if called.isError or called.structuredContent is None:
                            record.arguments_valid = False  # server rejected the arguments
                            output = " ".join(getattr(c, "text", "") for c in called.content) or "Tool call failed."
                        else:
                            if name == "validate_output":
                                record.validation_ok = bool(called.structuredContent.get("ok"))
                                raw_content = arguments.get("content")
                                record.content = raw_content if isinstance(raw_content, str) else None
                                record.errors = [Issue.model_validate(e)
                                                 for e in called.structuredContent.get("errors", [])]
                            output = json.dumps(called.structuredContent)
                    messages.append({"role": "tool", "tool_call_id": call.get("id", ""),
                                     "name": name, "content": output})
            else:
                return result("step_limit")

            # Host enforcement: never trust the model's claim that it validated.
            checked = await session.call_tool(
                "validate_output", {"schema_name": schema_name, "content": final_text or ""})
            if checked.isError or checked.structuredContent is None:
                raise RuntimeError(f"validate_output failed: {checked.content}")
            final = ValidationResult.model_validate(checked.structuredContent)
            if final.ok:
                return result("validated", data=final.data)
            return result("invalid_final", errors=final.errors)


def main() -> None:
    parser = argparse.ArgumentParser(description="Run a live model through the MCP server.")
    parser.add_argument("--schema", choices=["event", "contract"], default="event")
    parser.add_argument("--max-retries", type=int, default=2)
    parser.add_argument("--tool-calling", action="store_true",
                        help="Let the model call validate_output itself (needs a tool-capable model)")
    args = parser.parse_args()
    config = LiveConfig.from_environment()
    print(f"Live MCP run | model {config.model} @ {config.base_url}", flush=True)
    if args.tool_calling:
        tc = asyncio.run(run_tool_calling(
            args.schema, SOURCES[args.schema], lambda m, t: chat_completion(config, m, t),
            max_retries=args.max_retries, provider=config.model))
        for record in tc.tool_calls:
            print(f"  tool call: {record.name} args_valid={record.arguments_valid} "
                  f"validation_ok={record.validation_ok}")
        print(f"\nStatus: {tc.status} | model called validate_output: {tc.model_called_validate} "
              f"| model calls: {tc.model_calls}")
        for issue in tc.errors:
            print(f"  {issue.path}: {issue.message}")
        if tc.error:
            print(tc.error)
        if tc.data:
            print(tc.data)
        raise SystemExit(0 if tc.ok else 1)
    result = asyncio.run(run_live(args.schema, SOURCES[args.schema], chat_provider(config),
                                  max_retries=args.max_retries, provider=config.model))
    for attempt in result.attempts:
        status = "valid" if attempt.result.ok else "invalid"
        print(f"\nAttempt {attempt.number}: {status}")
        for error in attempt.result.errors:
            print(f"  {error.path}: {error.message}")
    print(f"\nStatus: {result.status}")
    if result.error:
        print(result.error)
        if "localhost:11434" in config.base_url and "TimeoutError" not in result.error:
            print("Is Ollama running? Install it from https://ollama.com and run: ollama pull " + config.model)
    if result.data:
        print(result.data)
    raise SystemExit(0 if result.ok else 1)


if __name__ == "__main__":
    main()
