# Daily gold Deep Research prompt

For Google Gemini's **Deep Research** (the consumer Gemini app, on a Pro/Ultra plan), run once a
day **before the evening report**. The app's report worker fires at 6 PM ET and the brief feed's
last run of the day is 17:17 ET, so schedule this for **~16:00 ET** and it will be the freshest
thing in the pipeline.

Why it is written the way it is: the app already scores gold on five components and a separate
forward signal. A generic "research gold today" prompt produces a report that *contradicts* the
app — different framing, different emphasis, numbers that do not reconcile. This one is pinned to
the same five drivers, so the report reads as the long-form version of what the Gold Index tab
already shows, not a competing opinion.

It ends with a JSON block in the feed's exact schema. Today that is for eyeballing; later, if the
Deep Research API is worth paying for, `build_brief.py` can consume it with no app change — the
app has never parsed a model response directly, only the feed's published shape.

---

## The prompt

**`deep_research_prompt.txt`**, in this directory. It is the whole prompt and nothing else —
select all, paste. No placeholders to fill in: it derives today's date and the two session dates
itself, which matters because a scheduled action runs the text verbatim every day and would
otherwise paste `[TODAY'S DATE]` literally.

The JSON block at the end is **part of the prompt** — it is the output instruction, telling the
model what to append to the report. Paste it along with everything else.

## Running it on a schedule

The Gemini app's **Scheduled Actions** (Pro/Ultra) can run this daily. Set it for ~16:00 ET so it
lands before the 17:17 ET feed run and the 6 PM ET report.

## Test run, 2026-09-26 — verdict: read it, don't wire it in

First run through Gemini Pro Deep Research, checked against FRED and the app's own quote.

**What was good.** The prose. "Why this is happening" gave a real causal chain — US exceptionalism
→ hawkish repricing → nominals up while breakevens stay anchored → real yields up → gold's
zero-yield penalty bites. The RSS brief cannot produce that from headlines. Sections 5, 6 and 10
(consensus check, scenarios with probabilities, falsifiers) are likewise beyond the cheap path.
1,296 words, all ten sections, valid JSON.

**What was wrong.** Two of five driver numbers: the 2-year at 5.14% against FRED's 4.87, and
central-bank demand at "~130t YTD" against the app's ~794 t/yr. GLD support at \$395 when GLD had
closed at \$393.41 — support above spot. All five news URLs were domain roots, not articles. And
`score: 75` on a BEARISH call, which the app renders as 75/100 bullish.

The last one was a prompt bug, the rest came from having no anchor. All four are now addressed in
`deep_research_prompt.txt`; a re-run should be checked against the same ground truth before
trusting it.

**The standing recommendation is unchanged: this is a daily read for the owner, not a feed input.**
Wiring it in safely needs URL resolution with drop-on-404, numeric cross-checks against FRED and
Yahoo with the feed's values overriding, and a re-verified score convention. That is real
infrastructure for a marginal gain over a brief that is already accurate because it is anchored.
Its value is the analysis, and the analysis is for a human.

## If this later feeds the app

`build_brief.py` would read the `brief` object straight through — it is already the published
schema, the same field names `GeminiResultJson` decodes. Two things to check first:

- **URLs.** The current RSS path makes hallucinated links structurally impossible, because the
  model picks headlines by index and never writes a URL. A Deep Research report writes its own,
  so they would need validating (resolve each, drop what 404s) before publishing.
- **Licensing.** Powering a published Play app from a consumer Gemini subscription is a different
  posture from the API. Worth confirming before it becomes load-bearing rather than a test drive.
