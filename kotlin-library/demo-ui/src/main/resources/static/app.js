// Demo page logic. Examples and Live model share one flow:
// class -> schema -> prompt -> what the model receives -> answers and checks -> result (+ field report).
// All server data is inserted with textContent, never innerHTML.

const STEP_DELAY_MS = 700;
const MAX_PROMPT_CHARS = 4000;
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

const byId = (id) => document.getElementById(id);
const pause = () => (reduceMotion ? Promise.resolve() : new Promise((r) => setTimeout(r, STEP_DELAY_MS)));

const state = {
  mode: "examples",
  targets: {},             // id -> target
  targetOrder: [],
  activeTargetId: null,
  scenarios: [],
  selectedScenario: null,
  liveTargetId: null,
  livePrompts: {},         // target id -> prompt text, so edits survive switching classes
  selectedModel: null,
  models: [],
  running: false,
};

const SAFEGUARDS = [
  ["allowMissing", "sg-missing", "missing"],
  ["requireEvidence", "sg-evidence", "evidence"],
  ["targetedRetry", "sg-targeted", "targeted retry"],
  ["crossCheck", "sg-crosscheck", "cross-check"],
];

// ---------- helpers ----------

async function request(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error ?? `The server returned ${response.status}.`);
  return body;
}

function postJson(url, body) {
  return request(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
}

function element(tag, { text, className } = {}, ...children) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  node.append(...children);
  return node;
}

function radioOption(name, value, title, description, onSelect) {
  const input = element("input");
  Object.assign(input, { type: "radio", name, value });
  input.addEventListener("change", onSelect);
  return element(
    "label",
    { className: "option" },
    input,
    element("span", { className: "option-title", text: title }),
    element("small", { text: description }),
  );
}

const ROLE_LABEL = {
  system: "System message (written by the library)",
  user: "User message",
  assistant: "The model's previous answer",
};

function currentSafeguards() {
  return Object.fromEntries(SAFEGUARDS.map(([key, id]) => [key, byId(id).checked]));
}

const short = (value) => {
  if (value === null || value === undefined) return "";
  const text = typeof value === "string" ? value : JSON.stringify(value);
  return text.length > 60 ? `${text.slice(0, 57)}…` : text;
};

// ---------- class and schema ----------

function activateTarget(targetId) {
  const target = state.targets[targetId];
  if (!target) return;
  state.activeTargetId = targetId;
  byId("target-heading").textContent = target.name;
  byId("target-note").textContent = target.note ?? "";
  byId("target-note").hidden = !target.note;
  byId("field-rows").replaceChildren(
    ...target.fields.map((field) =>
      element(
        "tr",
        {},
        element("td", {}, element("code", { text: field.name })),
        element("td", { text: field.type }),
        element("td", { text: field.required ? "Yes" : "No" }),
      ),
    ),
  );
  byId("target-source").textContent = target.source;
  byId("target-schema").textContent = JSON.stringify(target.schema, null, 2);
}

// ---------- examples ----------

function renderScenarios(scenarios) {
  state.scenarios = scenarios;
  byId("scenario-list").replaceChildren(
    ...scenarios.map((s) => radioOption("scenario", s.id, s.title, s.description, () => selectScenario(s.id))),
  );
  if (scenarios.length > 0) selectScenario(scenarios[0].id);
}

function selectScenario(id) {
  state.selectedScenario = id;
  document.querySelector(`input[name="scenario"][value="${CSS.escape(id)}"]`).checked = true;
  const scenario = state.scenarios.find((s) => s.id === id);
  byId("message-view").textContent = scenario.message;
  activateTarget(scenario.targetId);
  clearRun();
}

// ---------- live ----------

function renderLiveTargets() {
  byId("live-target-list").replaceChildren(
    ...state.targetOrder.map((id) => {
      const t = state.targets[id];
      return radioOption("live-target", id, t.name, t.fields.map((f) => f.name).join(", "), () => selectLiveTarget(id));
    }),
  );
  if (state.targetOrder.length > 0) selectLiveTarget(state.targetOrder[0]);
}

