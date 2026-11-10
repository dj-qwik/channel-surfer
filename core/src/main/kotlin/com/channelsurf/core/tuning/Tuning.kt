package com.channelsurf.core.tuning

import com.channelsurf.core.model.Channel
import java.util.concurrent.atomic.AtomicLong

/** Channel up/down over enabled channels, ordered by number, wrapping like a cable box. */
class ChannelNavigator(channels: List<Channel>) {
    val ordered: List<Channel> = channels.filter { it.enabled }.sortedBy { it.number }

    fun up(currentId: String?): Channel? = step(currentId, +1)
    fun down(currentId: String?): Channel? = step(currentId, -1)
    fun byNumber(number: Int): Channel? = ordered.firstOrNull { it.number == number }
    fun byId(id: String?): Channel? = ordered.firstOrNull { it.id == id }

    private fun step(currentId: String?, delta: Int): Channel? {
        if (ordered.isEmpty()) return null
        val idx = ordered.indexOfFirst { it.id == currentId }
        if (idx < 0) return if (delta > 0) ordered.first() else ordered.last()
        return ordered[Math.floorMod(idx + delta, ordered.size)]
    }
}

/** Remembers the current and previous channel for the LAST button. */
data class TuneHistory(val currentId: String? = null, val previousId: String? = null) {
    fun tuned(channelId: String): TuneHistory =
        if (channelId == currentId) this else TuneHistory(channelId, currentId)
}

/**
 * Numeric channel entry: digits accumulate on screen and commit after [timeoutMs] of inactivity,
 * when [maxDigits] are entered, or on ENTER. Pure logic; the caller supplies time.
 */
class NumberEntry(private val timeoutMs: Long = 1_500, private val maxDigits: Int = 3) {
    private val digits = StringBuilder()
    private var lastPressMs = 0L

    val isActive: Boolean get() = digits.isNotEmpty()
    val display: String get() = if (digits.isEmpty()) "" else digits.toString().padEnd(2, '-')

    /** Returns the committed channel number when this press completes the entry, else null. */
    fun press(digit: Int, nowMs: Long): Int? {
        require(digit in 0..9)
        digits.append(digit)
        lastPressMs = nowMs
        return if (digits.length >= maxDigits) commitNow() else null
    }

    fun deadlineMs(): Long? = if (digits.isEmpty()) null else lastPressMs + timeoutMs

    fun commitIfDue(nowMs: Long): Int? =
        if (digits.isNotEmpty() && nowMs - lastPressMs >= timeoutMs) commitNow() else null

    fun commitNow(): Int? {
        if (digits.isEmpty()) return null
        val n = digits.toString().toIntOrNull()
        digits.clear()
        return n
    }

    fun clear() = digits.clear()
}

/**
 * Guards against channel-change races: each tune gets a token, and work started for an older token
 * (metadata fetch, prepare, error retry) must be discarded.
 */
class TuneSequencer {
    private val generation = AtomicLong(0)
    fun begin(): Long = generation.incrementAndGet()
    fun isCurrent(token: Long): Boolean = generation.get() == token
    val current: Long get() = generation.get()
}
