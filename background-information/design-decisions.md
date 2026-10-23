# Design decisions

Why the system is built the way it is. Each entry gives the decision, the alternatives, the reasoning, and what
it costs. Entries marked **implicit** were made while building and are written down here for the first time;
they're the ones most worth revisiting with Blaise.

Evidence links point to [the experiment report](../experiments/results/REPORT.md) where a measurement exists.

| # | Decision | Track | Made |
|---|---|---|---|
| [D1](#d1) | Build both approaches in parallel instead of spiking one | both | deliberate |
| [D2](#d2) | Kotlin, with kotlinx-serialization instead of reflection | library | deliberate |
| [D3](#d3) | The developer passes a class, not a schema or a prompt | library | deliberate |
| [D4](#d4) | Decoding is the validation | library | deliberate |
| [D5](#d5) | Domain rules live in `init` blocks | library | implicit |
| [D6](#d6) | Retries resend the whole conversation | library | implicit |
| [D7](#d7) | Provider-native structured output is optional; validation always runs | library | deliberate |
| [D8](#d8) | Rewrite `oneOf` to `anyOf` per provider | library | deliberate |
| [D9](#d9) | Strip Markdown fences before decoding | library | implicit |
| [D10](#d10) | A small `Provider` interface, one module per provider | library | deliberate |
| [D11](#d11) | Python, FastMCP and Pydantic for the MCP server | MCP | implicit |
| [D12](#d12) | The server validates; the host drives the loop | MCP | deliberate |
| [D13](#d13) | A fixed catalog of named schemas | MCP | implicit |
| [D14](#d14) | Retry prompts are rebuilt, not accumulated | MCP | implicit |
| [D15](#d15) | Ollama as the default live model | both | deliberate |
| [D16](#d16) | JSON first; YAML as a second format; XML and CSV deferred | library | deliberate |
| [D17](#d17) | One shared corpus, scripted responses, three tracks | experiments | deliberate |

---

<a id="d1"></a>
## D1. Build both approaches in parallel

**Decision.** Two pairs, two prototypes: Parth and Sanil on the Kotlin wrapper, Mark and Rishi on the MCP server.

**Alternatives.** The architecture spike recommended building the library first and adding MCP "if the team proves
that MCP clients need to discover and call the validator."

**Why.** Blaise asked for both approaches to be investigated, and building both is the only way to compare them on
measured numbers instead of opinions (design doc feature 6).

**Cost.** The two tracks drifted: different languages, different validators, different prompt designs, no shared
code. The spike assumed "both can share one validator"; they don't. The experiments had to re-create matching types
in both languages ([report §5](../experiments/results/REPORT.md#5-schema-parity-between-the-two-implementations)) to compare them fairly.

<a id="d2"></a>
## D2. Kotlin, with kotlinx-serialization instead of reflection

**Decision.** The library is Kotlin. Schemas come from the descriptor the kotlinx-serialization compiler plugin
generates for every `@Serializable` class, and decoding uses the same serializer.

**Alternatives.**

| Option | Why not |
|---|---|
| Java + Jackson (reflection) | Generic type erasure needs `TypeReference` tricks; Jackson's defaults coerce and ignore, so strictness has to be configured feature by feature; null-safety is annotations, not types. |
| Python + Pydantic | The MCP track already covers Python; Python's types are hints, so "type-safe" means runtime validation only. |
| TypeScript | Types vanish at runtime, so the guarantee would rest entirely on a runtime validator such as Zod. |
| C# | Viable (System.Text.Json source generators avoid reflection too). Kotlin was preferred for null-safety in the type system and reified generics. |

**Why.** Kotlin's non-nullable types mean a decoded `Event` can't have a null `title` unless the class says so.
Reified generics let `llm.call<List<Clause>>(prompt)` work without passing a type token. The descriptor is
produced at compile time, so schema generation needs no reflection and fails at build time for unsupported types.

**Cost.**

- Every target type must be `@Serializable`. Third-party classes need hand-written serializers.
- kotlinx's default `Json` is less strict than it looks: the adversarial corpus found it **accepts quoted numbers
  and booleans** (`"attendee_count": "40"`) and **silently takes the last of duplicated keys**. Those are the
  library's three leaks in [report §1](../experiments/results/REPORT.md#1-correctness-on-the-adversarial-corpus-scripted-responses). Pydantic in strict mode rejects quoted primitives.
- Proposed fix (not implemented): parse to a `JsonElement` tree first, check every primitive's JSON kind against
  the descriptor and reject duplicate keys, then decode. About a day of work; needs a go-ahead.

<a id="d3"></a>
## D3. The developer passes a class

**Decision.** The developer's input is an ordinary Kotlin class. The library derives the schema, the prompt
instructions and the decoder from it.

```kotlin
@Serializable
data class Event(val title: String, val startsAt: String, val attendeeCount: Int, val category: Category)

val event: Event = llm.call<Event>("Extract the event from: $email")
```

**Alternatives.** The developer writes a JSON Schema by hand (the MCP server's and most providers' model); or the
developer just asks for "JSON" in the prompt and parses it themselves (what the project exists to replace).

**Why.** The class already exists in the application, since downstream code needs it. One definition means the
schema can't drift from the type, and the compiler checks every use of the result.

**Cost.** Types unknown until runtime (a schema a user uploads, say) don't fit. That is an open question for
Blaise: is runtime-defined schema a requirement?

<a id="d4"></a>
## D4. Decoding is the validation

**Decision.** There's no separate validator. A strict decoder (`ignoreUnknownKeys = false`, required fields
required, enums exact) either produces the object or throws, and the thrown message becomes the retry feedback.

**Why.** One code path; impossible for "validated" and "decoded" to disagree.

**Cost.** The error is whatever kotlinx says, and it stops at the first problem. The report measured a mean of 179
characters per error, including developer hints the model can't act on ("Use 'ignoreUnknownKeys = true'...") and
an echo of the input. Pydantic reports every problem in 73 characters on average. A cleaner error formatter is a
small, contained improvement.

<a id="d5"></a>
## D5. Domain rules in `init` blocks (implicit)

**Decision.** Ranges, patterns and cross-field rules are `require(...)` calls in the class's `init` block. They
run during decoding and throw `IllegalArgumentException`, which the retry loop catches.

**Why.** It's plain Kotlin, needs no annotation processor, and keeps rules next to the fields.

**Cost.**

- The rules are invisible to the schema, so the model is never told them up front. Report §5: Python advertises
  21 rule keywords (`minimum`, `minLength`, `pattern`, ...) across the four benchmark types; Kotlin advertises 0. The
  model only learns a rule by breaking it and burning a retry.
- Only `IllegalArgumentException` triggers a retry. A rule that throws something else (`LocalDate.parse` throws
  `DateTimeParseException`) escapes the loop as a crash. The benchmark types wrap parsing to avoid this; library
  users won't know to.
- Fix options: annotations such as `@Range(0, 365)` that both the schema generator and a validator read; or catch
  all exceptions thrown during decoding.

<a id="d6"></a>
## D6. Retries resend the whole conversation (implicit)

**Decision.** On failure the library appends the bad answer and the error as new chat turns and calls again.

**Why.** It's the natural shape for a chat API, and the model sees its own previous answers.

**Cost.** Prompts grow linearly: 993 → 1,481 → 1,975 characters over three attempts, against Python's rebuilt
prompt at 1,513 → 1,918 → 1,924 ([report §2](../experiments/results/REPORT.md#2-cost-what-each-track-sends-to-the-model)). At three attempts it doesn't matter; it would at higher limits
or with large source documents. Python's first prompt is larger because it embeds the source and stricter
instructions.

<a id="d7"></a>
## D7. Native structured output optional; validation always runs

**Decision.** Providers can enforce the schema while generating (OpenAI strict mode, Gemini `responseJsonSchema`).
The library turns this on per provider, and validates the result either way.

**Why.** Native enforcement is a hint, not a guarantee:

- Gemini ignores schema keywords it doesn't support, without an error.
- OpenAI strict mode supports a subset and rejects schemas outside it.
- No provider enforces the rules in D5.
- Many models (local Ollama models, older endpoints) have no native mode at all.

So the library's guarantee can't depend on it. With validation always on, native mode becomes a way to need fewer
retries, never a correctness requirement.

<a id="d8"></a>
## D8. Rewrite `oneOf` to `anyOf` per provider

**Decision.** The core generates standard JSON Schema: `oneOf` for sealed types, `$defs`/`$ref` for recursion.
Each provider module adapts it (`StrictSchema` for OpenAI, `GeminiSchema` for Gemini): `oneOf` becomes `anyOf`, a
root `$ref` is inlined, single-element `allOf` wrappers are collapsed.

**Why.** OpenAI, Anthropic and Gemini all reject or ignore `oneOf`. Because every branch of a sealed type carries a
distinct `type` discriminator, at most one branch can match, so `anyOf` means the same thing here.

**Cost.** Map types (`Map<String, X>`) still can't be expressed in OpenAI strict mode; `StrictSchema` falls back to
prompt-only for them.

<a id="d9"></a>
## D9. Strip Markdown fences (implicit)

**Decision.** The library removes a surrounding <code>```json ... ```</code> fence before decoding.

**Why.** Models add fences even when told not to. Stripping them is unambiguous: the content inside is unchanged.

**Contrast.** The MCP server deliberately does no stripping, so it rejects fenced output and spends retries on it
(the one false reject in report §1, `event-code-fence`). The library still rejects prose around JSON, because
pulling JSON out of prose is a guess.

<a id="d10"></a>
## D10. A small `Provider` interface, one module per provider

**Decision.**

```kotlin
interface Provider {
    val id: String
    suspend fun complete(request: CompletionRequest): String
    suspend fun completeWithUsage(request: CompletionRequest): Completion  // text + token usage
}
```

`provider-openai` covers anything OpenAI-compatible (OpenAI, Ollama, Azure OpenAI, Gemini's compatibility
endpoint); `provider-gemini` calls Gemini's native `generateContent` API.

**Why.** Retry, validation and schema logic are provider-independent; only the HTTP call and schema dialect vary.
A new provider is a new module with no change to `core`. `completeWithUsage` has a default, so a minimal provider
only implements `complete`.

**Why both Gemini routes.** The OpenAI-compatible endpoint is the quickest to wire up. The native API exposes
`responseJsonSchema`, thinking-token counts and safety blocks, and is the route the spike named.

<a id="d11"></a>
## D11. Python, FastMCP and Pydantic for the MCP server (implicit)

**Decision.** The MCP track is Python, using the official SDK's FastMCP and Pydantic models in strict mode.

**Why, as far as it was reasoned.** The Python MCP SDK is the most used and best documented. Pydantic generates
JSON Schema with rule keywords (`minimum`, `pattern`), validates strictly, and reports every error with a path.
FastMCP turns a typed Python function into a tool with an input schema and an `outputSchema` automatically.

**Why not Kotlin.** It could have been: there is an official Kotlin MCP SDK, so the server could reuse the
library's types and decoder, which is what "both can share one validator" in the spike assumed. The cost of not doing
this is the drift described in D1. It's worth asking Blaise whether a Kotlin MCP adapter over the library is the
version he'd want to see.

<a id="d12"></a>
## D12. The server validates; the host drives the loop

**Decision.** The server exposes `validate_output` (a tool), `extract_typed_json` (a prompt) and `list_schemas`.
The host calls the model, sends the answer to `validate_output`, and retries with the errors.

**Why.** MCP servers don't call models; hosts do. The server can supply the schema and the validator, but only the
host can decide that nothing unvalidated reaches the application. In tool-calling mode the model may skip the tool,
so the host has to re-validate the final answer anyway.

**Cost.** Two protocol round trips per attempt: ~9.9 ms each over stdio, ~38.5 ms of protocol time per extraction,
and ~656 ms to start a session ([report §3](../experiments/results/REPORT.md#3-latency-overhead-scripted-model-time-excluded)). Small next to a model call; large next to the library's 0.12 ms.

<a id="d13"></a>
## D13. A fixed catalog of named schemas (implicit)

**Decision.** Clients pick `"event"` or `"contract"`; they can't send their own schema.

**Why.** It keeps the demo simple and avoids validating arbitrary, possibly hostile schemas.

**Cost.** It limits the server to schemas it ships with, which undercuts the "reusable by any agent" argument for
MCP. A `validate_against(json_schema, content)` tool using a JSON Schema validator would remove the limit, but would
lose Pydantic's cross-field rules.

<a id="d14"></a>
## D14. Retry prompts rebuilt, not accumulated (implicit)

**Decision.** Each retry prompt is the original prompt plus only the latest answer and its errors.

**Why.** Constant prompt size, and the model isn't distracted by older mistakes. See D6 for the measured contrast.

<a id="d15"></a>
## D15. Ollama as the default live model

**Decision.** With no configuration, live mode uses a local Ollama model (`llama3.2:3b`).

**Why.** Free, no key, runs on a laptop; anyone on the team can run the demo. A small model also makes more
mistakes, which is useful for showing the retry loop. Gemini is one environment variable away.

<a id="d16"></a>
## D16. JSON first; YAML second; XML and CSV deferred

**Decision.** JSON and YAML are implemented through the same class and strict decoders. XML and CSV aren't.

**Why.** Native structured output exists only for JSON. YAML came cheaply through kaml, a kotlinx-serialization
format. XML needs a decision about attributes vs. elements; CSV can't hold nesting.

**Open.** The alternative suggested during research, generating JSON and converting the validated object to any
delivery format, gives every format the same guarantee. Worth confirming with Blaise which formats he actually needs
from the model, as opposed to delivered to downstream systems.

<a id="d17"></a>
## D17. One corpus, scripted responses, three tracks

**Decision.** 42 hand-written cases, each with the exact model responses to replay, run through the Kotlin
library, the Python validator in-process, and the Python validator over a real MCP session.

**Why.**

- Scripted responses make the comparison about the libraries, not model randomness, and run in CI.
- In-process vs. over-MCP isolates the protocol's cost.
- Live mode reuses the same cases against a real model, to measure how often each mistake actually happens.

**Cost.** Scripted mode can't tell you how often a model makes each mistake; only live runs can, and none have been
run yet.
