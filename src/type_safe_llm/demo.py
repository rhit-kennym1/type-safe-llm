"""A repeatable presentation demo using a real MCP stdio client/server session."""

import asyncio
import json
import sys
from datetime import timedelta
from typing import Any

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def exercise_server() -> dict[str, Any]:
    params = StdioServerParameters(command=sys.executable,
                                   args=["-m", "type_safe_llm.server"])
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=30)) as session:
            await session.initialize()
            tools = await session.list_tools()

            async def call(name: str, arguments: dict) -> dict:
                result = await session.call_tool(name, arguments)
                if result.isError or result.structuredContent is None:
                    raise RuntimeError(f"Tool {name} failed: {result.content}")
                return result.structuredContent

            catalog = await call("list_schemas", {})
            event = catalog["schemas"]["event"]["example"]
            valid = await call("validate_output", {"schema_name": "event", "content": json.dumps(event)})
            invalid_event = {**event, "attendee_count": "30"}
            invalid = await call("validate_output", {
                "schema_name": "event", "content": json.dumps(invalid_event),
            })
            recovered = await call("run_retry_demo", {"scenario": "wrong_type"})
            exhausted = await call("run_retry_demo", {"scenario": "exhausted", "max_retries": 1})
            contract = await call("run_retry_demo", {"schema_name": "contract", "scenario": "missing_field"})
            guide = await session.read_resource("demo://guide")
            prompt = await session.get_prompt("extract_typed_json", arguments={
                "schema_name": "event", "source": catalog["schemas"]["event"]["source"],
            })
            return {
                "tools": [t.name for t in tools.tools],
                "output_schemas": {t.name: t.outputSchema for t in tools.tools},
                "catalog": catalog, "valid": valid, "invalid": invalid,
                "recovered": recovered, "exhausted": exhausted, "contract": contract,
                "guide_available": bool(guide.contents), "prompt_available": bool(prompt.messages),
            }


def main() -> None:
    print("Type-Safe LLM | local MCP demo | simulated model, no API key", flush=True)
    results = asyncio.run(exercise_server())
    print("\nDiscovered tools: " + ", ".join(results["tools"]))
    for title, key in [
        ("1. Valid event -> accepted", "valid"),
        ('2. attendee_count="30" -> rejected (no coercion)', "invalid"),
        ("3. Invalid model response -> repair prompt -> validated", "recovered"),
        ("4. Repeated invalid responses -> retries exhausted", "exhausted"),
        ("5. Nested contract with missing title -> repaired", "contract"),
    ]:
        print(f"\n{title}\n{json.dumps(results[key], indent=2)}")
    print("\nGuide resource and extraction prompt discovered successfully.")


if __name__ == "__main__":
    main()
