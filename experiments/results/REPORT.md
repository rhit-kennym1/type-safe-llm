# Experiment results: wrapper library vs. MCP server

Generated 2026-10-23 19:50 UTC by `experiments/compare.py` from 126 result records. Regenerate after any run; do not edit by hand.

Corpus: 42 cases (baseline 4, recovery 6, format 5, types 6, constraints 7, structure 8, semantic 3, injection 2, stall 1), max 3 attempts per case. Case definitions: `experiments/cases/build_cases.py`.

Tracks: **Kotlin library** (`kotlin-library/`), **Python validator in-process** (the MCP server's validation core called directly, the way a library would use it), and **Python validator over MCP** (the same core behind a real stdio MCP session: the server supplies the prompt, and every attempt is a `tools/call`). Comparing the last two isolates the cost of the protocol; comparing the first two compares the implementations.

Runs included:

- `kotlin-library` · scripted · scripted · 42 records
- `python-core` · scripted · scripted · 42 records
- `mcp-server` · scripted · scripted · 42 records

## 1. Correctness on the adversarial corpus (scripted responses)

Scripted mode feeds every track byte-identical model responses, so differences come from the libraries, not the model.

| | Kotlin library | Python validator (in-process) | Python validator over MCP |
|---|---|---|---|
| Correct outcome on schema-detectable cases | 35/38 (92%) | 36/38 (95%) | 35/38 (92%) |
| Bad output returned as valid data (leaks) | 3: `contract-bool-as-string`, `event-count-as-string`, `event-duplicate-key` | 1: `event-duplicate-key` | 1: `event-duplicate-key` |
| Good output rejected (false rejects) | 0: none | 1: `event-code-fence` | 2: `event-code-fence`, `thread-deep-60` |
| Recovered after a bad first answer | 6/6 (100%) | 6/6 (100%) | 6/6 (100%) |
| Wrong-but-well-formed output passed (expected; no schema can catch it) | 4/4 (100%) | 4/4 (100%) | 4/4 (100%) |
| Problems named per error report | first one only | all of them | all of them |
| Mean length of an error report sent back to the model | 176 chars | 73 chars | 73 chars |

### Outcome for every case

Numbers in parentheses are attempts used. ⚠ marks an outcome that differs from the ideal on a case a schema check can detect.

| Case | Category | Ideal | Kotlin library | Python validator (in-process) | Python validator over MCP | What it probes |
|---|---|---|---|---|---|---|
| `event-valid` | baseline | accept | validated (1) | validated (1) | validated (1) | A fully valid event on the first try. |
| `contract-valid` | baseline | accept | validated (1) | validated (1) | validated (1) | A fully valid contract with nested clauses. |
| `thread-valid` | baseline | accept | validated (1) | validated (1) | validated (1) | A valid recursive comment tree, three levels deep. |
| `order-card-valid` | baseline | accept | validated (1) | validated (1) | validated (1) | A valid sealed-type value (card branch). |
| `event-prose-then-json` | recovery | accept | validated (2) | validated (2) | validated (2) | Chatty prose first; the retry prompt must get JSON back. |
| `event-missing-field-then-fix` | recovery | accept | validated (2) | validated (2) | validated (2) | A required field is missing; the error must name it so the retry can add it. |
| `contract-wrong-type-then-fix` | recovery | accept | validated (1) | validated (2) | validated (2) | A number arrives as a string; strict decoding rejects it, the retry fixes it. |
| `order-missing-discriminator-then-fix` | recovery | accept | validated (2) | validated (2) | validated (2) | The sealed-type discriminator is missing, so no branch can be chosen until the retry adds it. |
| `thread-wrong-shape-then-fix` | recovery | accept | validated (2) | validated (2) | validated (2) | A recursive field arrives as a string instead of a list of comments. |
| `event-two-mistakes` | recovery | accept | validated (3) | validated (3) | validated (3) | Two different mistakes in a row; recovery needs the third and last attempt. |
| `event-code-fence` | format | accept | validated (1) | **rejected (3)** ⚠ | **rejected (3)** ⚠ | Valid JSON inside a Markdown fence, which models add even when told not to. |
| `event-prose-around-json` | format | reject | rejected (3) | rejected (3) | rejected (3) | Valid JSON surrounded by prose. Strict libraries reject it; extracting it is a heuristic. |
| `event-trailing-comma` | format | reject | rejected (3) | rejected (3) | rejected (3) | A trailing comma: invalid JSON that many lenient parsers accept. |
| `event-truncated` | format | reject | rejected (3) | rejected (3) | rejected (3) | Output cut off mid-document (e.g., the model hit its token limit). |
| `event-empty` | format | reject | rejected (3) | rejected (3) | rejected (3) | An empty response. |
| `event-count-as-string` | types | reject | **validated (1)** ⚠ | rejected (3) | rejected (3) | An integer field arrives as a numeric string. |
| `event-count-as-float` | types | reject | rejected (3) | rejected (3) | rejected (3) | An integer field arrives as 40.0. |
| `order-total-as-int` | types | accept | validated (1) | validated (1) | validated (1) | A decimal field arrives as a whole number, which is still a valid number. |
| `event-null-required` | types | reject | rejected (3) | rejected (3) | rejected (3) | A required, non-nullable field is null. |
| `event-enum-case` | types | reject | rejected (3) | rejected (3) | rejected (3) | An enum value with the wrong capitalization. |
| `contract-bool-as-string` | types | reject | **validated (1)** ⚠ | rejected (3) | rejected (3) | A boolean arrives as the string "true". |
| `event-negative-count` | constraints | reject | rejected (3) | rejected (3) | rejected (3) | Attendee count must be zero or more. |
| `event-blank-title` | constraints | reject | rejected (3) | rejected (3) | rejected (3) | A required string that is only whitespace. |
| `contract-one-party` | constraints | reject | rejected (3) | rejected (3) | rejected (3) | A contract needs at least two parties. |
| `contract-impossible-date` | constraints | reject | rejected (3) | rejected (3) | rejected (3) | A well-formed but impossible calendar date. |
| `contract-expiry-before-start` | constraints | reject | rejected (3) | rejected (3) | rejected (3) | A cross-field rule: the contract cannot expire before it starts. |
| `order-bad-last4` | constraints | reject | rejected (3) | rejected (3) | rejected (3) | Card last-four must be exactly four digits. |
| `event-naive-timestamp` | constraints | reject | rejected (3) | rejected (3) | rejected (3) | A timestamp without a UTC offset is ambiguous and must be rejected. |
| `order-discriminator-mismatch` | structure | reject | rejected (3) | rejected (3) | rejected (3) | The discriminator says card but the body has bank fields. |
| `order-unknown-variant` | structure | reject | rejected (3) | rejected (3) | rejected (3) | A payment type that isn't one of the sealed branches. |
| `order-cash-valid` | structure | accept | validated (1) | validated (1) | validated (1) | A branch with no fields besides the discriminator. |
| `order-cash-with-extra` | structure | reject | rejected (3) | rejected (3) | rejected (3) | Extra fields on a field-less branch. |
| `thread-deep-60` | structure | accept | validated (1) | validated (1) | **server error (1)** ⚠ | A 60-level reply chain: recursion must not hit a depth limit. |
| `event-extra-field` | structure | reject | rejected (3) | rejected (3) | rejected (3) | A top-level field that isn't in the schema. |
| `event-nested-extra-field` | structure | reject | rejected (3) | rejected (3) | rejected (3) | An unknown field inside a nested object. |
| `event-duplicate-key` | structure | reject | **validated (1)** ⚠ | **validated (1)** ⚠ | **validated (1)** ⚠ | The same key twice. JSON leaves this undefined; a strict validator should not silently pick one. |
| `event-confident-wrong` | semantic | reject (undetectable) | validated (1) | validated (1) | validated (1) | Plausible but wrong values (400 attendees, wrong city). Nothing in the schema can catch this. |
| `contract-hallucinated-clause` | semantic | reject (undetectable) | validated (1) | validated (1) | validated (1) | A clause the source never mentions. |
| `thread-flattened` | semantic | reject (undetectable) | validated (1) | validated (1) | validated (1) | Valid tree, wrong shape: sam's reply to luis is flattened to the top level. |
| `order-injection-obeyed` | injection | reject (undetectable) | validated (1) | validated (1) | validated (1) | The model follows an instruction hidden in the source. The output is well-formed, so validation passes. |
| `order-injection-extra-field` | injection | reject | rejected (3) | rejected (3) | rejected (3) | An injected instruction adds a privileged field; the schema blocks it. |
| `event-stall` | stall | reject | rejected (3) | rejected (3) | rejected (3) | The same invalid answer every time. The loop must stop at its limit and return an error, not partial data. |

### Where the tracks disagree

**`event-code-fence`**: Valid JSON inside a Markdown fence, which models add even when told not to.

- Kotlin library: validated; accepted on the first attempt
- Python validator (in-process): rejected; first error: `$: json_invalid: Invalid JSON: expected value at line 1 column 1`
- Python validator over MCP: rejected; first error: `$: json_invalid: Invalid JSON: expected value at line 1 column 1`

**`event-count-as-string`**: An integer field arrives as a numeric string.

- Kotlin library: validated; accepted on the first attempt
- Python validator (in-process): rejected; first error: `attendee_count: int_type: Input should be a valid integer`
- Python validator over MCP: rejected; first error: `attendee_count: int_type: Input should be a valid integer`

**`contract-bool-as-string`**: A boolean arrives as the string "true".

- Kotlin library: validated; accepted on the first attempt
- Python validator (in-process): rejected; first error: `auto_renew: bool_type: Input should be a valid boolean`
- Python validator over MCP: rejected; first error: `auto_renew: bool_type: Input should be a valid boolean`

**`thread-deep-60`**: A 60-level reply chain: recursion must not hit a depth limit.

- Kotlin library: validated; accepted on the first attempt
- Python validator (in-process): validated; accepted on the first attempt
- Python validator over MCP: server error; first error: `server error: Error executing tool validate_output: Error serializing to JSON: ValueError: Circular reference detected (depth exceeded)`

### How each track classifies the first failure

| Category | Kotlin library | Python validator (in-process) | Python validator over MCP |
|---|---|---|---|
| MCP server failed to respond | 0 | 0 | 1 |
| domain rule | 7 | 7 | 7 |
| empty response | 1 | 1 | 1 |
| invalid enum value | 1 | 1 | 1 |
| missing field | 2 | 2 | 2 |
| not valid JSON | 5 | 6 | 6 |
| sealed-type discriminator | 2 | 2 | 2 |
| unexpected field | 5 | 5 | 5 |
| wrong type or malformed value | 3 | 6 | 6 |

## 2. Cost: what each track sends to the model

Characters of prompt and response text per extraction, retries included. Token and cost figures are estimates at 4 characters per token, priced on `gemini-3.1-flash-lite` ($0.25/1M input, $1.5/1M output; source in `pricing.json`). Live runs below use the provider's reported token counts instead.

| | Kotlin library | Python validator (in-process) | Python validator over MCP |
|---|---|---|---|
| Mean attempts per extraction | 2.14 | 2.31 | 2.31 |
| Prompt characters per extraction | 2,918 | 3,992 | 3,992 |
| Estimated input tokens per extraction | 730 | 998 | 998 |
| Estimated output tokens per extraction | 124 | 137 | 137 |
| Estimated cost per 1,000 extractions (gemini-3.1-flash-lite) | $0.368 | $0.454 | $0.454 |

Prompt size per attempt on the cases that used all three attempts (shows how retry prompts grow):

| | attempt 1 | attempt 2 | attempt 3 |
|---|---|---|---|
| Kotlin library | 993 chars | 1,479 chars | 1,970 chars |
| Python validator (in-process) | 1,513 chars | 1,918 chars | 1,924 chars |
| Python validator over MCP | 1,513 chars | 1,918 chars | 1,924 chars |

(Averaged over the 22 cases where every track used three attempts.)

## 3. Latency overhead (scripted, model time excluded)

With scripted responses the model answers instantly, so this is pure library or protocol time per extraction: each case re-run many times after a warm-up pass. Real model calls take hundreds to thousands of milliseconds, so compare these against that scale.

| | Kotlin library | Python validator (in-process) | Python validator over MCP |
|---|---|---|---|
| Median per extraction | 0.089 ms | 0.816 ms | 33.5 ms |
| 95th percentile per extraction | 0.285 ms | 1.273 ms | 42.0 ms |
| Timed samples | 2100 | 2100 | 2100 |
| MCP round trips per extraction | — | — | 1 `prompts/get` + 1 `tools/call` per attempt |
| Median time per MCP round trip | — | — | 8.787 ms |
| One-time MCP session start (process + handshake) | — | — | 1814.8 ms |

## 4. Live model runs

None yet. Live runs need a model endpoint (Ollama locally, or a Gemini key), which the environment that generated this report did not have. Commands are in `experiments/README.md`; rerun `compare.py` afterwards and this section fills in.

## 5. Schema parity between the two implementations

The benchmark types are written twice (Kotlin and Pydantic). This checks that both generate the same structure, so outcome differences come from the libraries rather than from mismatched schemas.

| Target | Same fields, types, required sets? | Rule keywords advertised to the model (Kotlin / Python) |
|---|---|---|
| contract | yes | 0 / 8 (format 2, maximum 1, minItems 2, minLength 2, minimum 1) |
| event | yes | 0 / 5 (format 1, minLength 3, minimum 1) |
| order | yes | 0 / 6 (minLength 2, minimum 1, pattern 3) |
| thread | yes | 0 / 2 (minLength 2) |

## 6. Method and caveats

- Scripted responses test the libraries' handling of model output; they say nothing about how often a real model makes each mistake. Section 4 covers that.
- Each track uses its own prompt design as built (Kotlin: system prompt with the schema plus the full conversation on retry; Python: one prompt with the schema, rebuilt with the previous answer and errors on retry). Prompt cost differences are a property of those designs.
- Per-extraction totals depend on how many attempts a track used. A track that wrongly accepts bad output on the first attempt looks cheaper and faster for that case, so compare prompt designs with the per-attempt table.
- Character counts exclude any schema a provider receives through a native structured-output field.
- Timings come from one machine. Treat ratios between tracks as meaningful and absolute numbers as indicative.
- `ideal` is the team's judgment of what a type-safe library should do; reasons are in each case's description.
