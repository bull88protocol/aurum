# Mac setup — building the iOS app (Phase 2+)

How to get a Mac (even a borrowed one) ready to build Aurum88's iOS app and sync with the Linux box.
The two machines never talk directly — **GitHub is the bridge.** You push from one, pull on the
other.

```
 Linux (KMP shared core + Android)  ──push──▶  GitHub  ◀──pull──  Mac (SwiftUI + iOS build + submit)
```

> **Golden rule on a borrowed Mac:** commit and push constantly. Nothing important should ever live
> only on the friend's machine — once it's pushed it's safe on GitHub and you can hand the laptop back.

## One-time setup (~30–45 min)
1. **Xcode** — install from the Mac App Store (large download). Open it once to finish component
   install + accept the license.
2. **JDK + Android Studio** (or just the JDK) — the KMP `shared/` module is built by Gradle, which
   Xcode invokes during the iOS build. Android Studio bundles a JDK and the Kotlin/KMP tooling.
3. **Claude Code** — install so you have AI help on the Mac too:
   ```bash
   npm install -g @anthropic-ai/claude-code   # or the macOS installer
   ```
   Run it **inside the cloned repo** — it auto-loads `CLAUDE.md`, so it starts with full project
   context. (Claude Code's *memory* does not sync between machines; the repo's `CLAUDE.md` + the
   `ios/` docs are the shared context.)
4. **Clone the repo:**
   ```bash
   git clone git@github.com:bull88protocol/aurum.git
   cd aurum
   git checkout ios-port      # the iOS migration branch (create it on Linux when Phase 1 starts)
   ```
5. **Apple ID in Xcode** — Xcode ▸ Settings ▸ Accounts ▸ **add your Apple ID**. Xcode supports
   multiple accounts, so you don't disturb your friend's. Your builds upload to *your* App Store
   Connect / TestFlight.

## Building
- The Xcode project builds the KMP shared framework from source via a Gradle build phase — so after a
  `git pull`, just **build in Xcode** and the latest `shared/` core is baked in. No manual framework
  copying.
- **Simulator needs no paid account.** You only need the **Apple Developer Program ($99/yr)** to run
  on a physical iPhone or upload to TestFlight.

## Working back-and-forth
- Phase 2+ (SwiftUI, iOS build) happens **on the Mac**, committed on `ios-port`, pushed.
- Shared-core / Android changes happen **on Linux**, pushed to the same branch; `git pull` on the Mac.
- You can read/edit Swift on Linux, but it only compiles/previews on the Mac.

## Before returning a borrowed Mac
- Xcode ▸ Settings ▸ Accounts ▸ **remove your Apple ID**.
- Delete any local secrets you copied in (`keystore.properties`, API keys). `.gitignore` keeps them
  out of git, but local copies on the Mac should go.
- Confirm everything is pushed: `git status` clean, `git log origin/ios-port` shows your latest.

## Reducing how often you need the Mac (later)
Once the Xcode project exists, **Xcode Cloud** or **Codemagic** can build + push to TestFlight from
the cloud — so routine releases don't need the Mac in hand, only interactive UI work does.
