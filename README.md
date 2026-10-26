# LLM-as-Judge lügt

How much can you trust an LLM judge when it scores RAG answers?

This repository measures four things, on a public dataset, with the judging as the only moving part:

1. **Self-consistency.** Same judge, same input, repeated calls. How far do the scores drift?
2. **Prompt sensitivity.** Same judge, three rubrics. How far do the scores move?
3. **Inter-judge disagreement.** Four models, identical inputs.
4. **Planted-error misses.** How often a judge accepts an answer that was built to be wrong, or accepts a correct answer that the retrieved passage does not support.

The harness is Kotlin on the JVM. Answer variants are generated once, deterministically, and checked in. Judge calls go through OpenRouter. Every raw response is cached, so analysis can be rerun without spending anything. A dry-run mode fills that cache with a fake judge, which is what the charts show until a real run replaces them.

## Run it

From the repository root, with JDK 21:

```bash
./gradlew test
./gradlew -q run --args="estimate"
./gradlew -q run --args="run --dry-run --fresh"
./gradlew -q run --args="analyze --run results/dry-run"
python3 -m http.server -d docs 8000
```

Open http://localhost:8000. The pages read `docs/data/` and need a local server. Opening the HTML files directly will not load the JSON.

`estimate` prints the projected cost and refuses (exit code 2) when expected cost × the safety margin exceeds the cap. It does not call a model. `run --dry-run` does not call a model either. `analyze` only reads the cache.

A paid run, after you have checked the live prices:

```bash
export OPENROUTER_API_KEY=...
./gradlew -q run --args="estimate --refresh"
./gradlew -q run --args="run --refresh"
./gradlew -q run --args="analyze"
```

`run` without `--out` writes `results/runs/<timestamp>/`. `analyze` without `--run` picks the newest cache under `results/`. A paid run refreshes the public OpenRouter model list first and will not start if that fetch fails, unless you pass `--allow-stale-prices`. If the process stops because the cap is hit, run the same command again: cached calls are skipped and cost nothing.

Exit codes: `0` finished, `1` bad arguments or missing key, `2` the estimate is over the cap, `3` the run stopped early because the next call's ceiling would cross the cap.

## Method

Fifty questions from the SQuAD 1.1 development set. Each one is scored under three answer conditions:

| Condition | What the judge sees |
| --- | --- |
| `correct` | Gold answer, original passage |
| `subtle_error` | A deterministic edit of the gold answer, same passage |
| `unsupported` | Gold answer, a passage from a different Wikipedia article |

Subtle errors are mostly numeric. The generator prefers the last four-digit year, otherwise the first number outside parentheses, and it keeps a candidate only when the new number is not in the passage and the edit does not duplicate a number (`10th and 11th` does not become `11th and 11th`). Eighteen items instead swap a multi-word proper name for another name in the same passage. The unsupported passage is chosen so it does not contain the gold answer or any number in that answer with three or more digits. Which passage that is depends on a stable hash of the question id, so donors are spread through the corpus rather than all being the first article.

The judge returns JSON: `faithfulness` and `correctness`, each an integer from 1 to 5, plus a one-sentence rationale. Faithfulness means the passage supports the answer. Correctness means the answer addresses the question as the passage states it. Outside knowledge is not supposed to raise a score. The three rubrics live in `Prompts.kt`:

- `strict`, repeated **3** times. This is the primary slice for consistency, agreement, and planted errors.
- `concise`, once. A short instruction.
- `cot`, once. At most two sentences of rationale before the scores.

Temperature is **0** for every model that accepts it. No seed is sent, so whatever nondeterminism the provider still has is part of the measurement. Calls are sequential on purpose: the budget check happens between them.

On the strict rubric's first repeat, a planted error is a **miss** when:

- a subtle error has correctness **≥ 4**, or
- an unsupported answer has faithfulness **≥ 4**.

A correct answer is a **false reject** when faithfulness or correctness is **≤ 2**. Both cutoffs are in `config/experiment.json`.

Inter-judge agreement is quadratic weighted Cohen's κ on the 1–5 scores, plus exact-agreement and mean absolute difference. Self-consistency is the fraction of items whose repeated scores are not identical, the mean range, and a histogram of ranges.

## Judges and cost

Prices below are USD per 1M tokens, read from `https://openrouter.ai/api/v1/models` on **26 October 2026**. The config file is the pin. `estimate --refresh` re-reads the list and, if a live price is higher, uses the higher one for the gate.

| Judge | OpenRouter id | Input | Output | This experiment |
| --- | --- | ---: | ---: | ---: |
| Grok 4.3 | `x-ai/grok-4.3` | $1.25 | $2.50 | $0.5169 |
| GPT-4o mini (2024-07-18) | `openai/gpt-4o-mini-2024-07-18` | $0.15 | $0.60 | $0.0832 |
| Claude Haiku 4.5 | `anthropic/claude-haiku-4.5` | $1.00 | $5.00 | $0.6250 |
| Gemini 2.5 Flash | `google/gemini-2.5-flash` | $0.30 | $2.50 | $0.2580 |

The dated GPT-4o mini snapshot is there so the id cannot silently move. Haiku 4.5 is the Haiku that still advertises a `temperature` parameter. Haiku 5.5 is cheaper ($0.10 / $0.50) but the OpenRouter listing does not advertise `temperature`, which would make the repeat arm incomparable. Gemini 2.5 Flash is the Flash model, not Flash Lite.

Call count: 50 questions × 3 variants × 4 judges × (3 + 1 + 1) prompts-and-repeats = **3000 calls**.

