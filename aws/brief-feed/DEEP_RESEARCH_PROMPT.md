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

## When to add a rule and when to delete one

Five runs in, the prompt had doubled to ~1,900 words while the failure count went 8 → 1 → 2 → 3.
Adding stopped helping around run 2, and twice a fix produced the next failure. The owner's
instinct — remove the offending rule rather than layer another on it — is right, with one
qualification that makes it safe:

> **Delete output constraints. Keep process constraints.**

Deleting a rule does not return you to neutral, it returns you to the original failure: drop "go to
fred.stlouisfed.org and take the bottom row" and the 2-year is wrong again within one run. But the
two rules that backfired both described what the answer should *look like*, and both could simply
go.

**Section 8 (LEVELS) was deleted outright on that basis.** Five runs produced: support above the
price; a level derived from an estimated low; a level derived by rescaling; all four mirrored from
one number to the cent; and finally an honest "Not found" for spot with GLD's levels being nothing
but Friday's high and low, where "resistance" sat 0.15% above the close. The cause is structural —
**Deep Research reads text, not charts** — so no phrasing was going to fix it. That is ~250 words
of prompt returned, the worst-performing section gone, and the app already computes technicals from
real price data. "Where might gold go" now lives in the scenarios, where it was always better
placed.

## The pattern across four runs

**Every fix worked, and two of them created the next problem.** That turns out to be the most
useful thing these runs taught, and it is worth internalising before editing this prompt again:

> **A constraint on the OUTPUT invites fabrication. A constraint on the PROCESS produces truth.**

"Pin these six numbers from these exact URLs, take FRED's bottom row" is a process constraint — it
took the data from wrong to exact. "Prefer Reuters, Bloomberg, FT" is an output constraint, and the
model satisfied it by inventing URLs at those domains. "The GLD and spot levels must imply the same
percentage move" is an output constraint, and the model satisfied it by deriving all four levels
from one number and rescaling, to the cent, while calling one of them a "prior breakdown level".

When adding a rule, ask which kind it is. If it describes what the answer should look like rather
than where to go and what to read, it will be met by construction rather than by research.

## Four test runs — what each one taught

| | run 1 | run 2 | run 3 | run 4 |
|---|---|---|---|---|
| Checker failures | 8 | 1 | 2 | 3 |
| Score direction | scored 75 on a bearish call | fixed | fixed | fixed |
| Levels vs price | support above close | fixed | fixed | fixed, but **derived** |
| Pinned data | n/a | 2y 27bp wrong | right value, **wrong row** | **all six exact** |
| News URLs | five homepages | real but worthless | **fabricated** | section removed |
| Falsifiers | generic | generic | generic | **names its own unverified assumption** |

Run 4 got the data completely right — all six pinned figures exact, with correct dates — and used
"Not found" honestly four times rather than inventing. Its third falsifier is exactly what that
section is for: *"This bearish call assumes ETF outflows drove Friday's price action; if SPDR
reports net inflows for September 25 when data drops Monday, my premise is flawed."* That is the
report criticising its own thesis with a clock on it.

It also quoted GC=F at +0.83% on a day GLD moved +0.44% — same metal, so one of them was misread.

**Anchoring worked.** Naming exact source URLs took it from 8 failures to 1, and the analysis got
better each time — run 3's "Western rate-driven capitulation sets the daily price, Eastern
accumulation sets the floor" is a genuinely useful frame, and section 5 naming the crowded short as
the risk to its own bearish call is exactly right.

**But section 9 failed three times, three different ways, and the third was caused by the fix for
the second.** Tightening the source rule to "prefer Reuters, Bloomberg, FT, WSJ, CNBC" made the
model satisfy it by *generating plausible URLs at those domains*: one contained the literal
placeholder `abc123xx`, one 404'd, and one cited a WGC Q3 report dated six days before Q3 ended.
That is strictly worse than run 2's junk-but-real links, because nothing about it looks wrong.

The lesson generalises: **a model asked for a URL it does not have will produce one.** The only
structural fix is to never ask — which is exactly what the production RSS path does, where the
model picks headlines by index from a real list and never writes a link. Section 9 now bans URLs
outright and the JSON's `news` array is always empty.

Run 3's other defect is subtler and worth watching: it read **real** FRED values but took the wrong
row — 2.76 dated "September 25" was the September 23 print, when the latest was 2.85. The number
was verifiable, the date was not, and the argument was then built on a stale figure. The prompt now
says to take FRED's bottom row and copy the date from that same row.

## Verdict: read it, don't wire it in

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
