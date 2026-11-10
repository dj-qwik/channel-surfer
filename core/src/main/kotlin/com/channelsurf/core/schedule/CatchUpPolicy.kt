package com.channelsurf.core.schedule

import kotlin.math.abs

/**
 * Keeps local playback on the broadcast clock without seek-thrashing.
 *
 * Small drift is closed by nudging the playback speed (a few percent, pitch-corrected, so nobody notices).
 * Only a large drift seeks, and right after a tune the bar is higher still: a seek throws the buffer away,
 * and on a slow link that is exactly what caused the stop-start "slideshow" after changing channels.
 */
object CatchUpPolicy {
    /** How long after a tune the player is considered to be settling in. */
    const val SETTLE_WINDOW_MS = 20_000L
    const val SETTLE_SEEK_MS = 10_000L
    const val STEADY_SEEK_MS = 6_000L

    /** Below this, playback is "on time". */
    const val IN_SYNC_MS = 250L
    /** A nudge starts only past this (hysteresis, so the speed doesn't flutter). */
    const val NUDGE_START_MS = 600L
    const val BIG_DRIFT_MS = 2_500L

    const val FAST = 1.04f
    const val FASTER = 1.08f
    const val SLOW = 0.96f
    const val SLOWER = 0.93f

    sealed interface Action {
        data class Speed(val speed: Float) : Action
        data class Seek(val driftMs: Long) : Action
    }

    /**
     * @param driftMs actual file position minus where the broadcast is (negative = behind live).
     * @param sinceTuneMs time since the item was prepared.
     * @param minSeekMs floor raised by repeated catch-up seeks (slow link).
     */
    fun decide(driftMs: Long, sinceTuneMs: Long, minSeekMs: Long, currentSpeed: Float): Action {
        val seekAt = maxOf(if (sinceTuneMs < SETTLE_WINDOW_MS) SETTLE_SEEK_MS else STEADY_SEEK_MS, minSeekMs)
        val a = abs(driftMs)
        if (a > seekAt) return Action.Seek(driftMs)
        if (a <= IN_SYNC_MS) return Action.Speed(1f)
        if (currentSpeed == 1f && a < NUDGE_START_MS) return Action.Speed(1f)
        return Action.Speed(
            if (driftMs < 0) (if (a > BIG_DRIFT_MS) FASTER else FAST)
            else (if (a > BIG_DRIFT_MS) SLOWER else SLOW)
        )
    }

    /**
     * Learns how long this device + connection takes to start a stream, so a tune can start that far
     * ahead of the live point and land on time instead of behind it.
     */
    class StartupEstimator(initialMs: Long = 1_500L) {
        var estimateMs: Long = initialMs
            private set

        fun record(measuredMs: Long) {
            if (measuredMs <= 0) return
            val m = measuredMs.coerceIn(MIN_MS, MAX_MS)
            estimateMs = ((estimateMs * 0.6) + (m * 0.4)).toLong().coerceIn(MIN_MS, MAX_MS)
        }

        companion object {
            const val MIN_MS = 300L
            const val MAX_MS = 6_000L
        }
    }
}
