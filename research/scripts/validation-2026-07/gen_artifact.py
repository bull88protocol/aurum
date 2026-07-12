#!/usr/bin/env python3
"""Generate the validation artifact: pre-baked SVG charts + tooltip JS, self-contained."""
import json
import math
import pandas as pd

SP = "/tmp/claude-1001/-home-sun-gold/9a849d98-cd7c-4df1-bc21-76eb168561c9/scratchpad"
app = pd.read_pickle(f"{SP}/app.pkl")
lbma = pd.read_pickle(f"{SP}/lbma.pkl")["pm"]
fw = pd.read_pickle(f"{SP}/fw.pkl")
me = pd.read_pickle(f"{SP}/me.pkl")

# ── data prep ────────────────────────────────────────────────────────────────
mo = me[["gold", "idx"]].dropna()                      # month-end 2005-02..2026-06/07
daily = pd.DataFrame({"gold": lbma, "idx": app["composite"]}).dropna().loc["2024-07-01":]
fstrip = fw["forward"].loc["2024-06-01":]              # month-end forward scores

def lab(v): return "BULLISH" if v >= 70 else ("NEUTRAL" if v >= 45 else "BEARISH")

# ── chart 1 geometry: 21y, two aligned panels ────────────────────────────────
W, XL, XR = 960, 56, 944
g_top, g_bot = 30, 280          # gold panel (log)
i_top, i_bot = 316, 436         # index panel
H1 = 476
n1 = len(mo)
xs1 = [XL + (XR - XL) * k / (n1 - 1) for k in range(n1)]
gmin, gmax = 380.0, 5600.0
def gy(v): return g_bot - (g_bot - g_top) * (math.log(v) - math.log(gmin)) / (math.log(gmax) - math.log(gmin))
def iy(v): return i_bot - (i_bot - i_top) * v / 100.0
path_g1 = "M" + " L".join(f"{x:.1f} {gy(v):.2f}" for x, v in zip(xs1, mo["gold"]))
path_i1 = "M" + " L".join(f"{x:.1f} {iy(v):.2f}" for x, v in zip(xs1, mo["idx"]))
mo_json = [[d.strftime("%Y-%m"), round(g, 0), round(i, 1)] for d, g, i in zip(mo.index, mo["gold"], mo["idx"])]

def x_of_month(ym):
    for k, d in enumerate(mo.index):
        if d.strftime("%Y-%m") == ym: return xs1[k]
    return None

eras = [("2005-02", "2012-12", "2005–12"), ("2013-01", "2018-12", "2013–18"),
        ("2019-01", "2021-12", "2019–21"), ("2022-01", "2026-07", "2022–26")]
era_rects = []
for k, (a, b, name) in enumerate(eras):
    xa = XL + (XR - XL) * max(0, (mo.index.searchsorted(a + "-01"))) / (n1 - 1)
    xb = XL + (XR - XL) * min(n1 - 1, (mo.index.searchsorted(b + "-28"))) / (n1 - 1)
    era_rects.append((xa, xb, name, k % 2))

landmarks = [("2011-09", "$1,895 peak", 1895, -8), ("2013-04", "2013 crash", 1395, 10),
             ("2015-12", "$1,049 bottom", 1049, 10), ("2020-08", "$2,067 peak", 2067, -8),
             ("2022-09", "$1,643 trough", 1643, 10), ("2026-01", "$5,405 top", 5405, -8)]
lm_svg = ""
for ym, txt, v, dy in landmarks:
    x = x_of_month(ym)
    if x is None: continue
    y = gy(v)
    anchor = "end" if x > 800 else "middle"
    lm_svg += f'<circle cx="{x:.1f}" cy="{y:.2f}" r="3.5" class="dot-g"/>'
    lm_svg += f'<text x="{x:.1f}" y="{y + (dy if dy < 0 else dy + 8):.1f}" class="ann" text-anchor="{anchor}">{txt}</text>'

yr_ticks1 = ""
for yr in range(2006, 2027, 2):
    k = mo.index.searchsorted(f"{yr}-01-01")
    if k >= n1: continue
    x = xs1[k]
    yr_ticks1 += f'<line x1="{x:.1f}" y1="{i_bot}" x2="{x:.1f}" y2="{i_bot + 4}" class="axis"/><text x="{x:.1f}" y="{i_bot + 16}" class="tick" text-anchor="middle">{yr}</text>'
