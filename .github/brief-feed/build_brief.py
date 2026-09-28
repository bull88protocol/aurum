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

# Tried in order, because the two failure modes pull in opposite directions:
#
#   * a PINNED id eventually gets retired and answers 404 forever. gemini-2.5-flash did exactly
#     that on 2026-09-24, taking the shipped app's AI Brief tab down for every new user.
#   * the -latest ALIAS never 404s, but it tracks the newest model, which is the most capacity
#     constrained. On 2026-09-25 gemini-flash-latest and gemini-3.8-flash both returned 503
#     UNAVAILABLE repeatedly while gemini-3.6-flash answered fine.
#
# So: a known-good pinned model first, the alias behind it as the retirement safety net. When 3.6
# is retired the 404 falls through to the alias and the feed keeps working; when the newest model
# is overloaded the pinned one carries it. Keep in step with MODELS in GeminiClient.kt.
MODELS = ("gemini-3.6-flash", "gemini-flash-latest")
GEMINI_BASE = os.environ.get("GEMINI_API_BASE", "https://generativelanguage.googleapis.com")
YAHOO_BASE = os.environ.get("YAHOO_API_BASE", "https://query1.finance.yahoo.com")
SYMBOL = "GLD"
ET = ZoneInfo("America/New_York")

# Headlines come from Google News RSS, not from the model. Search grounding would have supplied
# them, but it is not available on this key's tier at all — three days of 429 RESOURCE_EXHAUSTED
# on every model while plain generation answered fine (2026-09-24..26). RSS is free, unmetered and
# needs no key, and it removes the single dependency that was blocking the whole feed.
#
# The model never writes a URL. It picks headlines by index out of the list below and writes only
# the summaries; [to_brief] maps the indices back to the real RSS metadata. A model cannot
# hallucinate a link it is never asked to produce.
NEWS_QUERIES = (
    "gold price OR XAUUSD OR \"gold market\" when:2d",
    "gold Federal Reserve OR \"real yields\" OR \"dollar index\" when:3d",
    "central bank gold buying OR \"gold ETF\" flows when:7d",
)
NEWS_RSS = "https://news.google.com/rss/search?q={q}&hl=en-US&gl=US&ceid=US:en"
NEWS_MAX = 24          # how many headlines the prompt offers the model to choose from
NEWS_PICK = 5          # how many it must return

# A run that finds a published brief younger than this returns before the Gemini call. This is a
# backstop, not the schedule: EventBridge fires three times a day (see aws/brief-feed/deploy.sh)
# and this exists so a retry, a manual invoke or an overlapping tick cannot spend a second
# grounded call.
#
# The binding limit is Google *Search grounding* quota, which is far smaller than the plain
# generation allowance — on 2026-09-25 this key generated fine while every grounded call returned
# 429 RESOURCE_EXHAUSTED. Three a day is the owner's call (2026-09-25): the free grounding tier is
# small and gold's macro story does not turn over in an hour. 400 minutes sits comfortably under
# the 480-minute gap between scheduled runs, so it never blocks a real one.
MIN_AGE_MINUTES = 400

# Optional: a Google Doc holding the day's Deep Research report (aws/brief-feed/deep_research_prompt.txt).
# Set DEEP_RESEARCH_DOC_ID and the doc's JSON block supplies the ANALYSIS — signal, score and the
# three prose fields — while the RSS pass keeps supplying the headlines. It is a merge rather than
# a replacement because the app's News tab and the PDF's news section both read brief.news, and a
# Deep Research report always has news: [] by design.
#
# The doc is read through Docs' plain-text export, which needs no OAuth and no client library as
# long as the doc is link-shared, so the Lambda stays stdlib-only. Unset, everything below is
# skipped and the feed behaves exactly as before.
DEEP_RESEARCH_DOC = "https://docs.google.com/document/d/{doc_id}/export?format=txt"
DEEP_RESEARCH_MAX_AGE_H = 24

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


# ── News ─────────────────────────────────────────────────────────────────────

