package com.sun.aurum.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.sun.aurum.MainActivity
import com.sun.aurum.MainViewModel
import com.sun.aurum.data.DataRepository
import com.sun.aurum.data.GoogleAuthManager
import com.sun.aurum.data.SecurePrefs
import com.sun.aurum.model.SymbolState
import java.util.*
import java.util.concurrent.TimeUnit

class DailyRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs      = SecurePrefs(applicationContext)
        val googleAuth = GoogleAuthManager(applicationContext)
        val repo       = DataRepository(applicationContext)

        val states = mutableMapOf<String, SymbolState>()
        val accessToken    = googleAuth.getAccessToken()
        val updatedSheetId = repo.fetchAll(
            symbols      = MainViewModel.SYMBOLS,
            accessToken  = accessToken,
            sheetId      = prefs.googleSheetId.ifBlank { null },
            geminiKey    = prefs.geminiApiKey,
            fredKey      = prefs.fredApiKey,
            forceGemini  = true,   // new day — always get fresh briefing
        ) { state -> states[state.symbol] = state }

        if (updatedSheetId != null && updatedSheetId != prefs.googleSheetId) {
            prefs.googleSheetId = updatedSheetId
        }
        repo.saveCache(states)
        showNotification()
        schedule(applicationContext) // re-schedule for next 9 AM
        return Result.success()
    }

    private fun showNotification() {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Quote Updates", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pi = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(1, NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aurum88 Protocol — Market Open")
            .setContentText("Data ready: GOOG · SMH · NVDA · GLD — tap to view")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        const val CHANNEL_ID = "aurum_daily"
        const val WORK_NAME  = "aurum_9am_refresh"

        fun schedule(context: Context) {
            val now    = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"))
            val target = Calendar.getInstance(TimeZone.getTimeZone("America/New_York")).apply {
                set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DailyRefreshWorker>()
                    .setInitialDelay(target.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(WORK_NAME)
                    .build()
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
