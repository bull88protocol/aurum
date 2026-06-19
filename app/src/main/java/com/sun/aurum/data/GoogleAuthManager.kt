package com.sun.aurum.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps Google Sign-In with the least-privilege `drive.file` scope: the app can touch only the
 * one spreadsheet it creates, never the user's other files. `drive.file` is non-sensitive, so it
 * avoids the OAuth verification the broad `auth/spreadsheets` scope triggers for production.
 * (Testers who signed in under the old scope just sign out/in once; a sheet that becomes
 * inaccessible is transparently re-created by GoogleSheetsClient.)
 *
 * One-time setup the owner does in Google Cloud Console:
 *   1. Create a project (or reuse an existing one)
 *   2. Enable the "Google Sheets API"
 *   3. Create an Android OAuth 2.0 Client ID:
 *      Package name: com.sun.aurum
 *      SHA-1: run  ./gradlew signingReport  to get the debug SHA-1
 *
 * After that, Sign-In works automatically — no API key needed in the code.
 */
class GoogleAuthManager(private val context: Context) {

    private fun buildOptions() = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(SYNC_SCOPE))
        .build()

    private fun client() = GoogleSignIn.getClient(context, buildOptions())

    fun isSignedIn(): Boolean = GoogleSignIn.getLastSignedInAccount(context) != null

    fun getEmail(): String? = GoogleSignIn.getLastSignedInAccount(context)?.email

    fun getSignInIntent(): Intent = client().signInIntent

    fun signOut(): Task<Void> = client().signOut()

    /**
     * Returns a valid OAuth 2.0 Bearer token for the Sheets API.
     * Automatically refreshes if expired.
     * Returns null if not signed in or the Sheets scope was not granted.
     * Must be called from Dispatchers.IO.
     */
    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        val androidAccount = account.account ?: return@withContext null
        try {
            GoogleAuthUtil.getToken(context, androidAccount, "oauth2:$SYNC_SCOPE")
        } catch (e: UserRecoverableAuthException) {
            // drive.file scope not yet granted (e.g. right after the scope change) — the user
            // re-grants by signing in again via Settings.
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val SYNC_SCOPE = "https://www.googleapis.com/auth/drive.file"
        const val RC_SIGN_IN   = 2001
    }
}
