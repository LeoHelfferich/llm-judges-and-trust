const COLORS = {
  xAI: "#1e4a40",
  OpenAI: "#8f3418",
  Anthropic: "#8a5a2b",
  Google: "#1e3a5f",
};

async function main() {
  const root = document.querySelector("#report");
  let metrics;
  try {
    const response = await fetch("data/metrics.json");
    if (!response.ok) throw new Error("HTTP " + response.status);
    metrics = await response.json();
  } catch (error) {
    root.innerHTML = "";
    const box = document.createElement("p");
    box.className = "error";
    box.textContent = "Could not load data/metrics.json (" + error.message + "). Serve this folder over HTTP, for example: python3 -m http.server -d docs 8000";
    root.appendChild(box);
    return;
  }
  if (window.Chart) {
    Chart.defaults.font.family = '"Avenir Next", "Segoe UI", sans-serif';
    Chart.defaults.color = "#5e584e";
    Chart.defaults.borderColor = "#ddd4c4";
    Chart.defaults.animation = false;
  }
  render(root, metrics);
}

function render(root, metrics) {
  root.replaceChildren();
  const h = metrics.headlines;
  const meta = metrics.meta;
  if (meta.simulated) {
    root.appendChild(el("p", "banner", "These charts are the dry-run simulator. They exercise the pipeline and are not measurements of the named models. A paid run replaces this file."));
  }
  const stats = el("div", "stats");
  stats.append(
    stat(pct(h.meanFaithfulnessSelfDisagreement), "Same judge, same input, faithfulness not identical across repeats"),
    stat(num(h.meanPairwiseFaithfulnessKappa), "Mean pairwise quadratic κ on faithfulness"),
    stat(pct(h.meanSubtleMissRate), "Subtly wrong answers scored correct (≥ " + metrics.plantedErrors.missThreshold + ")"),
    stat(pct(h.meanSubtleMissRateAt5), "Subtly wrong answers scored a perfect 5"),
    stat(pct(h.meanUnsupportedMissRate), "Unsupported answers scored faithful (≥ " + metrics.plantedErrors.missThreshold + ")"),
  );
  root.appendChild(stats);
  root.appendChild(el("p", "note", costLine(meta)));

  const omitted = (meta.judges || []).filter((judge) => judge.supportsTemperature === false).map((judge) => judge.label);
  const temperatureNote = omitted.length
    ? " Temperature is " + meta.temperature + " when the model accepts it. " + omitted.join(", ") + " does not, so those calls omit it."
    : " Temperature is " + meta.temperature + ".";
  root.appendChild(section("Self-consistency",
    "The strict rubric is scored " + (meta.repeats.strict || 3) + " times." + temperatureNote + " The bars count how far faithfulness moves between those repeats. A range of 0 means the judge repeated itself.",
    driftChart(metrics),
    consistencyTable(metrics),
  ));

  root.appendChild(section("Prompt sensitivity",
    "Mean score change versus the strict rubric, on the first repeat. Positive means the other prompt was more generous.",
    promptChart(metrics),
    promptTable(metrics),
  ));

  root.appendChild(section("Inter-judge agreement",
    "Quadratic weighted κ on faithfulness, strict rubric, first repeat, " + metrics.interJudge.nItems + " answer variants. 1 is perfect agreement.",
    kappaTable(metrics.interJudge, metrics.interJudge.faithfulness.quadraticKappa),
  ));

  root.appendChild(section("Planted errors",
    metrics.plantedErrors.subtleMiss + ". Stricter cutoff: " + (metrics.plantedErrors.subtleMissAt5 || "correctness == 5") + ". " +
      metrics.plantedErrors.unsupportedMiss + ". " + metrics.plantedErrors.correctFalseReject + ". Strict rubric, first repeat.",
    plantedChart(metrics),
    plantedTable(metrics),
    disputedNote(metrics),
    parseNote(metrics),
  ));

  const more = el("p", "note");
  more.append("Three worked items are on the ");
  more.append(link("examples.html", "cases page"));
  more.append(". Every missed planted case is on the ");
  more.append(link("misses.html", "misses page"));
  more.append(". Row-level scores: ");
  more.append(link("data/scores.csv", "scores.csv"));
  more.append(".");
  root.appendChild(more);
}

