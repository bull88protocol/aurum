package com.sun.aurum.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal AES-256-GCM helper backed by the **Android Keystore** (framework only — no third-party
 * crypto lib). The key is generated once, never leaves the Keystore, and is not auth-bound (so it
 * survives lock-screen/biometric changes; the app's biometric gate is a separate concern). We store
 * `base64(iv ‖ ciphertext)`. minSdk 26 supports all of this.
 *
 * This replaces the deprecated/alpha Jetpack Security `EncryptedSharedPreferences` as the at-rest
 * mechanism — see [SecurePrefs].
 */
internal object Crypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "aurum_secure_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Returns base64(iv ‖ ciphertext). */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** Inverse of [encrypt]; returns null if the blob is missing/corrupt or the key is unavailable. */
    fun decrypt(blob: String): String? = try {
        val data = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_LEN))
        String(cipher.doFinal(data, IV_LEN, data.size - IV_LEN), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
