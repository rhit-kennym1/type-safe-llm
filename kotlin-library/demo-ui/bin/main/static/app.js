// Demo page logic. Two modes share one flow: message -> fields -> answers -> result.
// All server data is inserted with textContent, never innerHTML.

const STEP_DELAY_MS = 700;
const LIVE_SAMPLE = "can we move standup to 9:30 tomorrow in room 204? looping in dana and luis";
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

const byId = (id) => document.getElementById(id);
const pause = () => (reduceMotion ? Promise.resolve() : new Promise((r) => setTimeout(r, STEP_DELAY_MS)));

const state = {
  mode: "examples",
  targets: {},             // id -> target
  activeTargetId: null,
  scenarios: [],
  selectedScenario: null,
  selectedModel: null,
  running: false,
};

// ---------- helpers ----------

async function request(url, options = {}) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error ?? `The server returned ${response.status}.`);
  return body;
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

// ---------- fields ----------

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

// ---------- choices ----------

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

function renderLiveModels(models) {
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

// ---------- modes ----------

function setMode(mode) {
  state.mode = mode;
  const live = mode === "live";
  for (const tab of document.querySelectorAll('[role="tab"]')) {
    tab.setAttribute("aria-selected", String(tab.dataset.mode === mode));
  }
  byId("examples-controls").hidden = live;
  byId("live-controls").hidden = !live;
  byId("message-view").hidden = live;
  byId("message-input").hidden = !live;
  // Live mode always targets Event; examples mode shows whichever target the chosen scenario uses.
  if (live) {
    activateTarget("event");
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
  byId("attempts").replaceChildren();
  byId("answers-empty").hidden = false;
  byId("result").replaceChildren();
  byId("result").className = "result";
  byId("run-button").disabled = state.running;
}

function startRun() {
  if (state.mode === "live") {
    const message = byId("message-input").value.trim();
    return request("/api/live/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message, modelId: state.selectedModel }),
    });
  }
  return request(`/api/scenarios/${encodeURIComponent(state.selectedScenario)}/run`, { method: "POST" });
}

async function run() {
  if (state.running) return;
  setRunning(true);
  clearRun();
  byId("answers-empty").hidden = true;
  try {
    const result = await startRun();
    for (const attempt of result.attempts) {
      await pause();
      byId("attempts").append(attemptItem(attempt));
    }
    await pause();
    renderOutcome(result.outcome);
  } catch (error) {
    renderError(error);
  } finally {
    setRunning(false);
  }
}

function attemptItem(attempt) {
  const item = byId("attempt-template").content.firstElementChild.cloneNode(true);
  item.classList.add(attempt.accepted ? "accepted" : "rejected");
  item.querySelector(".attempt-number").textContent = `Try ${attempt.number}`;
  item.querySelector(".verdict").textContent = attempt.accepted ? "Fits" : "Sent back";
  item.querySelector(".attempt-output").textContent = attempt.output;

  const problem = item.querySelector(".attempt-problem");
  const error = item.querySelector(".attempt-error");
  if (attempt.problem) {
    problem.textContent = attempt.problem;
    error.querySelector("pre").textContent = attempt.error;
  } else {
    problem.remove();
    error.remove();
  }
  return item;
}

// ---------- result ----------

function renderOutcome(outcome) {
  const container = byId("result");
  const targetName = state.targets[state.activeTargetId]?.name ?? "value";
  if (outcome.type === "success") {
    container.classList.add("success");
    container.replaceChildren(
      element("p", { className: "result-title", text: `The program gets a complete ${targetName}` }),
      valueView(outcome.value),
    );
  } else {
    container.classList.add("failure");
    container.replaceChildren(
      element("p", { className: "result-title", text: `No usable answer after ${outcome.attempts} tries` }),
      element("p", { text: `The program gets an error it can handle, never a half-filled ${targetName}.` }),
    );
  }
}

/** Objects become label/value rows; lists become small tags. */
function valueView(value) {
  if (Array.isArray(value)) {
    if (value.length === 0) return element("span", { className: "muted", text: "none" });
    return element("ul", { className: "tags" }, ...value.map((item) => element("li", {}, valueView(item))));
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
    ? "Check that Ollama is running (ollama serve) and the model is downloaded, then try again."
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
  byId("message-input").value = LIVE_SAMPLE;

  const [targets, scenarios, models] = await Promise.all([
    request("/api/targets"),
    request("/api/scenarios"),
    request("/api/live/models"),
  ]);
  for (const t of targets) state.targets[t.id] = t;
  // Pick a target to show before any scenario is selected, so the fields panel isn't empty on load.
  if (targets.length > 0) activateTarget(targets[0].id);
  renderScenarios(scenarios);
  renderLiveModels(models);
}

init().catch(renderError);