package com.sun.aurum.util

/**
 * Fixed-decimal formatting for shared (commonMain) code, which can't use the JVM's `String.format`.
 * `expect`/`actual` so each platform formats natively: androidMain delegates to `String.format`
 * (byte-identical to the app's prior output); the iOS actual is added on the Mac in Phase 2.
 */
expect fun formatDecimals(value: Double, digits: Int): String