function selectLiveTarget(id) {
  const input = byId("message-input");
  if (state.liveTargetId) state.livePrompts[state.liveTargetId] = input.value;
  state.liveTargetId = id;
  document.querySelector(`input[name="live-target"][value="${CSS.escape(id)}"]`).checked = true;
  input.value = state.livePrompts[id] ?? state.targets[id].livePrompt ?? "";
  updateCount();
  if (state.mode === "live") activateTarget(id);
  clearRun();
}

function renderLiveModels(models) {
  state.models = models;
  byId("live-model-list").replaceChildren(
    ...models.map((m) => radioOption("live-model", m.id, m.label, `${m.description} (${m.model})`, () => selectModel(m.id))),
  );
  if (models.length > 0) selectModel(models[0].id);
}

function selectModel(id) {
  state.selectedModel = id;
  document.querySelector(`input[name="live-model"][value="${CSS.escape(id)}"]`).checked = true;
  clearRun();
}

function updateCount() {
  const length = byId("message-input").value.length;
  const count = byId("prompt-count");
  count.textContent = `${length.toLocaleString()} / ${MAX_PROMPT_CHARS.toLocaleString()} characters`;
  count.classList.toggle("over", length > MAX_PROMPT_CHARS);
}

// ---------- modes ----------

function setMode(mode) {
  state.mode = mode;
  const live = mode === "live";
  for (const tab of document.querySelectorAll('[role="tab"]')) {
    tab.setAttribute("aria-selected", String(tab.dataset.mode === mode));
  }
  byId("examples-controls").hidden = mode !== "examples";
  byId("live-controls").hidden = !live;
  byId("safeguard-controls").hidden = mode === "examples";
  byId("message-view").hidden = live;
  byId("message-input").hidden = !live;
  byId("prompt-count").hidden = !live;
  byId("prompt-hint").textContent = live
    ? "Sent to the model exactly as written, as the user message. The library adds its own system message with the schema (step 4)."
    : "The library wraps this message in a short instruction and sends it as the user message.";
  if (live) {
    activateTarget(state.liveTargetId);
  } else if (state.selectedScenario) {
    const scenario = state.scenarios.find((s) => s.id === state.selectedScenario);
    if (scenario) activateTarget(scenario.targetId);
  }
  clearRun();
}

// ---------- running ----------

function setRunning(running) {
  state.running = running;
  const button = byId("run-button");
  button.disabled = running;
  button.setAttribute("aria-busy", String(running));
  button.textContent = running ? (state.mode === "live" ? "Waiting for the model" : "Running") : "Run";
}

function clearRun() {
  byId("sent-messages").replaceChildren();
  byId("sent-note").hidden = true;
  byId("sent-empty").hidden = false;
  byId("attempts").replaceChildren();
  byId("answers-empty").hidden = false;
  byId("result").replaceChildren();
  byId("result").className = "result";
  byId("field-report").replaceChildren();
  byId("field-report-wrap").hidden = true;
  byId("run-button").disabled = state.running;
  setRunning(state.running);
}

function startRun() {
  if (state.mode === "live") {
    return postJson("/api/live/run", {
      prompt: byId("message-input").value.trim(),
      modelId: state.selectedModel,
      targetId: state.liveTargetId,
      safeguards: currentSafeguards(),
    });
  }
  return request(`/api/scenarios/${encodeURIComponent(state.selectedScenario)}/run`, { method: "POST" });
}

async function run() {
  if (state.running) return;
  setRunning(true);
  clearRun();
  byId("sent-empty").hidden = true;
  byId("answers-empty").hidden = true;
  try {
    const result = await startRun();
    renderSent(result);
    for (const [i, attempt] of result.attempts.entries()) {
      await pause();
      byId("attempts").append(attemptItem(attempt, result.attempts[i + 1]));
    }
    if (result.crossCheck) {
      await pause();
      byId("attempts").append(crossCheckItem(result.crossCheck));
    }
    await pause();
    renderOutcome(result.outcome);
    renderFieldReport(result);
  } catch (error) {
    byId("sent-empty").hidden = false;
    renderError(error);
  } finally {
    setRunning(false);
  }
}

// ---------- step 4: what the model receives ----------