def fetch_news(now_utc):
    """Recent gold/macro headlines from Google News RSS: [{headline, source, url, date}].

    Best effort — an empty list is survivable. validate() is what decides whether a brief with
    too few items is publishable.
    """
    import xml.etree.ElementTree as ET
    seen, out = set(), []
    for query in NEWS_QUERIES:
        url = NEWS_RSS.format(q=urllib.parse.quote(query))
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "aurum-brief-feed/1"})
            with urllib.request.urlopen(req, timeout=30) as resp:
                root = ET.fromstring(resp.read())
        except (urllib.error.URLError, TimeoutError, OSError, ET.ParseError) as e:
            print(f"news: {query[:30]}… failed ({type(e).__name__})")
            continue
        for item in root.findall(".//item"):
            title = (item.findtext("title") or "").strip()
            link = (item.findtext("link") or "").strip()
            if not title or not link.startswith("http"):
                continue
            key = title.lower()[:70]
            if key in seen:
                continue
            seen.add(key)
            src_el = item.find("{*}source")
            source = (src_el.text if src_el is not None else "") or ""
            if not source and " - " in title:          # Google appends " - Publisher"
                source = title.rsplit(" - ", 1)[1]
            out.append({
                "headline": title.rsplit(" - ", 1)[0] if source and title.endswith(source) else title,
                "source": source.strip(),
                "url": link,
                "date": _rss_date(item.findtext("pubDate"), now_utc),
            })
    out.sort(key=lambda n: n["date"], reverse=True)
    print(f"news: {len(out)} headlines from {len(NEWS_QUERIES)} queries")
    return out[:NEWS_MAX]


def _rss_date(raw, now_utc):
    """RFC-822 pubDate -> YYYY-MM-DD, falling back to today."""
    from email.utils import parsedate_to_datetime
    try:
        return parsedate_to_datetime(raw).astimezone(dt.timezone.utc).date().isoformat()
    except (TypeError, ValueError):
        return now_utc.date().isoformat()


def headlines_block(articles):
    """The numbered list the model chooses from. Indices are the contract."""
    if not articles:
        return "(No headlines could be fetched. Base the brief on the market data alone and return an empty news list.)"
    lines = [f"  [{i}] ({a['date']}, {a['source'] or 'unknown'}) {a['headline']}"
             for i, a in enumerate(articles)]
    return "RECENT HEADLINES — choose from these by index:\n" + "\n".join(lines)


# ── Prompt ────────────────────────────────────────────────────────────────────
# Kept in step with GeminiClient.goldPrompt by hand. The two are allowed to drift in wording — the
# app parses this script's OUTPUT, not the model's, so drift costs quality, never correctness. The
# JSON keys below are the contract: change them here and in GeminiClient.parseResponse together.

def build_prompt(last_long, next_long, facts, headlines):
    return f"""You are a senior precious-metals analyst writing a daily gold brief.
Last closed trading session: {last_long}.
Next trading session: {next_long}.
{facts}
{headlines}

Write the brief from the VERIFIED MARKET DATA and the headlines above. You have no web access —
do not invent prices, levels or events that are not supported by what is given.

Return ONLY a valid JSON object — no markdown, no code fences:
{{
  "signal": "BULLISH" or "NEUTRAL" or "BEARISH",
  "score": integer 0-100 (100=extremely bullish for gold, 50=neutral, 0=extremely bearish),
  "description": "2-3 sentence summary of gold's current macro setup",
  "key_factors": ["brief factor 1", "brief factor 2", "brief factor 3"],
  "yesterday_recap": "3-4 sentences on how gold traded on {last_long} — the closing level and % change from the verified data, plus the main drivers (real yields, the US dollar, Fed commentary, inflation data, geopolitics, ETF or central-bank flows) as far as the headlines support them.",
  "today_outlook": "3-4 sentences on what could move gold on {next_long} — scheduled macro releases, Fed speakers, the likely direction of the dollar and real yields, and key technical support/resistance levels implied by the verified high/low/close.",
  "news": [
    {{"i": <index of a headline above>, "summary": "1-2 sentence summary of why it matters for gold"}}
  ]
}}

Rules for "news": choose the {NEWS_PICK} most relevant to GOLD and its macro drivers, most
important first, by their [index]. Use each index at most once, and pick at most two from any one
publisher — a spread of sources is more useful than five takes from the same desk. Do NOT write
headlines, sources, dates or URLs — only the index and your summary. Skip anything about a single
mining company's corporate news unless it moves the gold market.

Consistency requirements — the VERIFIED MARKET DATA overrides any figure in a headline. If a
headline disagrees with it, the headline is stale; follow the verified data.
- Your stated direction and percentage move MUST match the verified change above.
- Any support level you cite must be BELOW the current price, and any resistance ABOVE it.
- Any intraday low you mention must be <= the close, and any intraday high >= the close.
- When you cite spot XAU/USD rather than the ETF, its percentage move must still agree with the
  verified change (the ETF tracks spot; the price scales differ, the percentages do not).
"""


