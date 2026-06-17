# Aurum88 Protocol — Google Play Store Listing (draft)

## Identity
- **App name:** Aurum88 Protocol
- **Tagline / promo text:** The Macro Pulse of Gold
- **Package (applicationId):** `com.sun.aurum`
- **Category:** Finance
- **Content rating target:** Everyone (informational finance tool; no UGC, no ads)
- **Contains ads:** No · **In-app purchases:** No

## Short description (≤ 80 chars)
> Read gold like a macro desk — one 0–100 index from yields, the dollar & more.

## Full description (≤ 4000 chars)
**Aurum88 Protocol distills the entire gold-macro picture into one number.**

Gold doesn't move on a single thing — it moves on real yields, the dollar,
central-bank demand, inflation expectations, and price action all at once. Aurum88 Protocol
reads all of them and gives you one clear **Gold Index from 0 to 100**, weighted
the way a commodity macro desk weights them:

• Real yields (the dominant driver)
• US dollar (the inverse gold–dollar pull)
• Central-bank demand (the structural bid)
• Inflation expectations (the hedge bid)
• Price technicals (timing only)

**What you get**
• A single composite Gold Index with a plain-language read
• A five-pillar breakdown so you see *why* the score is where it is
• A forward signal (bullish / neutral / bearish) with its reasoning
• A full-history index chart with regime bands — see where today sits vs the year
• One-tap CSV export of the complete index history, to chart or backtest yourself
• A daily AI market brief and the latest gold news in their own tabs
• Daily background refresh and a market-open notification
• Biometric lock to keep it private

**Your data stays yours.** Aurum88 Protocol runs no servers and collects nothing about you.
It works out of the box on public price data, and unlocks the full macro index
when you add your own free FRED key and (optionally) a Gemini key. Everything is
stored encrypted on your device. Optional sync writes to *your own* Google Sheet.

**Important:** Aurum88 Protocol is an informational tool, **not financial advice.** Data may
be inaccurate or delayed. You are solely responsible for your own decisions.
Trading and investing involve substantial risk of loss.

## Required Play assets
- [x] **App icon** 512×512 — `store/ic_play_512.png`
- [x] **Feature graphic** 1024×500 — `store/feature_graphic_1024x500.png`
- [x] **Phone screenshots** ×4 (1080×2160, ≤2:1) — `store/screenshots/`:
      01 Gold hero, 02 five-component + forward signal, 04 AI brief, 05 news.
- [x] **Privacy Policy** — live at https://www.cointranscend.com/privacy-policy-aurum88-protocol/

## Data safety form (answers)
- Data collected / shared with us: **None.** No backend, no analytics, no ads SDK.
- Data stored on device only; encrypted at rest. CSV export is user-initiated.
- Optional Google sign-in (email) used only as a label + Sheets-sync target.

## Pre-publish checklist
- [ ] Confirm `applicationId = com.sun.aurum` is unique on this Play account.
- [ ] Upload the AAB from `app/build/outputs/bundle/release/app-release.aab`.
- [ ] Confirm Play App Signing accepts the Bull88 upload key for this new app.
- [x] Privacy Policy + Terms hosted and wired into app Settings → About:
      https://www.cointranscend.com/privacy-policy-aurum88-protocol/
      https://www.cointranscend.com/terms-aurum88-protocol/
- [ ] Fill Data Safety + Content Rating questionnaires.
- [ ] Add screenshots, finalize short/full description.