function renderSent(result) {
  const first = result.attempts[0];
  const note = byId("sent-note");
  const sg = result.safeguards ?? {};
  const grounded = sg.allowMissing || sg.requireEvidence;
  let text;
  if (result.model === null || result.model === undefined) {
    text = "Scripted example: these are the real messages the library built. The answers in step 5 are pre-written.";
  } else if (result.enforced) {
    text = `Sent to ${result.model}. The schema also went to the provider's structured-output field, so the provider limited what the model could write while generating. The library still checks every answer.`;
  } else {
    text = `Sent to ${result.model}. The schema went only into the system message. Nothing forces the model to follow it, so every answer is checked.`;
  }
  if (sg.requireEvidence) text += " Safeguards changed the schema: each field is now a small object (quote, value), so the model must show where each value came from" + (sg.allowMissing ? "; an empty quote means the field is missing." : ".");
  else if (grounded) text += " Safeguards changed the schema: each field is now a small object (status, value), so the model can say \"missing\".";
  note.textContent = text;
  note.hidden = false;
  byId("sent-messages").replaceChildren(...(first?.sent ?? []).map(messageItem));
}

function messageItem(message) {
  return element(
    "li",
    { className: `sent ${message.role}` },
    element("p", { className: "sent-role", text: ROLE_LABEL[message.role] ?? message.role }),
    element("pre", { text: message.content }),
  );
}

// ---------- step 5: answers and checks ----------

function attemptItem(attempt, next) {
  const item = byId("attempt-template").content.firstElementChild.cloneNode(true);
  item.classList.add(attempt.accepted ? "accepted" : "rejected");
  item.querySelector(".attempt-number").textContent = `Try ${attempt.number}`;
  item.querySelector(".verdict").textContent = attempt.accepted ? (next ? "Followed up" : "Decoded") : "Sent back";
  item.querySelector(".check").textContent = !attempt.accepted ? `Failed: ${attempt.check}`
    : next ? "Decoded, but some fields need a second look" : "All checks passed";
  item.querySelector(".attempt-output").textContent = attempt.output;

  const meta = [];
  if (attempt.durationMs) meta.push(`${attempt.durationMs.toLocaleString()} ms`);
  if (attempt.inputTokens != null) meta.push(`${attempt.inputTokens.toLocaleString()} tokens in, ${attempt.outputTokens.toLocaleString()} out`);
  const metaNode = item.querySelector(".attempt-meta");
  if (meta.length) metaNode.textContent = meta.join(" · ");
  else metaNode.remove();

  const problem = item.querySelector(".attempt-problem");
  const error = item.querySelector(".attempt-error");
  if (attempt.problem) {
    problem.textContent = attempt.problem;
    error.querySelector("pre").textContent = attempt.error;
  } else {
    problem.remove();
    error.remove();
  }

  // The correction or targeted follow-up the library added before the next try.
  const sentBack = item.querySelector(".attempt-sentback");
  const correction = next?.sent?.filter((m) => m.role === "user").map((m) => m.content).join("\n\n");
  if (correction) {
    sentBack.querySelector("pre").textContent = correction;
    if (attempt.accepted) sentBack.querySelector("summary").textContent = "Targeted follow-up the library sent";
  } else sentBack.remove();
  return item;
}

function crossCheckItem(cc) {
  const item = element("li", { className: `attempt crosscheck ${cc.error ? "rejected" : "accepted"}` });
  item.append(
    element("div", { className: "attempt-head" },
      element("span", { className: "attempt-number", text: "Cross-check call" }),
      element("span", { className: "check", text: cc.error ? "Not usable" : "Independent quotes" })),
    element("p", { className: "attempt-meta", text: "A separate request that asks only for supporting quotes, field by field. Values it can't support are flagged as disputed." }),
    element("pre", { className: "attempt-output", text: cc.output }),
  );
  if (cc.sent?.length) {
    const details = element("details", { className: "attempt-sentback" }, element("summary", { text: "What the cross-check asked" }), element("pre", { text: cc.sent.map((m) => m.content).join("\n\n") }));
    item.append(details);
  }
  return item;
}

// ---------- step 6: result ----------

