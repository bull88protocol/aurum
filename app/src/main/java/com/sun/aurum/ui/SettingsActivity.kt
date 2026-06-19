package com.sun.aurum.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "Settings"; setDisplayHomeAsUpEnabled(true) }

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

        // Gemini key
        binding.etGeminiKey.setText(prefs.geminiApiKey)
        binding.btnSave.setOnClickListener {
            prefs.geminiApiKey = binding.etGeminiKey.text.toString().trim()
            Toast.makeText(this, "Gemini key saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnClear.setOnClickListener {
            prefs.geminiApiKey = ""
            binding.etGeminiKey.setText("")
            Toast.makeText(this, "Gemini key cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnGeminiTest.setOnClickListener {
            val key = binding.etGeminiKey.text.toString().trim()
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

        // FRED API key
        binding.etFredKey.setText(prefs.fredApiKey)
        binding.btnFredSave.setOnClickListener {
            prefs.fredApiKey = binding.etFredKey.text.toString().trim()
            Toast.makeText(this, "FRED key saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnFredClear.setOnClickListener {
            prefs.fredApiKey = ""
            binding.etFredKey.setText("")
            Toast.makeText(this, "FRED key cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnFredTest.setOnClickListener {
            val key = binding.etFredKey.text.toString().trim()
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

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