# ── Gemini ────────────────────────────────────────────────────────────────────

def generate(prompt, key):
    """One generateContent call. The key goes in a header so no URL ever carries it.

    Walks [MODELS] and returns (response, model) from the first that answers. A 404 (retired) or
    503 (overloaded) moves to the next model; a 429 does not, because grounding quota is
    project-wide and every model returns the same answer — verified 2026-09-25.
    """
    # No "tools" key: Search grounding is unavailable on this tier (429 on every model, three days
    # running, while plain generation works). Headlines come from RSS instead — see fetch_news.
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"responseMimeType": "text/plain"},
    }).encode()

    last_error = "unknown"
    for model in MODELS:
        url = f"{GEMINI_BASE}/v1beta/models/{model}:generateContent"
        for attempt in range(2):
            req = urllib.request.Request(url, data=body, headers={
                "Content-Type": "application/json",
                "x-goog-api-key": key,
            })
            try:
                with urllib.request.urlopen(req, timeout=180) as resp:
                    return json.load(resp), model
            except urllib.error.HTTPError as e:
                # Safe to include the body: this request carries the key in a header, not the URL.
                # A bare "HTTP 404" cost two round trips to diagnose on 2026-09-24.
                detail = e.read(300).decode("utf-8", "replace").replace("\n", " ")
                last_error = f"{model}: HTTP {e.code}: {detail}"
                if e.code in (404, 503):
                    break                  # retired or overloaded: try the next model
                if 400 <= e.code < 500:
                    # Every other 4xx, 429 included. 429 here is a quota error that does not clear
                    # in 20 seconds and is the same for every model; the schedule is the retry.
                    raise BriefError(f"Gemini request failed ({last_error})")
            except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
                last_error = f"{model}: {type(e).__name__}"
            if attempt == 0:
                time.sleep(20)
    raise BriefError(f"Gemini request failed ({last_error})")


def extract_json(text):
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start:end + 1])
    except ValueError:
        return None


# ── Deep Research overlay ────────────────────────────────────────────────────

def fetch_deep_research(doc_id, now_utc, expect_lsl):
    """The JSON block from the Deep Research doc, or None if unusable.

    Refuses anything that is not schema 1, is older than [DEEP_RESEARCH_MAX_AGE_H], or describes a
    different trading session than the one this run is covering — that last check is the important
    one. A report written on Sunday evening is still correct on Monday morning and wrong by Monday
    evening, and the session label says so precisely where an age in hours only approximates it.
    """
    if not doc_id:
        return None
    # Accepts a bare doc id, or a full URL — the latter so an Apps Script web app serving the
    # report directly can be swapped in without touching this code.
    url = doc_id if doc_id.startswith("http") else DEEP_RESEARCH_DOC.format(doc_id=doc_id)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "aurum-brief-feed/1"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            text = resp.read().decode("utf-8", "replace")
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"deep research: doc unreadable ({type(e).__name__}) — using the RSS brief")
        return None

    doc = _extract_json_block(text)
    if not doc or doc.get("schema") != SCHEMA:
        print("deep research: no schema-1 JSON block in the doc — using the RSS brief")
        return None

    try:
        age_h = (now_utc - dt.datetime.fromisoformat(
            doc["as_of_utc"].replace("Z", "+00:00"))).total_seconds() / 3600
    except (KeyError, ValueError, TypeError):
        print("deep research: unreadable as_of_utc — using the RSS brief")
        return None
    if not (-2 <= age_h <= DEEP_RESEARCH_MAX_AGE_H):
        print(f"deep research: report is {age_h:.1f}h old — using the RSS brief")
        return None

    got_lsl = (doc.get("brief") or {}).get("lsl", "")
    # "September 25, 2026" and "September 25" should both match; compare on the leading date.
    if not got_lsl.startswith(expect_lsl):
        print(f"deep research: report covers '{got_lsl}', this run covers '{expect_lsl}' "
              f"— using the RSS brief")
        return None

    print(f"deep research: using analysis from {doc['as_of_utc']} ({age_h:.1f}h old)")
    return doc["brief"]


