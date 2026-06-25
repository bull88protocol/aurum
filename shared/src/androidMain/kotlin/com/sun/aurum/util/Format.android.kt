package com.sun.aurum.util

// Matches the app's prior `String.format("%.Nf", x)` exactly (same default-locale behavior).
actual fun formatDecimals(value: Double, digits: Int): String = String.format("%.${digits}f", value)
