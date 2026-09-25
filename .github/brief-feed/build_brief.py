#!/usr/bin/env python3
"""Builds brief_daily.json, the hosted AI gold brief the app reads on the AI Brief and News tabs.

Run by .github/workflows/brief-feed.yml with the maintainer's Gemini key (repository secret
GEMINI_API_KEY). Asks gemini-2.5-flash, grounded with Google Search, for the same gold brief
GeminiClient.kt asks for with a user's own key, and publishes the *parsed* result.

Publishing the parsed result rather than the raw model response is deliberate: the app deserializes
a fixed schema it already knows (GeminiCache's field names), so this script and GeminiClient.kt can
word their prompts differently without the app ever mis-parsing a feed. Prompt drift costs quality
here, never correctness.

Why hosted at all: the grounded call takes 15-60s, which is the single slowest thing in a refresh,
and it used to sit in the critical path of every tab. Users without a key now get the brief as a
~200ms static download; users with a key get this instantly and their own fresh call in the
background. See CLAUDE.md, section "Hosted AI brief feed".

Refuses to publish a brief that looks wrong (missing sections, no news, impossible score) and exits
non-zero instead, which fails the run and makes GitHub email the repo owner: one bad generation must
not sit on every install's AI Brief tab for hours. The key travels in a header, never in a URL.

  GEMINI_API_KEY=... python3 build_brief.py --out feed/brief_daily.json [--previous published.json]

Writes changed=true|false and generated=<ISO8601> to $GITHUB_OUTPUT when that is set.
GEMINI_API_BASE and YAHOO_API_BASE override the endpoints (for testing against a local mock only).
"""
import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from zoneinfo import ZoneInfo

# An alias, not a pinned version, and deliberately so. gemini-2.5-flash — which this used, and
# which the app shipped with — was retired to "no longer available to new users" and started
# answering 404 to any key whose project had not used it before. A pinned id turns a Google
# retirement into a dead feed and, worse, a silently empty AI Brief tab in a shipped app that
# cannot be patched without a Play review. The cost of the alias is that Google can change the
# model under us; validate() is what catches that, and it fails loudly rather than publishing.
MODEL = "gemini-flash-latest"
GEMINI_BASE = os.environ.get("GEMINI_API_BASE", "https://generativelanguage.googleapis.com")
YAHOO_BASE = os.environ.get("YAHOO_API_BASE", "https://query1.finance.yahoo.com")
SYMBOL = "GLD"
ET = ZoneInfo("America/New_York")

# A run that finds a published brief younger than this does nothing and exits 0. GitHub fires
# scheduled runs late and in bursts, so the hourly cron is a best-effort upper bound, not a
# promise; this is what actually caps how many grounded calls the key makes in a day.
MIN_AGE_MINUTES = 50

SCHEMA = 1


class BriefError(Exception):
    """A reason not to publish. The CLI turns it into a non-zero exit (which fails the GitHub run
    and emails the owner); the Lambda turns it into a failed invocation (which alarms). Either way
    nothing is published and the last good brief stays up."""


# ── Trading sessions ──────────────────────────────────────────────────────────
# Mirrors GeminiClient.getTradingSessionDates: the brief always covers the last closed session
# and the next open one, so it reads the same whether it came from here or from a user's key.

def session_dates(now_et):
    weekday = now_et.weekday() < 5                    # Mon..Fri
    closed_for_today = (not weekday) or now_et.hour >= 16   # equities close 4 PM ET

    last = now_et.date()
    if closed_for_today:
        while last.weekday() >= 5:                    # weekend: back up to Friday
            last -= dt.timedelta(days=1)
    else:
        last -= dt.timedelta(days=1)
        while last.weekday() >= 5:
            last -= dt.timedelta(days=1)

    nxt = last + dt.timedelta(days=1)
    while nxt.weekday() >= 5:
        nxt += dt.timedelta(days=1)

    short = "%B %-d"
    long_ = "%A, %B %-d, %Y"
    return ((last.strftime(short), last.strftime(long_)),
            (nxt.strftime(short), nxt.strftime(long_)))


# ── Yahoo quote ───────────────────────────────────────────────────────────────

