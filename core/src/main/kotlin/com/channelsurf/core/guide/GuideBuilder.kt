package com.channelsurf.core.guide

import com.channelsurf.core.model.BlockKind
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.ProgramBlock
import com.channelsurf.core.schedule.ScheduleEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class GuideSlot(val startUtcMs: Long, val label: String)

data class GuideCell(
    val block: ProgramBlock,
    /** Clipped to the visible window. */
    val visibleStartUtcMs: Long,
    val visibleEndUtcMs: Long,
    val continuesFromLeft: Boolean,
    val continuesToRight: Boolean,
    val isOnNow: Boolean,
) {
    val title: String get() = block.displayTitle
    val visibleDurationMs: Long get() = visibleEndUtcMs - visibleStartUtcMs
    val isSlate: Boolean get() = block.kind != BlockKind.PROGRAM
}

data class GuideRow(val channel: Channel, val cells: List<GuideCell>) {
    fun cellAt(utcMs: Long): GuideCell? =
        cells.firstOrNull { utcMs >= it.visibleStartUtcMs && utcMs < it.visibleEndUtcMs } ?: cells.lastOrNull()
}

/**
 * Builds the 1990s grid guide. Slots are 30 minutes and aligned to LOCAL half hours; slot boundaries
 * advance in UTC so labels are correct across DST transitions (e.g. 1:00, 1:30, 1:00, 1:30 on fall-back night).
 */
object GuideBuilder {
    const val SLOT_MS = 30 * 60 * 1000L

    fun alignToLocalHalfHour(utcMs: Long, zone: ZoneId): Long {
        val z = Instant.ofEpochMilli(utcMs).atZone(zone)
        val aligned = z.withSecond(0).withNano(0).withMinute(if (z.minute < 30) 0 else 30)
        return aligned.toInstant().toEpochMilli()
    }

    fun label(utcMs: Long, zone: ZoneId, locale: Locale = Locale.US): String =
        DateTimeFormatter.ofPattern("h:mm a", locale).format(Instant.ofEpochMilli(utcMs).atZone(zone))

    fun clockLabel(utcMs: Long, zone: ZoneId, locale: Locale = Locale.US): String =
        DateTimeFormatter.ofPattern("h:mm", locale).format(Instant.ofEpochMilli(utcMs).atZone(zone))

    fun dayLabel(utcMs: Long, zone: ZoneId, locale: Locale = Locale.US): String =
        DateTimeFormatter.ofPattern("EEE MMM d", locale).format(Instant.ofEpochMilli(utcMs).atZone(zone))

    fun slots(windowStartUtcMs: Long, count: Int, zone: ZoneId, locale: Locale = Locale.US): List<GuideSlot> =
        (0 until count).map { i ->
            val t = windowStartUtcMs + i * SLOT_MS
            GuideSlot(t, label(t, zone, locale))
        }

    fun rows(
        engine: ScheduleEngine,
        channels: List<Channel>,
        windowStartUtcMs: Long,
        windowEndUtcMs: Long,
        nowUtcMs: Long,
    ): List<GuideRow> = channels.map { ch ->
        val cells = airings(engine.blocksBetween(ch.id, windowStartUtcMs, windowEndUtcMs)).mapNotNull { b ->
            val s = maxOf(b.startUtcMs, windowStartUtcMs)
            val e = minOf(b.endUtcMs, windowEndUtcMs)
            if (e <= s) null else GuideCell(
                block = b,
                visibleStartUtcMs = s,
                visibleEndUtcMs = e,
                continuesFromLeft = b.startUtcMs < windowStartUtcMs,
                continuesToRight = b.endUtcMs > windowEndUtcMs,
                isOnNow = b.contains(nowUtcMs),
            )
        }
        GuideRow(ch, mergeAdjacentSlates(cells))
    }

    /**
     * A show's acts, its commercial breaks and its "up next" card are one listing in the guide, the way a
     * real cable guide read: "7:00 Seinfeld", not every commercial.
     */
    fun airings(blocks: List<ProgramBlock>): List<ProgramBlock> {
        val out = ArrayList<ProgramBlock>(blocks.size)
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            val slot = b.slotStartUtcMs
            if (slot == null) { out += b; i++; continue }
            var j = i
            while (j + 1 < blocks.size && blocks[j + 1].slotStartUtcMs == slot) j++
            // Description of the show itself (its first act), not of a commercial.
            val main = (i..j).map { blocks[it] }.firstOrNull { it.assetId == b.slotAssetId } ?: b
            out += b.slotView(main.description)
            i = j + 1
        }
        return out
    }

    /** Hourly "off air" slates read better as one cell. */
    private fun mergeAdjacentSlates(cells: List<GuideCell>): List<GuideCell> {
        val out = ArrayList<GuideCell>()
        for (c in cells) {
            val last = out.lastOrNull()
            if (last != null && last.isSlate && c.isSlate && last.block.kind == c.block.kind &&
                last.visibleEndUtcMs == c.visibleStartUtcMs
            ) {
                out[out.size - 1] = last.copy(
                    visibleEndUtcMs = c.visibleEndUtcMs,
                    continuesToRight = c.continuesToRight,
                    isOnNow = last.isOnNow || c.isOnNow,
                )
            } else out += c
        }
        return out
    }

    /** Fraction [0,1] of the window at which the "now" line is drawn, or null when outside. */
    fun nowFraction(windowStartUtcMs: Long, windowEndUtcMs: Long, nowUtcMs: Long): Float? {
        if (nowUtcMs < windowStartUtcMs || nowUtcMs >= windowEndUtcMs) return null
        return ((nowUtcMs - windowStartUtcMs).toDouble() / (windowEndUtcMs - windowStartUtcMs)).toFloat()
    }

    /** Read-only progress through a block, for the info banner and guide. */
    fun progress(block: ProgramBlock, nowUtcMs: Long): Float =
        ((nowUtcMs - block.startUtcMs).toDouble() / block.durationMs.coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
}
