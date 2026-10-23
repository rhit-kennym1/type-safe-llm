# Market landscape

What already exists for typed LLM output, where it falls short, what MCP provides natively, and where this project
can add something. Checked October 2026; links at the end. Library details change fast, so re-check before quoting
any row externally.

## Summary

Typed LLM output is a crowded space. Every major provider has native structured output, and every major framework
has a "give me a class back" API. Our wrapper library is **not novel as a concept**: JetBrains' Koog already does
nearly the same thing in Kotlin. What is still open:

1. **Strictness.** Most tools return whatever a lenient parser accepts. None of the JVM tools we checked documents
   domain-rule validation, and kotlinx-serialization itself lets quoted numbers through (our own library inherits
   this; see the [report](../experiments/results/REPORT.md)).
2. **Measured evidence.** Nobody publishes a side-by-side of library vs. MCP validation on the same adversarial
   inputs. Our experiment harness is that comparison.
3. **Validation as an MCP capability.** The protocol carries schemas but leaves checking model output to hosts.
   Almost no servers use the structured parts of the protocol today.

## Provider-native structured output

Providers can constrain generation to a JSON Schema. That fixes syntax, but each supports a different subset, and
none enforces business rules.

| | OpenAI | Anthropic | Gemini |
|---|---|---|---|
| `oneOf` | no | no | no |
| `anyOf` | partial | yes | sources disagree (see note) |
| `$ref` / `$defs` | yes | yes | yes |
| Recursive schemas | yes | no | sources disagree |
| `minimum` / `maximum` | yes | no | yes |
| `pattern` | yes | yes | no |
| Unsupported keyword | API error (strict mode) | SDK strips it, moves it into the description, validates client-side | silently ignored |

Source: dottxt provider comparison, verified Feb 26 2026. **Note:** Google's own announcement (Nov 2025) says the
Gemini API now supports `anyOf` and `$ref`. Our `provider-gemini` module rewrites `oneOf` to `anyOf` either way;
`LiveGeminiTest` settles the question against the real API once someone runs it with a key.

