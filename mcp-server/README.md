# Type-Safe LLM MCP demo

A local MCP server demonstrating the project's schema -> parse -> validate -> retry
workflow. No API key is needed. The retry demo uses **simulated model responses**;
`validate_output` can validate JSON from any actual model. The validation core is
independent of MCP so a wrapper library can reuse it.

## View the report without installing anything

A saved presentation report is included at [docs/demo-report.html](docs/demo-report.html).
On GitHub, open that file and use **Download raw file**, then open the downloaded
HTML file in Chrome, Edge, Firefox, or Safari. GitHub's file view displays the source;
the downloaded file displays the interactive report.

All five tabs, navigation buttons, and expandable details work offline. No Python,
server, API key, or extra files are needed. This is a snapshot of a completed demo
with simulated model responses; it does not rerun validation or call an LLM.

## Run the demo (PowerShell)

Requires Python 3.11 or newer. From the repository root, enter `mcp-server`
before creating the virtual environment or running the commands:

```powershell
cd mcp-server
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

## Web UI (like the Kotlin demo)

```powershell
.\.venv\Scripts\python.exe -m type_safe_llm.web
```

Opens http://localhost:8765 (`--port` to change, `--no-open` to skip the browser). Standard
library only. Two tabs, with the same four-step flow as the Kotlin demo (source text, fields,
what happened, result):

- **Examples**: simulated model answers (wrong type, missing field, broken JSON, retries
  exhausted, ...) validated through a real MCP stdio session.
- **Live model**: your configured model (`.env`). Choose **who validates**: *the program*
  (it calls `validate_output` after every answer and re-prompts) or *the model* (it decides
  whether to call the tool; the result shows whether it did, and the program still checks the
  final answer). You can edit the source text and pick `event` or `contract`.

Safety: binds to `127.0.0.1` only, checks the `Host` header, requires JSON `Content-Type` on
POSTs, serves only a fixed list of static files, and never sends the API key to the browser.
Each run starts its own MCP server subprocess, so a run takes a second or two plus model time.

## Run with a real model (Ollama, free and local)

1. Install Ollama from https://ollama.com and start it.
2. Download the default model once: `ollama pull llama3.2:3b`
3. From `mcp-server`:

```powershell
.\.venv\Scripts\python.exe -m type_safe_llm.live --schema event
.\.venv\Scripts\python.exe -m type_safe_llm.live --schema contract --max-retries 3
```

Here the **host drives the loop**: it fetches the `extract_typed_json` prompt from the
server, sends it to the model, calls `validate_output` over a real MCP stdio session,
and re-prompts with the errors on failure. This differs from `run_retry_demo`, which
simulates the model on the server side. Small models often fail strict JSON; that is a
legitimate result and is reported, not hidden.

Any OpenAI-compatible endpoint works by setting `LIVE_BASE_URL`, `LIVE_API_KEY` and
`LIVE_MODEL` (these are the same variables the Kotlin demo uses). Live tests are skipped
by default; run them with `$env:TYPED_LLM_LIVE = "1"` then `pytest tests/test_live.py -s`.

### Tool-calling mode and other providers

```powershell
.\.venv\Scripts\python.exe -m type_safe_llm.live --schema event --tool-calling
```

Here the **model** decides whether to call `validate_output` (the server's tools are sent
to it in the OpenAI `tools` format). The host still validates the final text itself, and
the output reports whether the model ever called the validator. Needs a tool-capable model.

| Backend | `LIVE_BASE_URL` | `LIVE_API_KEY` | `LIVE_MODEL` |
| --- | --- | --- | --- |
| Ollama (default) | `http://localhost:11434/v1` | any | `llama3.2:3b` |
| Gemini free tier | `https://generativelanguage.googleapis.com/v1beta/openai` | Google AI Studio key | `gemini-3.1-flash-lite` |
| OpenAI (paid) | `https://api.openai.com/v1` | your key | e.g. `gpt-4o-mini` |

Put these in `mcp-server/.env` (copy [.env.example](.env.example)); the file is gitignored and
loaded automatically. Variables already set in your shell take priority. Rate limits (HTTP 429) are
retried twice. Gemini and Ollama have been run; the OpenAI row is untested. Google retires
models for new accounts and gets overloaded (HTTP 503) often; if a model 404s or 503s, list
what your key can use with a GET to `<LIVE_BASE_URL>/models` and pick another.

## Connect an MCP host

Use [examples/mcp-config.json](examples/mcp-config.json) in a host that accepts the
`mcpServers` configuration format. Replace the command with the **absolute path**
to this repository's virtual environment Python. Editable installation above makes
the package available regardless of the host's working directory. On macOS/Linux
use `.venv/bin/python` instead of `.venv/Scripts/python.exe`.

The virtual environment lives inside `mcp-server`, so the absolute executable path
ends in `type-safe-llm/mcp-server/.venv/Scripts/python.exe` on Windows.
Run the remaining commands in this guide from `mcp-server`.

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
