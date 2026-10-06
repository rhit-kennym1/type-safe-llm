# Type-Safe LLM MCP demo

A local MCP server demonstrating the project's schema -> parse -> validate -> retry
workflow. No API key is needed. The retry demo uses **simulated model responses**;
`validate_output` can validate JSON from any actual model. The validation core is
independent of MCP so a wrapper library can reuse it.

## Run the demo (PowerShell)

Requires Python 3.11 or newer. From this repository:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e '.[dev]'
.\.venv\Scripts\python.exe -m type_safe_llm.demo
```

The script starts a server subprocess and connects using a real MCP stdio client,
then opens a **visual report in your browser**. It shows a workflow diagram and five
examples with validation timelines, clear pass/fail results, and readable data tables.
Use the example tabs or Previous/Next buttons while presenting. Full JSON and repair
prompts are in expandable sections.

The report is saved to `demo-output/report.html`. You can reopen or share that single
HTML file without Python, internet access, or a running server. It captures the results
of the last run; the browser does not call the server. Use browser Print / Save as PDF
to export all five examples. Server and client stop after the report is generated.

```powershell
# Generate the report without opening a browser:
.\.venv\Scripts\python.exe -m type_safe_llm.demo --no-open

# Original detailed terminal output:
.\.venv\Scripts\python.exe -m type_safe_llm.demo --terminal
```

## Connect an MCP host

Use [examples/mcp-config.json](examples/mcp-config.json) in a host that accepts the
`mcpServers` configuration format. Replace the command with the **absolute path**
to this repository's virtual environment Python. Editable installation above makes
the package available regardless of the host's working directory. On macOS/Linux
use `.venv/bin/python` instead of `.venv/Scripts/python.exe`.

For a stdio host UI, set command to that Python executable and arguments to
`-m type_safe_llm.server`. The host launches the server. Running it directly waits
for protocol messages; it does not open a webpage or interactive terminal menu.

The adapter uses the official MCP Python SDK's v1 FastMCP API, constrained to `<2`
to avoid incompatible major-version changes. See the [SDK documentation](https://py.sdk.modelcontextprotocol.io/v1/).

## Presentation walkthrough

Ask the connected host:

1. “List the schemas available on the Type-Safe LLM server.”
2. “Validate the sample event. Then replace attendee_count with the string
   `"30"` and validate again. Explain the field-level error.”
3. “Run the event retry demo with scenario wrong_type. Show the failed attempt,
   repair prompt, and validated object.”
4. “Run scenario exhausted with max_retries 1. Show why no usable data is returned.”
5. “Run the contract retry demo with scenario missing_field.”

| Capability | MCP name | Behavior |
| --- | --- | --- |
| Schema discovery | `list_schemas` | JSON schemas, valid examples, synthetic source text |
| Runtime validation | `validate_output` | `ok`, validated `data`, field-level `errors` |
| Retry simulation | `run_retry_demo` | Attempts, repair prompts, bounded success/failure |
| Guide resource | `demo://guide` | Walkthrough and validation boundary |
| Extraction prompt | `extract_typed_json` | Schema-aware prompt for the host's model |

Tools advertise structured output schemas. Validation failures are expected domain
results: `ok=false`, `data=null`, and errors, rather than MCP protocol failures.
Invalid tool arguments are rejected by the MCP adapter.

Retry scenarios: `valid`, `wrong_type`, `missing_field`, `malformed_json`,
`domain_error`, `exhausted`. Choose `event` or `contract`. `max_retries` is 0–5
and excludes the initial attempt; 2 permits at most 3 provider calls.

## Schemas and guarantees

`event` demonstrates a timezone-aware timestamp, nonnegative integer, enum,
nested location, array, and optional notes. `contract` demonstrates parties,
ISO dates, a bounded payment period, boolean, nested clause array, and expiration
date ordering. Missing required fields, extra fields, invalid JSON, and wrong
types fail. Numeric strings and booleans do not pass as integers. JSON date strings
are parsed as dates; validated results serialize dates back to ISO strings.
See [Pydantic strict mode](https://docs.pydantic.dev/latest/concepts/strict_mode/).

This demo supports JSON only. It does not call Gemini, deploy to Azure, or implement
XML/YAML/CSV. Validation establishes structure and declared constraints, not factual
accuracy. An MCP host must actually invoke validation and require `ok=true` before
passing `data` downstream; tool discovery alone cannot guarantee this.

## Reuse the core with a real provider

```python
from type_safe_llm.core import generate_validated

# Supply a function that calls your provider and returns its response as text.
result = generate_validated("contract", contract_text, generate_text,
                            max_retries=2, provider="your-provider")
if not result.ok:
    raise RuntimeError(result.status)
consume(result.data)
```

The core supplies the schema and source on every call, with validation feedback on
retries. Configure timeouts and credentials inside your provider adapter. Provider
exceptions produce `provider_error` without exposing their exception messages.
Retry traces include source text and prior responses; the supplied demo data is synthetic.

## Verify

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

Tests cover strict/nested validation, parse errors, missing fields, enums, date and
domain constraints, provider failure, retry budgets, and actual MCP subprocess
discovery/tool/resource/prompt calls. No network or API credentials are required
after dependencies are installed.