function section(title, note, ...nodes) {
  const block = el("section");
  block.append(el("h2", null, title), el("p", "note", note));
  nodes.filter(Boolean).forEach((node) => {
    if (node.tagName === "TABLE") {
      const scroller = el("div", "scroller");
      scroller.append(node);
      block.append(scroller);
    } else {
      block.append(node);
    }
  });
  return block;
}

function driftChart(metrics) {
  const judges = metrics.selfConsistency.judges;
  return chart("drift", {
    type: "bar",
    data: {
      labels: judges.map((j) => j.label),
      datasets: [
        { label: "Range 0", data: judges.map((j) => j.faithfulness.rangeHistogram[0]), backgroundColor: "#1e4a40" },
        { label: "Range 1", data: judges.map((j) => j.faithfulness.rangeHistogram[1]), backgroundColor: "#c4a574" },
        { label: "Range 2+", data: judges.map((j) => j.faithfulness.rangeHistogram.slice(2).reduce((a, b) => a + b, 0)), backgroundColor: "#8f3418" },
      ],
    },
    options: stackedOptions("Answer variants"),
  });
}

function promptChart(metrics) {
  const judges = metrics.meta.judges;
  const datasets = metrics.promptSensitivity.comparisons.map((comparison, index) => ({
    label: comparison.prompt + " − strict",
    data: comparison.judges.map((j) => j.meanDeltaFaithfulness),
    backgroundColor: index === 0 ? "#8f3418" : "#1e4a40",
  }));
  return chart("prompt", {
    type: "bar",
    data: { labels: judges.map((j) => j.label), datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: { ticks: xTicks() },
        y: { title: { display: true, text: "Mean faithfulness change" } },
      },
    },
  });
}