def _escape_inner_quotes(raw):
    """Escape double quotes that sit inside a JSON string value.

    The other thing a Docs export does to a code block: a report whose prose contains a quoted
    phrase — 'the "imminent Fed pivot" thesis' — comes back with those quotes unescaped, which
    ends the string early and breaks the parse a couple of thousand characters in.

    A quote is a real terminator when the next non-space character is one of , : } ] — anywhere
    else inside a string it is part of the prose. That is a heuristic, not a parser, which is why
    it only runs after the strict parse has already failed.
    """
    out, in_str, i = [], False, 0
    while i < len(raw):
        ch = raw[i]
        if not in_str:
            if ch == '"':
                in_str = True
            out.append(ch)
        elif ch == "\\":
            out.append(raw[i:i + 2])
            i += 2
            continue
        elif ch == '"':
            j = i + 1
            while j < len(raw) and raw[j] in " \t\r\n":
                j += 1
            if j < len(raw) and raw[j] in ",:}]":
                in_str = False
                out.append(ch)
            else:
                out.append('\\"')
        else:
            out.append(ch)
        i += 1
    return "".join(out)


def _loads_forgiving(raw):
    """(parsed, repair_used) — json.loads, progressively repairing what Docs does to a code block.

    Exporting a doc flattens the fenced JSON: its newlines come back as literal backslash-n
    sequences, which outside a string is a syntax error, so a strict parse finds nothing and the
    feed falls back to RSS looking like the doc was never wired up. Docs also likes turning
    straight quotes into curly ones.

    Strict first, always — a repair only runs once the raw text has genuinely failed, so a
    well-formed block is never touched. The \\n repair would corrupt a deliberately escaped
    newline inside a string value, which no field in this schema has.
    """
    attempts = (
        ("as written", lambda t: t),
        ("escaping quotes inside string values", _escape_inner_quotes),
        ("un-escaping literal \\n", lambda t: t.replace("\\n", "\n")),
        ("straightening smart quotes", lambda t: t.replace("\u201c", '"').replace("\u201d", '"')),
        ("both", lambda t: t.replace("\\n", "\n").replace("\u201c", '"').replace("\u201d", '"')),
        ("all three", lambda t: _escape_inner_quotes(
            t.replace("\\n", "\n").replace("\u201c", '"').replace("\u201d", '"'))),
    )
    for label, repair in attempts:
        try:
            return json.loads(repair(raw)), label
        except ValueError:
            continue
    return None, None


def _extract_json_block(text):
    """The NEWEST schema-1 JSON block in the doc, by as_of_utc.

    Newest rather than first on purpose. Appending each day's report to one running doc is the
    natural way to keep this, and taking the first block would pin the feed to the oldest report
    forever — which the session check would then reject, falling back to RSS silently and looking
    like the doc was never wired up. Scanning them all means append or replace both work.
    """
    blocks = []
    for start in (i for i, c in enumerate(text) if c == "{"):
        depth, in_str, esc = 0, False, False
        for i, ch in enumerate(text[start:], start):
            if in_str:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = False
                continue
            if ch == '"':
                in_str = True
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    obj, repair = _loads_forgiving(text[start:i + 1])
                    if isinstance(obj, dict) and "schema" in obj:
                        blocks.append((obj, repair))
                    break
    if not blocks:
        return None
    if len(blocks) > 1:
        print(f"deep research: {len(blocks)} reports in the doc — taking the newest")
    obj, repair = max(blocks, key=lambda b: str(b[0].get("as_of_utc", "")))
    if repair != "as written":
        print(f"deep research: JSON needed repairing ({repair}) — the Docs export mangles code blocks")
    return obj


