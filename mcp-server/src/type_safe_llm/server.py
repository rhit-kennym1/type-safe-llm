"""Local stdio MCP server; stdout is reserved for the protocol."""

from typing import Annotated, Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.prompts.base import UserMessage
from pydantic import BaseModel, Field

from .core import RetryResult, ValidationResult, extraction_prompt, generate_validated, validate_json
from .fixtures import EXAMPLES, SOURCES, simulated_responses
from .schemas import SCHEMAS

SchemaName = Literal["event", "contract"]
Scenario = Literal["valid", "wrong_type", "missing_field", "malformed_json", "domain_error", "exhausted"]

mcp = FastMCP("Type-Safe LLM Demo", instructions=(
    "Discover schemas, validate JSON model output, and demonstrate retry recovery. "
    "run_retry_demo uses simulated responses, not a live LLM. "
    "Only treat data as validated when ok is true."
))


class SchemaCatalog(BaseModel):
    schemas: dict[str, dict]


@mcp.tool()
def list_schemas() -> SchemaCatalog:
    """List named JSON schemas with valid examples and synthetic source text."""
    return SchemaCatalog(schemas={name: {
        "json_schema": model.model_json_schema(),
        "example": EXAMPLES[name], "source": SOURCES[name],
    } for name, model in SCHEMAS.items()})


@mcp.tool()
def validate_output(schema_name: SchemaName, content: str) -> ValidationResult:
    """Strictly validate JSON: reject wrong types, missing/extra fields and domain errors.

    Invalid content returns ok=false, data=null and field-level errors.
    Dates are ISO strings in JSON. No markdown stripping or numeric-string coercion.
    """
    return validate_json(schema_name, content)


@mcp.tool()
def run_retry_demo(
    schema_name: SchemaName = "event", scenario: Scenario = "wrong_type",
    max_retries: Annotated[int, Field(strict=True, ge=0, le=5)] = 2,
) -> RetryResult:
    """Run an offline SIMULATED model demo, showing validation errors and repair prompts.

    Most scenarios produce one bad response followed by a valid response.
    exhausted always returns invalid content. max_retries excludes the first attempt.
    """
    responses = simulated_responses(schema_name, scenario)
    index = 0

    def mock_generate(prompt: str) -> str:
        nonlocal index
        response = responses[min(index, len(responses) - 1)]
        index += 1
        return response

    return generate_validated(schema_name, SOURCES[schema_name], mock_generate,
                              max_retries=max_retries, provider="simulated (no LLM call)")


@mcp.resource("demo://guide")
def demo_guide() -> str:
    return (
        "1. list_schemas to inspect event and contract schemas.\n"
        "2. validate_output with an example; then change a number to text.\n"
        "3. run_retry_demo(scenario='wrong_type') to see recovery.\n"
        "4. run_retry_demo(scenario='exhausted') to see bounded failure.\n"
        "Retry responses are synthetic. JSON only. Validate real model output using "
        "validate_output and require ok=true before consuming data. Validation checks "
        "structure and domain rules, not factual accuracy."
    )


@mcp.prompt()
def extract_typed_json(schema_name: SchemaName, source: str) -> list[UserMessage]:
    """Prepare a schema-aware extraction prompt for the host's model.

    Message 1 is the extraction task. Message 2 tells a tool-calling host to validate;
    a host without tool access should send only message 1.
    """
    return [
        UserMessage(extraction_prompt(schema_name, source)),
        UserMessage(
            "Before using the extracted object, call validate_output with this schema "
            "and your JSON. If ok=false, repair it using the errors and validate again."
        ),
    ]


def main() -> None:
    mcp.run(transport="stdio")


if __name__ == "__main__":
    main()
