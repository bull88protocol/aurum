#!/usr/bin/env python3
"""A local stand-in for Yahoo and the Gemini API, so build_brief.py can be exercised without a key.

  python3 .github/brief-feed/mock_server.py &
  GEMINI_API_BASE=http://127.0.0.1:8731 YAHOO_API_BASE=http://127.0.0.1:8731 \
    GEMINI_API_KEY=x python3 .github/brief-feed/build_brief.py --out /tmp/brief.json

It asserts the two things that must never regress about how the real key is handled: it travels in
the x-goog-api-key header, and it never appears in a URL (which would put it in server logs and on
the workflow's run page). Pass --thin to return a brief that should fail validation instead.
"""
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8731

QUOTE = {"chart": {"result": [{"meta": {
    "regularMarketPrice": 398.47, "chartPreviousClose": 389.66, "marketState": "REGULAR",
    "regularMarketDayHigh": 399.10, "regularMarketDayLow": 392.00,
}}]}}

BRIEF = {
    "signal": "BULLISH", "score": 72,
    "description": "Gold is supported by falling real yields and a softer dollar as the market prices a faster easing path into year-end.",
    "key_factors": ["10y TIPS yield down 12bp on the week", "DXY off 0.6%", "Central-bank buying steady"],
    "yesterday_recap": "Gold rose 2.26% to settle near $398.47 on the GLD ETF as a soft core PCE print pulled the 10-year real yield lower and knocked the dollar index down half a percent. Buying was broad, with ETF inflows turning positive. Risk sentiment was mixed, with equities flat.",
    "today_outlook": "Attention turns to jobless claims and two Fed speakers, either of which could reprice the front end and with it gold's real-yield tailwind. A sustained move below 2.60% on the 10-year TIPS would open room toward the prior high, while resistance sits near $399.10 and reclaimed support around $392.00.",
    # The model picks headlines by INDEX out of the list the prompt offers it, and writes only
    # summaries. build_brief.to_brief maps these back to the real RSS metadata, so a model can
    # never invent a URL. Note fetch_news is NOT mocked — it hits Google News RSS for real, which
    # is free and needs no key, so a run here exercises the true headline path.
    "news": [
        {"i": 0, "summary": "Sets the tone for the session and speaks directly to the rate path."},
        {"i": 1, "summary": "Official-sector demand is the structural bid under the market."},
        {"i": 2, "summary": "The dollar leg of the move, which the index scores separately."},
        {"i": 3, "summary": "Positioning colour that explains the intraday range."},
        {"i": 4, "summary": "Flows data, the slowest-moving of the five drivers."},
    ],
}

THIN = {"signal": "BULLISH", "score": 72, "description": "Too short.", "key_factors": [], "news": []}


class Handler(BaseHTTPRequestHandler):
    def _json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        assert "finance/chart" in self.path, f"unexpected GET {self.path}"
        self._json(QUOTE)

    def do_POST(self):
        assert self.headers.get("x-goog-api-key"), "the key must travel in the x-goog-api-key header"
        assert "key=" not in self.path, "the key must never appear in a URL"
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        brief = THIN if "--thin" in sys.argv else BRIEF
        # Fenced, as the real model often replies: build_brief.py has to dig the object out.
        self._json({"candidates": [{"content": {"parts": [
            {"text": "```json\n" + json.dumps(brief) + "\n```"}]}}]})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", PORT), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f"mock Yahoo + Gemini on http://127.0.0.1:{PORT} — ctrl-C to stop")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