gold_ticks = "".join(
    f'<line x1="{XL}" y1="{gy(v):.1f}" x2="{XR}" y2="{gy(v):.1f}" class="grid"/><text x="{XL - 6}" y="{gy(v) + 3.5:.1f}" class="tick" text-anchor="end">${v:,}</text>'
    for v in [500, 1000, 2000, 4000])
idx_ticks = "".join(
    f'<line x1="{XL}" y1="{iy(v):.1f}" x2="{XR}" y2="{iy(v):.1f}" class="{cls}"/><text x="{XL - 6}" y="{iy(v) + 3.5:.1f}" class="tick" text-anchor="end">{v}</text>'
    for v, cls in [(0, "grid"), (45, "thresh"), (70, "thresh"), (100, "grid")])
era_svg = "".join(
    f'<rect x="{xa:.1f}" y="{g_top - 16}" width="{xb - xa:.1f}" height="{i_bot - g_top + 16}" class="era{alt}"/>'
    f'<text x="{(xa + xb) / 2:.1f}" y="{g_top - 4}" class="era-lab" text-anchor="middle">{nm}</text>'
    for xa, xb, nm, alt in era_rects)

chart1 = f'''<svg viewBox="0 0 {W} {H1}" role="img" aria-label="Gold price and Gold Index, 2005 to 2026, monthly">
{era_svg}{gold_ticks}
<text x="{XL}" y="{g_top - 4}" class="series-lab lab-g">Gold — LBMA PM fix (log)</text>
<path d="{path_g1}" class="line-g"/>
{lm_svg}{idx_ticks}
<text x="{XL}" y="{i_top - 5}" class="series-lab lab-i">Aurum88 Gold Index (0–100) — spot / conditions</text>
<text x="{XR}" y="{iy(70) - 4:.1f}" class="thresh-lab" text-anchor="end">HOT ≥70</text>
<text x="{XR}" y="{iy(45) + 11:.1f}" class="thresh-lab" text-anchor="end">WEAK &lt;45</text>
<path d="{path_i1}" class="line-i"/>
<line x1="{XL}" y1="{i_bot}" x2="{XR}" y2="{i_bot}" class="axis"/>{yr_ticks1}
<g class="hover" style="display:none"><line y1="{g_top}" y2="{i_bot}" class="xhair"/><circle r="3.5" class="hdot hdot-g"/><circle r="3.5" class="hdot hdot-i"/></g>
</svg>'''

# ── chart 2: zoom Jul-2024 → Jul-2026, daily + forward strip ────────────────
n2 = len(daily)
xs2 = [XL + (XR - XL) * k / (n2 - 1) for k in range(n2)]
zg_top, zg_bot = 30, 270
s_top, s_bot = 292, 310
zi_top, zi_bot = 344, 452
H2 = 492
zmin, zmax = 2300.0, 5600.0
def zgy(v): return zg_bot - (zg_bot - zg_top) * (v - zmin) / (zmax - zmin)
def ziy(v): return zi_bot - (zi_bot - zi_top) * v / 100.0
path_g2 = "M" + " L".join(f"{x:.1f} {zgy(v):.2f}" for x, v in zip(xs2, daily["gold"]))
path_i2 = "M" + " L".join(f"{x:.1f} {ziy(v):.2f}" for x, v in zip(xs2, daily["idx"]))
d2_json = [[d.strftime("%Y-%m-%d"), round(g, 0), round(i, 1)] for d, g, i in zip(daily.index, daily["gold"], daily["idx"])]

# HOT fill on index panel where idx >= 70
hot_svg = ""
run = None
for k, v in enumerate(daily["idx"]):
    if v >= 70 and run is None: run = k
    if (v < 70 or k == n2 - 1) and run is not None:
        hot_svg += f'<rect x="{xs2[run]:.1f}" y="{zi_top}" width="{max(xs2[k] - xs2[run], 2):.1f}" height="{zi_bot - zi_top}" class="hotband"/>'
        run = None

