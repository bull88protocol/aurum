#!/usr/bin/env python3
"""Checks the Deep Research Google Doc is set up so the feed can actually read it.

    python3 aws/brief-feed/check_doc.py <DOC_ID or the full docs.google.com URL>

Run this once after sharing the doc, and again any time the feed logs that it fell back to RSS.
It fetches the doc exactly the way the Lambda does — plain-text export, no credentials — so if
this passes, the Lambda can read it too.
"""
import datetime as dt
import json
import re
import sys
import urllib.error
import urllib.request

sys.path.insert(0, __file__.rsplit("/", 2)[0] + "/.github/brief-feed")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    raw = sys.argv[1]
    m = re.search(r"/document/d/([A-Za-z0-9_-]+)", raw)
    doc_id = m.group(1) if m else raw.strip()
    url = f"https://docs.google.com/document/d/{doc_id}/export?format=txt"
    print(f"doc id: {doc_id}\n")

    try:
        req = urllib.request.Request(url, headers={"User-Agent": "aurum-brief-feed/1"})
        with urllib.request.urlopen(req, timeout=30) as r:
            text = r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        sys.exit(f"  FAIL  HTTP {e.code} fetching the doc.\n"
                 f"        401/403 means it is not link-shared: Share > General access >\n"
                 f"        Anyone with the link > Viewer. 404 means the id is wrong.")
    except Exception as e:
        sys.exit(f"  FAIL  could not fetch the doc ({type(e).__name__})")
    print(f"  PASS  doc is readable without credentials ({len(text)} chars)")

    import build_brief as bb
    block = bb._extract_json_block(text)
    if not block:
        sys.exit("  FAIL  no schema-1 JSON block found. Paste the whole report including the\n"
                 "        fenced json block at the end — that block is what the feed reads.")
    print(f"  PASS  found a schema-1 block, as_of_utc {block.get('as_of_utc')}")

    now = dt.datetime.now(dt.timezone.utc)
    try:
        age = (now - dt.datetime.fromisoformat(block["as_of_utc"].replace("Z", "+00:00"))).total_seconds() / 3600
        ok = -2 <= age <= bb.DEEP_RESEARCH_MAX_AGE_H
        print(f"  {'PASS' if ok else 'FAIL'}  report is {age:.1f}h old "
              f"(limit {bb.DEEP_RESEARCH_MAX_AGE_H}h)")
    except Exception:
        print("  FAIL  as_of_utc is missing or unreadable")

    (last, _), _ = bb.session_dates(now.astimezone(bb.ET))
    got = (block.get("brief") or {}).get("lsl", "")
    ok = got.startswith(last)
    print(f"  {'PASS' if ok else 'FAIL'}  covers '{got}'; a run right now would want '{last}'")
    if not ok:
        print("        Not necessarily a problem — it means the report predates the current\n"
              "        session. Regenerate after the next close.")

    b = block.get("brief") or {}
    missing = [k for k in ("sig", "score", "desc", "yr", "to", "kf") if not b.get(k)]
    print(f"  {'PASS' if not missing else 'FAIL'}  analysis fields present"
          + (f" — missing {missing}" if missing else ""))
    if b.get("news"):
        print(f"  WARN  the block has {len(b['news'])} news items; they are ignored on purpose "
              f"(headlines come from RSS)")
    print("\nIf every line above passes, set DEEP_RESEARCH_DOC_ID to this id and redeploy.")


if __name__ == "__main__":
    main()
