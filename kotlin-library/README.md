# typed-llm

Ask an LLM for a Kotlin type. Get back a validated instance of that type, or a typed exception. Never a half-parsed object, never a stray `null`.

```kotlin
@Serializable data class Contract(val parties: List<String>, val effectiveDate: String, val totalValue: Double)

val llm = TypedLLM(provider, format = OutputFormat.YAML, maxAttempts = 3)
val contract: Contract = llm.call("Extract the contract terms from: ...")
```

## Modules
- **`core`** is the library: schema generation, strict decoding, and the self-correcting retry loop.
- **`provider-openai`** connects to OpenAI (or any OpenAI-compatible API). It can use native structured output on models that support it, or prompt-only mode on models that don't.
- **`demo-ui`** is a local web demo. A small Ktor server runs scripted scenarios through `core` and serves a page that shows each attempt.

## The guarantee
`call<T>()` either returns a `T` that fully satisfies the type (every required field present, correct types, valid enum values, no unknown fields) or throws `TypedOutputException` with every attempt recorded.

## Commands (run from this folder)
```
.\gradlew.bat test              # all tests, both modules
.\gradlew.bat :demo-ui:run      # start the demo, then open http://localhost:8080
```

### Live model tab (free, runs locally)
1. Install Ollama from https://ollama.com and start it.
2. Download the default model once: `ollama pull llama3.2:3b`
3. Run the demo and open the Live model tab.

Any OpenAI-compatible endpoint works instead: set `LIVE_BASE_URL`, `LIVE_API_KEY` and `LIVE_MODEL` before starting the demo.

### Live tests against OpenAI
Skipped by default so normal runs and CI cost nothing. To run them (PowerShell):
```
$env:OPENAI_API_KEY = "sk-..."
$env:TYPED_LLM_LIVE = "1"
.\gradlew.bat :provider-openai:test --tests "*Live*" --rerun
```
