"""Standalone, offline presentation of results from the real MCP demo session."""

import json
from html import escape
from pathlib import Path
from typing import Any


CASES = [
    ("valid", "Valid event", "Correct data passes", "The event matches the declared schema. The validator returns an object the application can use."),
    ("invalid", "Wrong type", "Text is not an integer", 'The value "30" looks like a number, but it is a string. Strict validation rejects it instead of silently converting it.'),
    ("recovered", "Retry recovery", "Feedback guides another attempt", "The first response has the wrong type. The system prepares a repair prompt, then validates a second simulated response."),
    ("exhausted", "Retry limit", "Failure stops safely", "Both responses are invalid. With one retry allowed, the system stops after two attempts and returns no usable data."),
    ("contract", "Nested contract", "A missing title is detected", "The first response omits a required title. The next simulated response includes it. The validator also checks the nested clauses."),
]


def pretty(value: Any) -> str:
    return escape(json.dumps(value, indent=2, ensure_ascii=False))


def render_report(results: dict[str, Any]) -> str:
    tabs = []
    panels = []
    for index, (key, label, headline, explanation) in enumerate(CASES):
        result = results[key]
        ok = result["ok"]
        status = "Validated" if ok else "Retries exhausted" if result.get("status") == "retries_exhausted" else "Rejected"
        tabs.append(f'<button role="tab" id="tab-{key}" aria-controls="case-{key}" aria-selected="{str(index == 0).lower()}" tabindex="{0 if index == 0 else -1}" data-case="{key}"><span>0{index + 1}</span>{label}</button>')
        attempts = result.get("attempts") or [{"number": 1, "result": result}]
        steps = []
        for attempt in attempts:
            outcome = attempt["result"]
            success = outcome["ok"]
            issues = "".join(
                f'<li><code>{escape(error["path"])}</code><span>{escape(error["message"])}</span></li>'
                for error in outcome["errors"]
            )
            feedback = ""
            if attempt.get("repair_prompt"):
                feedback = f'<details><summary>View repair prompt</summary><pre>{escape(attempt["repair_prompt"])}</pre></details>'
            steps.append(f'''<div class="attempt {'pass' if success else 'fail'}">
                <div class="attempt-top"><span>Attempt {attempt['number']}</span><strong>{'Passed' if success else 'Failed'}</strong></div>
                {'<p>All declared checks passed.</p>' if success else f'<ul class="issues">{issues}</ul>'}
                {feedback}</div>''')
        data = result["data"]
        if data is not None:
            rows = []
            for field, value in data.items():
                if isinstance(value, (dict, list)):
                    noun = "item" if isinstance(value, list) else "field"
                    text = f"{len(value)} {noun}{'s' if len(value) != 1 else ''}"
                elif isinstance(value, bool):
                    text = "true" if value else "false"
                elif value is None:
                    text = "Not supplied (optional)"
                else:
                    text = str(value)
                rows.append(f'<tr><th scope="row">{escape(field)}</th><td>{escape(text)}</td></tr>')
            data_view = '<table><tbody>' + "".join(rows) + '</tbody></table>'
        else:
            data_view = '<div class="empty"><span>Data withheld</span><p>No validated object is returned.<br>The application must handle the failure.</p></div>'
        panels.append(f'''<section role="tabpanel" id="case-{key}" aria-labelledby="tab-{key}" {"hidden" if index else ""}>
            <div class="case-heading"><div><p class="eyebrow">Example 0{index + 1} / {escape(result['schema_name'])} schema</p><h2>{headline}</h2></div><span class="badge {'good' if ok else 'bad'}">{status}</span></div>
            <p class="explanation">{explanation}</p>
            <div class="columns"><article><h3>Validation timeline</h3><div class="timeline">{''.join(steps)}</div></article>
            <article><h3>Final result <code>ok: {str(ok).lower()}</code></h3>{data_view}</article></div>
            <details class="raw"><summary>View full MCP result (JSON)</summary><pre>{pretty(result)}</pre></details>
            </section>''')
    tools = "".join(f'<code>{escape(name)}</code>' for name in results["tools"])
    return '''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Type-Safe LLM · MCP demo</title>
<style>
:root{color-scheme:light;--ink:#172d40;--muted:#5b6c7b;--line:#dde5e9;--green:#0a755d;--red:#ae3641}
*{box-sizing:border-box}body{margin:0;background:#f3f6f8;color:var(--ink);font:16px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}
.hero{background:#142c40;color:#fff;padding:42px max(24px,calc((100vw - 1120px)/2));border-bottom:5px solid #64d4bc}
.eyebrow{text-transform:uppercase;font-size:12px;font-weight:700;letter-spacing:1.8px;margin:0 0 10px;color:var(--muted)}.hero .eyebrow{color:#91dcca}
h1{font-size:clamp(30px,4vw,44px);letter-spacing:-1.4px;margin:0 0 10px;line-height:1.15}.hero p:last-child{color:#c2d2df;max-width:720px;margin:0}
main{max-width:1168px;margin:auto;padding:28px 24px 40px}.notice{display:flex;gap:12px;align-items:baseline;padding:14px 18px;border:1px solid #e5d9b9;background:#fff9e9;border-radius:10px;font-size:14px}.notice strong{white-space:nowrap;color:#78591c}.notice p{margin:0}
.workflow{display:flex;gap:10px;align-items:center;margin:24px 0}.node{flex:1;border:1px solid var(--line);background:white;border-radius:10px;padding:16px}.node b{display:block;font-size:15px}.node small{color:var(--muted)}.arrow{font-size:24px;color:#7794a7}.node:last-child{border-color:#a4d6c6;background:#eef9f5}
.loop{color:var(--muted);font-size:14px;text-align:center;margin:-14px 0 24px}
.workspace{background:white;border:1px solid var(--line);border-radius:14px;overflow:hidden;box-shadow:0 8px 28px #172d4006}
.tabs{display:flex;background:#edf2f5;border-bottom:1px solid var(--line)}.tabs button{flex:1;cursor:pointer;padding:18px 10px;background:transparent;border:0;border-bottom:3px solid transparent;color:var(--muted);font:600 14px system-ui}.tabs button span{display:block;font-size:11px;margin-bottom:4px;opacity:.7}.tabs button[aria-selected=true]{background:white;color:var(--green);border-bottom-color:var(--green)}button:focus-visible,summary:focus-visible{outline:3px solid #2563eb;outline-offset:-3px}
section{padding:28px}section[hidden]{display:none}.case-heading{display:flex;gap:18px;align-items:center;justify-content:space-between}h2{margin:0;font-size:28px;line-height:1.25;letter-spacing:-.7px}.badge{white-space:nowrap;padding:6px 12px;border-radius:30px;font-size:13px;font-weight:700}.good{background:#e0f5eb;color:var(--green)}.bad{background:#ffedf0;color:var(--red)}.explanation{color:var(--muted);max-width:820px;margin:16px 0 24px}
.columns{display:grid;grid-template-columns:1fr 1fr;gap:26px}article{min-width:0}h3{font-size:14px;margin:0 0 12px;display:flex;justify-content:space-between;align-items:center}h3 code{font-weight:400;font-size:12px;color:var(--muted)}.timeline{display:grid;gap:14px}.attempt{border:1px solid var(--line);border-left:4px solid var(--red);border-radius:8px;padding:15px}.attempt.pass{border-left-color:var(--green)}.attempt-top{display:flex;justify-content:space-between;font-size:14px}.attempt-top strong{color:var(--red)}.pass .attempt-top strong{color:var(--green)}.attempt p{margin:10px 0 0;color:var(--muted);font-size:14px}.issues{list-style:none;padding:0;margin:12px 0 0}.issues li{display:flex;flex-wrap:wrap;gap:6px 12px;font-size:14px}.issues code{color:var(--red)}
table{width:100%;border-collapse:collapse;border:1px solid var(--line);font-size:14px}td,th{text-align:left;padding:9px 12px;border-bottom:1px solid var(--line);overflow-wrap:anywhere}th{font-weight:500;color:var(--muted);width:46%;background:#f8fafb}td{font-weight:600}.empty{background:#fff5f5;border:1px dashed #e0b4b9;border-radius:10px;padding:28px;text-align:center}.empty span{font-weight:700;color:var(--red)}.empty p{font-size:14px;color:var(--muted);margin-bottom:0}
details{margin-top:12px}summary{cursor:pointer;color:var(--muted);font-size:13px;padding:7px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:420px;overflow:auto;background:#132c40;color:#d8eee8;border-radius:8px;padding:18px;font:12px/1.7 ui-monospace,Consolas,monospace}.raw{margin-top:24px;border-top:1px solid var(--line);padding-top:8px}.footer{display:flex;justify-content:space-between;gap:18px;margin:20px 0 0;font-size:12px;color:var(--muted)}.tools{display:flex;flex-wrap:wrap;gap:8px}.tools code{background:#e7eef1;border-radius:4px;padding:3px 7px}.footer p{margin:0;max-width:480px}
.nav{display:flex;align-items:center;justify-content:space-between;margin-top:18px}.nav span{font-size:12px;color:var(--muted)}.nav button{cursor:pointer;border:1px solid var(--line);border-radius:7px;background:white;color:var(--ink);padding:8px 16px;font:600 13px system-ui}.nav button:disabled{opacity:.4;cursor:default}
@media(max-width:720px){.hero{padding:28px 20px}main{padding:20px 14px}.notice,.footer{display:block}.notice p{margin-top:4px}.workflow{flex-wrap:wrap}.node{flex-basis:40%}.arrow{display:none}.loop{text-align:left;margin-top:0}.tabs{flex-wrap:wrap}.tabs button{flex-basis:30%;padding:12px 6px}.columns{grid-template-columns:1fr}.case-heading{align-items:flex-start}h2{font-size:23px}section{padding:20px}.footer p{margin-top:12px}}
@media print{.hero{background:white;color:var(--ink);padding:20px}.hero p:last-child{color:var(--muted)}.tabs,.nav{display:none}section[hidden]{display:block}.workspace{border:0;box-shadow:none}section{break-inside:avoid;page-break-before:always}.raw{display:none}main{padding:10px}.notice{background:white}}
</style></head><body>
<header class="hero"><p class="eyebrow">Senior capstone / working MCP prototype</p><h1>Type-Safe LLM</h1><p>Turn unpredictable model output into validated data, with clear errors and bounded retries.</p></header>
<main><aside class="notice"><strong>Simulated model responses</strong><p>The MCP connection and validation are real. Retry responses are predefined; no live LLM is called.</p></aside>
<div class="workflow" aria-label="Validation workflow"><div class="node"><b>1. Define schema</b><small>Required fields, types, rules</small></div><span class="arrow" aria-hidden="true">→</span><div class="node"><b>2. Model response</b><small>JSON to be checked</small></div><span class="arrow" aria-hidden="true">→</span><div class="node"><b>3. Validate</b><small>Parse and check every field</small></div><span class="arrow" aria-hidden="true">→</span><div class="node"><b>4. Return result</b><small>Validated object or clear failure</small></div></div>
<p class="loop">If validation fails: errors → repair prompt → another response → validate again, up to the retry limit.</p>
<div class="workspace"><nav class="tabs" role="tablist" aria-label="Demo examples">''' + "".join(tabs) + '</nav>' + "".join(panels) + '''</div>
<div class="nav"><button id="previous" type="button">← Previous</button><span id="position" aria-live="polite">Example 1 of 5 · Use arrow keys to navigate tabs</span><button id="next" type="button">Next →</button></div>
<footer class="footer"><div><p>Discovered through MCP</p><div class="tools">''' + tools + '''</div></div><p>Validation checks structure and declared rules, not factual accuracy. A consuming application must require <code>ok=true</code> before using the data.</p></footer>
</main><script>
const tabs=[...document.querySelectorAll('[role=tab]')];let current=0;
function select(index,focus=false){current=Math.max(0,Math.min(tabs.length-1,index));tabs.forEach((tab,i)=>{tab.setAttribute('aria-selected',String(i===current));tab.tabIndex=i===current?0:-1;document.getElementById(tab.getAttribute('aria-controls')).hidden=i!==current;});document.getElementById('previous').disabled=current===0;document.getElementById('next').disabled=current===tabs.length-1;document.getElementById('position').textContent=`Example ${current+1} of ${tabs.length} · Use arrow keys to navigate tabs`;if(focus)tabs[current].focus();}
tabs.forEach((tab,i)=>{tab.addEventListener('click',()=>select(i));tab.addEventListener('keydown',e=>{if(['ArrowRight','ArrowLeft','Home','End'].includes(e.key)){e.preventDefault();select(e.key==='Home'?0:e.key==='End'?tabs.length-1:current+(e.key==='ArrowRight'?1:-1),true);}});});
document.getElementById('previous').addEventListener('click',()=>select(current-1));document.getElementById('next').addEventListener('click',()=>select(current+1));select(0);
</script></body></html>'''


def write_report(results: dict[str, Any], destination: Path) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(render_report(results), encoding="utf-8")
    return destination.resolve()