function renderOutcome(outcome) {
  const container = byId("result");
  const target = state.targets[state.activeTargetId];
  const targetName = target?.name ?? "value";
  const className = target?.className || targetName;
  if (outcome.type === "success") {
    container.classList.add("success");
    container.replaceChildren(
      element("p", { className: "result-title", text: `A complete ${targetName} object` }),
      element("p", { className: "muted", text: `llm.extract<${className}>(prompt) returned Complete. Every field passed the checks that were switched on.` }),
      valueView(outcome.value),
    );
  } else if (outcome.type === "incomplete") {
    container.classList.add("partial");
    container.replaceChildren(
      element("p", { className: "result-title", text: `Incomplete: the source doesn't state ${outcome.missing.join(", ")}` }),
      element("p", { className: "muted", text: `The library returned what it found and named the gaps, instead of a ${targetName} with invented values.` }),
      valueView(outcome.partial),
    );
  } else if (outcome.type === "unverified") {
    container.classList.add("partial");
    container.replaceChildren(
      element("p", { className: "result-title", text: `Unverified: ${outcome.flagged.join(", ")} may be invented` }),
      element("p", { className: "muted", text: "The value decoded, but the safeguards couldn't find support in the source for the flagged fields." }),
      valueView(outcome.value),
    );
  } else {
    container.classList.add("failure");
    container.replaceChildren(
      element("p", { className: "result-title", text: `No usable answer after ${outcome.attempts} tries` }),
      element("p", { text: `The program gets a typed failure with every attempt inside, never a half-filled ${targetName}.` }),
    );
  }
}

const STATUS_CLASS = { found: "good", missing: "warn", unverified: "bad", disputed: "bad" };

function renderFieldReport(result) {
  const fields = result.fields ?? [];
  if (!fields.length) return;
  byId("field-report").replaceChildren(
    ...fields.map((f) =>
      element(
        "tr",
        {},
        element("td", {}, element("code", { text: f.name })),
        element("td", {}, element("span", { className: `chip ${STATUS_CLASS[f.status] ?? ""}`, text: f.status })),
        element("td", { text: short(f.value) }),
        element("td", { className: "quote", text: f.quote ? `“${f.quote}”` : "" }),
        element("td", { className: "muted", text: f.note ?? "" }),
      ),
    ),
  );
  byId("field-report-wrap").hidden = false;
}

/** Objects become label/value rows; lists of plain values become small tags; lists of objects stack. */
function valueView(value) {
  if (Array.isArray(value)) {
    if (value.length === 0) return element("span", { className: "muted", text: "none" });
    if (value.every((v) => v === null || typeof v !== "object")) {
      return element("ul", { className: "tags" }, ...value.map((item) => element("li", {}, valueView(item))));
    }
    return element("ol", { className: "nested" }, ...value.map((item) => element("li", {}, valueView(item))));
  }
  if (value !== null && typeof value === "object") {
    const rows = Object.entries(value).flatMap(([key, v]) => [element("dt", { text: key }), element("dd", {}, valueView(v))]);
    return element("dl", {}, ...rows);
  }
  if (value === null) return element("span", { className: "muted", text: "not given" });
  return element("span", { text: String(value) });
}

function renderError(error) {
  const container = byId("result");
  container.className = "result failure";
  const hint = state.mode === "live"
    ? "Check that Ollama is running (ollama serve) and the model is downloaded, or pick another model, then try again."
    : "Check that the demo server is still running, then try again.";
  container.replaceChildren(
    element("p", { className: "result-title", text: "That run didn't finish" }),
    element("p", { text: hint }),
    element("p", { className: "muted", text: error.message }),
  );
}

// ---------- start ----------

async function init() {
  byId("run-button").addEventListener("click", run);
  for (const tab of document.querySelectorAll('[role="tab"]')) {
    tab.addEventListener("click", () => setMode(tab.dataset.mode));
  }
  byId("message-input").addEventListener("input", updateCount);
  for (const [, id] of SAFEGUARDS) byId(id).addEventListener("change", () => clearRun());

  const [targets, scenarios, models] = await Promise.all([
    request("/api/targets"),
    request("/api/scenarios"),
    request("/api/live/models"),
  ]);
  for (const t of targets) state.targets[t.id] = t;
  state.targetOrder = targets.map((t) => t.id);
  // Pick a target to show before any scenario is selected, so the class panel isn't empty on load.
  if (targets.length > 0) activateTarget(targets[0].id);
  renderLiveTargets();
  renderScenarios(scenarios);
  renderLiveModels(models);
  setMode("examples");
}

init().catch(renderError);