def fetch_quote():
    """GLD's current quote, or None. Anchors the prompt the way the app anchors its own."""
    url = (f"{YAHOO_BASE}/v8/finance/chart/{SYMBOL}?interval=5m&range=1d")
    for attempt in range(3):
        for u in (url, url.replace("query1.", "query2.")):
            try:
                req = urllib.request.Request(u, headers={"User-Agent": "aurum-brief-feed/1"})
                with urllib.request.urlopen(req, timeout=30) as resp:
                    meta = json.load(resp)["chart"]["result"][0]["meta"]
                    return parse_quote(meta)
            except (urllib.error.URLError, TimeoutError, OSError,
                    KeyError, IndexError, TypeError, ValueError):
                continue
        time.sleep(5 * (attempt + 1))
    return None


def parse_quote(meta):
    """The subset of YahooFinanceClient.parseQuote the prompt's facts block uses."""
    price = meta.get("regularMarketPrice")
    prev = meta.get("chartPreviousClose")
    if not isinstance(price, (int, float)) or not isinstance(prev, (int, float)) or prev == 0:
        return None
    state = meta.get("marketState", "REGULAR")
    live, baseline = price, prev
    if state == "PRE" and isinstance(meta.get("preMarketPrice"), (int, float)) and meta["preMarketPrice"] > 0:
        live = meta["preMarketPrice"]
    elif state in ("POST", "POSTPOST") and isinstance(meta.get("postMarketPrice"), (int, float)) and meta["postMarketPrice"] > 0:
        live, baseline = meta["postMarketPrice"], (price if price > 0 else prev)
    change = live - baseline
    return {
        "marketState": state,
        "price": live,
        "change": change,
        "changePct": change / baseline * 100.0,
        "previousClose": prev,
        "high": meta.get("regularMarketDayHigh", price),
        "low": meta.get("regularMarketDayLow", price),
    }


def facts_block(q):
    """The app's verified numbers, rendered as the block the model is told to defer to."""
    if not q:
        return ""
    return (
        "VERIFIED MARKET DATA (from this app's own market feed — authoritative):\n"
        f"  {SYMBOL} — market state {q['marketState']}\n"
        f"    price ${q['price']:,.2f}  (change {q['change']:+.2f} = {q['changePct']:+.2f}% "
        f"vs previous close ${q['previousClose']:,.2f})\n"
        f"    regular session: high ${q['high']:,.2f} · low ${q['low']:,.2f}\n"
    )


# ── Prompt ────────────────────────────────────────────────────────────────────
# Kept in step with GeminiClient.goldPrompt by hand. The two are allowed to drift in wording — the
# app parses this script's OUTPUT, not the model's, so drift costs quality, never correctness. The
# JSON keys below are the contract: change them here and in GeminiClient.parseResponse together.

def build_prompt(last_long, next_long, facts):
    return f"""You are a senior precious-metals analyst. Research the current state of GOLD
(spot gold / XAU-USD; the GLD ETF tracks it) using real-time sources.
Last closed trading session: {last_long}.
Next trading session: {next_long}.
{facts}
Provide a daily gold market brief covering exactly these two sessions. Return ONLY a valid JSON object — no markdown, no code fences:
{{
  "signal": "BULLISH" or "NEUTRAL" or "BEARISH",
  "score": integer 0-100 (100=extremely bullish for gold, 50=neutral, 0=extremely bearish),
  "description": "2-3 sentence summary of gold's current macro setup",
  "key_factors": ["brief factor 1", "brief factor 2", "brief factor 3"],
  "yesterday_recap": "3-4 sentences on how gold traded on {last_long} — the actual closing price/level and % change, plus the main drivers (real yields, the US dollar/DXY, Fed commentary, inflation data, geopolitics, ETF or central-bank flows). One sentence on broader risk sentiment if relevant.",
  "today_outlook": "3-4 sentences on what could move gold on {next_long} — scheduled macro releases (CPI, jobs, FOMC), Fed speakers, the likely direction of the dollar and real yields, and key technical support/resistance levels to watch.",
  "news": [
    {{"headline": "...", "summary": "1-2 sentence summary", "source": "publisher name", "url": "https://actual-article-url.com/...", "date": "YYYY-MM-DD"}}
  ]
}}
Rules: Include the 5 most impactful GOLD / macro news items from the past 7 days (real article URLs). Be specific — cite actual gold prices/levels, percentages, yields, and events. Focus on gold and its macro drivers, not unrelated single stocks.

Consistency requirements — the VERIFIED MARKET DATA above overrides any figure you find
in a source. If a source disagrees with it, the source is stale; follow the verified data.
- Your stated direction and percentage move MUST match the verified change above.
- Any support level you cite must be BELOW the current price, and any resistance ABOVE it.
  If price has already broken through a level a source calls support, describe it as
  broken support or a reclaim level — never as an intact floor.
- Any intraday low you mention must be <= the close, and any intraday high >= the close.
- When you cite spot XAU/USD rather than the ETF, its percentage move must still agree
  with the verified change above (the ETF tracks spot; the price scales differ, the
  percentages do not).
"""