**Consequence for us:** the provider can't be the source of truth for validity. "Gemini silently ignores" is the
worst case: the schema looks enforced but isn't. This is why the library validates every response
([D7](design-decisions.md#d7)).

## Libraries and frameworks

| Tool | Language | How you declare the type | Validation | Retry on failure | Notes |
|---|---|---|---|---|---|
| **Koog** (JetBrains) | Kotlin | `@Serializable` class, `@LLMDescription` | Structural (decode) only; no documented domain rules | `StructureFixingParser`: a second model call repairs the JSON (default 3 tries) | Native or prompt mode per provider; full generator supports sealed classes and recursion |
| **LangChain4j** | Java | POJO / record | Deserialization only | Not documented | Sealed types need `anyOf`; missing optional primitives become 0/false |
| **Spring AI** | Java | Class via `BeanOutputConverter` (Jackson) | Docs say conversion is not guaranteed; optional validation | Not built in | Ties into Spring Boot |
| **Instructor** | Python (plus ports to other languages) | Pydantic model | Pydantic, including custom validators | Yes, re-asks with the errors | The closest analogue to our MCP track's validator |
| **BAML** | DSL compiling to Python, TS, Ruby | `.baml` schema files | Lenient parser that repairs output, then checks types | Configurable | Requires a separate language and codegen step |
| **Outlines** (dottxt) | Python | Pydantic / JSON Schema / regex | Constrained decoding: the model can't emit invalid tokens | Not needed for syntax | Needs control of the decoder (local or dottxt-hosted models) |
| **This project: library** | Kotlin | `@Serializable` class, `@Description` | Strict decode plus `init`-block rules | Yes, resends with the error | Rules not advertised to the model ([D5](design-decisions.md#d5)) |
| **This project: MCP server** | Python, any MCP host | Named Pydantic schemas | Pydantic strict mode, every error reported | Host-driven | Fixed schema catalog ([D13](design-decisions.md#d13)) |

### Koog, honestly

Koog overlaps with the wrapper library almost feature for feature: same serialization library, same descriptor-based
schema generation, sealed classes, recursion, field descriptions, native vs. prompt-only modes, retries.

Where we differ:

- **Repair strategy.** Koog's fixing parser asks a model to repair the JSON. We resend the error to the original
  conversation. Neither is measured against the other yet.
- **Domain rules.** Koog's docs describe structural parsing and repair only. Our `init`-block rules reject
  well-formed but invalid values (negative counts, impossible dates, expiry before start).
- **Evidence.** We have an adversarial corpus and measured comparison. Koog publishes none.
- **Scope.** Koog is a full agent framework; ours is one function.

**What this means for the project.** If Blaise's goal is a usable Kotlin library, the honest answer is "use Koog,
and here is what it misses." If the goal is to investigate how to make LLM output type-safe, the value is in the
strictness work, the comparison and the findings. Worth asking him directly.

## What MCP provides natively

This answers "doesn't MCP already do JSON type checking?" Short answer: **in places, but not for model-generated
answers, and not as a guarantee.**

From the MCP specification (2025-11-25), tools section:

| Mechanism | What the spec says | Does it validate model output? |
|---|---|---|
| `inputSchema` on every tool | "**MUST** be a valid JSON Schema object." Servers "**MUST** validate all tool inputs." | **Yes, indirectly.** When a model calls a tool, its arguments are model-generated JSON, and the server must check them. |
| Tool execution errors | Input validation failures are returned with `isError: true`. "Clients **SHOULD** provide tool execution errors to language models to enable self-correction." | Gives a retry loop if the host forwards errors. |
| `outputSchema` on a tool | "Servers **MUST** provide structured results that conform to this schema. Clients **SHOULD** validate structured results against this schema." | **No.** It describes what the *server* returns. |
| `structuredContent` | "server-produced result data and is unrelated to LLM 'structured outputs' (schema-constrained model generation)." | **No**, explicitly. |

So:

- **The protocol has no "make the model's final answer match this schema" feature.** `outputSchema` and
  `structuredContent` type the server's results, not the model's.
- **The closest native mechanism is tool arguments.** Define a tool such as `submit_event(event: Event)`; the model
  "answers" by calling it, and the server validates the arguments against `inputSchema` and returns errors for the
  model to fix. Instructor's tool-calling mode works this way at the provider level. This is a
  legitimate alternative design for our MCP track that we haven't built.
- **Nothing forces the model to call the tool.** The host decides whether an unvalidated answer can reach the
  application. That's the spike's "MCP provides tool integration, not an automatic type guarantee," and it holds.
- **Rule strength.** The checks that matter to us are "MUST" on servers and "SHOULD" on clients. A host that ignores
  `isError` or skips validation is still spec-compliant.
- **Adoption is low.** A June 2026 survey of 22 popular MCP servers found 3 emit `structuredContent` and only 1
  declares an `outputSchema` that reaches clients. Our server is in the minority: FastMCP generates `outputSchema`
  from `validate_output`'s return type, so its results are typed.

### Where the MCP track adds value

- A validator any MCP host can discover and call, in any language, including hosts that aren't JVM.
- Pydantic's detailed, all-errors reports, which the report shows are shorter and more complete than the Kotlin
  library's.
- Prompts (`extract_typed_json`) that carry the schema and its rules to the host.

What it can't add: a guarantee. That stays with whoever controls the loop, which is the library in-process and the
host over MCP.

## Sources

- [JetBrains Koog: Structured output](https://docs.koog.ai/structured-output/)
- [LangChain4j: Structured outputs](https://docs.langchain4j.dev/tutorials/structured-outputs)
- [Spring AI: Structured output converter](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)
- [Instructor](https://python.useinstructor.com/)
- [BAML](https://docs.boundaryml.com/)
- [dottxt: Provider comparison](https://docs.dottxt.ai/providers-comparison.md)
- [Google: Improving structured outputs in the Gemini API](https://blog.google/technology/developers/gemini-api-structured-outputs/)
- [Model Context Protocol specification 2025-11-25: Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- [Structured content vs content: MCP server survey](https://olaservo-sandyland.static.hf.space/mcp/structured-content/server-survey.html)
