package com.sun.aurum.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import com.sun.aurum.R
import com.sun.aurum.data.GoogleAuthManager
import com.sun.aurum.data.SecurePrefs
import com.sun.aurum.databinding.ActivitySettingsBinding
import com.sun.aurum.network.FredClient
import com.sun.aurum.network.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SecurePrefs
    private lateinit var googleAuth: GoogleAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This screen handles the user's API keys: keep it out of screenshots, screen recordings
        // and the recents thumbnail. FLAG_SECURE does *not* hide text from accessibility services
        // (a UI-hierarchy dump still reads the tree), which is why the saved keys below are never
        // written back into the input fields.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The layout supplies the Toolbar this screen never had: Theme.Aurum is NoActionBar, so
        // without this supportActionBar is null and the line below is a silent no-op — no title,
        // no back arrow, and the onSupportNavigateUp() override further down never fires.
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply { title = "Settings"; setDisplayHomeAsUpEnabled(true) }

        // targetSdk 36 enforces edge-to-edge, so this activity draws behind the system bars and
        // the theme can no longer colour them (statusBarColor/navigationBarColor are no-ops there).
        // Pad the toolbar down by the status-bar inset — its surface colour then fills the bar —
        // and pad the scroll view up off the navigation bar. Same shape as MainActivity. Without
        // it the title sits under the status bar and the Terms row under the nav bar.
        // The IME is folded in on purpose. This screen has two key fields, and at targetSdk 35+
        // windowSoftInputMode="adjustResize" no longer resizes an edge-to-edge window — the
        // keyboard would simply cover whichever field is being typed into. Taking the larger of
        // the nav-bar and IME insets gives the scroll view room to bring that field into view.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime  = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.toolbar.updatePadding(top = bars.top)
            binding.settingsScroll.updatePadding(bottom = maxOf(bars.bottom, ime.bottom))
            insets
        }

        prefs      = SecurePrefs(this)
        googleAuth = GoogleAuthManager(this)

        // Version display
        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvVersion.text = versionName

        // Legal links
        binding.tvPrivacyPolicy.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(getString(R.string.privacy_url))))
            }
        }
        binding.tvTerms.setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(getString(R.string.terms_url))))
            }
        }

        // Dark mode toggle
        val appPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        binding.switchDarkMode.isChecked =
            appPrefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_YES) == AppCompatDelegate.MODE_NIGHT_YES
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            val mode = if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            appPrefs.edit().putInt("night_mode", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        // Gemini key — the field starts empty even when a key is stored; only the masked summary
        // is shown. Typing a new key replaces the stored one.
        renderKeyStatus(binding.tvGeminiKeyStatus, prefs.geminiApiKey)
        binding.btnSave.setOnClickListener {
            val entered = binding.etGeminiKey.text.toString().trim()
            if (entered.isBlank()) {
                Toast.makeText(this, "Enter a Gemini API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.geminiApiKey = entered
            binding.etGeminiKey.setText("")
            renderKeyStatus(binding.tvGeminiKeyStatus, prefs.geminiApiKey)
            Toast.makeText(this, "Gemini key saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnClear.setOnClickListener {
            prefs.geminiApiKey = ""
            binding.etGeminiKey.setText("")
            renderKeyStatus(binding.tvGeminiKeyStatus, prefs.geminiApiKey)
            Toast.makeText(this, "Gemini key cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnGeminiTest.setOnClickListener {
            // Test what is being typed; fall back to the stored key when the field is empty.
            val key = binding.etGeminiKey.text.toString().trim().ifBlank { prefs.geminiApiKey }
            if (key.isBlank()) {
                Toast.makeText(this, "Enter a Gemini API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnGeminiTest.isEnabled = false
            binding.btnGeminiTest.text = "Testing…"
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { GeminiClient().testApiKey(key) }
                binding.btnGeminiTest.isEnabled = true
                binding.btnGeminiTest.text = "Test"
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) "Gemini key is valid" else "Gemini key invalid or network error",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // FRED API key — same rule as the Gemini key above: never re-populated into the field.
        renderKeyStatus(binding.tvFredKeyStatus, prefs.fredApiKey)
        binding.btnFredSave.setOnClickListener {
            val entered = binding.etFredKey.text.toString().trim()
            if (entered.isBlank()) {
                Toast.makeText(this, "Enter a FRED API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.fredApiKey = entered
            binding.etFredKey.setText("")
            renderKeyStatus(binding.tvFredKeyStatus, prefs.fredApiKey)
            Toast.makeText(this, "FRED key saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnFredClear.setOnClickListener {
            prefs.fredApiKey = ""
            binding.etFredKey.setText("")
            renderKeyStatus(binding.tvFredKeyStatus, prefs.fredApiKey)
            Toast.makeText(this, "FRED key cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnFredTest.setOnClickListener {
            // Test what is being typed; fall back to the stored key when the field is empty.
            val key = binding.etFredKey.text.toString().trim().ifBlank { prefs.fredApiKey }
            if (key.isBlank()) {
                Toast.makeText(this, "Enter a FRED API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnFredTest.isEnabled = false
            binding.btnFredTest.text = "Testing…"
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { FredClient().testApiKey(key) }
                binding.btnFredTest.isEnabled = true
                binding.btnFredTest.text = "Test"
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) "FRED key is valid" else "FRED key invalid or network error",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        // Google Sign-In
        updateGoogleUI()
        binding.btnGoogleSignIn.setOnClickListener {
            startActivityForResult(googleAuth.getSignInIntent(), GoogleAuthManager.RC_SIGN_IN)
        }
        binding.btnGoogleSignOut.setOnClickListener {
            googleAuth.signOut().addOnCompleteListener {
                prefs.googleSheetId = ""   // forget the sheet so it re-creates next sign-in
                updateGoogleUI()
                Toast.makeText(this, "Signed out from Google", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GoogleAuthManager.RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                updateGoogleUI()
                Toast.makeText(this, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                // 10 = DEVELOPER_ERROR: the OAuth client (package + SHA-1) isn't registered
                // in Google Cloud for this build's signing key.
                val hint = when (e.statusCode) {
                    10    -> "code 10 — Google Sign-In isn't available for this build. Quotes will use Yahoo Finance instead."
                    12501 -> "cancelled"
                    7     -> "network error"
                    else  -> "code ${e.statusCode}"
                }
                Toast.makeText(this, "Sign-in failed: $hint", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateGoogleUI() {
        val signedIn = googleAuth.isSignedIn()
        binding.tvGoogleStatus.text = if (signedIn)
            "Signed in as ${googleAuth.getEmail()}\nSyncing your market data to your own Google Sheet. Quotes use Yahoo Finance (live)."
        else
            "Optional — sign in to sync your data to your own Google Sheet. Quotes use Yahoo Finance either way."
        binding.btnGoogleSignIn.visibility  = if (signedIn) View.GONE  else View.VISIBLE
        binding.btnGoogleSignOut.visibility = if (signedIn) View.VISIBLE else View.GONE
    }

    /**
     * Renders the one line the user gets about a stored key. The key itself never reaches the view
     * tree — only a `•••• 11d2` tail, which is enough to tell two keys apart and to confirm a save
     * landed, but useless to anyone reading the screen or the UI hierarchy.
     */
    private fun renderKeyStatus(label: TextView, key: String) {
        label.text = when {
            key.isBlank()          -> "No key saved yet"
            key.length <= TAIL_LEN -> "Saved · •••• — enter a new key above to replace it"
            else -> "Saved · •••• ${key.takeLast(TAIL_LEN)} — enter a new key above to replace it"
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private companion object {
        /** Trailing characters left visible in a masked key summary. */
        const val TAIL_LEN = 4
    }
}
