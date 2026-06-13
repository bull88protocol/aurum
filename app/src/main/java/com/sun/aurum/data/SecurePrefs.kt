package com.sun.aurum.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "aurum_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GEMINI, v).apply()

    // Spreadsheet ID for the auto-created Aurum Market Data sheet
    var googleSheetId: String
        get() = prefs.getString(KEY_SHEET_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SHEET_ID, v).apply()

    var fredApiKey: String
        get() = prefs.getString(KEY_FRED, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FRED, v).apply()

    companion object {
        private const val KEY_GEMINI   = "gemini_api_key"
        private const val KEY_SHEET_ID = "google_sheet_id"
        private const val KEY_FRED     = "fred_api_key"
    }
}
