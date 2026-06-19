package com.sun.aurum

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import com.google.android.material.tabs.TabLayoutMediator
import com.sun.aurum.data.BiometricAuth
import com.sun.aurum.databinding.ActivityMainBinding
import com.sun.aurum.ui.QuotePagerAdapter
import com.sun.aurum.ui.SettingsActivity
import com.sun.aurum.worker.DailyRefreshWorker

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var biometricAuth: BiometricAuth

    // Track API-key presence so returning from Settings with a new key auto-refreshes.
    private var lastGeminiPresent = false
    private var lastFredPresent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        biometricAuth = BiometricAuth(this)

        binding.viewPager.adapter = QuotePagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2   // keep all three section tabs alive
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, pos ->
            tab.text = QuotePagerAdapter.TAB_TITLES[pos]
        }.attach()

        // Retry button on the lock overlay
        binding.btnRetryAuth.setOnClickListener { triggerBiometricPrompt() }

        DailyRefreshWorker.schedule(this)

        // Initial load if nothing cached (biometric check in onResume handles the auth gate).
        // Pull-to-refresh on any tab triggers vm.refresh() afterwards.
        if (vm.states.value.values.all { it.lastUpdated == 0L }) vm.refresh()

        lastGeminiPresent = vm.hasGeminiKey
        lastFredPresent   = vm.hasFredKey
    }

    override fun onResume() {
        super.onResume()
        checkBiometricSession()
        // If the user just added/removed a key in Settings, refetch so the new
        // data (AI brief, news, FRED components) shows without a manual refresh.
        val gemini = vm.hasGeminiKey
        val fred   = vm.hasFredKey
        if (gemini != lastGeminiPresent || fred != lastFredPresent) {
            lastGeminiPresent = gemini
            lastFredPresent   = fred
            vm.refresh()
        }
    }

    // ── Biometric auth ────────────────────────────────────────────────────────

    private fun checkBiometricSession() {
        if (!biometricAuth.isAvailable()) { maybeShowGettingStarted(); return }   // no biometric enrolled — skip the gate
        if (biometricAuth.isSessionValid()) { maybeShowGettingStarted(); return } // within 4-hour window — no prompt needed
        showLockOverlay("Touch fingerprint sensor to unlock")
        triggerBiometricPrompt()
    }

    private fun triggerBiometricPrompt() {
        biometricAuth.prompt(
            activity  = this,
            onSuccess = {
                hideLockOverlay()
                // Force-flush Gemini cache and fetch all fresh data on authentication
                vm.refresh(forceGemini = true)
                maybeShowGettingStarted()
            },
            onError = { errorCode ->
                val msg = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON  -> "Tap Try Again to authenticate"
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "Too many attempts. Try again later."
                    else                                   -> "Authentication failed. Tap Try Again."
                }
                showLockOverlay(msg, showRetry = true)
            },
        )
    }

    private fun showLockOverlay(message: String, showRetry: Boolean = false) {
        binding.lockOverlay.visibility = View.VISIBLE
        binding.tvLockMessage.text = message
        binding.btnRetryAuth.visibility = if (showRetry) View.VISIBLE else View.GONE
    }

    private fun hideLockOverlay() {
        binding.lockOverlay.visibility = View.GONE
        binding.btnRetryAuth.visibility = View.GONE
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_clear_cache -> {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Clear Cache")
                    .setMessage("Delete all cached data and fetch everything fresh, including new Gemini analysis?")
                    .setPositiveButton("Clear & Refresh") { _, _ -> vm.clearCacheAndRefresh() }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            R.id.action_help -> {
                showGettingStartedDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Getting Started ─────────────────────────────────────────────────────────

    /** Shows the welcome guide once per install; the Help menu re-opens it anytime. */
    private fun maybeShowGettingStarted() {
        val p = getSharedPreferences("app_settings", MODE_PRIVATE)
        if (p.getBoolean("seen_getting_started", false)) return
        p.edit().putBoolean("seen_getting_started", true).apply()
        showGettingStartedDialog()
    }

    private fun showGettingStartedDialog() {
        val message = """
            Aurum88 Protocol works the moment you open it — live gold price, intraday chart, and a Gold Index built from market data.

            To unlock the full macro picture, add two free keys in Settings:

            🔑  FRED key  (free, instant)
            Adds the Real Yield and Inflation drivers — two of the biggest inputs to the Gold Index.

            🔑  Gemini key  (free tier, no card)
            Adds AI market analysis and daily news.

            🔓  Optional — Sign in with Google
            Syncs your market data to your own Google Sheet. Quotes always use live Yahoo Finance.

            Without the keys:
            The app still runs: the Gold Index uses 3 of its 5 components — USD, Central Bank Demand (built in), and Technicals — and the AI analysis and news sections stay hidden.

            Tip: pull down on any tab to refresh. Step-by-step links to grab both keys are in Settings.
        """.trimIndent()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Getting Started")
            .setMessage(message)
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("Got it", null)
            .show()
    }
}
