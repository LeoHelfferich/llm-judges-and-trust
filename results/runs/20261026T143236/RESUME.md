# How this run was completed

This run was done in three passes, all into this directory, against the same prompts (prompt hash `3b781b568255`) and pinned prices.

1. **14:32–16:14 UTC, 26 Oct 2026.** 3,433 of 3,750 trials. The process hung on one HTTP call. The request timeout in `OpenRouterJudge` only covers the response headers, so a body that never finishes blocks forever. 11 of these rows were transient OpenRouter errors: 10× HTTP 429 (Gemini 2.5 Flash rate-limited upstream) and 1× "model at capacity" (Grok 4.3).
2. **About 17:00 UTC.** The 11 error rows were removed from `responses.jsonl`, so the runner would retry them. Error rows are never billed. Then `run --refresh --out results/runs/20261026T143236` resumed from the cache. This pass added 26 trials and hung again on the same kind of call.
3. **After the fix.** `Judges.kt` now has a hard 120 s deadline per attempt (`sendAsync().get(timeout)`). The run resumed again and finished 3,750 / 3,750 trials with 0 error rows.

The cache key covers model, prompt id, prompt hash, item, variant, repeat, temperature, provider and max_tokens, so no completed trial was requested twice.

`manifest.json` is written by the last pass. Two fields were corrected by hand afterwards:
- `startedAt` was restored to the first pass.
- `spentUsd` is the sum of `billedCostUsd` over all 3,750 rows: 1.47481989 USD, about 1.32 EUR at 0.89342 EUR per USD.

Up to two calls that hung may have been billed by OpenRouter without being recorded. At these prices that is well under 0.01 EUR.
