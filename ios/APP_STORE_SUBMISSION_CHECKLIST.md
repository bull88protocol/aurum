# App Store Submission Checklist — Aurum88 Protocol (iOS)

Actionable pre-flight for the **first** App Store / TestFlight submission. Pairs with
[`APPLE_RELEASE_PLAN.md`](APPLE_RELEASE_PLAN.md). Check items as you go.

## Accounts & tooling
- [ ] **Mac + Xcode** (latest stable) — hard requirement; iOS can't be built/signed elsewhere.
- [ ] **Apple Developer Program** enrolled ($99/yr), legal entity / name finalized.
- [ ] App record created in **App Store Connect**; **bundle id** locked (`com.bull88.aurum`?).
- [ ] **Automatic signing** working in Xcode (certs + provisioning profiles generated).

## Build & identity
- [ ] Deployment target set (**iOS 16** recommended).
- [ ] App **version** (e.g. 1.0.0) + **build number** scheme decided.
- [ ] App icon **1024×1024** (no alpha, no rounded corners) + all required asset-catalog sizes.
- [ ] Launch screen.
- [ ] Shared-core **parity tests green on the iOS simulator target** (index/HMAI match Android).

## Privacy & compliance  *(the parts that fail review if skipped)*
- [ ] **App Privacy ("nutrition") labels** filled in App Store Connect — honest; mostly *Data Not
      Collected* (BYO-keys, on-device, no backend; Drive writes to the user's own Drive).
- [ ] **`PrivacyInfo.xcprivacy`** added — declare required-reason APIs (UserDefaults, file timestamp)
      + include the Google Sign-In SDK privacy manifest.
- [ ] **Export compliance** — `ITSAppUsesNonExemptEncryption = false` (standard HTTPS only).
- [ ] **Sign in with Apple** — confirmed **not required** because Google is "Connect Drive," not a
      login. UI/metadata wording reviewed to keep it that way.
- [ ] **Privacy policy URL** live (host `PRIVACY.md`) and **support URL / email** set
      (`sunny@bull88protocol.com`).
- [ ] **"Not investment advice"** disclaimer visible in-app and consistent in metadata.
- [ ] Store copy contains **no performance / "always wins" / guaranteed-return claims**.

## Listing assets
- [ ] **Screenshots** — iPhone 6.7"/6.9" (required) + optional 6.5"/iPad; captured from the real UI.
- [ ] **Name**, **subtitle**, **description**, **keywords**, **promotional text**.
- [ ] **Age rating** questionnaire completed (likely 17+ if it references trading/finance — confirm).
- [ ] **Category** (Finance) + secondary.

## Functionality review pass (test on device, release build)
- [ ] Face ID gate works (and graceful fallback if unavailable / not enrolled).
- [ ] API keys persist in **Keychain**; app runs key-less (degraded) without crashing.
- [ ] **Google Drive sync** connects + syncs under `drive.file`; disconnect works.
- [ ] Live Yahoo quotes (incl. pre/after-hours); FRED, Gemini, CB feed all resolve; offline fallback OK.
- [ ] Daily **local notification** fires; **background refresh** registered.
- [ ] Charts render across device sizes, light/dark, large Dynamic Type.
- [ ] No private APIs, no placeholder/"lorem" content, no broken links (Apple Guideline 2.x).

## Beta → release
- [ ] **Internal TestFlight** build green; team install verified.
- [ ] **External TestFlight** — testers added (email/public link = the iOS side of `TESTING.md`);
      passes Beta App Review.
- [ ] Submit for **App Review**; respond to any feedback.
- [ ] **Release** (manual or phased rollout).
- [ ] `README.md` / `TESTING.md` updated: TestFlight link is live.
