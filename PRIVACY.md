# Privacy Policy — Aurum88 Protocol

**Last updated: 2026-06-13**

This Privacy Policy explains how the **Aurum88 Protocol** application ("the App"),
a product of **CoinTranscend** ("we", "us"), handles your data. The App is designed to keep
your data on your device and under your control.

> Plain-language summary: **We run no servers and collect nothing about you.**
> Your data lives on your device. The App talks directly to the data services it
> uses, using **your own** API keys where keys are required. An optional sync
> goes to **your own** Google Sheet. We never see, collect, or sell your data.

## 1. We operate no backend and collect no personal data
The App has **no server of ours**. We do not have a database, we do not receive
telemetry or analytics, and we do not collect, store, or sell any of your
personal or financial information. There is no account with us to create.

## 2. Data stored on your device
The following are stored **locally on your device**, encrypted at rest using
Android's EncryptedSharedPreferences (AES-256):

- Your optional **Gemini** and **FRED** API keys.
- Your optional Google Sheet ID and app settings.
- The email address of the Google account you select (used as a sign-in label and
  as your Sheets-sync target — see §4).
- A local cache of the market and index data the App has fetched, so it can open
  instantly.

These never leave your device except as described below.

**Files you export are stored unencrypted.** When you export the Gold Index
history, the App writes a plain-text **CSV** to your device's shared storage so
you can open, chart, or share it yourself. Like any file in shared storage, it
can be read by other apps you have granted file access. You can delete it any
time with a file manager.

## 3. Data sent to third parties
When you use features that require them, the App connects **directly** from your
device to these services. We are not a party to these connections:

- **Yahoo Finance** — public price/quote and historical candle data for gold (GLD),
  the dollar index, and any symbol you search. No key required.
- **Aurum data file** (GitHub) — the App downloads a small **public** file of World
  Gold Council central-bank net-purchase figures to keep the Gold Index current. This
  is an anonymous one-way download of public data: **no key and nothing about you is
  sent** — only an HTTPS request for the file. No key required.
- **FRED API** (St. Louis Fed) — macroeconomic series (real yields `DFII10`,
  inflation expectations `T10YIE`), under **your own** FRED key.
- **Google Gemini API** — the symbol/topic analyzed (e.g., central-bank and gold
  sentiment), sent under **your own** Gemini key.
- **Google Sheets API** — if you enable sync, your market data is written to
  **your own** Google Sheet under your Google sign-in.

Your use of these services is governed by **their** privacy policies and terms.

## 4. Optional Google Sheets sync (your own sheet)
If you sign in with Google and enable sync, the App creates/updates a Google Sheet
in **your own** Google Drive and writes the market data it displays there. This lives in your Google account; we have no access to it. Sign-in
is optional and the email is used only as a label and sync target. The App requests
only the per-file **`drive.file`** scope, so it can access **only the one sheet it
creates** — never your other Drive files.

## 5. No advertising, no tracking
The App contains no advertising SDKs, no third-party analytics, and no tracking
identifiers used by us.

## 6. Children
The App is not directed to children and is intended for adults.

## 7. Security
Local app data is encrypted at rest (files you export are plain text — see §2);
all network traffic uses HTTPS. No method of storage or transmission is 100%
secure, and you are responsible for the security of your device and your accounts.

## 8. Your control
You can delete all local data by clearing the App's data or uninstalling it. You
can delete the synced sheet from your own Google Drive. Removing the App removes
all on-device data.

## 9. Changes
We may update this Policy; the "Last updated" date will change accordingly.

## 10. Contact
Questions: **sunny@bull88protocol.com**.