Input tokens are `ceil(chars / 4)` on the exact system and user text (about 1.09M tokens for the full plan). Expected output is 80 / 70 / 160 tokens for strict / concise / cot. `max_tokens` is 160 / 140 / 280, and that ceiling is what the in-run guard uses. Models that advertise reasoning are sent `reasoning.effort = none`, because reasoning tokens are billed as output.

FX pin: **1 USD = 0.89342 EUR** on 23 October 2026, from the Frankfurter API (`https://api.frankfurter.app/2026-10-23?from=USD&to=EUR`).

| | USD | EUR |
| --- | ---: | ---: |
| Expected | 1.4832 | 1.3251 |
| Expected × 1.5 safety margin (this is the gate) | 2.2248 | **1.9876** |
| Ceiling if every call hits `max_tokens` | 2.1669 | 1.9359 |
| Stress: that ceiling, plus 1000 extra output tokens on models that support reasoning | 9.6669 | 8.6366 |
| Cap (default) | | **10.00** |

The run is allowed. The stress row is not the gate. It is the "reasoning was not actually turned off, and every call grew by a thousand tokens" case, and it still fits under 10 EUR. If a provider bills more than that, the runner stops before the next call once actual spend plus the next ceiling would pass the cap. One in-flight call can overshoot the cap by its own bill. Actual cost comes from OpenRouter's `usage.cost` when the response includes it, otherwise from the token estimate.

Change the set, the repeats, or `capEur` in `config/experiment.json`. Vendors must stay unique, and the primary prompt needs at least two repeats.

## What is checked in

```
config/experiment.json      judges, prices, repeats, cap
data/items.json             50 items and their variants
data/DATASOURCE.md          SQuAD citation and CC BY-SA 4.0
results/dry-run/            manifest, responses.jsonl, metrics, scores
docs/                       static pages
docs/data/                  the JSON and CSV the pages fetch
```

`responses.jsonl` is the cache. A line holds the model, prompt id, item, variant, repeat, the raw completion text, token counts, and cost. The manifest records model ids, the prompt text, the prompt hash, temperature, the fact that no seed was sent, the price pin, the FX pin, and the cost estimate. Analysis parses the raw text again, so a parser change is a re-analysis, not a new paid run.

The pages are `docs/index.html` (charts), `docs/examples.html` (three items), and `docs/method.html`. Chart.js is loaded from a CDN. The tables do not need it. GitHub Pages can serve the `docs/` directory on `main` with no build.

## Dry run

`run --dry-run` writes the same files a paid run would, with `"simulated": true` and a billed cost of zero. The fake judge is deterministic and intentionally uneven, so the charts are not empty. **Do not quote those percentages as findings.** They are a pipeline fixture. The banner on the site says the same thing, and it disappears when a real cache is analyzed.

## Dataset

SQuAD 1.1 dev, 50 questions, passages of 480–1400 characters. License **CC BY-SA 4.0**. Citation, source URL, and the regenerate command are in [`data/DATASOURCE.md`](data/DATASOURCE.md). The passages are Wikipedia text, which is also CC BY-SA. This subset is a derivative and stays under that license.

Regenerate after a rule change:

```bash
curl -fsSL -o /tmp/dev-v1.1.json https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v1.1.json
./gradlew -q run --args="prepare --squad /tmp/dev-v1.1.json --out data/items.json"
```

## Layout

Kotlin, Gradle Kotlin DSL, JUnit 5. No HTTP library beyond `java.net.http`. JSON is kotlinx.serialization.

| Piece | Where |
| --- | --- |
| CLI (`prepare`, `estimate`, `run`, `analyze`) | `Cli.kt` |
| Variant generator | `VariantRules.kt` |
| Rubrics | `Prompts.kt` |
| Cost, cap, in-run guard | `Cost.kt` |
| OpenRouter request, response, price reconcile | `OpenRouter.kt` |
| Fake judge and HTTP judge | `Judges.kt` |
| Trial order and cache | `Planner.kt`, `Runner.kt`, `Cache.kt` |
| Metrics | `Analyze.kt` |

Trial order is fixed: items in file order, then `correct` / `subtle_error` / `unsupported`, then judges in config order, then prompts in `promptOrder`, then repeats `0..n-1`.

## Limitations

- The questions are short English Wikipedia factoids. This does not say how a judge behaves on long answers, multi-hop questions, or a messy retriever.
- Numeric edits are obvious to a careful reader. They are easier than a real hallucination that keeps the right shape. Entity swaps are other names from the same paragraph, and some of those names are a different kind of thing (a place swapped for a person).
- The unsupported passage is a different article, not a near-miss chunk from the same page. A judge that fails here is failing an easy case. A judge that passes may still fail on near misses.
- Temperature 0 measures provider nondeterminism, not sampling noise. A temperature of 0.7 would move the scores more and would cost the same per call only if you do not add repeats. This design spends its repeats on the strict rubric at temperature 0.
- There is no human rater. The planted labels are the reference, not an agreement with a person.
- Token estimates use characters divided by four. The 1.5 margin and the max-token ceiling exist because that estimate is rough. Reasoning tokens, if a provider ignores `effort: none`, are the main way to spend more than the estimate. The stress row above is that case at +1000 tokens.
- OpenRouter prices and the euro move. Refresh before a paid run. The cap is enforced in EUR using the pinned FX rate, while the API bills USD.
- Parse failures are excluded from rates and counted separately. A model that does not return JSON is a failure of compliance, not a miss.
- The dry-run charts are simulated.

## Tests

`./gradlew test` covers the variant rules, the score parser, quadratic κ against a hand-computed case, the OpenRouter request shape, the budget refusal and the mid-run stop, cache reuse, the checked-in 50 items, the 10 EUR gate on the real plan, and the published dry-run metrics against the cache.
