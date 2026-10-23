"""MCP server for the experiment: the same validator as the demo server, registered for the benchmark schemas.

Kept separate from server.py so the demo's tool contract (schema names event/contract) doesn't change.
The experiment's host loop talks to this over real stdio MCP, which is what makes protocol overhead measurable.
Run directly: python -m type_safe_llm.bench.server
"""

from typing import Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.prompts.base import UserMessage

from ..core import ValidationResult, extraction_prompt, validate_json
from .schemas import BENCH_SCHEMAS

BenchSchema = Literal["event", "contract", "thread", "order"]

mcp = FastMCP("Type-Safe LLM Bench", instructions=(
    "Validate JSON model output against the benchmark schemas. Only treat data as validated when ok is true."
), log_level="WARNING")  # the default INFO logs every request to stderr, which would inflate protocol timings


@mcp.tool()
def validate_output(schema_name: BenchSchema, content: str) -> ValidationResult:
    """Strictly validate JSON against a benchmark schema; returns field-level errors when invalid."""
    return validate_json(schema_name, content, model=BENCH_SCHEMAS[schema_name])


@mcp.prompt()
def extract_typed_json(schema_name: BenchSchema, source: str) -> list[UserMessage]:
    """Schema-aware extraction prompt for the host's model."""
    return [UserMessage(extraction_prompt(schema_name, source, model=BENCH_SCHEMAS[schema_name]))]


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
