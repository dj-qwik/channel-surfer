package com.channelsurf.core.schedule

import com.channelsurf.core.model.BlockKind
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.ProgramBlock
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.model.Segment

enum class SlateKind { NO_PROGRAMMING, TECHNICAL_DIFFICULTIES, STATION_BREAK }

/** What a tuner should do at one instant on one channel. */
sealed interface TuneResolution {
    val channel: Channel
    val block: ProgramBlock
    /** The UTC instant this resolution was computed for. */
    val atUtcMs: Long

    data class Play(
        override val channel: Channel,
        override val block: ProgramBlock,
        override val atUtcMs: Long,
        val asset: MediaAsset,
        val mediaUrl: String,
        /** Position in the media file at [atUtcMs]. */
        val filePositionMs: Long,
        /** Stop/advance when the clock reaches this instant. */
        val playUntilUtcMs: Long,
        val segment: Segment?,
        val isSubstitute: Boolean,
    ) : TuneResolution {
        /** Where the file SHOULD be at [nowUtcMs]: loading/buffering delay is never allowed to put us behind. */
        fun expectedFilePositionMs(nowUtcMs: Long): Long = filePositionMs + (nowUtcMs - atUtcMs)
        fun isFinishedAt(nowUtcMs: Long): Boolean = nowUtcMs >= playUntilUtcMs
    }

    data class Slate(
        override val channel: Channel,
        override val block: ProgramBlock,
        override val atUtcMs: Long,
        val kind: SlateKind,
        val untilUtcMs: Long,
    ) : TuneResolution
}

/**
 * Turns a scheduled block into a concrete playback instruction, applying device-local health:
 * an unhealthy asset is replaced, for the same slot and on the same clock, by a healthy asset from the
 * channel's fallback pool. A substitute that is shorter than the remaining slot is NOT looped; the rest
 * of the slot becomes a "technical difficulties" slate.
 */
class TuneResolver(
    private val engine: ScheduleEngine,
    private val healthOf: (assetId: String) -> HealthStatus = { HealthStatus.UNKNOWN },
    /** Alternate URL to use for an asset (e.g. after a codec failure), or null for its primary URL. */
    private val urlOverride: (assetId: String) -> String? = { null },
) {
    fun resolve(channelId: String, nowUtcMs: Long): TuneResolution {
        val channel = engine.channel(channelId) ?: throw IllegalArgumentException("Unknown channel '$channelId'")
        val block = engine.blockAt(channelId, nowUtcMs)
        val offsetInBlock = nowUtcMs - block.startUtcMs
        when (block.kind) {
            BlockKind.SLATE_NO_PROGRAMMING ->
                return TuneResolution.Slate(channel, block, nowUtcMs, SlateKind.NO_PROGRAMMING, block.endUtcMs)
            BlockKind.SLATE_TECHNICAL_DIFFICULTIES ->
                return TuneResolution.Slate(channel, block, nowUtcMs, SlateKind.TECHNICAL_DIFFICULTIES, block.endUtcMs)
            BlockKind.SLATE_STATION_BREAK ->
                return TuneResolution.Slate(channel, block, nowUtcMs, SlateKind.STATION_BREAK, block.endUtcMs)
            BlockKind.PROGRAM -> Unit
        }
        val scheduled = engine.lineup.assets[block.assetId]
        if (scheduled != null && isAirable(scheduled)) {
            val pos = scheduled.sourceStartMs + block.sourceOffsetMs + offsetInBlock
            return TuneResolution.Play(
                channel, block, nowUtcMs, scheduled, urlOverride(scheduled.id) ?: scheduled.url,
                pos, block.endUtcMs, scheduled.segmentAt(pos), isSubstitute = false,
            )
        }
        val sub = pickSubstitute(channel, block, exclude = block.assetId)
        if (sub != null && offsetInBlock < sub.playDurationMs) {
            val pos = sub.sourceStartMs + offsetInBlock
            return TuneResolution.Play(
                channel, block, nowUtcMs, sub, urlOverride(sub.id) ?: sub.url, pos,
                minOf(block.endUtcMs, block.startUtcMs + sub.playDurationMs), sub.segmentAt(pos), isSubstitute = true,
            )
        }
        return TuneResolution.Slate(channel, block, nowUtcMs, SlateKind.TECHNICAL_DIFFICULTIES, block.endUtcMs)
    }

    private fun isAirable(a: MediaAsset): Boolean =
        a.rightsStatus != RightsStatus.BLOCKED && healthOf(a.id) != HealthStatus.UNHEALTHY && engine.isEligible(a)

    private fun pickSubstitute(channel: Channel, block: ProgramBlock, exclude: String?): MediaAsset? {
        val pool = channel.fallbackPool.distinct()
            .filter { it != exclude }
            .mapNotNull { engine.lineup.assets[it] }
            .filter { isAirable(it) }
        if (pool.isEmpty()) return null
        // Deterministic choice per slot so every device with the same health picks the same substitute.
        val seed = (block.startUtcMs / 1000) xor channel.id.hashCode().toLong()
        return pool[Math.floorMod(seed, pool.size.toLong()).toInt()]
    }
}

/** Decides whether the player is close enough to the live simulated position. */
object DriftPolicy {
    const val DEFAULT_TOLERANCE_MS = 3_000L
    const val TUNE_TOLERANCE_MS = 1_500L

    sealed interface Decision {
        data object InSync : Decision
        data class Seek(val toFilePositionMs: Long, val driftMs: Long) : Decision
        data object AdvanceToNextBlock : Decision
    }

    fun evaluate(
        resolution: TuneResolution.Play,
        nowUtcMs: Long,
        actualFilePositionMs: Long,
        toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    ): Decision {
        if (resolution.isFinishedAt(nowUtcMs)) return Decision.AdvanceToNextBlock
        val expected = resolution.expectedFilePositionMs(nowUtcMs)
        val drift = actualFilePositionMs - expected
        return if (kotlin.math.abs(drift) > toleranceMs) Decision.Seek(expected, drift) else Decision.InSync
    }
}
