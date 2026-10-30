async function main() {
  const root = document.querySelector("#cases");
  let payload;
  try {
    const response = await fetch("data/examples.json");
    if (!response.ok) throw new Error("HTTP " + response.status);
    payload = await response.json();
  } catch (error) {
    root.textContent = "Could not load data/examples.json (" + error.message + "). Serve docs/ over HTTP.";
    return;
  }
  root.replaceChildren();
  if (payload.simulated) {
    const banner = document.createElement("p");
    banner.className = "banner";
    banner.textContent = "Scores on this page come from the dry-run simulator, not from the named models.";
    root.appendChild(banner);
  }
  payload.cases.forEach((item) => root.appendChild(renderCase(item)));
}

function renderCase(item) {
  const card = document.createElement("article");
  card.className = "case";
  card.append(heading(item.title.replaceAll("_", " ")));
  card.append(paragraph(item.question));
  const pair = document.createElement("div");
  pair.className = "pair";
  pair.append(labeled("Gold answer", item.goldAnswer, false));
  pair.append(labeled("Planted wrong answer (" + item.subtleKind + ")", item.subtleAnswer, true));
  card.append(pair);
  card.append(passage("Source passage", item.sourceContext));
  card.append(passage("Unsupported passage, from " + item.donorTitle.replaceAll("_", " "), item.donorContext));
  card.append(scoreTable(item.scores));
  return card;
}

function scoreTable(scores) {
  const judges = [];
  scores.forEach((score) => {
    if (!judges.some((judge) => judge.id === score.judge)) judges.push({ id: score.judge, label: score.label });
  });
  const variants = ["correct", "subtle_error", "unsupported"];
  const table = document.createElement("table");
  const head = document.createElement("tr");
  head.append(cell("th", "Variant"));
  judges.forEach((judge) => head.append(cell("th", judge.label + "  F / C")));
  const thead = document.createElement("thead");
  thead.append(head);
  const body = document.createElement("tbody");
  variants.forEach((variant) => {
    const tr = document.createElement("tr");
    tr.append(cell("td", variant));
    judges.forEach((judge) => {
      const score = scores.find((row) => row.judge === judge.id && row.variant === variant);
      tr.append(cell("td", score ? score.faithfulness + " / " + score.correctness : "—"));
    });
    body.append(tr);
  });
  table.append(thead, body);
  const scroller = document.createElement("div");
  scroller.className = "scroller";
  scroller.append(table);
  return scroller;
}

function passage(title, text) {
  const details = document.createElement("details");
  const summary = document.createElement("summary");
  summary.textContent = title;
  const body = document.createElement("div");
  body.className = "passage";
  body.textContent = text;
  details.append(summary, body);
  return details;
}

function labeled(name, value, wrong) {
  const block = document.createElement("div");
  const label = document.createElement("div");
  label.className = "note";
  label.textContent = name;
  const answer = document.createElement("p");
  if (wrong) answer.className = "wrong";
  answer.textContent = value;
  block.append(label, answer);
  return block;
}

function heading(text) {
  const node = document.createElement("h2");
  node.textContent = text;
  return node;
}

function paragraph(text) {
  const node = document.createElement("p");
  node.textContent = text;
  return node;
}

function cell(tag, text) {
  const node = document.createElement(tag);
  node.textContent = text;
  return node;
}

main();
