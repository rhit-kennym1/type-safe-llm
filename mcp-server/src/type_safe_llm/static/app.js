// Demo page logic. Two modes share one flow: source -> fields -> what happened -> result.
// All server data is inserted with textContent, never innerHTML.

const STEP_DELAY_MS = 600;
const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

const LIVE_MODES = [
  { id: "host", title: "The program", description: "The program checks every answer with validate_output and re-prompts with the errors." },
  { id: "tools", title: "The model", description: "The model decides whether to call validate_output. The program still checks the final answer." },
];

const byId = (id) => document.getElementById(id);
const pause = () => (reduceMotion ? Promise.resolve() : new Promise((r) => setTimeout(r, STEP_DELAY_MS)));

const state = {
  mode: "examples",
  schemas: {},
  scenarios: [],
  selectedScenario: null,
  liveSchema: "event",
  liveMode: "host",
  running: false,
  shownSchema: null,
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

function check(name, value) {
  document.querySelector(`input[name="${name}"][value="${CSS.escape(value)}"]`).checked = true;
}

// ---------- fields ----------

function showSchema(name) {
  const schema = state.schemas[name];
  state.shownSchema = schema;
  byId("field-rows").replaceChildren(
    ...schema.fields.map((field) =>
      element(
        "tr",
        {},
        element("td", {}, element("code", { text: field.name })),
        element("td", { text: field.type }),
        element("td", { text: field.required ? "Yes" : "No" }),
      ),
    ),
  );
  byId("target-source").textContent = schema.code;
  byId("target-schema").textContent = JSON.stringify(schema.json_schema, null, 2);
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
  check("scenario", id);
  const scenario = state.scenarios.find((s) => s.id === id);
  byId("message-view").textContent = state.schemas[scenario.schema].source;
  showSchema(scenario.schema);
  clearRun();
}

function renderLiveControls() {
  byId("live-mode-list").replaceChildren(
    ...LIVE_MODES.map((m) => radioOption("live-mode", m.id, m.title, m.description, () => selectLiveMode(m.id))),
  );
  byId("live-schema-list").replaceChildren(
    ...Object.values(state.schemas).map((s) =>
      radioOption("live-schema", s.name, s.title, `${s.fields.length} fields`, () => selectLiveSchema(s.name)),
    ),
  );
  check("live-mode", state.liveMode);
  check("live-schema", state.liveSchema);
}

function selectLiveMode(id) {
  state.liveMode = id;
  clearRun();
}

function selectLiveSchema(name) {
  state.liveSchema = name;
  byId("message-input").value = state.schemas[name].source;
  if (state.mode === "live") showSchema(name);
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
  if (live) {
    showSchema(state.liveSchema);
  } else {
    selectScenario(state.selectedScenario);
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
  byId("meta").textContent = "";
  byId("run-button").disabled = state.running;
}

function startRun() {
  const post = (url, body) =>
    request(url, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body ?? {}) });
  if (state.mode === "live") {
    return post("/api/live/run", {
      schema: state.liveSchema,
      mode: state.liveMode,
      source: byId("message-input").value.trim(),
    });
  }
  return post(`/api/scenarios/${encodeURIComponent(state.selectedScenario)}/run`);
}

async function run() {
  if (state.running) return;
  setRunning(true);
  clearRun();
  byId("answers-empty").hidden = true;
  try {
    const result = await startRun();
    for (const step of result.steps) {
      await pause();
      byId("attempts").append(stepItem(step));
    }
    await pause();
    renderOutcome(result);
  } catch (error) {
    renderError(error);
  } finally {
    setRunning(false);
  }
}

function stepItem(step) {
  const item = byId("attempt-template").content.firstElementChild.cloneNode(true);
  item.classList.add(step.accepted ? "accepted" : "rejected");
  item.querySelector(".attempt-number").textContent = step.label;
  item.querySelector(".verdict").textContent = step.accepted ? "Fits" : "Rejected";
  const output = item.querySelector(".attempt-output");
  if (step.output) output.textContent = step.output;
  else output.remove();

  const problem = item.querySelector(".attempt-problem");
  const error = item.querySelector(".attempt-error");
  if (step.problem) {
    problem.textContent = step.problem;
    if (step.error) error.querySelector("pre").textContent = step.error;
    else error.remove();
  } else {
    problem.remove();
    error.remove();
  }
  const repair = item.querySelector(".attempt-repair");
  if (step.repair_prompt) repair.querySelector("pre").textContent = step.repair_prompt;
  else repair.remove();
  return item;
}

// ---------- result ----------

function renderOutcome(result) {
  const container = byId("result");
  const title = state.shownSchema.title;
  if (result.outcome.type === "success") {
    container.classList.add("success");
    container.replaceChildren(
      element("p", { className: "result-title", text: `The program gets a complete ${title}` }),
      valueView(result.outcome.value),
    );
  } else {
    container.classList.add("failure");
    container.replaceChildren(
      element("p", { className: "result-title", text: result.outcome.message }),
      element("p", { text: `The program gets an error it can handle, never a half-filled ${title}.` }),
    );
  }
  const parts = [`Model: ${result.provider}`];
  if (result.meta) {
    parts.push(`Model called validate_output: ${result.meta.model_called_validate ? "yes" : "no"}`);
    parts.push(`Model calls: ${result.meta.model_calls}`);
  }
  byId("meta").textContent = parts.join(" · ");
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
    ? "Check mcp-server/.env, and if you use Ollama that it is running (ollama serve) with the model pulled."
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
  const [schemas, scenarios, config] = await Promise.all([
    request("/api/schemas"),
    request("/api/scenarios"),
    request("/api/live/config"),
  ]);
  state.schemas = Object.fromEntries(schemas.map((s) => [s.name, s]));
  byId("message-input").value = state.schemas[state.liveSchema].source;
  byId("live-config").textContent = `Using ${config.model} at ${config.base_url}. Change it in mcp-server/.env.`;
  renderScenarios(scenarios);
  renderLiveControls();
}

init().catch(renderError);
