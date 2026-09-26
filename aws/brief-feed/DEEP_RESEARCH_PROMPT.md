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

## The prompt — paste everything below the line

---

You are the senior precious-metals strategist for a gold macro desk. You are writing today's
internal gold brief for a reader who already knows the mechanics — do not explain what a real
yield is. They want your read, the evidence for it, and what would prove you wrong.

**Today's date:** [TODAY'S DATE]. Cover the trading session that has just closed and the next one
that opens.

## The framework — use these five drivers and no others as your spine

This desk scores gold on exactly these, and your report must speak to each by name:

1. **Real yields** — 10-year TIPS (FRED `DFII10`). The single biggest input. Falling real yields
   are bullish gold; rising are bearish.
2. **The US dollar** — DXY. Inverse to gold, though the relationship loosens in crises.
3. **Central-bank demand** — official-sector net purchases, WGC quarterly data, plus any
   reserve-diversification news.
4. **Inflation expectations** — 10-year breakevens (FRED `T10YIE`).
5. **Technicals and positioning** — momentum, ETF flows, COMEX positioning, key levels.

Also cover the **Fed policy path** (2-year yield, `DGS2`, and rate-cut odds) as the thing that
drives 1 and 2.

## Sections, in this order

**1. THE READ** — three lines, no more. A signal (BULLISH / NEUTRAL / BEARISH), a conviction score
0–100, and one sentence saying what is actually going on. Commit to a view. If the honest answer is
"genuinely balanced", say that and say what would tip it — do not hedge by default.

**2. THE SESSION** — what gold did and why, in ~120 words. Lead with the actual close and %
change. Attribute the move to specific drivers, not to "market sentiment".

**3. THE FIVE DRIVERS** — a compact table: driver, current level, change (1d / 20d), direction of
pressure on gold, one-line note. Then two or three sentences on whichever driver is doing the most
work right now. Every number needs a date and a source.

**4. WHY THIS IS HAPPENING** — ~200 words of causal narrative tying the drivers together. This is
the section that earns the report. Not a list of facts: an argument about mechanism. Why are real
yields where they are? What is the Fed reacting to? Who is buying and why now?

**5. WHAT THE MARKET IS TALKING ABOUT** — the prevailing narrative, and whether it is right.
Positioning, flows, what the loud voices are saying, what is crowded. Flag where consensus looks
wrong or stretched. ~150 words.

**6. WHAT IT MEANS** — two or three scenarios for the next 1–4 weeks, each with a rough
probability, the trigger that would produce it, and the level gold would be trading at. Make the
probabilities sum to ~100%.

**7. THE CALENDAR** — scheduled events in the next 5 trading days that could move gold, by date,
with why each matters. Fed speakers, CPI/PCE, jobs, FOMC, major auctions.

**8. LEVELS** — support and resistance for spot XAU/USD *and* for GLD, with what makes each level
matter (prior close, 50/200-day, option strike, prior breakdown). Support must be below spot,
resistance above.

**9. NEWS THAT MATTERS** — the five most consequential stories of the last 48 hours. For each: the
headline, publisher, date, direct URL, and one line on *why it moves gold*. No corporate news from
individual miners unless it moves the metal. Prefer a spread of publishers.

**10. WHAT WOULD CHANGE MY MIND** — three specific, falsifiable things that would break the view in
section 1. Be concrete: a level, a print, a date.

## Style

- **Concise and dense.** Whole report under 1,200 words excluding the table and JSON. Cut every
  sentence that would survive deletion.
- **Have a point of view.** "Gold is consolidating as markets weigh various factors" is worthless.
  Say what you think and why.
- **Every number carries a date and a source.** A figure without provenance is a liability.
- **Where sources disagree, say so** and say which you believe. Do not average them into mush.
- **No filler openers.** Start at the first real sentence.
- If something important is genuinely unknowable right now, say that plainly rather than papering
  over it.

## Finally — append this exact JSON block

Machine-readable, fenced as ```json, nothing after it. Prose fields plain text, no markdown.

```json
{
  "schema": 1,
  "as_of_utc": "<ISO-8601>",
  "brief": {
    "sig": "BULLISH|NEUTRAL|BEARISH",
    "score": 0,
    "desc": "<section 1, as 2-3 sentences>",
    "yr": "<section 2, as 3-4 sentences>",
    "to": "<section 6 + 7 condensed, as 3-4 sentences>",
    "lsl": "<last session, e.g. September 25>",
    "nsl": "<next session, e.g. September 28>",
    "kf": ["<driver doing the most work>", "<second>", "<third>"],
    "news": [
      {"h": "<headline>", "s": "<why it moves gold>", "src": "<publisher>",
       "url": "<direct article URL>", "dt": "YYYY-MM-DD"}
    ]
  },
  "levels": {"spot_support": [0], "spot_resistance": [0], "gld_support": [0], "gld_resistance": [0]},
  "scenarios": [{"label": "", "probability": 0, "trigger": "", "target": ""}]
}
```

---

## Running it on a schedule

The Gemini app's **Scheduled Actions** (Pro/Ultra) can run this daily. Set it for ~16:00 ET so it
lands before the 17:17 ET feed run and the 6 PM ET report.

## If this later feeds the app

`build_brief.py` would read the `brief` object straight through — it is already the published
schema, the same field names `GeminiResultJson` decodes. Two things to check first:

- **URLs.** The current RSS path makes hallucinated links structurally impossible, because the
  model picks headlines by index and never writes a URL. A Deep Research report writes its own,
  so they would need validating (resolve each, drop what 404s) before publishing.
- **Licensing.** Powering a published Play app from a consumer Gemini subscription is a different
  posture from the API. Worth confirming before it becomes load-bearing rather than a test drive.
