package com.sun.aurum.data

import android.content.Context
import com.sun.aurum.model.GeminiResult
import org.json.JSONObject
import java.io.File

/**
 * Per-symbol cache for gold brief results, whatever produced them — a call with the user's own
 * Gemini key or the hosted feed. Valid for 8 hours (one trading day).
 *
 * [save] records where the brief came from and when it was generated, so the AI Brief tab can say
 * so: a feed brief is anchored to the price at the time it was written, which may be an hour or
 * more behind the quote the app is showing above it.
 */
object GeminiCache {

    private const val CACHE_FILE = "gemini_brief_cache.json"
    private const val MAX_AGE_MS = 8 * 60 * 60 * 1000L

    /** Where a cached brief came from. */
    enum class Origin { OWN_KEY, FEED }

    data class Entry(
        val result: GeminiResult,
        val origin: Origin,
        val generatedUtc: String?,
        /** How long ago this entry was written, in ms. */
        val ageMs: Long,
    )

    fun save(context: Context, symbol: String, result: GeminiResult,
             origin: Origin = Origin.OWN_KEY, generatedUtc: String? = null) {
        try {
            val all = loadRaw(context)
            all.put(symbol, JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("origin", origin.name)
                if (generatedUtc != null) put("gen", generatedUtc)
                put("data", GeminiResultJson.encode(result))
            })
            File(context.filesDir, CACHE_FILE).writeText(all.toString())
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        try { File(context.filesDir, CACHE_FILE).delete() } catch (_: Exception) {}
    }

    fun load(context: Context, symbol: String): GeminiResult? = loadEntry(context, symbol)?.result

    fun loadEntry(context: Context, symbol: String): Entry? {
        return try {
            val entry = loadRaw(context).optJSONObject(symbol) ?: return null
            val age   = System.currentTimeMillis() - entry.getLong("ts")
            if (age > MAX_AGE_MS) return null
            Entry(
                result       = GeminiResultJson.decode(entry.getJSONObject("data")),
                // Entries written before v2.9.0 carry no origin; they can only have been own-key.
                origin       = runCatching { Origin.valueOf(entry.optString("origin")) }.getOrDefault(Origin.OWN_KEY),
                generatedUtc = entry.optString("gen").takeIf { it.isNotBlank() },
                ageMs        = age,
            )
        } catch (_: Exception) { null }
    }

    private fun loadRaw(context: Context): JSONObject {
        val file = File(context.filesDir, CACHE_FILE)
        return if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        else JSONObject()
    }
}
