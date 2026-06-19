package com.sun.aurum.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's API keys + sheet id, encrypted at rest with Android-Keystore AES-256-GCM
 * (see [Crypto]) inside a plain `SharedPreferences`.
 *
 * This replaces the deprecated/alpha Jetpack Security `EncryptedSharedPreferences` as the live
 * store. That library is now used **only once**, to read-and-migrate any keys a previous build
 * saved (see [migrateLegacyIfNeeded]); after that it is never touched again. The
 * `androidx.security:security-crypto` dependency can be dropped entirely in a later release once
 * the migration window has passed.
 */
class SecurePrefs(private val context: Context) {

    private val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
    }

    var geminiApiKey: String
        get() = read(KEY_GEMINI)
        set(v) = write(KEY_GEMINI, v)

    var googleSheetId: String
        get() = read(KEY_SHEET_ID)
        set(v) = write(KEY_SHEET_ID, v)

    var fredApiKey: String
        get() = read(KEY_FRED)
        set(v) = write(KEY_FRED, v)

    private fun read(key: String): String =
        prefs.getString(key, null)?.let { Crypto.decrypt(it) } ?: ""

    private fun write(key: String, value: String) {
        if (value.isBlank()) prefs.edit().remove(key).apply()
        else prefs.edit().putString(key, Crypto.encrypt(value)).apply()
    }

    /**
     * One-time copy of keys saved by an older build into the new Keystore-backed store. Wrapped in
     * runCatching so a failure of the deprecated/alpha library (e.g. a keystore hiccup) degrades to
     * "no migration" — the user just re-enters their keys — rather than crashing on launch.
     */
    private fun migrateLegacyIfNeeded() {
        if (prefs.getBoolean(MIGRATED, false)) return
        runCatching {
            val legacy = EncryptedSharedPreferences.create(
                context,
                LEGACY_STORE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            for (key in listOf(KEY_GEMINI, KEY_FRED, KEY_SHEET_ID)) {
                val v = legacy.getString(key, "") ?: ""
                if (v.isNotBlank()) prefs.edit().putString(key, Crypto.encrypt(v)).apply()
            }
        }
        prefs.edit().putBoolean(MIGRATED, true).apply()
    }

    companion object {
        private const val STORE = "aurum_secure_v2"
        private const val LEGACY_STORE = "aurum_secure"
        private const val MIGRATED = "migrated_from_legacy_v1"
        private const val KEY_GEMINI = "gemini_api_key"
        private const val KEY_SHEET_ID = "google_sheet_id"
        private const val KEY_FRED = "fred_api_key"
    }
}
