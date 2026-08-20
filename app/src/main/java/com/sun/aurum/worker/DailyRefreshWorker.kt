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
import com.sun.aurum.R
import com.sun.aurum.data.DataRepository
import com.sun.aurum.data.GoogleAuthManager
import com.sun.aurum.data.SecurePrefs
import com.sun.aurum.model.SymbolState
import com.sun.aurum.report.GoldReportContent
import com.sun.aurum.report.GoldReportPdf
import com.sun.aurum.report.ReportActionActivity
import java.io.File
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

        // The whole point of the 9 AM run: turn the day's fetch into a PDF now, while the data is
        // in hand, so tapping the notification opens a finished report instead of relaunching the
        // app and refetching everything.
        val report = GoldReportPdf.generate(
            context      = applicationContext,
            states       = states,
            hasFredKey   = prefs.fredApiKey.isNotBlank(),
            hasGeminiKey = prefs.geminiApiKey.isNotBlank(),
        )
        showNotification(report, states[GoldReportContent.GOLD])
        schedule(applicationContext) // re-schedule for next 9 AM
        return Result.success()
    }

    /**
     * Posts the daily notification. When the report was generated, the tap opens the PDF directly
     * and the actions save or share it — no app launch, no refetch. If generation failed there is
     * nothing to hand over, so it falls back to the old behaviour of opening the app.
     */
    private fun showNotification(report: File?, gold: SymbolState?) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Daily Gold Report", NotificationManager.IMPORTANCE_DEFAULT)
        )

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(GoldReportContent.notificationTitle(System.currentTimeMillis()))
            .setAutoCancel(true)

        if (report == null) {
            builder.setContentText("Today's Gold Index and market brief are ready — tap to view")
                .setContentIntent(openAppIntent(ctx))
        } else {
            builder.setContentText(GoldReportContent.notificationSummary(gold))
                .setStyle(NotificationCompat.BigTextStyle().bigText(GoldReportContent.notificationBigText(gold)))
                .setContentIntent(reportIntent(ctx, report, ReportActionActivity.ACTION_OPEN, RC_OPEN))
                .addAction(0, "Save PDF", reportIntent(ctx, report, ReportActionActivity.ACTION_SAVE, RC_SAVE))
                .addAction(0, "Share", reportIntent(ctx, report, ReportActionActivity.ACTION_SHARE, RC_SHARE))
        }
        nm.notify(1, builder.build())
    }

    private fun reportIntent(ctx: Context, report: File, action: String, requestCode: Int) =
        PendingIntent.getActivity(
            ctx, requestCode,
            ReportActionActivity.intent(ctx, report, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openAppIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, RC_APP,
        Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "aurum_daily"
        // Opaque unique-work key — deliberately NOT renamed when the send time moved from 9 AM to
        // 6 PM (v2.5.0). Renaming it would leave the old 9 AM work enqueued on upgraded installs
        // while a second one was added under the new name, i.e. two reports a day.
        const val WORK_NAME  = "aurum_9am_refresh"

        /**
         * 6 PM ET. The report is about the session that just closed, and 6 PM is the first hour at
         * which every input is final: GLD's daily bar sets at the 4 PM equity close, the Fed's H.15
         * (DGS2 / DFII10 / T10YIE) posts at 4:15 PM and needs a little while to reach FRED, and DXY
         * has settled. It is also exactly when CME gold reopens for the next trading day, so the
         * same moment is both the close of one gold day and the start of the next. A 9 AM send —
         * what this was through v2.4.0 — carried an index and four FRED-backed components that were
         * all a full day stale, since nothing they read changes between 4 PM and the next close.
         */
        private const val SEND_HOUR_ET = 18

        // Distinct request codes so the day's PendingIntents stay distinct from one another, and
        // FLAG_UPDATE_CURRENT so each day's notification points at that day's file.
        private const val RC_APP   = 0
        private const val RC_OPEN  = 1
        private const val RC_SAVE  = 2
        private const val RC_SHARE = 3

        /**
         * The next weekday 6 PM ET at or after [nowMs]. Kept pure — and separate from [schedule] —
         * so the weekend roll and the ET/DST arithmetic are unit-testable without WorkManager.
         */
        internal fun nextSendTimeMs(nowMs: Long): Long {
            val zone = TimeZone.getTimeZone("America/New_York")
            val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
            return Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                set(Calendar.HOUR_OF_DAY, SEND_HOUR_ET); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);                 set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
                // Saturday and Sunday close no US session, so a weekend send would just repeat
                // Friday's report under a weekend date. Roll forward to Monday instead.
                while (get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }.timeInMillis
        }

        fun schedule(context: Context) {
            val now = System.currentTimeMillis()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DailyRefreshWorker>()
                    .setInitialDelay(nextSendTimeMs(now) - now, TimeUnit.MILLISECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(WORK_NAME)
                    .build()
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