def overlay_deep_research(brief, dr):
    """Deep Research supplies the analysis; the RSS pass keeps the headlines.

    why/cons/fals are the three deep sections; only a Deep Research brief has them, and the app
    hides each one it does not get. news is deliberately NOT taken from [dr]: the prompt bans it from writing URLs, so its news
    array is always empty, and brief.news is what the app's News tab and the PDF both render.
    """
    merged = dict(brief)
    for field in ("sig", "score", "desc", "yr", "to", "kf", "why", "cons", "fals"):
        value = dr.get(field)
        if value not in (None, "", []):
            merged[field] = value
    return merged


# ── Parse + validate ──────────────────────────────────────────────────────────
# The output uses GeminiCache's short field names (sig/score/desc/yr/to/lsl/nsl/kf/news) so the app
# deserializes a feed brief with exactly the code that reads its own on-disk cache.

def to_brief(raw, last_short, next_short, articles):
    """Assembles the publishable brief. [articles] is the RSS list the prompt offered the model.

    The model returns news as {"i": index, "summary": ...}. Headline, source, URL and date are
    taken from [articles] — never from the model — so a link is always one that really exists.
    An out-of-range or repeated index is dropped rather than guessed at.
    """
    signal = str(raw.get("signal", "NEUTRAL")).upper()
    signal = "BULLISH" if "BULL" in signal else "BEARISH" if "BEAR" in signal else "NEUTRAL"

    news, used = [], set()
    for pick in raw.get("news") or []:
        if not isinstance(pick, dict):
            continue
        try:
            i = int(pick.get("i"))
        except (TypeError, ValueError):
            continue
        if not (0 <= i < len(articles)) or i in used:
            continue
        used.add(i)
        a = articles[i]
        news.append({
            "h": a["headline"], "s": str(pick.get("summary", "")).strip(),
            "src": a["source"], "url": a["url"], "dt": a["date"],
        })
        if len(news) >= NEWS_PICK:
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
    # URL and headline come from RSS so they are real by construction; the summary is the part
    # the model writes, and an empty one renders as a bare headline with a gap under it.
    if any(len(n["s"].strip()) < 15 for n in brief["news"]):
        problems.append("a news item has no usable summary")
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

    articles = fetch_news(now_utc)
    prompt = build_prompt(last_long, next_long, facts_block(quote), headlines_block(articles))
    raw_response, model = generate(prompt, key)
    try:
        text = raw_response["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError, TypeError):
        raise BriefError("Gemini returned no candidate text")
    parsed = extract_json(text)
    if parsed is None:
        raise BriefError("Gemini returned no parseable JSON object")

    brief = to_brief(parsed, last_short, next_short, articles)

    # Optional overlay. Anything wrong with the doc — missing, stale, wrong session, unparseable —
    # logs a line and leaves the RSS brief untouched, so the feed degrades to its normal output
    # rather than failing.
    dr = fetch_deep_research(os.environ.get("DEEP_RESEARCH_DOC_ID", "").strip(),
                             now_utc, last_short)
    analysis_source = "rss"
    if dr:
        merged = overlay_deep_research(brief, dr)
        try:
            validate(merged)
        except BriefError as e:
            # The overlay is an enhancement, never a dependency. A Deep Research report too terse
            # for the validator must not take the whole feed down when a perfectly good RSS brief
            # is already in hand — publish that instead and say why.
            print(f"deep research: overlay rejected ({e}) — publishing the RSS brief")
        else:
            brief, analysis_source = merged, "deep-research"

    validate(brief)

    print(f"{brief['sig']} {brief['score']}/100 · {len(brief['news'])} news items · sessions "
          f"{brief['lsl']} → {brief['nsl']}")
    return {
        "schema": SCHEMA,
        "generated_utc": now_utc.isoformat(timespec="seconds").replace("+00:00", "Z"),
        "model": model,
        "symbol": SYMBOL,
        "quote_at_generation": quote,   # what the brief's numbers were written against
        "analysis_source": analysis_source,   # "rss" or "deep-research"; the app ignores it
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
