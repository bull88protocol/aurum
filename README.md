# Aurum88 Protocol — The Macro Pulse of Gold

**Gold, decoded.** A pro-trader reference app that reads gold the way a macro desk
does — a single 0–100 **Gold Index** built from real yields, the dollar,
central-bank demand, inflation, and price technicals, with a forward signal,
history chart, AI market brief, news, and exportable data.

> ## ⚠️ Pre-release BETA — read before installing
>
> - This build is a **beta / testing build**, provided **free** and **as-is**.
> - **Use it entirely at your own risk.** See [TERMS.md](TERMS.md) and
>   [PRIVACY.md](PRIVACY.md) — by installing, you accept them.
> - It is **not** the official release, may contain bugs, and may stop working
>   at any time. **Future updates ship on the Google Play Store version.**
> - This is **not financial or investment advice.** Every trading decision and
>   every API key is yours alone.

---

## What it is

Aurum88 Protocol is a **bring-your-own-keys** gold-market app for Android. It runs
entirely on your device and connects directly to public (and optionally your own)
data services — **there is no backend, and nothing is shipped or shared by us.**
Your keys and data stay on your device. See [PRIVACY.md](PRIVACY.md) for exactly
what is stored where.

## What it does

It distills the gold macro picture into one number and a clear read, across three tabs:

- **Gold** — a composite **0–100 Gold Index** from five components, weighted the way
  a commodity macro desk weights them:
  - **Real yields** — the dominant driver (10-yr TIPS / FRED `DFII10`).
  - **US dollar** — the inverse gold–dollar relationship (DXY).
  - **Central-bank demand** — structural, persistent bid.
  - **Inflation expectations** — the inflation-hedge bid (FRED `T10YIE`).
  - **Price technicals** — timing only.
  Each component is macro-weighted by our proprietary model. Plus a **forward
  signal** (3–6 month lean), a **history chart** with regime bands,
  and one-tap **CSV export** of the full index history.
- **AI Brief** — a daily AI-generated market read: sentiment, last-session recap,
  next-session outlook, and the key factors moving gold *(needs a free Gemini key)*.
- **News** — the day's most relevant gold headlines, with source links
  *(needs a free Gemini key)*.

**Pull down on any tab to refresh.** Data is cached for instant open and auto-refreshes
each morning; adding a key in Settings refreshes the relevant tabs automatically.

> The components above are what the engine weighs; the exact thresholds, regime
> bands, and scoring math are intentionally not published.

## Requirements

- Android **8.0+** (API 26 or newer).
- Works out of the box on **public price data** (Yahoo Finance) — no key needed for
  the gold quote, intraday chart, and the USD + technical parts of the index.
- *(Optional, for the full macro index + AI)* your own **free** keys, added in
  **Settings**:
  - **FRED** key — unlocks the Real Yield and Inflation components (St. Louis Fed).
  - **Gemini** key — unlocks the AI Brief and News. (Central-Bank Demand now ships built-in
    from a WGC net-purchase series — no key needed.)
  The app degrades gracefully without them.
- *(Optional)* a Google account for real-time Google Finance quotes + Sheets sync.

## How to download / install (sideloading the beta)

1. Download [**`aurum-beta.apk`**](aurum-beta.apk) from this repo.
2. When prompted, allow your browser/file manager to **install unknown apps**
   (Android warns you — this is normal for any APK installed outside Play).
3. Open the APK and tap **Install** → **Open**.
4. On first launch, the **Getting Started** guide explains what each key unlocks.
   In **Settings**, paste your free FRED / Gemini keys to unlock the full app
   (optional — it runs without them).

## Build from source

```bash
./gradlew :app:assembleDebug                      # debug APK   -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease                    # signed APK  -> app/build/outputs/apk/release/
./gradlew :app:bundleRelease                      # Play AAB    -> app/build/outputs/bundle/release/
```

Release signing reads `keystore.properties` (gitignored) — see
[plan_release_android.md](plan_release_android.md). The app reuses the shared
**Bull88 upload key**; on Google Play one upload key may sign multiple apps, each
with its own `applicationId` (`com.sun.aurum`) and its own Play-managed signing key.

## Contact

Questions, bug reports, or feedback: **sunny@bull88protocol.com**

## Legal

- [Terms of Use / EULA](TERMS.md)
- [Privacy Policy](PRIVACY.md)

© CoinTranscend · "Aurum88 Protocol" and "Bull88 Protocol" are products of CoinTranscend.
*The Macro Pulse of Gold.*
