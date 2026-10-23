"""Builds experiments/results/REPORT.md from every results file, the case corpus, prices, and dumped schemas.

Standard library only. Run from anywhere:  python experiments/compare.py
Every number in the report is computed here from the JSONL files; nothing is typed in by hand.
"""

import json
import math
import re
import statistics
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
TRACK_ORDER = ["kotlin-library", "python-core", "mcp-server"]
TRACK_LABEL = {
    "kotlin-library": "Kotlin library",
    "python-core": "Python validator (in-process)",
    "mcp-server": "Python validator over MCP",
}
CHARS_PER_TOKEN = 4  # rough English/JSON average; only used when the provider reported no real counts


# ---------------------------------------------------------------- loading

def load_runs() -> dict[tuple[str, str, str], list[dict]]:
    runs: dict[tuple[str, str, str], list[dict]] = defaultdict(list)
    for path in sorted(RESULTS.glob("*.jsonl")):
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rec = json.loads(line)
                runs[(rec["track"], rec["mode"], rec["provider"])].append(rec)
    return runs


def run_sort_key(key: tuple[str, str, str]) -> tuple:
    track, mode, provider = key
    return (mode != "scripted", provider, TRACK_ORDER.index(track) if track in TRACK_ORDER else 99)


# ---------------------------------------------------------------- stats helpers

def median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def p95(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]


def ms(value: float | None) -> str:
    if value is None:
        return "n/a"
    return f"{value:.3f} ms" if value < 10 else f"{value:.1f} ms"


def pct(n: int, d: int) -> str:
    return f"{n}/{d} ({100 * n / d:.0f}%)" if d else "n/a"


# ---------------------------------------------------------------- classification

def classify(track: str, error: str | None, response_chars: int) -> str:
    """Normalize each track's first error into a shared category so error handling can be compared."""
    if response_chars == 0:
        return "empty response"
    e = error or ""
    if track == "kotlin-library":
        rules = [
            (r"constraint violated", "domain rule"),
            (r"Class discriminator|polymorphic scope", "sealed-type discriminator"),
            (r"unknown key", "unexpected field"),
            (r"is required|are required", "missing field"),
            (r"does not contain element with name", "invalid enum value"),
            (r"Trailing comma|Expected quotation mark|Expected start of the object '\{'", "not valid JSON"),
            (r"at path: \$\.", "wrong type or malformed value"),
        ]
    else:
        rules = [
            (r"union_tag", "sealed-type discriminator"),
            (r"extra_forbidden", "unexpected field"),
            (r": missing:", "missing field"),
            (r"literal_error|enum", "invalid enum value"),
            (r"json_invalid|json_type", "not valid JSON"),
            (r"greater_than|less_than|too_short|too_long|pattern|date_parsing|value_error|timezone", "domain rule"),
            (r"_type:", "wrong type or malformed value"),
        ]
    for pattern, label in rules:
        if re.search(pattern, e):
            return label
    return "other"


def issue_count(track: str, error: str | None) -> int:
    """How many problems one error report names (Python lists all; Kotlin stops at the first)."""
    if not error:
        return 0
    return error.count("; ") + 1 if track != "kotlin-library" else 1


# ---------------------------------------------------------------- per-run summaries