strip_svg = ""
for d, v in fstrip.items():
    pos = daily.index.searchsorted(d)
    if pos >= n2: continue
    x = xs2[pos]
    cls = {"BULLISH": "st-good", "NEUTRAL": "st-neut", "BEARISH": "st-bad"}[lab(v)]
    strip_svg += f'<rect x="{x - 8:.1f}" y="{s_top}" width="16" height="{s_bot - s_top}" rx="3" class="{cls}"><title>{d.strftime("%Y-%m")}: forward {v:.0f} {lab(v)}</title></rect>'

q_ticks = ""
for d in pd.date_range("2024-07-01", "2026-07-01", freq="QS"):
    pos = daily.index.searchsorted(d)
    if pos >= n2: continue
    x = xs2[pos]
    q_ticks += f'<line x1="{x:.1f}" y1="{zi_bot}" x2="{x:.1f}" y2="{zi_bot + 4}" class="axis"/><text x="{x:.1f}" y="{zi_bot + 16}" class="tick" text-anchor="middle">{d.strftime("%b %y")}</text>'
zg_ticks = "".join(
    f'<line x1="{XL}" y1="{zgy(v):.1f}" x2="{XR}" y2="{zgy(v):.1f}" class="grid"/><text x="{XL - 6}" y="{zgy(v) + 3.5:.1f}" class="tick" text-anchor="end">${v:,}</text>'
    for v in [2500, 3500, 4500, 5500])
zi_ticks = "".join(
    f'<line x1="{XL}" y1="{ziy(v):.1f}" x2="{XR}" y2="{ziy(v):.1f}" class="{c}"/><text x="{XL - 6}" y="{ziy(v) + 3.5:.1f}" class="tick" text-anchor="end">{v}</text>'
    for v, c in [(0, "grid"), (45, "thresh"), (70, "thresh"), (100, "grid")])

top_x = xs2[daily.index.searchsorted("2026-01-29")]
crash_note_x = xs2[min(daily.index.searchsorted("2026-05-20"), n2 - 1)]
chart2 = f'''<svg viewBox="0 0 {W} {H2}" role="img" aria-label="Gold price, forward signal and index, July 2024 to July 2026, daily">
{zg_ticks}
<text x="{XL}" y="{zg_top - 4}" class="series-lab lab-g">Gold — LBMA PM fix, daily</text>
<path d="{path_g2}" class="line-g"/>
<circle cx="{top_x:.1f}" cy="{zgy(5405):.1f}" r="3.5" class="dot-g"/>
<text x="{top_x:.1f}" y="{zgy(5405) - 8:.1f}" class="ann" text-anchor="middle">$5,405 · Jan 29</text>
<text x="{crash_note_x:.1f}" y="{zgy(4100):.1f}" class="ann" text-anchor="start">−24% off the top</text>
<text x="{XL}" y="{s_top - 5}" class="series-lab">Forward Signal v2 at month-end (3–6M outlook)</text>
{strip_svg}
{zi_ticks}{hot_svg}
<text x="{XL}" y="{zi_top - 5}" class="series-lab lab-i">Spot index — went HOT (≥70) the day of the top, WEAK now</text>
<path d="{path_i2}" class="line-i"/>
<line x1="{XL}" y1="{zi_bot}" x2="{XR}" y2="{zi_bot}" class="axis"/>{q_ticks}
<g class="hover" style="display:none"><line y1="{zg_top}" y2="{zi_bot}" class="xhair"/><circle r="3.5" class="hdot hdot-g"/><circle r="3.5" class="hdot hdot-i"/></g>
</svg>'''

