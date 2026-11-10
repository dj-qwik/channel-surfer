package com.channelsurf.core.time

/** Wall clock (may jump when the user changes the device time). */
fun interface WallClock { fun nowMs(): Long }

/** Monotonic clock that never jumps (Android: SystemClock.elapsedRealtime()). */
fun interface MonotonicClock { fun elapsedMs(): Long }

/** The single source of "now" for every schedule computation. Always UTC epoch milliseconds. */
interface BroadcastClock {
    fun nowUtcMs(): Long
    val isServerSynced: Boolean
}

/**
 * Broadcast clock anchored to trusted server time when available.
 *
 * After a sync, time advances with the monotonic clock, so a user changing the device clock or time zone
 * cannot move the broadcast. Before any sync (or with no server configured) it falls back to the device
 * wall clock, which gives each device its own clock-synchronized experience.
 */
class SyncedBroadcastClock(
    private val wall: WallClock,
    private val mono: MonotonicClock,
) : BroadcastClock {

    data class Anchor(val utcMs: Long, val monoMs: Long, val roundTripMs: Long)

    @Volatile
    var anchor: Anchor? = null
        private set

    override val isServerSynced: Boolean get() = anchor != null

    override fun nowUtcMs(): Long {
        val a = anchor ?: return wall.nowMs()
        return a.utcMs + (mono.elapsedMs() - a.monoMs)
    }

    /**
     * Applies a server time sample. [serverUtcMs] was produced by the server somewhere between
     * [requestSentMonoMs] and [responseReceivedMonoMs]; assume the midpoint (NTP-style).
     * Returns false and keeps the previous anchor when the sample is unusable.
     */
    fun applyServerSample(serverUtcMs: Long, requestSentMonoMs: Long, responseReceivedMonoMs: Long): Boolean {
        val rtt = responseReceivedMonoMs - requestSentMonoMs
        if (rtt < 0 || rtt > MAX_ROUND_TRIP_MS || serverUtcMs <= 0) return false
        val current = anchor
        // Prefer the sample with the tighter round trip unless the old one is stale.
        if (current != null && rtt > current.roundTripMs * 2 &&
            responseReceivedMonoMs - current.monoMs < STALE_ANCHOR_MS
        ) return false
        anchor = Anchor(serverUtcMs + rtt / 2, responseReceivedMonoMs, rtt)
        return true
    }

    /** Server time minus device wall time, for diagnostics. */
    fun offsetFromWallMs(): Long? = anchor?.let { nowUtcMs() - wall.nowMs() }

    fun restore(anchor: Anchor?) { this.anchor = anchor }

    companion object {
        const val MAX_ROUND_TRIP_MS = 5_000L
        const val STALE_ANCHOR_MS = 6 * 3_600_000L
    }
}
