package com.sun.aurum.report

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a generated report to the user. Everything here works from the notification without the app
 * being opened — the PDF is served through the existing FileProvider, so the receiving viewer gets a
 * temporary read grant rather than a raw path.
 */
object ReportDelivery {

    const val MIME = "application/pdf"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Opens the PDF in the device's viewer. False when no app can handle a PDF. */
    fun open(context: Context, file: File): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(context, file), MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return context.launch(intent)
    }

    /** Share sheet — the route to Drive, Files, mail, or a chat app. */
    fun share(context: Context, file: File): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return context.launch(Intent.createChooser(intent, "Share gold report"))
    }

    /**
     * Copies the report into the shared Downloads collection, where the Files app and the browser's
     * download list can find it — a genuine "download", and it outlives the app's own 7-report
     * rotation. Scoped storage makes this permission-free on API 29+; older devices would need
     * WRITE_EXTERNAL_STORAGE, which the app deliberately doesn't hold, so callers fall back to
     * [share] when this returns false.
     */
    fun saveToDownloads(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching { insertIntoDownloads(context, file) }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoDownloads(context: Context, file: File): Boolean {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending) ?: return false
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: run { resolver.delete(uri, null, null); return false }
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return false
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return true
    }

    /** A notification tap has no Activity behind it, so those launches need their own task. */
    private fun Context.launch(intent: Intent): Boolean = runCatching {
        if (this !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        true
    }.getOrDefault(false)
}
