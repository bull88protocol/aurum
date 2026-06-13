package com.sun.aurum

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.sun.aurum.data.BiometricAuth
import com.sun.aurum.databinding.ActivityMainBinding
import com.sun.aurum.ui.QuotePagerAdapter
import com.sun.aurum.ui.SettingsActivity
import com.sun.aurum.worker.DailyRefreshWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private var refreshBtn: ImageButton? = null
    private val currentTabFlow = MutableStateFlow(0)
    private lateinit var biometricAuth: BiometricAuth

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

        // Track current tab so refresh targets the right symbol
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { currentTabFlow.value = position }
        })

        // Spin the refresh icon while the current tab's symbol is loading
        lifecycleScope.launch {
            // Tabs are sections of one instrument (gold); the spinner tracks that symbol.
            combine(vm.states, currentTabFlow) { states, _ ->
                states[MainViewModel.SYMBOLS.first()]?.loading == true
            }.distinctUntilChanged().collectLatest { loading ->
                if (loading) startSpinning() else stopSpinning()
            }
        }

        // Retry button on the lock overlay
        binding.btnRetryAuth.setOnClickListener { triggerBiometricPrompt() }

        DailyRefreshWorker.schedule(this)

        // Initial load if nothing cached (biometric check in onResume handles the auth gate)
        if (vm.states.value.values.all { it.lastUpdated == 0L }) vm.refresh()
    }

    override fun onResume() {
        super.onResume()
        checkBiometricSession()
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
        val refreshItem = menu.findItem(R.id.action_refresh)
        refreshBtn = refreshItem?.actionView as? ImageButton
        refreshBtn?.setOnClickListener {
            vm.refreshSymbol(MainViewModel.SYMBOLS.first())
        }
        // Sync animation if already loading
        if (vm.states.value[MainViewModel.SYMBOLS.first()]?.loading == true) startSpinning()
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
            Adds the Real Yield (35%) and Inflation (15%) drivers — the two biggest inputs to the Gold Index.

            🔑  Gemini key  (free tier, no card)
            Adds AI market analysis, daily news, and the Central Bank Demand (20%) pillar.

            🔓  Optional — Sign in with Google
            Switches quotes to real-time Google Finance (otherwise Yahoo Finance is used).

            Without the keys:
            The app still runs, but the Gold Index uses only 2 of its 5 pillars (USD + Technicals), and the AI analysis and news sections stay hidden.

            Step-by-step links to grab both keys are in Settings.
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

    // ── Refresh spinner ───────────────────────────────────────────────────────

    private fun startSpinning() {
        val btn = refreshBtn ?: return
        if (btn.animation != null) return   // already spinning
        btn.startAnimation(RotateAnimation(0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 700
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        })
    }

    private fun stopSpinning() {
        refreshBtn?.clearAnimation()
    }
}
