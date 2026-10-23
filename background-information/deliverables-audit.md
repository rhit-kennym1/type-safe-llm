# Deliverables audit

Status of everything the project has committed to, by source and by track, as of **October 22, 2026** (week 7).
Status is based on what is in the repository, not on what was planned.

**Key:** ✅ done · 🟡 partial · ❌ not started · — not applicable to that track

## 1. Design document features (end of Winter)

| # | Feature | Wrapper library (Kotlin) | MCP server (Python) | Gap |
|---|---|---|---|---|
| 1 | Schema definition | ✅ derived from a `@Serializable` class; `@Description` for field docs | ✅ named Pydantic models (`event`, `contract`) | MCP clients can't supply their own schema ([D13](design-decisions.md#d13)) |
| 2 | Validation layer | 🟡 strict decode + `init` rules; **3 leaks** on the adversarial corpus | 🟡 Pydantic strict; **1 leak** | Both accept duplicate keys; Kotlin accepts quoted numbers/booleans ([report §1](../experiments/results/REPORT.md#1-correctness-on-the-adversarial-corpus-scripted-responses)) |
| 3 | Retry / reprompt loop | ✅ 6/6 recovery cases | ✅ 6/6 recovery cases | — |
| 4 | Multi-format | 🟡 JSON, YAML | 🟡 JSON | XML and CSV on neither track ([D16](design-decisions.md#d16)) |
| 5 | End-to-end with a real model | 🟡 live demo tab on Ollama; OpenAI-compatible and native Gemini providers | 🟡 `live.py` on Ollama/Gemini, host-driven and tool-calling | No measured live run yet; no Azure deployment |
| 6 | Comparative evaluation | ✅ harness + scripted report (shared) | ✅ harness + scripted report (shared) | Live runs; integration effort and usability not yet assessed |
| 7 | Developer documentation | 🟡 README, architecture, decisions | 🟡 README, demo docs | No per-format worked examples; no API reference |
| 8 | Provider abstraction | ✅ `Provider` interface; OpenAI-compatible + native Gemini modules | ✅ OpenAI-compatible provider (Ollama, Gemini shim) | Anthropic on neither |
| 9 | Configurable failure behavior | 🟡 `maxAttempts`; always throws `TypedOutputException` | ✅ `max_retries`; returns a `RetryResult` with a status | Library has no "return a failure result" option |
| 10 | Detailed error reporting | 🟡 first error only, raw kotlinx text (mean 179 chars) | ✅ every error, with path and code (mean 73 chars) | Library error formatter ([D4](design-decisions.md#d4)) |
| 11 | Automated test suite | ✅ 65 tests across 5 modules (2 live, opt-in) | ✅ 63 tests (2 live, opt-in), including a real stdio MCP session | Plus a 42-case shared corpus run by both; CI runs both tracks |
| 12 | Complex / nested types | ✅ nested, lists, optional, enums, sealed types, recursion | ✅ nested, lists, optional, enums; sealed and recursive in the benchmark schemas | — |
| 13 | Observability and logging | 🟡 `onAttempt` hook with raw output, error, token usage, duration | 🟡 every attempt recorded in the result | No structured logging on either |
| 14 | API usability | ✅ `llm.call<Event>(prompt)` | ✅ discoverable tools, prompt and schema list | Not yet tried by anyone outside the team |
| 15 | XML / YAML / CSV reliability testing | ❌ | ❌ | Depends on feature 4 |

## 2. Architecture spike plan

| Step | Status | Evidence |
|---|---|---|
| 1. One representative schema with nesting, enums, optional fields, a constrained number | ✅ | `Event`, `Contract`, plus sealed `Order` and recursive `Comment` in the benchmark |
| 2. Wrapper prototype tested on valid, malformed, missing, wrong-type, invalid-domain, empty, timeout | 🟡 | All but timeout are in the corpus. Providers have timeouts, but no test injects one. |
| 3. Validator over MCP with a real client; check whether the host rejects unchecked responses | ✅ | `live.py` host-driven and tool-calling modes; the host re-validates the final answer |
| 4. Compare validated success, error classification, median/p95 latency, integration effort, token and hosting cost | 🟡 | [REPORT.md](../experiments/results/REPORT.md) covers success, classification, latency and token cost on scripted runs. Live runs, integration effort and hosting cost are not done. |

Spike shared criteria:

| Criterion | Status |
|---|---|
| One declared schema per request, no parsing code in the consumer | ✅ library · 🟡 MCP (the host must check `ok`) |
| Explicit errors for invalid content, empty responses, timeouts, provider failures | ✅ both |
| Tests for nested objects, arrays, enums, optional values, domain rules | ✅ both |
| Defined retry limits, secure credentials, comparable latency and cost measurements | ✅ limits and credentials from environment · 🟡 measurements scripted only |

## 3. Pre-meeting work list (agreed Oct 22)

| Item | Status | Where |
|---|---|---|
| Updated design document | ✅ | [design-document-v2.md](design-document-v2.md) |
| Deliverables audit | ✅ | this file |
| Architecture diagrams | ✅ | [architecture.md](architecture.md), SVGs in `diagrams/` |
| Reasoning for design choices | ✅ | [design-decisions.md](design-decisions.md), 17 decisions, implicit ones flagged |
| Experiments across both tracks: correctness, token use, response time | ✅ scripted · ❌ live | [experiments/](../experiments/) |
| Adversarial tests with confusing cases, run on both | ✅ | 42 cases in 9 categories; run in CI on both tracks |
| Existing market tools and what MCP provides natively | ✅ | [market-landscape.md](market-landscape.md) |
| Multi-provider interface, with Gemini wired in | ✅ code and tests · ❌ not yet run against the live Gemini API | `provider-gemini/`, demo UI, bench |

Raised but not on the agreed list:

| Item | Status |
|---|---|
| Demo UI page that shows the library's flaws (quoted numbers, duplicate keys) | ❌ The UI has recursive and sealed-type scenarios; none of the leaks are shown yet |
| What the developer's input looks like (class vs. schema vs. prompt) | ✅ [D3](design-decisions.md#d3) |
| An MCP server written in Kotlin over the library | ❌ discussed in [D11](design-decisions.md#d11), not built |
| Why Pydantic, not Kotlin, for MCP; doesn't MCP already type-check? | ✅ [D11](design-decisions.md#d11), [market-landscape.md](market-landscape.md#what-mcp-provides-natively) |

## 4. What's left, in priority order

1. **Live runs** on Ollama and Gemini, all three tracks, three repeats. Fills report §4 and answers how often real
   models make each mistake. Needs a machine with Ollama or a Gemini key (the build environment has neither).
2. **Close the Kotlin leaks**: type-check the parsed JSON tree against the descriptor before decoding, and reject
   duplicate keys. Needs a go-ahead.
3. **Advertise rules to the model** (annotations read by both schema and validator) so the library stops spending
   retries on rules the model never saw ([D5](design-decisions.md#d5)).
4. **Library error formatter**: report every problem, drop kotlinx's developer hints.
5. **XML and CSV**, once Blaise confirms which formats he needs generated vs. delivered.
6. **Demo UI flaw page** using the corpus cases where the tracks disagree.
7. Decide with Blaise: Koog overlap, Kotlin MCP adapter, runtime-defined schemas, Azure hosting.
