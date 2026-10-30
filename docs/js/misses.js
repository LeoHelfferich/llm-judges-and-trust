async function main() {
  const root = document.querySelector("#misses");
  let payload;
  try {
    const response = await fetch("data/misses.json");
    if (!response.ok) throw new Error("HTTP " + response.status);
    payload = await response.json();
  } catch (error) {
    root.textContent = "Could not load data/misses.json (" + error.message + "). Serve docs/ over HTTP.";
    return;
  }
  const state = { judge: "", prompt: "", variant: "", only5: false };
  const controls = document.querySelector("#controls");
  const values = (key) => [...new Set(payload.rows.map((r) => r[key]))].sort();
  controls.append(
    select("Judge", values("label"), (v) => { state.judge = v; draw(); }),
    select("Prompt", values("prompt"), (v) => { state.prompt = v; draw(); }),
    select("Case", values("variant"), (v) => { state.variant = v; draw(); }),
    checkbox("Only perfect 5s", (v) => { state.only5 = v; draw(); }),
  );

  function draw() {
    root.replaceChildren();
    if (payload.simulated) {
      root.append(el("p", "banner", "These rows come from the dry-run simulator, not from the named models."));
    }
    (payload.disputed || []).forEach((d) => root.append(el("p", "note", "Excluded from the analysis (not actually wrong): item " + d.itemId + ". " + d.reason)));
    const rows = payload.rows.filter((r) =>
      (!state.judge || r.label === state.judge) &&
      (!state.prompt || r.prompt === state.prompt) &&
      (!state.variant || r.variant === state.variant) &&
      (!state.only5 || r.missedAt5));
    root.append(el("p", "note", rows.length + " of " + payload.rows.length + " rows. " + payload.definition));
    root.append(summary(rows));
    const table = document.createElement("table");
    table.className = "misses";
    const head = document.createElement("thead");
    head.append(tr("th", ["Item", "Question", "Gold", "Shown answer", "Judge", "Prompt", "Rep", "F / C", "Rationale"]));
    const body = document.createElement("tbody");
    rows.forEach((r) => {
      const shown = r.variant === "unsupported"
        ? r.shownAnswer + " (passage from " + (r.donorTitle || "").replaceAll("_", " ") + ")"
        : r.shownAnswer + " (" + r.subtleKind + ")";
      const row = tr("td", [
        r.title.replaceAll("_", " "),
        r.question, r.goldAnswer, shown, r.label, r.prompt, String(r.repeat),
        r.faithfulness + " / " + r.correctness, r.rationale || "",
      ]);
      if (r.missedAt5) row.children[7].className = "five";
      body.append(row);
    });
    table.append(head, body);
    root.append(table);
  }
  draw();
}

function summary(rows) {
  const counts = {};
  rows.forEach((r) => { counts[r.label] = (counts[r.label] || 0) + 1; });
  const text = Object.entries(counts).sort((a, b) => b[1] - a[1]).map(([k, v]) => k + " " + v).join(" · ");
  return el("p", "note", text || "No rows.");
}

function select(label, options, onChange) {
  const wrap = el("label", null, label + " ");
  const node = document.createElement("select");
  node.append(new Option("all", ""));
  options.forEach((o) => node.append(new Option(o.replaceAll("_", " "), o)));
  node.addEventListener("change", () => onChange(node.value));
  wrap.append(node);
  return wrap;
}

function checkbox(label, onChange) {
  const wrap = el("label");
  const node = document.createElement("input");
  node.type = "checkbox";
  node.addEventListener("change", () => onChange(node.checked));
  wrap.append(node, " " + label);
  return wrap;
}

function tr(tag, values) {
  const row = document.createElement("tr");
  values.forEach((v) => {
    const c = document.createElement(tag);
    c.textContent = v;
    row.append(c);
  });
  return row;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

main();
