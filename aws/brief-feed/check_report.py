#!/usr/bin/env python3
"""Checks a Deep Research report against ground truth before you trust it.

    python3 aws/brief-feed/check_report.py ~/Downloads/"Daily Gold Deep Research - Test Run.docx"

Takes a .docx, .json or .txt, finds the JSON block, and checks it against the live FRED feed and
the gold quote the app itself is using. Exists because the first test run (2026-09-26) looked
authoritative and had the 2-year yield 27bp wrong, GLD support above the GLD close, a bearish call
scored 75/100 bullish, and five news links pointing at homepages. None of that is visible by
reading; all of it is one command.

Exit status is 0 when everything passes, 1 when anything fails.
"""
import html
import json
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
    """The report's JSON block: the first balanced {...} containing a "schema" key."""
    for start in (m.start() for m in re.finditer(r"\{", text)):
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
                    try:
                        obj = json.loads(text[start:i + 1])
                    except ValueError:
                        break
                    if isinstance(obj, dict) and "schema" in obj:
                        return obj
                    break
    return None


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
    quote = (brief.get("quote_at_generation") or {})
    gld = quote.get("price")
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

    print("\nLEVELS vs the gold quote the app is using")
    lv = report.get("levels") or {}
    if gld:
        for key, want_below in (("gld_support", True), ("gld_resistance", False)):
            for x in lv.get(key) or []:
                ok = (x < gld) if want_below else (x > gld)
                check(ok, f"{key} {x}", f"GLD close ${gld:.2f}")
    else:
        print("  SKIP  brief feed unreachable, cannot check GLD levels")

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

    print("\nNEWS LINKS")
    for n in b.get("news") or []:
        url = n.get("url", "")
        path = re.sub(r"^https?://[^/]+", "", url)
        check(len(path.strip("/")) > 3, f"{n.get('src', '?')} link has an article path", url or "(none)")

    print(f"\n{'ALL CHECKS PASSED' if not fails else str(len(fails)) + ' CHECK(S) FAILED'}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
