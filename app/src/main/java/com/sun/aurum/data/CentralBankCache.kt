package com.sun.aurum.data

import android.content.Context
import java.io.File

/**
 * On-disk cache for the hosted central-bank quarterly feed. The feed changes only when WGC
 * publishes a new quarter (~4×/yr), so we refresh weekly and always keep the last-good copy for
 * offline use. Stores the raw JSON body verbatim.
 */
object CentralBankCache {
    private const val FILE = "cb_quarterly_cache.json"
    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun isFresh(ctx: Context): Boolean {
        val f = file(ctx)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS
    }

    /**
     * Force the next [CentralBankClient.loadCached] to re-fetch instead of serving the ≤7-day copy
     * — this is what lets the "Clear Cache" action pull a corrected WGC feed on demand rather than
     * waiting out the weekly TTL. Marks the file stale (rather than deleting it) so the current
     * numbers survive as an offline fallback if that forced fetch fails; hard-deletes only on the
     * rare filesystem that ignores setLastModified.
     */
    fun invalidate(ctx: Context) {
        val f = file(ctx)
        if (!f.exists()) return
        if (!f.setLastModified(System.currentTimeMillis() - TTL_MS - 1_000)) f.delete()
    }

    fun load(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.let {
        runCatching { it.readText() }.getOrNull()
    }

    fun save(ctx: Context, json: String) {
        runCatching { file(ctx).writeText(json) }
    }
}