# ── Gemini ────────────────────────────────────────────────────────────────────

def generate(prompt, key):
    """One grounded generateContent call. The key goes in a header so no URL ever carries it."""
    url = f"{GEMINI_BASE}/v1beta/models/{MODEL}:generateContent"
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "tools": [{"google_search": {}}],
        "generationConfig": {"responseMimeType": "text/plain"},
    }).encode()
    last_error = "unknown"
    for attempt in range(3):
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json",
            "x-goog-api-key": key,
        })
        try:
            with urllib.request.urlopen(req, timeout=180) as resp:
                return json.load(resp)
        except urllib.error.HTTPError as e:
            # Safe to include the body here, unlike in the FRED builder: this request carries the
            # key in a header, not the URL, so nothing secret is in the response. Worth the space —
            # a bare "HTTP 404" cost two round trips to diagnose on 2026-09-24 when the real
            # message was "this model is no longer available to new users".
            detail = e.read(300).decode("utf-8", "replace").replace("\n", " ")
            last_error = f"HTTP {e.code}: {detail}"
            if 400 <= e.code < 500:
                # Every 4xx, 429 included. 429 used to be retried on the theory that a rate limit
                # clears in a minute; in practice it is a *quota* error that does not, and the
                # retries turned one scheduled brief into three grounded calls and a 120-second
                # invocation. The hourly schedule is the retry — there is nothing to gain from a
                # second attempt 20 seconds later, and a quota error is made worse by one.
                break
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
            last_error = type(e).__name__
        time.sleep(20 * (attempt + 1))
    raise BriefError(f"Gemini request failed ({last_error})")


def extract_json(text):
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except ValueError:
        return None


# ── Parse + validate ──────────────────────────────────────────────────────────
# The output uses GeminiCache's short field names (sig/score/desc/yr/to/lsl/nsl/kf/news) so the app
# deserializes a feed brief with exactly the code that reads its own on-disk cache.

def to_brief(raw, last_short, next_short, today):
    signal = str(raw.get("signal", "NEUTRAL")).upper()
    signal = "BULLISH" if "BULL" in signal else "BEARISH" if "BEAR" in signal else "NEUTRAL"

    seven_days_ago = today - dt.timedelta(days=7)
    news = []
    for n in raw.get("news") or []:
        if not isinstance(n, dict):
            continue
        date_str = str(n.get("date", ""))
        try:                                  # drop items older than 7 days, as the app does
            if dt.date.fromisoformat(date_str) < seven_days_ago:
                continue
        except ValueError:
            pass                              # unparseable date: keep it, the app does too
        news.append({
            "h": str(n.get("headline", "")), "s": str(n.get("summary", "")),
            "src": str(n.get("source", "")), "url": str(n.get("url", "")),
            "dt": date_str,
        })
        if len(news) >= 5:
            break

    try:
        score = max(0, min(100, int(raw.get("score", 50))))
    except (TypeError, ValueError):
        score = 50

    return {
        "sig": signal, "score": score,
        "desc": str(raw.get("description", "")),
        "yr": str(raw.get("yesterday_recap", "")),
        "to": str(raw.get("today_outlook", "")),
        "lsl": last_short, "nsl": next_short,
        "kf": [str(f) for f in (raw.get("key_factors") or []) if str(f).strip()],
        "news": news,
    }


