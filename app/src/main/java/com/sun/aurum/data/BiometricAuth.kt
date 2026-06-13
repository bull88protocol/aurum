package com.sun.aurum.data

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Manages biometric authentication and the 4-hour session window.
 *
 * Rules:
 * - If biometric is not enrolled on the device: auth is skipped entirely.
 * - On successful auth: session timestamp is saved; no re-prompt for 4 hours.
 * - On successful auth: caller should trigger a force-fresh data refresh.
 */
class BiometricAuth(private val context: Context) {

    companion object {
        private const val PREFS_NAME       = "biometric_session"
        private const val KEY_LAST_AUTH    = "last_auth_ms"
        val SESSION_DURATION_MS            = 4 * 60 * 60 * 1000L  // 4 hours
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True if at least one biometric (fingerprint/face) is enrolled on this device. */
    fun isAvailable(): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS

    /** True if the last successful auth was within the 4-hour window. */
    fun isSessionValid(): Boolean {
        val lastAuth = prefs.getLong(KEY_LAST_AUTH, 0L)
        return System.currentTimeMillis() - lastAuth < SESSION_DURATION_MS
    }

    /** Saves the current time as the last successful auth timestamp. */
    fun recordAuth() {
        prefs.edit().putLong(KEY_LAST_AUTH, System.currentTimeMillis()).apply()
    }

    /**
     * Shows the system biometric prompt.
     * [onSuccess] is called on the main thread after a successful scan.
     * [onError] is called with an error code if the user cancels or auth fails too many times.
     */
    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (errorCode: Int) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                recordAuth()
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(errorCode)
            }
            // onAuthenticationFailed = bad scan; BiometricPrompt retries automatically
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Aurum88 Protocol")
            .setSubtitle("Authenticate to view your dashboard")
            .setNegativeButtonText("Cancel")
            .setAllowedAuthenticators(BIOMETRIC_WEAK)
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(info)
    }
}