def first_by_case(records: list[dict]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for rec in records:
        out.setdefault(rec["case_id"], rec)
    return out


def summarize(records: list[dict], cases: dict[str, dict], prices: dict, model_for_cost: str | None) -> dict:
    detectable = [r for r in records if cases[r["case_id"]]["detectable"]]
    correct = [r for r in detectable if r["ok"] == (cases[r["case_id"]]["ideal"] == "accept")]
    leaks = sorted({r["case_id"] for r in detectable if r["ok"] and cases[r["case_id"]]["ideal"] == "reject"})
    false_rejects = sorted({r["case_id"] for r in records if not r["ok"] and cases[r["case_id"]]["ideal"] == "accept"})
    recovery = [r for r in records if cases[r["case_id"]]["category"] == "recovery"]
    undetectable = [r for r in records if not cases[r["case_id"]]["detectable"]]
    samples = [s for r in records for s in r.get("overhead_samples_ms", [])]
    loops = [r["loop_ms"] for r in records]
    prompt_chars = [sum(a["prompt_chars"] for a in r["attempts"]) for r in records]
    response_chars = [sum(a["response_chars"] for a in r["attempts"]) for r in records]
    tokens_in = [sum(a["input_tokens"] or 0 for a in r["attempts"]) for r in records
                 if r["attempts"] and all(a["input_tokens"] is not None for a in r["attempts"])]
    tokens_out = [sum(a["output_tokens"] or 0 for a in r["attempts"]) for r in records
                  if r["attempts"] and all(a["output_tokens"] is not None for a in r["attempts"])]
    attempt_latency = [a["latency_ms"] for r in records for a in r["attempts"]]
    error_lengths = [len(a["error"]) for r in records for a in r["attempts"] if a["error"]]
    real_tokens = bool(tokens_in) and len(tokens_in) == len(records)
    in_tok = statistics.mean(tokens_in) if real_tokens else statistics.mean(prompt_chars) / CHARS_PER_TOKEN
    out_tok = statistics.mean(tokens_out) if real_tokens and tokens_out else statistics.mean(response_chars) / CHARS_PER_TOKEN
    price = prices.get(model_for_cost or "")
    cost_per_1000 = (in_tok * price["input"] + out_tok * price["output"]) / 1e6 * 1000 if price else None
    return {
        "n": len(records), "validated": sum(r["ok"] for r in records),
        "correct": len(correct), "detectable": len(detectable), "leaks": leaks, "false_rejects": false_rejects,
        "recovered": sum(r["ok"] for r in recovery), "recovery_total": len(recovery),
        "recovery_attempts": statistics.mean(len(r["attempts"]) for r in recovery if r["ok"]) if any(r["ok"] for r in recovery) else None,
        "undetectable_passed": sum(r["ok"] for r in undetectable), "undetectable_total": len(undetectable),
        "overhead_median": median(samples), "overhead_p95": p95(samples),
        "loop_median": median(loops), "loop_p95": p95(loops), "attempt_latency_median": median(attempt_latency),
        "mean_attempts": statistics.mean(len(r["attempts"]) for r in records),
        "prompt_chars": statistics.mean(prompt_chars), "response_chars": statistics.mean(response_chars),
        "in_tokens": in_tok, "out_tokens": out_tok, "real_tokens": real_tokens,
        "cost_per_1000": cost_per_1000, "cost_model": model_for_cost if price else None,
        "error_chars": statistics.mean(error_lengths) if error_lengths else None,
        "session_setup_ms": next((r.get("session_setup_ms") for r in records if r.get("session_setup_ms")), None),
    }


def model_of(provider: str) -> str | None:
    """'live:gemini-3.1-flash-lite' / 'gemini:gemini-3.1-flash-lite+native' / 'openai-compatible:x' -> model name."""
    if provider == "scripted":
        return None
    return provider.split(":", 1)[-1].removesuffix("+native")


# ---------------------------------------------------------------- schema parity

def normalize(schema: dict, root: dict, seen: tuple = ()) -> dict:
    if "$ref" in schema:
        name = schema["$ref"].split("/")[-1]
        if name in seen:
            return {"recursive": True}
        return normalize(root.get("$defs", {})[name], root, seen + (name,))
    if "allOf" in schema and len(schema["allOf"]) == 1:
        return normalize(schema["allOf"][0], root, seen)
    for key in ("anyOf", "oneOf"):
        if key in schema:
            branches = [normalize(b, root, seen) for b in schema[key]]
            non_null = [b for b in branches if b != {"type": "null"}]
            nullable = len(non_null) < len(branches)
            if len(non_null) == 1:
                return {**non_null[0], **({"nullable": True} if nullable else {})}

            def tag(b: dict) -> str:
                return json.dumps(b.get("properties", {}).get("type", {}).get("enum", b), sort_keys=True)
            return {"union": sorted(non_null, key=tag), **({"nullable": True} if nullable else {})}
    if "const" in schema:
        return {"type": "string", "enum": [schema["const"]]}
    kind = schema.get("type")
    if "enum" in schema:
        return {"type": kind or "string", "enum": sorted(schema["enum"])}
    if kind == "object" and "properties" in schema:
        return {"type": "object",
                "properties": {k: normalize(v, root, seen) for k, v in sorted(schema["properties"].items())},
                "required": sorted(schema.get("required", []))}
    if kind == "array":
        return {"type": "array", "items": normalize(schema.get("items", {}), root, seen)}
    return {"type": kind}


def diff(a: object, b: object, path: str = "$") -> list[str]:
    if type(a) is not type(b):
        return [f"{path}: {json.dumps(a)} vs {json.dumps(b)}"]
    if isinstance(a, dict):
        out = []
        for key in sorted(set(a) | set(b)):
            if key not in a or key not in b:
                out.append(f"{path}.{key}: only in {'Kotlin' if key in a else 'Python'}")
            else:
                out += diff(a[key], b[key], f"{path}.{key}")
        return out
    if isinstance(a, list):
        if len(a) != len(b):
            return [f"{path}: {len(a)} items vs {len(b)}"]
        return [d for i, (x, y) in enumerate(zip(a, b)) for d in diff(x, y, f"{path}[{i}]")]
    return [] if a == b else [f"{path}: {a!r} vs {b!r}"]


CONSTRAINT_KEYS = {"minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
                   "pattern", "minItems", "maxItems", "format"}


def count_constraints(node: object) -> Counter:
    found: Counter = Counter()
    if isinstance(node, dict):
        found.update(k for k in node if k in CONSTRAINT_KEYS)
        for value in node.values():
            found += count_constraints(value)
    elif isinstance(node, list):
        for value in node:
            found += count_constraints(value)
    return found


def schema_parity() -> list[str]:
    kdir, pdir = ROOT / "schemas" / "kotlin", ROOT / "schemas" / "python"
    if not kdir.exists() or not pdir.exists():
        return ["Schemas not dumped yet. Run both harnesses with `--schemas-out` (see experiments/README.md)."]
    lines = ["| Target | Same fields, types, required sets? | Rule keywords advertised to the model (Kotlin / Python) |",
             "|---|---|---|"]
    notes = []
    for path in sorted(kdir.glob("*.json")):
        k = json.loads(path.read_text())
        p = json.loads((pdir / path.name).read_text())
        differences = diff(normalize(k, k), normalize(p, p))
        kc, pc = count_constraints(k), count_constraints(p)
        same = "yes" if not differences else f"no ({len(differences)} differences)"
        lines.append(f"| {path.stem} | {same} | {sum(kc.values())} / {sum(pc.values())} "
                     f"({', '.join(f'{key} {n}' for key, n in sorted(pc.items())) or 'none'}) |")
        notes += [f"- `{path.stem}` {d}" for d in differences]
    if notes:
        lines += ["", "Differences:", *notes]
    return lines


# ---------------------------------------------------------------- report

def outcome_cell(rec: dict | None, case: dict) -> str:
    if rec is None:
        return "not run"
    n = len(rec["attempts"])
    text = f"validated ({n})" if rec["ok"] else ("provider error" if rec["status"] == "provider_error" else f"rejected ({n})")
    wrong = case["detectable"] and rec["ok"] != (case["ideal"] == "accept")
    return f"**{text}** ⚠" if wrong else text


def main() -> None:
    corpus = json.loads((ROOT / "cases" / "cases.json").read_text(encoding="utf-8"))
    cases = {c["id"]: c for c in corpus["cases"]}
    pricing = json.loads((ROOT / "pricing.json").read_text(encoding="utf-8"))
    prices, reference = pricing["models"], pricing["reference_model"]
    runs = load_runs()
    if not runs:
        raise SystemExit("No results in experiments/results/. Run the harnesses first (see experiments/README.md).")
    keys = sorted(runs, key=run_sort_key)
    scripted = [k for k in keys if k[1] == "scripted"]
    live = [k for k in keys if k[1] == "live"]
    summaries = {k: summarize(runs[k], cases, prices, model_of(k[2]) or reference) for k in keys}

    out: list[str] = []
    w = out.append
    w("# Experiment results: wrapper library vs. MCP server")
    w("")
    w(f"Generated {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')} by `experiments/compare.py` from "
      f"{sum(len(v) for v in runs.values())} result records. Regenerate after any run; do not edit by hand.")
    w("")
    w(f"Corpus: {len(cases)} cases ({', '.join(f'{c} {n}' for c, n in Counter(c['category'] for c in cases.values()).items())}), "
      f"max {corpus['max_attempts']} attempts per case. Case definitions: `experiments/cases/build_cases.py`.")
    w("")
    w("Tracks: **Kotlin library** (`kotlin-library/`), **Python validator in-process** (the MCP server's validation core "
      "called directly, the way a library would use it), and **Python validator over MCP** (the same core behind a real "
      "stdio MCP session: the server supplies the prompt, and every attempt is a `tools/call`). Comparing the last two "
      "isolates the cost of the protocol; comparing the first two compares the implementations.")
    w("")
    w("Runs included:")
    w("")
    for k in keys:
        w(f"- `{k[0]}` · {k[1]} · {k[2]} · {len(runs[k])} records")
    w("")

    if scripted:
        w("## 1. Correctness on the adversarial corpus (scripted responses)")
        w("")
        w("Scripted mode feeds every track byte-identical model responses, so differences come from the libraries, not the model.")
        w("")
        w("| | " + " | ".join(TRACK_LABEL.get(k[0], k[0]) for k in scripted) + " |")
        w("|---|" + "---|" * len(scripted))
        rows = [
            ("Correct outcome on schema-detectable cases", lambda s: pct(s["correct"], s["detectable"])),
            ("Bad output returned as valid data (leaks)", lambda s: f"{len(s['leaks'])}: " + (", ".join(f"`{x}`" for x in s["leaks"]) or "none")),
            ("Good output rejected (false rejects)", lambda s: f"{len(s['false_rejects'])}: " + (", ".join(f"`{x}`" for x in s["false_rejects"]) or "none")),
            ("Recovered after a bad first answer", lambda s: pct(s["recovered"], s["recovery_total"])),
            ("Wrong-but-well-formed output passed (expected; no schema can catch it)", lambda s: pct(s["undetectable_passed"], s["undetectable_total"])),
            ("Problems named per error report", lambda s: "all of them" if s.get("_multi") else "first one only"),
            ("Mean length of an error report sent back to the model", lambda s: f"{s['error_chars']:.0f} chars" if s["error_chars"] else "n/a"),
        ]
        for k in scripted:
            summaries[k]["_multi"] = k[0] != "kotlin-library"
        for label, fn in rows:
            w(f"| {label} | " + " | ".join(fn(summaries[k]) for k in scripted) + " |")
        w("")

        w("### Outcome for every case")
        w("")
        w("Numbers in parentheses are attempts used. ⚠ marks an outcome that differs from the ideal on a case a schema check can detect.")
        w("")
        w("| Case | Category | Ideal | " + " | ".join(TRACK_LABEL.get(k[0], k[0]) for k in scripted) + " | What it probes |")
        w("|---|---|---|" + "---|" * len(scripted) + "---|")
        by_run = {k: first_by_case(runs[k]) for k in scripted}
        for cid, case in cases.items():
            ideal = case["ideal"] + ("" if case["detectable"] else " (undetectable)")
            cells = [outcome_cell(by_run[k].get(cid), case) for k in scripted]
            w(f"| `{cid}` | {case['category']} | {ideal} | " + " | ".join(cells) + f" | {case['probes']} |")
        w("")

        w("### Where the tracks disagree")
        w("")
        disagreements = [cid for cid in cases if len({by_run[k].get(cid, {}).get("ok") for k in scripted}) > 1]
        if not disagreements:
            w("All tracks produced the same outcome on every case.")
        for cid in disagreements:
            w(f"**`{cid}`**: {cases[cid]['probes']}")
            w("")
            for k in scripted:
                rec = by_run[k].get(cid)
                if rec is None:
                    continue
                first_error = next((a["error"] for a in rec["attempts"] if a["error"]), None)
                detail = f"first error: `{first_error[:160]}`" if first_error else "accepted on the first attempt"
                w(f"- {TRACK_LABEL.get(k[0], k[0])}: {'validated' if rec['ok'] else 'rejected'}; {detail}")
            w("")

        w("### How each track classifies the first failure")
        w("")
        cats: dict = defaultdict(Counter)
        for k in scripted:
            for rec in by_run[k].values():
                bad = next((a for a in rec["attempts"] if not a["ok"]), None)
                if bad:
                    cats[classify(k[0], bad["error"], bad["response_chars"])][k] += 1
        w("| Category | " + " | ".join(TRACK_LABEL.get(k[0], k[0]) for k in scripted) + " |")
        w("|---|" + "---|" * len(scripted))
        for cat in sorted(cats):
            w(f"| {cat} | " + " | ".join(str(cats[cat][k]) for k in scripted) + " |")
        w("")

        w("## 2. Cost: what each track sends to the model")
        w("")
        w(f"Characters of prompt and response text per extraction, retries included. Token and cost figures are "
          f"estimates at {CHARS_PER_TOKEN} characters per token, priced on `{reference}` "
          f"(${prices[reference]['input']}/1M input, ${prices[reference]['output']}/1M output; source in `pricing.json`). "
          "Live runs below use the provider's reported token counts instead.")
        w("")
        w("| | " + " | ".join(TRACK_LABEL.get(k[0], k[0]) for k in scripted) + " |")
        w("|---|" + "---|" * len(scripted))
        for label, fn in [
            ("Mean attempts per extraction", lambda s: f"{s['mean_attempts']:.2f}"),
            ("Prompt characters per extraction", lambda s: f"{s['prompt_chars']:,.0f}"),
            ("Estimated input tokens per extraction", lambda s: f"{s['in_tokens']:,.0f}"),
            ("Estimated output tokens per extraction", lambda s: f"{s['out_tokens']:,.0f}"),
            (f"Estimated cost per 1,000 extractions ({reference})", lambda s: f"${s['cost_per_1000']:.3f}" if s["cost_per_1000"] is not None else "n/a"),
        ]:
            w(f"| {label} | " + " | ".join(fn(summaries[k]) for k in scripted) + " |")
        w("")
        w("Prompt size per attempt on the cases that used all three attempts (shows how retry prompts grow):")
        w("")
        three = [cid for cid in cases if all(len(by_run[k].get(cid, {}).get("attempts", [])) == 3 for k in scripted)]
        w("| | " + " | ".join(f"attempt {i}" for i in (1, 2, 3)) + " |")
        w("|---|---|---|---|")
        for k in scripted:
            per = [statistics.mean(by_run[k][cid]["attempts"][i]["prompt_chars"] for cid in three) for i in range(3)] if three else [0, 0, 0]
            w(f"| {TRACK_LABEL.get(k[0], k[0])} | " + " | ".join(f"{v:,.0f} chars" for v in per) + " |")
        w("")
        w(f"(Averaged over the {len(three)} cases where every track used three attempts.)")
        w("")

        w("## 3. Latency overhead (scripted, model time excluded)")
        w("")
        w("With scripted responses the model answers instantly, so this is pure library or protocol time per extraction: "
          "each case re-run many times after a warm-up pass. Real model calls take hundreds to thousands of milliseconds, "
          "so compare these against that scale.")
        w("")
        w("| | " + " | ".join(TRACK_LABEL.get(k[0], k[0]) for k in scripted) + " |")
        w("|---|" + "---|" * len(scripted))
        w("| Median per extraction | " + " | ".join(ms(summaries[k]["overhead_median"]) for k in scripted) + " |")
        w("| 95th percentile per extraction | " + " | ".join(ms(summaries[k]["overhead_p95"]) for k in scripted) + " |")
        w("| Timed samples | " + " | ".join(str(sum(len(r.get('overhead_samples_ms', [])) for r in runs[k])) for k in scripted) + " |")
        mcp = next((k for k in scripted if k[0] == "mcp-server"), None)
        if mcp:
            per_trip = [statistics.median(r["overhead_samples_ms"]) / (1 + len(r["attempts"]))
                        for r in runs[mcp] if r.get("overhead_samples_ms")]
            w(f"| MCP round trips per extraction | — | — | 1 `prompts/get` + 1 `tools/call` per attempt |")
            w(f"| Median time per MCP round trip | — | — | {ms(median(per_trip))} |")
            w(f"| One-time MCP session start (process + handshake) | — | — | {ms(summaries[mcp]['session_setup_ms'])} |")
        w("")

    if live:
        w("## 4. Live model runs")
        w("")
        w("Real model, real tokens. Each record is one extraction; cases may be repeated to average out model randomness.")
        w("")
        w("| Run | Extractions | Validated | Correct on detectable cases | Recovered | Mean attempts | Median / p95 latency per extraction | Median provider time per attempt | Tokens in / out per extraction | Cost per 1,000 |")
        w("|---|---|---|---|---|---|---|---|---|---|")
        for k in live:
            s = summaries[k]
            tok = f"{s['in_tokens']:,.0f} / {s['out_tokens']:,.0f}" + ("" if s["real_tokens"] else " (est.)")
            cost = f"${s['cost_per_1000']:.3f} ({s['cost_model']})" if s["cost_per_1000"] is not None else "add model to pricing.json"
            w(f"| {TRACK_LABEL.get(k[0], k[0])} · {k[2]} | {s['n']} | {pct(s['validated'], s['n'])} | {pct(s['correct'], s['detectable'])} | "
              f"{pct(s['recovered'], s['recovery_total'])} | {s['mean_attempts']:.2f} | {ms(s['loop_median'])} / {ms(s['loop_p95'])} | "
              f"{ms(s['attempt_latency_median'])} | {tok} | {cost} |")
        w("")
    else:
        w("## 4. Live model runs")
        w("")
        w("None yet. Live runs need a model endpoint (Ollama locally, or a Gemini key), which the environment that generated "
          "this report did not have. Commands are in `experiments/README.md`; rerun `compare.py` afterwards and this section fills in.")
        w("")

    w("## 5. Schema parity between the two implementations")
    w("")
    w("The benchmark types are written twice (Kotlin and Pydantic). This checks that both generate the same structure, "
      "so outcome differences come from the libraries rather than from mismatched schemas.")
    w("")
    out.extend(schema_parity())
    w("")

    w("## 6. Method and caveats")
    w("")
    w("- Scripted responses test the libraries' handling of model output; they say nothing about how often a real model "
      "makes each mistake. Section 4 covers that.")
    w("- Each track uses its own prompt design as built (Kotlin: system prompt with the schema plus the full conversation on "
      "retry; Python: one prompt with the schema, rebuilt with the previous answer and errors on retry). Prompt cost "
      "differences are a property of those designs.")
    w("- Per-extraction totals depend on how many attempts a track used. A track that wrongly accepts bad output on the "
      "first attempt looks cheaper and faster for that case, so compare prompt designs with the per-attempt table.")
    w("- Character counts exclude any schema a provider receives through a native structured-output field.")
    w("- Timings come from one machine. Treat ratios between tracks as meaningful and absolute numbers as indicative.")
    w("- `ideal` is the team's judgment of what a type-safe library should do; reasons are in each case's description.")

    report = RESULTS / "REPORT.md"
    report.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"wrote {report}")


if __name__ == "__main__":
    main()