function plantedChart(metrics) {
  const judges = metrics.plantedErrors.judges;
  return chart("planted", {
    type: "bar",
    data: {
      labels: judges.map((j) => j.label),
      datasets: [
        { label: "Subtle miss (≥ " + metrics.plantedErrors.missThreshold + ")", data: judges.map((j) => (j.subtleMissRate || 0) * 100), backgroundColor: "#8f3418" },
        { label: "Subtle miss (= 5)", data: judges.map((j) => (j.subtleMissRateAt5 || 0) * 100), backgroundColor: "#d08a6a" },
        { label: "Unsupported miss", data: judges.map((j) => (j.unsupportedMissRate || 0) * 100), backgroundColor: "#1e3a5f" },
        { label: "False reject", data: judges.map((j) => (j.correctFalseRejectRate || 0) * 100), backgroundColor: "#c4a574" },
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      scales: {
        x: { ticks: xTicks() },
        y: { title: { display: true, text: "Percent of items" }, beginAtZero: true },
      },
    },
  });
}

function chart(id, config) {
  const box = el("div", "chart");
  const canvas = document.createElement("canvas");
  canvas.id = id;
  box.append(canvas);
  if (!window.Chart) {
    box.append(el("p", "note", "Chart library did not load. The table below has the same numbers."));
    return box;
  }
  requestAnimationFrame(() => new Chart(canvas, config));
  return box;
}

function xTicks() {
  const narrow = window.matchMedia("(max-width: 720px)").matches;
  return narrow
    ? { maxRotation: 60, minRotation: 60, autoSkip: false, font: { size: 10 } }
    : { maxRotation: 0, minRotation: 0, autoSkip: false };
}

function stackedOptions(yTitle) {
  return {
    responsive: true,
    maintainAspectRatio: false,
    scales: {
      x: { stacked: true, ticks: xTicks() },
      y: { stacked: true, title: { display: true, text: yTitle }, beginAtZero: true },
    },
  };
}

function consistencyTable(metrics) {
  const table = document.createElement("table");
  table.append(row("thead", ["Judge", "Groups", "Faithfulness not identical", "Mean faithfulness range", "Correctness not identical"]));
  const body = document.createElement("tbody");
  metrics.selfConsistency.judges.forEach((judge) => {
    body.append(row("tr", [
      judge.label,
      String(judge.nGroups),
      pct(judge.faithfulness.pctNotIdentical),
      num(judge.faithfulness.meanRange),
      pct(judge.correctness.pctNotIdentical),
    ]));
  });
  table.append(body);
  return table;
}

function promptTable(metrics) {
  const table = document.createElement("table");
  table.append(row("thead", ["Prompt", "Judge", "n", "Δ faithfulness", "Δ correctness", "|Δ faith| ≥ 2"]));
  const body = document.createElement("tbody");
  metrics.promptSensitivity.comparisons.forEach((comparison) => {
    comparison.judges.forEach((judge) => {
      body.append(row("tr", [
        comparison.prompt,
        judge.label,
        String(judge.n),
        signed(judge.meanDeltaFaithfulness),
        signed(judge.meanDeltaCorrectness),
        pct(judge.pctFaithfulnessShiftAtLeast2),
      ]));
    });
  });
  table.append(body);
  return table;
}

function kappaTable(inter, matrix) {
  const table = document.createElement("table");
  table.className = "heat";
  const head = document.createElement("tr");
  head.append(document.createElement("th"));
  inter.labels.forEach((label) => head.append(cell("th", label)));
  const thead = document.createElement("thead");
  thead.append(head);
  const body = document.createElement("tbody");
  matrix.forEach((values, i) => {
    const tr = document.createElement("tr");
    tr.append(cell("th", inter.labels[i]));
    values.forEach((value) => {
      const td = cell("td", num(value));
      const tone = Math.max(0, Math.min(1, value));
      td.style.background = mix("#f7f3ea", "#1e4a40", tone * tone);
      if (tone > 0.82) td.style.color = "#f7f3ea";
      tr.append(td);
    });
    body.append(tr);
  });
  table.append(thead, body);
  return table;
}

function disputedNote(metrics) {
  const disputed = metrics.plantedErrors.disputed || [];
  const h = metrics.headlines;
  const box = el("div", "note");
  if (!disputed.length) return box;
  box.append(el("p", null,
    "Disputed: " + disputed.length + " planted case" + (disputed.length > 1 ? "s are" : " is") + " arguably not wrong. " +
    disputed.map((d) => d.reason).join(" ") +
    " Mean subtle miss with it: " + pct(h.meanSubtleMissRate) + " (≥ " + metrics.plantedErrors.missThreshold + "), " + pct(h.meanSubtleMissRateAt5) + " (= 5). " +
    "Without it: " + pct(h.meanSubtleMissRateExcludingDisputed) + " (≥ " + metrics.plantedErrors.missThreshold + "), " + pct(h.meanSubtleMissRateAt5ExcludingDisputed) + " (= 5)."));
  return box;
}

function parseNote(metrics) {
  const box = el("div", "note");
  (metrics.parseFailures || []).filter((p) => p.unparsed > 0).forEach((p) => {
    const reasons = Object.entries(p.byReason).map(([k, v]) => v + "× " + k.replaceAll("_", " ")).join(", ");
    const prompts = Object.entries(p.byPrompt).map(([k, v]) => v + " " + k).join(", ");
    const variants = Object.entries(p.byVariant).map(([k, v]) => v + " " + k.replaceAll("_", " ")).join(", ");
    let text = p.label + ": " + p.unparsed + " of " + p.calls + " replies did not parse (" + reasons + "; prompts: " + prompts + "; variants: " + variants + ").";
    const mc = p.missingCorrectnessFaithfulness || {};
    if (Object.keys(mc).length) {
      text += " Every reply without a correctness score gave faithfulness " + Object.keys(mc).join("/") +
        ", so the judge did flag the answer. These rows are left out of every rate, which makes its subtle-miss rate rest on fewer items (see the n column).";
    }
    box.append(el("p", null, text));
  });
  return box;
}

function plantedTable(metrics) {
  const table = document.createElement("table");
  table.append(row("thead", ["Judge", "n", "Subtle miss ≥ " + metrics.plantedErrors.missThreshold, "Subtle miss = 5", "Without disputed (≥ " + metrics.plantedErrors.missThreshold + " / = 5)", "Unparsed subtle", "Unsupported miss", "False reject", "Entity-swap miss", "Numeric miss"]));
  const body = document.createElement("tbody");
  metrics.plantedErrors.judges.forEach((judge) => {
    const kinds = judge.subtleMissRateByKind || {};
    const numericKeys = Object.keys(kinds).filter((key) => key !== "entity_swap");
    const numeric = numericKeys.length ? average(numericKeys.map((key) => kinds[key])) : null;
    body.append(row("tr", [
      judge.label,
      String(judge.subtleN),
      pct(judge.subtleMissRate),
      pct(judge.subtleMissRateAt5),
      pct(judge.subtleMissRateExcludingDisputed) + " / " + pct(judge.subtleMissRateAt5ExcludingDisputed),
      judge.subtleUnparsed ? judge.subtleUnparsed + (judge.subtleMissingCorrectness ? " (no correctness)" : "") : "0",
      pct(judge.unsupportedMissRate),
      pct(judge.correctFalseRejectRate),
      pct(kinds.entity_swap),
      pct(numeric),
    ]));
  });
  table.append(body);
  return table;
}

function costLine(meta) {
  const billed = meta.simulated
    ? "Nothing was billed."
    : "Billed so far: " + money(meta.billedCostUsd * meta.eurPerUsd) + " EUR.";
  return "Projected full run " + money(meta.estimatedCostEur) + " EUR (" + money(meta.estimatedCostUsd) + " USD), " +
    money(meta.estimatedCostEurWithMargin) + " EUR with the " + meta.safetyMargin + "× safety margin. Cap " +
    money(meta.capEur) + " EUR. Prices as of " + meta.pricingAsOf + ". " + billed;
}

function stat(value, label) {
  const card = el("div", "stat");
  card.append(el("b", null, value), el("span", null, label));
  return card;
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function row(tag, values) {
  const node = document.createElement(tag === "thead" ? "tr" : tag);
  values.forEach((value, index) => node.append(cell(tag === "thead" ? "th" : "td", value)));
  if (tag === "thead") {
    const head = document.createElement("thead");
    head.append(node);
    return head;
  }
  return node;
}

function cell(tag, text) {
  const node = document.createElement(tag);
  node.textContent = text;
  return node;
}

function link(href, text) {
  const node = document.createElement("a");
  node.href = href;
  node.textContent = text;
  return node;
}

function pct(value) {
  if (value == null || Number.isNaN(value)) return "—";
  return (value * 100).toFixed(1) + "%";
}

function num(value) {
  if (value == null || Number.isNaN(value)) return "—";
  return Number(value).toFixed(3);
}

function signed(value) {
  if (value == null || Number.isNaN(value)) return "—";
  const text = Number(value).toFixed(3);
  return value > 0 ? "+" + text : text;
}

function money(value) {
  return Number(value).toFixed(2);
}

function average(values) {
  if (!values.length) return null;
  return values.reduce((a, b) => a + b, 0) / values.length;
}

function mix(from, to, t) {
  const a = hex(from);
  const b = hex(to);
  const c = a.map((channel, i) => Math.round(channel + (b[i] - channel) * t));
  return "rgb(" + c.join(",") + ")";
}

function hex(value) {
  return [1, 3, 5].map((i) => parseInt(value.slice(i, i + 2), 16));
}

main();
