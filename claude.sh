What I just added

  - Gemini "Test" button — sits next to Save/Clear in Settings. Validates the key with a no-cost models-list call → toasts "Gemini key is valid" or "invalid or network error." (FRED
  already had its Test button.)
  - Actionable sign-in errors — a failed Google sign-in now says exactly why. The common one: code 10 — OAuth client not configured for com.sun.aurum + this signing key, pointing you
  to the setup note.
  - On-screen setup note now shows your real debug SHA-1 and the "add yourself as a Test user" step, so the OAuth fix is doable straight from the phone.

  So key entry is now fully verifiable in-app: FRED ✓ test, Gemini ✓ test, Google ✓ clear error.

  ---
  What's remaining on the plan

  A. Blocking real data right now (your actions, no code):
  1. Register the OAuth Android client in Google Cloud (package com.sun.aurum + debug SHA-1 + add yourself as test user). This is the only thing blocking Google sign-in / Sheets. FRED 
  and Gemini just need you to paste + Test your keys — no cloud setup.
  2. Paste your FRED key (unlocks Real Yield + Inflation) and optionally Gemini key (AI brief + central-bank score), then hit Test on each.

  B. Before Play submission:
  3. Screenshots (2–8) from the app: index hero, five-pillar breakdown, history chart, CSV export — now with the new gold theme.
  4. Host PRIVACY.md + TERMS.md at public URLs; paste the privacy URL into the listing.
  5. Data Safety + Content Rating questionnaires (answers are drafted in store/STORE_LISTING.md).
  6. Build & upload the release AAB to an internal-testing track; accept Play App Signing.
  7. Register the release OAuth client — your upload-key SHA-1 (51:24:…:F3) plus the Play App Signing SHA-1 that Play shows after first upload — or Google sign-in breaks in the
  published build.
  8. GitHub commit/push — still deferred per your earlier call; the search-removal deletions and these changes are staged but uncommitted.

  C. Optional roadmap (not required, from the index review):
  9. Ground central-bank demand in real WGC data and give it a forward-signal slot.
  10. Decide percentile vs. absolute scoring as a deliberate product call.

  The fastest path to seeing live numbers today: do A1 + A2. Want me to capture the screenshots (B3) off the phone now, or help with anything in B when you're ready to submit?


