# iOS — Aurum88 Protocol on Apple

Planning and (soon) source for the **Apple App Store** version of Aurum88 Protocol.

The Android app is live (v2.0.0 on Google Play). This folder holds the plan to bring the same
gold-macro intelligence to iPhone — natively, without forking the model.

## Start here
- **[APPLE_RELEASE_PLAN.md](APPLE_RELEASE_PLAN.md)** — the full engineering + release plan. Read this
  first.
- **[MAC_SETUP.md](MAC_SETUP.md)** — get a Mac (even a borrowed one) ready to build the iOS app and
  sync with the Linux box via GitHub.
- **[APP_STORE_SUBMISSION_CHECKLIST.md](APP_STORE_SUBMISSION_CHECKLIST.md)** — the pre-submission
  checklist for TestFlight + App Store.

## The one-paragraph version
Build a **native SwiftUI** app on a **Kotlin Multiplatform (KMP) shared core**. Share the tested
scoring engines, data clients, and models (one source of truth — the iOS numbers must equal Android's
exactly); rewrite the UI natively for a first-class Apple feel (Face ID, Swift Charts, widgets,
Watch). **Hard prerequisite:** a Mac with Xcode — iOS can't be built or submitted from Linux.

## Status
- 📋 **Planning** — no Xcode project yet. The `Aurum88.xcodeproj` and SwiftUI sources land in Phase 2
  (on the Mac); the KMP `shared/` module is Phase 1 and can start on any OS.

## Testers
iOS ships through **TestFlight**. When the beta opens, email **sunny@bull88protocol.com** to join —
see [`../TESTING.md`](../TESTING.md).
