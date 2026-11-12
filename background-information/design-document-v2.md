# Type-Safe LLM Library: Design Document v2

Rose-Hulman CSSE497 · Team 1 · Parth Sundaram, Sanil Maheshwari, Mark J. Kenny, Rishi Ramesh
Updated October 22, 2026 (week 7). Replaces the design document and architecture spike in this folder; both
originals are kept for reference.

| Companion document | Covers |
|---|---|
| [architecture.md](architecture.md) | Diagrams of both approaches |
| [design-decisions.md](design-decisions.md) | Why each choice was made, with alternatives and costs |
| [market-landscape.md](market-landscape.md) | Existing tools, provider limits, what MCP provides natively |
| [deliverables-audit.md](deliverables-audit.md) | Feature-by-feature status on both tracks |

## 1. Problem

Organizations use LLMs to pull structured data out of unstructured text: contract clauses, event details, orders.
Models return text. Developers then parse it, check it and convert it into their application's types, and they
rewrite that code for every application. Missing fields, wrong types and malformed output cause failures downstream.

## 2. Project goal

> A caller asks for a result of type `T` and gets either a fully validated `T` or an explicit, typed failure. Never
> a partially filled object, an unchecked cast, or a raw string to parse.

What "validated" covers, and what it can't:

| Level | Example | Checked? |
|---|---|---|
| Syntax | Is it JSON at all? | Yes |
| Structure | Required fields present, correct types, no unknown fields, valid enum values | Yes |
| Domain rules | Count ≥ 0, expiry after start, card last-four is four digits | Yes, when the type declares them |
| Meaning | Is the extracted city actually the one in the email? | **No.** No schema can check this. The October test corpus had 4 such cases; every track accepted them, as expected. |

## 3. Two approaches

| | Wrapper library | MCP validation server |
|---|---|---|
| Built in | Kotlin (`kotlin-library/`) | Python (`mcp-server/`) |
| Developer writes | A `@Serializable` Kotlin class | Nothing; picks a named schema |
| Who calls the model | The library | The MCP host |
| Who guarantees validity | The library, always | The host, if it checks `ok` |
| Caller receives | A typed object, or an exception with every attempt | JSON plus `ok` and errors |
| Reach | JVM applications | Any MCP host, any language |

Diagrams: [architecture.md](architecture.md).

**How a library call works.** The schema comes from the class's compile-time descriptor. The library sends the
prompt with the schema (natively, when the provider supports it), strictly decodes the reply into the class, and on
failure resends the error to the model, up to a limit.

```kotlin
val llm = TypedLLM(GeminiProvider.fromEnvironment(model = "gemini-3.1-flash-lite"), maxAttempts = 3)
val event: Event = llm.call<Event>("Extract the event from: $email")
```

**How the MCP server works.** The server exposes `validate_output`, `list_schemas` and the `extract_typed_json`
prompt. The host gets the prompt, calls its model, sends the answer to `validate_output`, and retries with the
errors. In tool-calling mode the model calls `validate_output` itself, so the host must re-check the final answer.

## 4. Current implementation

Full status: [deliverables-audit.md](deliverables-audit.md). Highlights since week 5:

- **Library**: recursive types, sealed types (polymorphism), field descriptions, token usage and timing per attempt,
  and a native Gemini provider alongside the OpenAI-compatible one. The demo UI can run Gemini when a key is set.
- **Shared**: CI for both tracks. An experiment harness (a 42-case adversarial corpus and a missing-data suite) was
  built and run in October, then removed so the repository holds only the libraries, demos and their tests. It will
  be rebuilt for live runs.
- **Docs**: architecture diagrams, design decisions, market landscape.

Not built: XML and CSV, Azure hosting, a Kotlin MCP adapter, live experiment runs.

## 5. Results so far

All numbers are from scripted runs on Windows (Oct 23) of the experiment harness, since removed: each case's model
responses were fixed, so every track saw identical input. The Kotlin leaks below have since been fixed.

| | Kotlin library | Python, in-process | Python, over MCP |
|---|---|---|---|
| Correct on schema-detectable cases | 35/38 | 36/38 | 35/38 |
| Bad output returned as valid (leaks) | 3 | 1 | 1 |
| Good output rejected | 0 | 1 | 2 |
| Recovered after a bad first answer | 6/6 | 6/6 | 6/6 |
| Problems named per error | first only | all | all |
| Mean error length sent to the model | 176 chars | 73 chars | 73 chars |
| Prompt size, attempts 1 / 2 / 3 | 993 / 1,479 / 1,970 | 1,513 / 1,918 / 1,924 | same as in-process |
| Est. cost per 1,000 extractions (gemini-3.1-flash-lite) | $0.37 | $0.45 | $0.45 |
| Overhead per extraction, median / p95 | 0.09 / 0.29 ms | 0.82 / 1.27 ms | 33.5 / 42.0 ms |
| Rule keywords shown to the model (4 types) | 0 | 21 | 21 |

What this says:

1. **Neither validator was as strict as it should be.** Both silently accepted a duplicated key, and
   kotlinx-serialization also accepted `"40"` for an integer and `"true"` for a boolean. The library now runs its own
   type check before decoding and rejects all three; the Python validator still accepts duplicate keys.
2. **The tracks fail in opposite directions.** The library strips Markdown fences, so it never wrongly rejects fenced
   JSON. The Python validator doesn't, so it spends retries on it.
3. **Python's error feedback is better.** It names every problem in less than half the characters.
4. **The library hides its rules from the model.** Domain rules live in code, so the model learns them only by
   breaking them. Python's schemas carry them.
