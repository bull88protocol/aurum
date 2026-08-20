package com.sun.aurum.report

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.sun.aurum.MainActivity
import java.io.File

/**
 * Invisible target for the daily notification. It exists so a tap can go straight to the PDF instead
 * of into the app: it resolves the file, hands it off, and finishes before anything is drawn.
 *
 * A trampoline rather than a direct `ACTION_VIEW` PendingIntent because the fallbacks matter — a
 * device with no PDF viewer, or a report that has since been pruned, would otherwise leave the tap
 * doing nothing at all. (Activity trampolines are still permitted on Android 12+; only service and
 * broadcast ones are blocked.)
 */
class ReportActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        if (file == null || !file.exists()) {
            toast("That report is no longer on this device — opening the app to rebuild it.")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_SAVE ->
                if (ReportDelivery.saveToDownloads(this, file)) toast("Saved to Downloads · ${file.name}")
                else if (!ReportDelivery.share(this, file)) toast("Couldn't save the report")
            ACTION_SHARE ->
                if (!ReportDelivery.share(this, file)) toast("No app available to share a PDF")
            else ->
                // No viewer installed: the share sheet still lets the user get the file somewhere useful.
                if (!ReportDelivery.open(this, file) && !ReportDelivery.share(this, file))
                    toast("No PDF viewer installed — install one to read the report")
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val ACTION_OPEN  = "com.sun.aurum.report.OPEN"
        const val ACTION_SAVE  = "com.sun.aurum.report.SAVE"
        const val ACTION_SHARE = "com.sun.aurum.report.SHARE"

        private const val EXTRA_PATH   = "report_path"
        private const val EXTRA_ACTION = "report_action"

        /**
         * Intent equality ignores extras, so PendingIntents built from these would collapse into one
         * another — hence the distinct [action] on each, alongside distinct request codes.
         */
        fun intent(context: Context, file: File, action: String): Intent =
            Intent(context, ReportActionActivity::class.java).apply {
                setAction(action)
                putExtra(EXTRA_PATH, file.absolutePath)
                putExtra(EXTRA_ACTION, action)
            }
    }
}
