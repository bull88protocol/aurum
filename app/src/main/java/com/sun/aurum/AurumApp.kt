package com.sun.aurum

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class AurumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = getSharedPreferences("app_settings", MODE_PRIVATE)
            .getInt("night_mode", AppCompatDelegate.MODE_NIGHT_YES)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