# ── chart 3: outcome bars (next-3M mean return by label), two small multiples ─
b_v2 = [("BULLISH", 8.18, 83.7, 49), ("NEUTRAL", 3.11, 62.8, 113), ("BEARISH", 0.30, 51.1, 92)]
b_sp = [("HOT ≥70", -0.31, 47.4, 38), ("MIXED", 3.63, 64.1, 156), ("WEAK <45", 4.24, 70.5, 61)]
BW, BH, byz = 430, 270, 190          # panel size, y of zero line
def by(v): return byz - v * 17.5     # -2..+9% domain
BASE = 3.07
def bars(panel_x, data, title):
    s = f'<text x="{panel_x + 6}" y="24" class="series-lab">{title}</text>'
    for gv in [0, 4, 8]:
        s += f'<line x1="{panel_x + 46}" y1="{by(gv):.1f}" x2="{panel_x + BW - 10}" y2="{by(gv):.1f}" class="grid"/><text x="{panel_x + 40}" y="{by(gv) + 3.5:.1f}" class="tick" text-anchor="end">{gv:+d}%</text>'
    s += f'<line x1="{panel_x + 46}" y1="{by(BASE):.1f}" x2="{panel_x + BW - 10}" y2="{by(BASE):.1f}" class="baserate"/><text x="{panel_x + BW - 10}" y="{by(BASE) - 4:.1f}" class="thresh-lab" text-anchor="end">base +3.1%</text>'
    for k, (nm, mean, up, n) in enumerate(data):
        x = panel_x + 70 + k * 122
        h = abs(by(mean) - byz)
        ytop = min(by(mean), byz)
        s += f'<rect x="{x}" y="{ytop:.1f}" width="64" height="{max(h, 2):.1f}" rx="4" class="bar" data-tip="{nm}: mean {mean:+.2f}%, {up:.0f}% of months up (n={n})"/>'
        s += f'<text x="{x + 32}" y="{(ytop - 6) if mean >= 0 else (ytop + h + 14):.1f}" class="bar-val" text-anchor="middle">{mean:+.1f}%</text>'
        s += f'<text x="{x + 32}" y="{byz + 18}" class="tick" text-anchor="middle">{nm}</text>'
        s += f'<text x="{x + 32}" y="{byz + 34}" class="tick2" text-anchor="middle">{up:.0f}% up · n={n}</text>'
    return s
chart3 = f'''<svg viewBox="0 0 {W} {BH}" role="img" aria-label="Mean gold return over the next three months by signal label">
{bars(20, b_v2, "After the FORWARD label (v2) — direction lives here")}
{bars(510, b_sp, "After the SPOT condition — HOT is a warning, not a buy")}
</svg>'''

# ── page ─────────────────────────────────────────────────────────────────────
data_js = json.dumps({"mo": mo_json, "d2": d2_json,
                      "geo1": {"xl": XL, "xr": XR, "n": n1, "H": H1},
                      "geo2": {"xl": XL, "xr": XR, "n": n2, "H": H2}}, separators=(",", ":"))

