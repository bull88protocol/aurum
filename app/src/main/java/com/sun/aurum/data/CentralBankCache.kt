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

    fun load(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.let {
        runCatching { it.readText() }.getOrNull()
    }

    fun save(ctx: Context, json: String) {
        runCatching { file(ctx).writeText(json) }
    }
}
