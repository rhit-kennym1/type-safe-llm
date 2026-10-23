# Architecture

How the two approaches are built, where they share ideas, and how the experiment harness compares them.
Diagrams are Mermaid, so they render on GitHub. Rendered SVGs are in [`diagrams/`](diagrams/) for slides.

## 1. The system at a glance

Both approaches turn a model's text into a value a program can trust. They differ in **who runs validation** and
**where the typed value appears**.

```mermaid
flowchart LR
    subgraph app["Application process (Kotlin)"]
        code["Developer code<br/>llm.call&lt;Order&gt;(prompt)"]
        lib["TypedLLM<br/>schema · decode · retry"]
        code --> lib
    end
    subgraph host["Agent host (any language)"]
        loop["Host loop<br/>prompt · model · validate · repair"]
    end
    subgraph server["MCP server process (Python)"]
        tools["validate_output · list_schemas<br/>extract_typed_json prompt"]
        core["Validation core<br/>Pydantic strict models"]
        tools --> core
    end
    model[("LLM provider<br/>OpenAI-compatible · Gemini · Ollama")]

    lib -- "HTTPS" --> model
    loop -- "HTTPS" --> model
    loop -- "MCP over stdio<br/>prompts/get · tools/call" --> tools
    lib -. "returns Order or throws" .-> code
```

| | Wrapper library | MCP server |
|---|---|---|
| Who calls the model | the library | the host |
| Who runs validation | the library, always | the host, by calling a tool (or the model, in tool-calling mode) |
| What the caller gets | a typed Kotlin object, or an exception with every attempt | a JSON object plus `ok`; the host must check `ok` before trusting it |
| Language reach | Kotlin/JVM only | any MCP client |

## 2. Wrapper library: one call

```mermaid
sequenceDiagram
    autonumber
    participant Dev as Developer code
    participant T as TypedLLM
    participant S as SchemaGenerator
    participant P as Provider
    participant M as Model
    Dev->>T: call<Order>(prompt)
    T->>S: generate(Order descriptor)
    S-->>T: JSON Schema ($defs/$ref for recursion, oneOf for sealed types)
    loop up to maxAttempts
        T->>P: completeWithUsage(messages, schema if JSON)
        P->>M: HTTP request (schema adapted to the provider's dialect when native mode is on)
        M-->>P: text + token usage
        P-->>T: Completion(text, usage)
        T->>T: strict decode into Order (unknown keys, missing fields, init-block rules)
        alt decodes
            T-->>Dev: Order
        else fails
            T->>T: append the answer and the error to the conversation
        end
    end
    T-->>Dev: TypedOutputException(attempts)
```

Points worth knowing:

- **No reflection.** `SchemaGenerator` reads the descriptor that the kotlinx-serialization compiler plugin generates, so every target type must be `@Serializable`. See [design-decisions.md](design-decisions.md#d2) for the trade-off.
- **Decoding is the validation.** A strict `Json` decoder rejects unknown keys, missing required fields, bad enum values and wrong JSON types. Domain rules (ranges, patterns, dates) live in `init` blocks that run during decoding.
- **Retries resend the conversation.** Each retry appends the bad answer and the error, so prompts grow with every attempt (measured in the [report](../experiments/results/REPORT.md#2-cost-what-each-track-sends-to-the-model)).
- **Native structured output is optional.** When on, providers receive the schema in their own dialect: `StrictSchema` for OpenAI strict mode, `GeminiSchema` for Gemini's `responseJsonSchema`. Both rewrite `oneOf` to `anyOf` and inline a root `$ref`, because those providers reject or silently ignore the originals. The library validates the response either way.

### Module layout

```mermaid
flowchart BT
    core["core<br/>TypedLLM · SchemaGenerator · Provider · OutputFormat · Description"]
    openai["provider-openai<br/>OpenAI-compatible: OpenAI, Ollama, Azure, Gemini shim"]
    gemini["provider-gemini<br/>native Gemini generateContent"]
    demo["demo-ui<br/>Ktor web demo"]
    bench["bench<br/>experiment harness"]
    openai --> core
    gemini --> core
    demo --> core
    demo --> openai
    demo --> gemini
    bench --> core
    bench --> openai
    bench --> gemini
```

`Provider` is the extension point: implement `complete`, and override `completeWithUsage` to report tokens. Adding
Anthropic, for example, is a new module with no changes to `core`.

## 3. MCP server: host-driven validation

This is the mode the experiments measure. The host owns the loop; the server owns the prompt and the validator.

```mermaid
sequenceDiagram
    autonumber
    participant H as Host
    participant S as MCP server
    participant M as Model
    H->>S: initialize (once per session)
    H->>S: prompts/get extract_typed_json(schema, source)
    S-->>H: extraction prompt with the JSON Schema
    loop up to max attempts
        H->>M: prompt
        M-->>H: text
        H->>S: tools/call validate_output(schema, text)
        S-->>H: {ok, data, errors[]}
        alt ok
            H->>H: use data
        else not ok
            H->>H: rebuild prompt = original + previous answer + errors
        end
    end
```

In **tool-calling mode** (`live.py --tool-calling`) the model decides whether to call `validate_output`. The model
might skip the tool, so the host validates the model's final text again before trusting it. That re-check is
the only thing that makes the result safe, and it's why the architecture spike said "MCP provides tool integration,
not an automatic type guarantee." [market-landscape.md](market-landscape.md#what-mcp-provides-natively) covers what
the MCP spec does and doesn't promise.

## 4. Experiment harness

```mermaid
flowchart TB
    cases["experiments/cases/cases.json<br/>42 cases · identical responses for every track"]
    kt["kotlin-library/bench<br/>BenchRunner"]
    pyc["mcp-server bench runner<br/>--track python-core"]
    pym["mcp-server bench runner<br/>--track mcp-server"]
    bsrv["bench MCP server<br/>same validator, benchmark schemas"]
    res["experiments/results/*.jsonl<br/>one shared record format"]
    cmp["experiments/compare.py"]
    rep["REPORT.md"]
    sch["experiments/schemas/<br/>kotlin vs python"]
    cases --> kt & pyc & pym
    pym <-->|stdio MCP| bsrv
    kt & pyc & pym --> res
    kt & pyc -->|--schemas-out| sch
    res & sch --> cmp --> rep
```

The benchmark types are written in both languages with identical fields and rules. `compare.py` diffs the schemas
both sides generate, so an outcome difference can be traced to the library rather than to mismatched types.