html = """<title>Aurum88 Gold Index — reality check vs LBMA</title>
<style>
.viz-root{
  --surface-1:#fcfcfb; --page:#f9f9f7; --ink:#0b0b0b; --ink-2:#52514e; --muted:#898781;
  --grid:#e1e0d9; --axisc:#c3c2b7; --border:rgba(11,11,11,.10);
  --s-gold:#eda100; --s-idx:#2a78d6; --good:#0ca30c; --bad:#d03b3b; --neut:#b3b1a7;
  --warnwash:rgba(250,178,25,.16); --era:rgba(11,11,11,.03);
}
@media (prefers-color-scheme: dark){ .viz-root{
  --surface-1:#1a1a19; --page:#0d0d0d; --ink:#fff; --ink-2:#c3c2b7; --muted:#898781;
  --grid:#2c2c2a; --axisc:#383835; --border:rgba(255,255,255,.10);
  --s-gold:#c98500; --s-idx:#3987e5; --neut:#55544f; --warnwash:rgba(250,178,25,.13); --era:rgba(255,255,255,.035);
}}
:root[data-theme="dark"] .viz-root{
  --surface-1:#1a1a19; --page:#0d0d0d; --ink:#fff; --ink-2:#c3c2b7; --muted:#898781;
  --grid:#2c2c2a; --axisc:#383835; --border:rgba(255,255,255,.10);
  --s-gold:#c98500; --s-idx:#3987e5; --neut:#55544f; --warnwash:rgba(250,178,25,.13); --era:rgba(255,255,255,.035);
}
:root[data-theme="light"] .viz-root{
  --surface-1:#fcfcfb; --page:#f9f9f7; --ink:#0b0b0b; --ink-2:#52514e; --muted:#898781;
  --grid:#e1e0d9; --axisc:#c3c2b7; --border:rgba(11,11,11,.10);
  --s-gold:#eda100; --s-idx:#2a78d6; --neut:#b3b1a7; --warnwash:rgba(250,178,25,.16); --era:rgba(11,11,11,.03);
}
.viz-root{background:var(--page); color:var(--ink); font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; margin:0; padding:28px 16px 48px;}
.wrap{max-width:1000px; margin:0 auto;}
h1{font-size:22px; margin:0 0 4px;} .sub{color:var(--ink-2); margin:0 0 20px; max-width:72ch;}
h2{font-size:16px; margin:34px 0 2px;} .note{color:var(--ink-2); font-size:13.5px; margin:0 0 10px; max-width:80ch;}
.card{background:var(--surface-1); border:1px solid var(--border); border-radius:10px; padding:14px 12px 8px; position:relative;}
svg{width:100%; height:auto; display:block;}
.tiles{display:grid; grid-template-columns:repeat(auto-fit,minmax(190px,1fr)); gap:10px; margin:18px 0 6px;}
.tile{background:var(--surface-1); border:1px solid var(--border); border-radius:10px; padding:12px 14px;}
.tile .k{font-size:12.5px; color:var(--ink-2);} .tile .v{font-size:24px; font-weight:650; margin-top:2px;}
.tile .d{font-size:12px; color:var(--muted); margin-top:2px;}
.v .up{color:var(--good);} .v .dn{color:var(--bad);}
.grid{stroke:var(--grid); stroke-width:1;} .axis{stroke:var(--axisc); stroke-width:1;}
.thresh{stroke:var(--grid); stroke-width:1; stroke-dasharray:4 4;}
.tick{fill:var(--muted); font-size:11.5px; font-variant-numeric:tabular-nums;} .tick2{fill:var(--muted); font-size:10.5px;}
.line-g{fill:none; stroke:var(--s-gold); stroke-width:2; stroke-linejoin:round;}
.line-i{fill:none; stroke:var(--s-idx); stroke-width:2; stroke-linejoin:round;}
.dot-g{fill:var(--s-gold); stroke:var(--surface-1); stroke-width:2;}
.series-lab{font-size:12px; fill:var(--ink-2); font-weight:600;}
.lab-g{fill:var(--s-gold);} .lab-i{fill:var(--s-idx);}
.ann{font-size:11.5px; fill:var(--ink-2);} .thresh-lab{font-size:10.5px; fill:var(--muted);}
.era0{fill:none;} .era1{fill:var(--era);} .era-lab{font-size:10.5px; fill:var(--muted);}
.st-good{fill:var(--good);} .st-neut{fill:var(--neut);} .st-bad{fill:var(--bad);}
.hotband{fill:var(--warnwash);}
.bar{fill:var(--s-idx);} .bar-val{font-size:12px; font-weight:650; fill:var(--ink);}
.baserate{stroke:var(--muted); stroke-width:1.5; stroke-dasharray:2 3;}
.xhair{stroke:var(--muted); stroke-width:1; stroke-dasharray:3 3;}
.hdot{stroke:var(--surface-1); stroke-width:2;} .hdot-g{fill:var(--s-gold);} .hdot-i{fill:var(--s-idx);}
.tip{position:absolute; pointer-events:none; background:var(--surface-1); border:1px solid var(--border);
  border-radius:8px; padding:7px 10px; font-size:12.5px; box-shadow:0 4px 14px rgba(0,0,0,.14); display:none; z-index:5; white-space:nowrap;}
.tip b{font-variant-numeric:tabular-nums;}
.legend{display:flex; gap:16px; flex-wrap:wrap; font-size:12.5px; color:var(--ink-2); margin:6px 2px 0;}
.legend span{display:inline-flex; align-items:center; gap:6px;}
.sw{width:12px; height:12px; border-radius:3px; display:inline-block;}
table{border-collapse:collapse; font-size:13.5px; margin-top:8px; min-width:640px;}
.tblwrap{overflow-x:auto;} th,td{padding:6px 12px; border-bottom:1px solid var(--grid); text-align:right;}
th:first-child,td:first-child{text-align:left;} th{color:var(--ink-2); font-weight:600;}
td{font-variant-numeric:tabular-nums;}
.foot{color:var(--muted); font-size:12.5px; margin-top:28px; max-width:86ch;}
</style>
<div class="viz-root"><div class="wrap">
<h1>Aurum88 Gold Index — reality check against real gold prices</h1>
<p class="sub">Every number on this page was recomputed on 2026-07-10 from the app's own 20-year CSV export,
cross-checked against <b>independent LBMA PM fixes</b> (14,636 fixes, 1968→2026-07-09) and fresh FRED data.
The app export matches the engine's test dump to CSV rounding (±0.05) — the exported values are engine-exact.</p>

<div class="tiles">
<div class="tile"><div class="k">Nowcast fidelity (2005–26)</div><div class="v">+0.40</div><div class="d">Spearman: index level vs trailing-3M gold return</div></div>
<div class="tile"><div class="k">Forward v2, out-of-sample IC</div><div class="v">+0.35</div><div class="d">2019–26 test, vs independent LBMA outcomes</div></div>
<div class="tile"><div class="k">Spot index today</div><div class="v">33.7 <span class="dn">WEAK</span></div><div class="d">gold $4,130, −24% off the Jan top — coherent</div></div>
<div class="tile"><div class="k">Forward signal today</div><div class="v">76.3 <span class="up">BULLISH</span></div><div class="d">real rates 2.31% = 95th pctile → historically 84% up next 3M</div></div>
</div>

<h2>21 years, side by side: the index is a faithful conditions gauge</h2>
<p class="note">High at the 2011, 2020 and 2026 peaks, low at the 2015 and 2022 troughs. That is what a
<i>nowcast</i> should do — and why its label reads HOT/MIXED/WEAK, not buy/sell: readings ≥70 historically
mark <i>stretched</i> conditions (see the bars below). Monthly closes; gold on a log scale.</p>
<div class="card" id="c1">CHART1<div class="tip"></div></div>

<h2>The live test: the 2025 run, the $5,405 top, the −24% crash</h2>
<p class="note">The forward signal called the whole 2025 advance (BULLISH from Jun-25; next-3M outcomes +14…+24%).
It <b>stayed bullish through the Jan–Mar 2026 top and crash</b> — its known blind spot: a parabolic 12-month trend
(+79% ROC) maxes its trend sleeve while real rates stayed high. The spot dial, by design, flashed HOT the day of the top.</p>
<div class="card" id="c2">CHART2<div class="tip"></div></div>
<div class="legend">
<span><span class="sw" style="background:var(--good)"></span>Forward BULLISH ≥70</span>
<span><span class="sw" style="background:var(--neut)"></span>NEUTRAL 45–70</span>
<span><span class="sw" style="background:var(--bad)"></span>BEARISH &lt;45</span>
<span><span class="sw" style="background:var(--warnwash); border:1px solid var(--border)"></span>Spot HOT ≥70 (stretched conditions)</span>
</div>

<h2>What actually followed each label (255 month-ends, 2005–2026)</h2>
<p class="note">Next-63-trading-day LBMA return. The forward label is calibrated and monotone; the spot label is
deliberately the opposite kind of dial — HOT has been a stretch warning (−0.3% mean vs +3.1% base).</p>
<div class="card" id="c3">CHART3<div class="tip"></div></div>

<h2>The same, as a table</h2>
<div class="card tblwrap"><table>
<tr><th>Signal</th><th>Label</th><th>n</th><th>Mean next 3M</th><th>% up</th><th>vs base (+3.1%, 63% up)</th></tr>
<tr><td rowspan="3">Forward v2</td><td>BULLISH</td><td>49</td><td>+8.18%</td><td>84%</td><td>strong positive tilt</td></tr>
<tr><td>NEUTRAL</td><td>113</td><td>+3.11%</td><td>63%</td><td>= base</td></tr>
<tr><td>BEARISH</td><td>92</td><td>+0.30%</td><td>51%</td><td>edge removed (not “crash”)</td></tr>
<tr><td rowspan="3">Spot (conditions)</td><td>HOT ≥70</td><td>38</td><td>−0.31%</td><td>47%</td><td>stretched — worst bucket</td></tr>
<tr><td>MIXED</td><td>156</td><td>+3.63%</td><td>64%</td><td>≈ base</td></tr>
<tr><td>WEAK &lt;45</td><td>61</td><td>+4.24%</td><td>71%</td><td>washed-out — best bucket</td></tr>
</table></div>

<p class="foot">Method: app export <code>gold_index_history.csv</code> (5,393 daily rows, 2005-02-01→2026-07-10) merged with
LBMA PM USD fixes; month-end observations; forward = next 63 LBMA trading days. Forward v2 history is the engine's own
dumped output (research/cache). Verified against research/README.md claims: nowcast +0.401 (claim +0.40), forward IC full
+0.319 / test +0.353 (claims +0.33/+0.38), v1 IC −0.04 (claim −0.05), all four eras positive. GLD input authenticated vs
LBMA: monthly return correlation 0.975, ratio drift −0.43%/yr ≈ GLD's 0.40% expense ratio. FRED inputs identical to fresh
FRED pulls. Signals are probability tilts, not oracles: IC +0.33 ≈ 10% of rank variance.</p>
</div></div>
<script>
const D = DATA_JSON;
function wire(id, geo, rows, fmt){
  const card = document.getElementById(id), svg = card.querySelector('svg'), tip = card.querySelector('.tip');
  const hov = svg.querySelector('.hover');
  if(!hov) {  // bars
    svg.querySelectorAll('.bar').forEach(b=>{
      b.addEventListener('pointerenter',e=>{ tip.textContent=b.dataset.tip; tip.style.display='block'; });
      b.addEventListener('pointermove',e=>{ place(e); });
      b.addEventListener('pointerleave',()=>{ tip.style.display='none'; });
    });
  } else {
    const line = hov.querySelector('.xhair'), dg = hov.querySelector('.hdot-g'), di = hov.querySelector('.hdot-i');
    svg.addEventListener('pointermove', e=>{
      const r = svg.getBoundingClientRect(), sx = 960/r.width;
      const px = (e.clientX - r.left)*sx;
      if(px < geo.xl-8 || px > geo.xr+8){ hov.style.display='none'; tip.style.display='none'; return; }
      const k = Math.max(0, Math.min(geo.n-1, Math.round((px-geo.xl)/(geo.xr-geo.xl)*(geo.n-1))));
      const x = geo.xl + (geo.xr-geo.xl)*k/(geo.n-1);
      hov.style.display=''; line.setAttribute('x1',x); line.setAttribute('x2',x);
      const [d,g,i] = rows[k];
      const gy = paths[id].g(g), iy = paths[id].i(i);
      dg.setAttribute('cx',x); dg.setAttribute('cy',gy); di.setAttribute('cx',x); di.setAttribute('cy',iy);
      tip.innerHTML = '<b>'+d+'</b> · gold <b>$'+Number(g).toLocaleString()+'</b> · index <b>'+i.toFixed(1)+'</b>';
      tip.style.display='block'; place(e);
    });
    svg.addEventListener('pointerleave', ()=>{ hov.style.display='none'; tip.style.display='none'; });
  }
  function place(e){
    const cr = card.getBoundingClientRect();
    let lx = e.clientX-cr.left+14, ly = e.clientY-cr.top-34;
    if(lx > cr.width-190) lx = e.clientX-cr.left-14-tip.offsetWidth;
    tip.style.left=lx+'px'; tip.style.top=Math.max(4,ly)+'px';
  }
}
const LOG=Math.log;
const paths = {
  c1: { g:v=> GBOT1 - (GBOT1-GTOP1)*(LOG(v)-LOG(380))/(LOG(5600)-LOG(380)), i:v=> IBOT1 - (IBOT1-ITOP1)*v/100 },
  c2: { g:v=> ZGBOT - (ZGBOT-ZGTOP)*(v-2300)/(5600-2300), i:v=> ZIBOT - (ZIBOT-ZITOP)*v/100 }
};
wire('c1', D.geo1, D.mo);
wire('c2', D.geo2, D.d2);
wire('c3', null, null);
</script>"""

html = html.replace("CHART1", chart1).replace("CHART2", chart2).replace("CHART3", chart3)
html = html.replace("DATA_JSON", data_js)
html = html.replace("GBOT1", str(g_bot)).replace("GTOP1", str(g_top)).replace("IBOT1", str(i_bot)).replace("ITOP1", str(i_top))
html = html.replace("ZGBOT", str(zg_bot)).replace("ZGTOP", str(zg_top)).replace("ZIBOT", str(zi_bot)).replace("ZITOP", str(zi_top))

open(f"{SP}/gold-index-reality-check.html", "w").write(html)
print(f"written: {len(html)/1024:.0f} KB | chart1 {n1} months, chart2 {n2} days, strip {len(fstrip)} month-ends")
print("hot bands:", hot_svg.count("rect"), "| landmarks:", lm_svg.count("circle"))
