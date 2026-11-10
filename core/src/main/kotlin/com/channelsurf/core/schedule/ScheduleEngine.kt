package com.channelsurf.core.schedule

import com.channelsurf.core.model.BlockKind
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.Lineup
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.ProgramBlock
import com.channelsurf.core.model.SchedulePolicy

/**
 * Deterministic always-on broadcast scheduler.
 *
 * Every channel timeline is a pure function of (lineup revision, channel epoch, UTC instant). There is no
 * per-viewer playhead: tuning in, leaving, killing the app or rebooting cannot change what is "on" at a given
 * instant. All arithmetic uses UTC epoch milliseconds; time zones only affect labels in the guide.
 */
class ScheduleEngine(
    val lineup: Lineup,
    private val allowTestOnly: Boolean = false,
    private val extraApprovedHosts: Set<String> = emptySet(),
) {
    /** Editorially eligible assets, shared by every DAYPARTED channel. Declared first: compile() reads it. */
    private val eligibleAssets: List<MediaAsset> = lineup.assets.values.filter {
        Eligibility.isEligible(it, lineup.eraPolicy, allowTestOnly, extraApprovedHosts, lineup.reviewPolicy)
    }

    /** Same, ignoring broadcast year: specialty channels (Star Trek 24/7…) air recordings of any year. */
    private val anyYearAssets: List<MediaAsset> by lazy {
        lineup.assets.values.filter {
            Eligibility.isEligible(it, ANY_YEAR, allowTestOnly, extraApprovedHosts, lineup.reviewPolicy)
        }
    }

    private val compiled: Map<String, CompiledChannel> =
        lineup.channels.associate { it.id to compile(it) }

    val revision: String get() = lineup.revision

    fun channel(channelId: String): Channel? = lineup.channel(channelId)

    fun isEligible(asset: MediaAsset): Boolean =
        Eligibility.isEligible(asset, lineup.eraPolicy, allowTestOnly, extraApprovedHosts, lineup.reviewPolicy)

    /** True when the channel has at least one airable program in its schedule. */
    fun hasProgramming(channelId: String): Boolean = when (val c = compiled[channelId]) {
        is CompiledChannel.Cyclic -> true
        is CompiledChannel.Fixed -> c.pieces.any { it.kind == BlockKind.PROGRAM }
        is CompiledChannel.Dayparted -> c.scheduler.hasProgramming
        else -> false
    }

    /** The block on [channelId] that contains [nowUtcMs]. Always returns a block (a slate when nothing airs). */
    fun blockAt(channelId: String, nowUtcMs: Long): ProgramBlock {
        val c = compiled[channelId] ?: throw IllegalArgumentException("Unknown channel '$channelId'")
        return when (c) {
            is CompiledChannel.Cyclic -> cyclicBlockAt(c, nowUtcMs)
            is CompiledChannel.Fixed -> fixedBlockAt(c, nowUtcMs)
            is CompiledChannel.Dayparted -> c.scheduler.blockAt(nowUtcMs)
            is CompiledChannel.Empty -> slate(c.channel, hourFloor(nowUtcMs), HOUR_MS, BlockKind.SLATE_NO_PROGRAMMING)
        }
    }

    /** Gapless, non-overlapping blocks covering [fromUtcMs, toUtcMs). */
    fun blocksBetween(channelId: String, fromUtcMs: Long, toUtcMs: Long): List<ProgramBlock> {
        require(toUtcMs >= fromUtcMs) { "Window end before start" }
        val out = ArrayList<ProgramBlock>()
        var b = blockAt(channelId, fromUtcMs)
        var guard = 0
        while (b.startUtcMs < toUtcMs) {
            out += b
            if (++guard > MAX_BLOCKS_PER_WINDOW) break
            b = blockAt(channelId, b.endUtcMs)
        }
        return out
    }

    // ---------------------------------------------------------------- compile

    private sealed interface CompiledChannel {
        val channel: Channel

        class Cyclic(
            override val channel: Channel,
            val assets: List<MediaAsset>,
            /** cumulative start offsets within one cycle */
            val starts: LongArray,
            val cycleMs: Long,
        ) : CompiledChannel

        class Fixed(override val channel: Channel, val pieces: List<Piece>, val periodMs: Long?) : CompiledChannel
        class Dayparted(override val channel: Channel, val scheduler: DaypartScheduler) : CompiledChannel
        class Empty(override val channel: Channel) : CompiledChannel
    }

    /** A FIXED-schedule span relative to the epoch / period start. */
    private data class Piece(
        val startOffsetMs: Long,
        val durationMs: Long,
        val kind: BlockKind,
        val asset: MediaAsset?,
        val sourceOffsetMs: Long,
        val title: String,
        val description: String,
    ) {
        val endOffsetMs get() = startOffsetMs + durationMs
    }

    private fun compile(channel: Channel): CompiledChannel = when (channel.policy) {
        SchedulePolicy.CYCLIC -> {
            val assets = channel.playlist.mapNotNull { lineup.assets[it] }.filter { isEligible(it) }
            if (assets.isEmpty()) CompiledChannel.Empty(channel) else {
                val starts = LongArray(assets.size)
                var acc = 0L
                assets.forEachIndexed { i, a -> starts[i] = acc; acc += a.playDurationMs }
                CompiledChannel.Cyclic(channel, assets, starts, acc)
            }
        }
        SchedulePolicy.FIXED -> compileFixed(channel)
        SchedulePolicy.DAYPARTED -> CompiledChannel.Dayparted(
            channel,
            DaypartScheduler(channel, if (channel.ignoreEra) anyYearAssets else eligibleAssets, lineup.revision),
        )
    }

    private fun compileFixed(channel: Channel): CompiledChannel {
        val pieces = ArrayList<Piece>()
        for (e in channel.fixedEntries.sortedBy { it.startOffsetMs }) {
            if (e.durationMs <= 0) continue
            val asset = lineup.assets[e.assetId]
            if (asset == null || !isEligible(asset)) {
                pieces += Piece(e.startOffsetMs, e.durationMs, BlockKind.SLATE_NO_PROGRAMMING, null, 0, OFF_AIR_TITLE, "")
                continue
            }
            val available = (asset.playDurationMs - e.sourceOffsetMs).coerceAtLeast(0)
            val programMs = minOf(available, e.durationMs)
            if (programMs > 0) {
                pieces += Piece(
                    e.startOffsetMs, programMs, BlockKind.PROGRAM, asset, e.sourceOffsetMs,
                    e.displayTitle ?: asset.title, e.description ?: asset.description,
                )
            }
            if (programMs < e.durationMs) {
                // Media is shorter than the slot: say so honestly rather than looping or stretching.
                pieces += Piece(
                    e.startOffsetMs + programMs, e.durationMs - programMs, BlockKind.SLATE_TECHNICAL_DIFFICULTIES,
                    null, 0, TECHNICAL_TITLE, "",
                )
            }
        }
        return if (pieces.isEmpty()) CompiledChannel.Empty(channel) else CompiledChannel.Fixed(channel, pieces, channel.fixedPeriodMs)
    }

    // ---------------------------------------------------------------- resolve

    private fun cyclicBlockAt(c: CompiledChannel.Cyclic, nowUtcMs: Long): ProgramBlock {
        val elapsed = nowUtcMs - c.channel.epochUtcMs
        val cycleIndex = Math.floorDiv(elapsed, c.cycleMs)
        val pos = Math.floorMod(elapsed, c.cycleMs)
        val i = indexOfStart(c.starts, pos)
        val a = c.assets[i]
        val start = c.channel.epochUtcMs + cycleIndex * c.cycleMs + c.starts[i]
        return ProgramBlock(
            id = blockId(c.channel.id, start),
            channelId = c.channel.id,
            assetId = a.id,
            startUtcMs = start,
            durationMs = a.playDurationMs,
            sourceOffsetMs = 0,
            displayTitle = a.title,
            description = a.description,
            provenance = a.provenance,
            kind = BlockKind.PROGRAM,
            revision = lineup.revision,
        )
    }

    private fun fixedBlockAt(c: CompiledChannel.Fixed, nowUtcMs: Long): ProgramBlock {
        val epoch = c.channel.epochUtcMs
        val period = c.periodMs
        val (base, pos) = if (period != null) {
            val idx = Math.floorDiv(nowUtcMs - epoch, period)
            (epoch + idx * period) to Math.floorMod(nowUtcMs - epoch, period)
        } else {
            epoch to (nowUtcMs - epoch)
        }
        val p = c.pieces.firstOrNull { pos >= it.startOffsetMs && pos < it.endOffsetMs }
        if (p != null) {
            val start = base + p.startOffsetMs
            return ProgramBlock(
                id = blockId(c.channel.id, start),
                channelId = c.channel.id,
                assetId = p.asset?.id,
                startUtcMs = start,
                durationMs = p.durationMs,
                sourceOffsetMs = p.sourceOffsetMs,
                displayTitle = p.title,
                description = p.description,
                provenance = p.asset?.provenance,
                kind = p.kind,
                revision = lineup.revision,
            )
        }
        // Gap between entries: fill with an "off air" slate, bounded to the hour grid so the guide stays tidy.
        val prevEnd = c.pieces.filter { it.endOffsetMs <= pos }.maxOfOrNull { it.endOffsetMs }
        val nextStart = c.pieces.filter { it.startOffsetMs > pos }.minOfOrNull { it.startOffsetMs }
        val gapStart = when {
            prevEnd != null -> base + prevEnd
            period != null -> base
            else -> Long.MIN_VALUE
        }
        val gapEnd = when {
            nextStart != null -> base + nextStart
            period != null -> base + period
            else -> Long.MAX_VALUE
        }
        val hourStart = hourFloor(nowUtcMs)
        val start = maxOf(gapStart, hourStart)
        val end = minOf(gapEnd, hourStart + HOUR_MS)
        return slate(c.channel, start, end - start, BlockKind.SLATE_NO_PROGRAMMING)
    }

    private fun slate(channel: Channel, start: Long, duration: Long, kind: BlockKind) = ProgramBlock(
        id = blockId(channel.id, start),
        channelId = channel.id,
        assetId = null,
        startUtcMs = start,
        durationMs = duration,
        sourceOffsetMs = 0,
        displayTitle = if (kind == BlockKind.SLATE_TECHNICAL_DIFFICULTIES) TECHNICAL_TITLE else OFF_AIR_TITLE,
        description = if (kind == BlockKind.SLATE_NO_PROGRAMMING) NO_PROGRAMMING_DESCRIPTION else "",
        provenance = null,
        kind = kind,
        revision = lineup.revision,
    )

    companion object {
        private val ANY_YEAR = com.channelsurf.core.model.EraPolicy(minYear = 0, maxYear = 9999, preferredMaxYear = 9999)
        const val HOUR_MS = 3_600_000L
        const val MAX_BLOCKS_PER_WINDOW = 50_000
        const val OFF_AIR_TITLE = "Off Air"
        const val TECHNICAL_TITLE = "Technical Difficulties"
        const val NO_PROGRAMMING_DESCRIPTION =
            "No approved recordings are scheduled on this channel yet. Add Archive items in Setup > Curation."

        fun blockId(channelId: String, startUtcMs: Long) = "$channelId@$startUtcMs"
        fun hourFloor(utcMs: Long) = Math.floorDiv(utcMs, HOUR_MS) * HOUR_MS

        /** Largest i such that starts[i] <= pos. */
        internal fun indexOfStart(starts: LongArray, pos: Long): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= pos) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}

object ScheduleValidator {
    /** Returns human-readable problems; empty means the window is gapless and non-overlapping. */
    fun problems(blocks: List<ProgramBlock>, fromUtcMs: Long, toUtcMs: Long): List<String> = buildList {
        if (blocks.isEmpty()) { add("No blocks"); return@buildList }
        if (blocks.first().startUtcMs > fromUtcMs) add("Gap at window start")
        if (blocks.last().endUtcMs < toUtcMs) add("Gap at window end")
        blocks.zipWithNext().forEach { (a, b) ->
            when {
                b.startUtcMs > a.endUtcMs -> add("Gap between ${a.id} and ${b.id}")
                b.startUtcMs < a.endUtcMs -> add("Overlap between ${a.id} and ${b.id}")
            }
        }
        blocks.filter { it.durationMs <= 0 }.forEach { add("Empty block ${it.id}") }
    }
}
