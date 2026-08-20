package com.sun.aurum.worker

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When the daily report fires. Two things are easy to get wrong and expensive to notice — the
 * weekend roll (a Saturday send would just repeat Friday's report under a Saturday date) and the
 * ET/DST arithmetic (a fixed UTC hour would drift an hour twice a year) — so both are pinned here.
 */
class DailyRefreshScheduleTest {

    private val et = TimeZone.getTimeZone("America/New_York")

    private fun at(etTime: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = et }.parse(etTime)!!.time

    /** The computed send time, rendered back in ET for readable assertions. */
    private fun nextSend(fromEt: String): String =
        SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = et }
            .format(DailyRefreshWorker.nextSendTimeMs(at(fromEt)))

    // ── Same-day vs next-day ──────────────────────────────────────────────────

    @Test
    fun `before 6 PM on a weekday sends the same evening`() {
        // Tue 2026-08-11, mid-session
        assertEquals("Tue 2026-08-11 18:00", nextSend("2026-08-11 14:30"))
    }

    @Test
    fun `after 6 PM on a weekday rolls to the next weekday`() {
        assertEquals("Wed 2026-08-12 18:00", nextSend("2026-08-11 18:30"))
    }

    @Test
    fun `exactly 6 PM does not skip the day`() {
        assertEquals("Tue 2026-08-11 18:00", nextSend("2026-08-11 18:00"))
    }

    // ── Weekend skip ──────────────────────────────────────────────────────────

    @Test
    fun `Friday evening rolls past the weekend to Monday`() {
        assertEquals("Mon 2026-08-17 18:00", nextSend("2026-08-14 19:00"))
    }

    @Test
    fun `Saturday sends on Monday, not Saturday`() {
        assertEquals("Mon 2026-08-17 18:00", nextSend("2026-08-15 09:00"))
        assertEquals("Mon 2026-08-17 18:00", nextSend("2026-08-15 23:00"))
    }

    @Test
    fun `Sunday sends on Monday`() {
        // Before and after 6 PM Sunday — gold futures reopen at 6 PM but no US session has closed.
        assertEquals("Mon 2026-08-17 18:00", nextSend("2026-08-16 12:00"))
        assertEquals("Mon 2026-08-17 18:00", nextSend("2026-08-16 20:00"))
    }

    @Test
    fun `Friday before 6 PM still sends Friday`() {
        assertEquals("Fri 2026-08-14 18:00", nextSend("2026-08-14 10:00"))
    }

    // ── Daylight saving ───────────────────────────────────────────────────────

    @Test
    fun `send time tracks ET across the DST boundary, not a fixed UTC hour`() {
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        // Summer (EDT, UTC-4): 18:00 ET == 22:00 UTC
        assertEquals("2026-08-11 22:00", utc.format(DailyRefreshWorker.nextSendTimeMs(at("2026-08-11 09:00"))))
        // Winter (EST, UTC-5): 18:00 ET == 23:00 UTC
        assertEquals("2026-01-14 23:00", utc.format(DailyRefreshWorker.nextSendTimeMs(at("2026-01-14 09:00"))))
    }
}