def validate(brief):
    """Exits non-zero rather than publish a brief that would look broken on the tab."""
    problems = []
    for field, label, min_len in (("desc", "description", 40),
                                  ("yr", "yesterday_recap", 80),
                                  ("to", "today_outlook", 80)):
        if len(brief[field].strip()) < min_len:
            problems.append(f"{label} is missing or too short ({len(brief[field].strip())} chars)")
    if len(brief["kf"]) < 2:
        problems.append(f"{len(brief['kf'])} key factors (need at least 2)")
    if len(brief["news"]) < 2:
        problems.append(f"{len(brief['news'])} usable news items (need at least 2)")
    if any(not n["url"].startswith("http") or not n["h"].strip() for n in brief["news"]):
        problems.append("a news item has no headline or no real URL")
    if problems:
        raise BriefError("refusing to publish: " + "; ".join(problems))


# ── Main ──────────────────────────────────────────────────────────────────────

def published_age_minutes(path, now_utc):
    """Minutes since the currently published brief was generated, or None if there isn't one."""
    if not path or not os.path.exists(path) or os.path.getsize(path) == 0:
        return None
    try:
        with open(path) as f:
            stamp = json.load(f)["generated_utc"]
        generated = dt.datetime.fromisoformat(stamp.replace("Z", "+00:00"))
    except (OSError, ValueError, KeyError, TypeError):
        return None
    return (now_utc - generated).total_seconds() / 60.0


def build_feed(key, now_utc=None):
    """Generates one brief and returns the feed dict, or raises [BriefError].

    The whole job minus where the answer goes: the GitHub workflow writes it to a file and pushes,
    the Lambda commits it through the GitHub API. Keeping this one function is what stops the two
    front ends drifting into two different briefs.
    """
    now_utc = now_utc or dt.datetime.now(dt.timezone.utc)
    now_et = now_utc.astimezone(ET)
    (last_short, last_long), (next_short, next_long) = session_dates(now_et)

    quote = fetch_quote()
    if quote:
        print(f"anchored to {SYMBOL} ${quote['price']:,.2f} ({quote['changePct']:+.2f}%, {quote['marketState']})")
    else:
        print("no Yahoo quote — the brief will be generated unanchored")

    raw_response = generate(build_prompt(last_long, next_long, facts_block(quote)), key)
    try:
        text = raw_response["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError, TypeError):
        raise BriefError("Gemini returned no candidate text")
    parsed = extract_json(text)
    if parsed is None:
        raise BriefError("Gemini returned no parseable JSON object")

    brief = to_brief(parsed, last_short, next_short, now_et.date())
    validate(brief)

    print(f"{brief['sig']} {brief['score']}/100 · {len(brief['news'])} news items · sessions "
          f"{brief['lsl']} → {brief['nsl']}")
    return {
        "schema": SCHEMA,
        "generated_utc": now_utc.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "model": MODEL,
        "symbol": SYMBOL,
        "quote_at_generation": quote,   # what the brief's numbers were written against
        "brief": brief,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--previous", help="the currently published brief, to rate-limit publishes")
    ap.add_argument("--force", action="store_true", help="ignore the freshness guard")
    args = ap.parse_args()

    key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not key:
        sys.exit("GEMINI_API_KEY is not set (add it under Settings > Secrets and variables > Actions)")

    now_utc = dt.datetime.now(dt.timezone.utc)
    age = published_age_minutes(args.previous, now_utc)
    if age is not None and age < MIN_AGE_MINUTES and not args.force:
        # A burst of late runs would otherwise spend several grounded calls republishing the same
        # hour. Exit 0 so the run is a success, not a failure.
        print(f"published brief is {age:.0f} min old (< {MIN_AGE_MINUTES}) — nothing to do")
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as f:
                f.write("changed=false\n")
        return

    try:
        feed = build_feed(key, now_utc)
    except BriefError as e:
        sys.exit(str(e))

    generated = feed["generated_utc"]
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(feed, f, separators=(",", ":"), ensure_ascii=False)

    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(f"changed=true\ngenerated={generated}\n")


if __name__ == "__main__":
    main()