5. **MCP's protocol overhead is real but small next to a model call.** About 34 ms per extraction and 1.8 s to start
   a session on Windows (0.7 s on Linux), against model calls of hundreds to thousands of milliseconds. The library
   adds under a tenth of a millisecond.
6. **Crossing the MCP boundary can change the outcome, not just the latency.** On Windows, the server validates the
   60-level reply chain correctly but then can't send the result back: pydantic-core's serialization depth limit is
   lower there (a bare comment chain fails at 50 levels on Windows; over 100 on Linux). In-process Python and the
   Kotlin library both return it. It only affects deeply recursive data; the project's contract and event types are
   4–5 levels deep.
7. **Cost comparisons are confounded.** Kotlin looks cheaper partly because it wrongly accepted some bad answers on
   the first try. Per-attempt prompt size is the fair comparison: Kotlin's prompts start smaller but grow with every
   retry, Python's stay flat.
8. **Validation can't catch wrong-but-plausible answers.** Hallucinated clauses and followed prompt injections pass
   every track.

Missing: live runs, which show how often real models make each mistake.

## 6. Key decisions

Full reasoning in [design-decisions.md](design-decisions.md). The ones that shape the project most:

- **Kotlin with kotlinx-serialization, no reflection** ([D2](design-decisions.md#d2)): compile-time schemas,
  null-safety, reified generics. The serialization library's leniency is the main cost.
- **The developer passes a class** ([D3](design-decisions.md#d3)): one definition for schema, decoder and
  downstream code.
- **Native structured output is optional; validation always runs** ([D7](design-decisions.md#d7)): providers
  support different schema subsets, and Gemini ignores what it doesn't support.
- **The host owns the guarantee in MCP** ([D12](design-decisions.md#d12)): the protocol types tool inputs and
  server results, not the model's answer.

## 7. Position in the market

Typed LLM output is well served at the concept level. Every major provider offers schema-constrained output, and
frameworks in every major language return typed objects. JetBrains' **Koog** already does in Kotlin most of what our
library does. Our distinct contributions are:

- strict validation, including domain rules, measured on adversarial input;
- a side-by-side comparison of library vs. MCP validation;
- an MCP server that validates model output.

The MCP protocol has no feature that makes a model's answer match a schema. See
[market-landscape.md](market-landscape.md).

## 8. Known limitations

- The Python track accepts duplicate keys.
- Kotlin domain rules are invisible to the model, and rules that throw anything other than
  `IllegalArgumentException` crash the call instead of triggering a retry.
- Kotlin type errors are now listed together, but domain rules in `init` blocks still stop at the first failure.
- The MCP server only validates against the schemas it ships with.
- Over MCP on Windows, results nested beyond roughly 50 levels can't be returned (pydantic-core serialization limit).
  Separately, Pydantic's JSON parser rejects input nested beyond about 200 levels on every platform, so even
  in-process Python refuses a valid 100-level reply chain.
- No XML or CSV.
- No live measurements, and no experiment harness in the repository until it is rebuilt.
- The Ktor demo and its tests have been changed this week but not compiled in the environment the changes were made
  in; run `./gradlew build` locally before demoing.

## 9. Questions for Blaise

1. **Koog.** It covers most of the library's surface in Kotlin. Should the project become "make typed LLM output
   strict, and prove it" (contributing findings, possibly to Koog), or stay a standalone library?
2. **Formats.** Which formats must the model *generate*, and which only need to be *delivered* downstream? The
   second can be done by generating JSON and converting the validated object.
3. **Runtime schemas.** Do users need schemas that aren't known until runtime (uploaded, configured)? The library
   assumes compile-time classes.
4. **MCP direction.** Options: keep the Python validator; build a Kotlin MCP adapter over the library (one
   validator for both); or switch to the tool-arguments pattern (`submit_event(event)`), which is the protocol's
   closest native mechanism.
5. **Live evaluation budget.** Which models and how many runs? A full Gemini Flash-Lite pass is well under a dollar.
6. **Azure.** Is hosting still in scope for this term, or a spring item?

## 10. Roadmap

Research behind each choice is in the team's research log (Google Doc).

**Done, awaiting live runs**
1. Strict type pass before decoding (`StrictJson`): rejects quoted numbers and booleans, duplicate keys and plain
   text, and reports every problem in one message.
2. Near-exact quote matching for evidence checks: quotes of 12+ characters match source text that is at least 90% the
   same, and the value is then checked against the source's own words, with a note on the field.
3. Value normalization before comparing a value with its quote: number words, currency names and symbols, and
   kg/g/lb/oz and km/mi conversions within 1%.

**Next**
4. Rebuild the experiments (adversarial corpus and missing-data suite) and run them live on Ollama and Gemini.
5. Measure whether the cross-check adds anything over evidence quotes; it stays an opt-in switch until then.

**Later**
6. Character spans for each value, so the demo can highlight its evidence in the source.
7. Few-shot examples as an option on `extract()`.
8. MCP: fence stripping, and whether `validate_output` should return data as a JSON string (Windows depth limit).
9. XML/CSV, depending on Blaise's answer to question 2.
10. Rule annotations shared by the schema and the validator.

**Future scope**
11. Chunking and multiple passes for long documents (as in LangExtract), if the contract use case means multi-page
    input.
12. Schema-centered design with per-language clients and a hosted service, depending on Blaise's answers about
    languages and hosting.
13. A verifier model for paraphrased values, only if evidence checks still flag too many correct answers.
