#!/usr/bin/env python3
"""Checks a Deep Research report against ground truth before you trust it.

    python3 aws/brief-feed/check_report.py ~/Downloads/"Daily Gold Deep Research - Test Run.docx"

Takes a .docx, .json or .txt, finds the JSON block, and checks it against the live FRED feed. Exists because the first test run (2026-09-26) looked
authoritative and had the 2-year yield 27bp wrong, GLD support above the GLD close, a bearish call
scored 75/100 bullish, and five news links pointing at homepages. None of that is visible by
reading; all of it is one command.

Exit status is 0 when everything passes, 1 when anything fails.
"""
import html
import json
import os
import re
import sys
import urllib.error
import urllib.request
import zipfile

FRED_FEED = "https://raw.githubusercontent.com/bull88protocol/aurum/fred-data/fred_daily.json"
BRIEF_FEED = "https://raw.githubusercontent.com/bull88protocol/aurum/brief-data/brief_daily.json"
TOLERANCE = 0.06        # percentage points; FRED's one-day lag makes exact equality unfair


def load_text(path):
    if path.lower().endswith(".docx"):
        xml = zipfile.ZipFile(path).read("word/document.xml").decode("utf-8")
        stripped = re.sub(r"<[^>]+>", "", xml.replace("</w:p>", "\n"))
        # Word escapes every quote in the JSON block as &quot;, so unescaping is not cosmetic —
        # without it there is no JSON in the document at all, only a lookalike.
        return html.unescape(stripped)
    with open(path, encoding="utf-8") as f:
        return f.read()


def extract_json(text):
    """The report's JSON block, via the generator's own extractor.

    Deliberately not a second implementation. There was one here, it did not get the fix for the
    literal \\n sequences a Google Docs export leaves in a code block, and it then reported "no
    JSON block" on a doc the Lambda parsed fine — the two disagreeing about the same file is worse
    than either being wrong.
    """
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    "..", "..", ".github", "brief-feed"))
    import build_brief
    return build_brief._extract_json_block(text)


def fetch(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as r:
            return json.load(r)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        return None


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    report = extract_json(load_text(sys.argv[1]))
    if not report:
        sys.exit("no JSON block found in the report")

    fred = fetch(FRED_FEED) or {}
    brief = fetch(BRIEF_FEED) or {}
    series = {k: v["values"][-1] for k, v in (fred.get("series") or {}).items()}
    dates = {k: v["dates"][-1] for k, v in (fred.get("series") or {}).items()}

    fails = []
    def check(ok, label, detail=""):
        print(f"  {'PASS' if ok else 'FAIL'}  {label}{(' — ' + detail) if detail else ''}")
        if not ok:
            fails.append(label)

    b = report.get("brief", {})
    print("\nSIGNAL")
    sig, score = b.get("sig"), b.get("score")
    if sig == "BEARISH":
        check(isinstance(score, int) and score < 50, "bearish call scores below 50",
              f"sig={sig} score={score} (score is bullishness, not conviction)")
    elif sig == "BULLISH":
        check(isinstance(score, int) and score > 50, "bullish call scores above 50", f"score={score}")
    else:
        check(True, "neutral", f"score={score}")

    print("\nDRIVER NUMBERS vs FRED")
    # Look for each driver's value near its own name in the full report, not just anywhere in it.
    # A cruder "is any number in the report far from this one" test fails on unrelated figures —
    # it flagged the breakeven as wrong because the 2-year appeared elsewhere in the same string.
    full = load_text(sys.argv[1])
    DRIVERS = (
        ("DFII10", "10y TIPS",      r"(?:TIPS|DFII10|real yield)"),
        ("DGS2",   "2y Treasury",   r"(?:2-?\s?year|2Y|DGS2)"),
        ("T10YIE", "10y breakeven", r"(?:breakeven|break-even|T10YIE|inflation exp)"),
    )
    for fred_id, label, pattern in DRIVERS:
        truth = series.get(fred_id)
        if truth is None:
            continue
        cited = []
        for m in re.finditer(pattern, full, re.I):
            window = full[m.start():m.start() + 120]
            cited += [float(x) for x in re.findall(r"(\d\.\d{1,2})\s*%", window)]
        cited = sorted(set(cited))
        if not cited:
            print(f"  SKIP  {label} — no figure cited near its name")
            continue
        ok = any(abs(x - truth) <= TOLERANCE for x in cited)
        check(ok, f"{label} matches FRED {truth}",
              f"as of {dates[fred_id]}; report cites {cited}")

    print("\nNARRATIVE vs WHERE THE PRICE ACTUALLY IS")
    # The failure this catches: every pinned number correct, the story around them inverted.
    # Run 6 called gold "pinned near historic highs" while GLD sat 20.7% below its 52-week high.
    try:
        import urllib.request as _u
        _r = _u.Request("https://query1.finance.yahoo.com/v8/finance/chart/GLD?interval=1d&range=1y",
                        headers={"User-Agent": "Mozilla/5.0"})
        with _u.urlopen(_r, timeout=30) as _resp:
            _d = json.load(_resp)["chart"]["result"][0]
        _q = _d["indicators"]["quote"][0]
        closes = [c for c in _q["close"] if c]
        # Intraday highs, not closing highs: that is the 52-week-high convention, and using
        # closes understated the drawdown by ~2pp when this was first written.
        highs = [h for h in _q["high"] if h]
        drawdown = closes[-1] / max(highs) - 1
        prose = " ".join(str(b.get(k, "")) for k in ("desc", "yr", "to")).lower()
        near_highs = any(p in prose for p in
                         ("near historic high", "near record", "near its high", "at record",
                          "near range high", "record high", "historic high"))
        if near_highs and drawdown < -0.10:
            check(False, "narrative says 'near highs' but the price is not",
                  f"GLD is {drawdown*100:.1f}% below its 52-week high")
        else:
            print(f"  PASS  narrative consistent with position — GLD {drawdown*100:+.1f}% from its 52-week high")
    except Exception as e:
        print(f"  SKIP  could not fetch GLD history ({type(e).__name__})")

    print("\nNEWS LINKS")
    news = b.get("news") or []
    if not news:
        print("  SKIP  no news array — correct; the prompt bans URLs and the app sources news from RSS")
    for n in news:
        url = n.get("url", "")
        path = re.sub(r"^https?://[^/]+", "", url)
        if len(path.strip("/")) <= 3:
            check(False, f"{n.get('src', '?')} link has an article path", url or "(none)")
            continue
        # A placeholder slug is the clearest fabrication tell there is.
        if re.search(r"\b(abc|xxx|example|placeholder|slug|1234)\w*\b", url, re.I):
            check(False, f"{n.get('src', '?')} link looks fabricated", url)
            continue
        # 404 is decisive. 401/403 usually means a paywall or bot-block, which proves nothing.
        try:
            req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "Mozilla/5.0"})
            code = urllib.request.urlopen(req, timeout=20).status
        except urllib.error.HTTPError as e:
            code = e.code
        except Exception:
            code = 0
        if code == 404:
            check(False, f"{n.get('src', '?')} link is a 404", url)
        elif code in (401, 403, 0):
            print(f"  ????  {n.get('src', '?')} link unverifiable (HTTP {code or 'no response'}) — paywall or bot-block")
        else:
            check(True, f"{n.get('src', '?')} link resolves", f"HTTP {code}")

    print(f"\n{'ALL CHECKS PASSED' if not fails else str(len(fails)) + ' CHECK(S) FAILED'}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
