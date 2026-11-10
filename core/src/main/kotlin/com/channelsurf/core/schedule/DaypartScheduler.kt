package com.channelsurf.core.schedule

import com.channelsurf.core.model.BlockKind
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.ContentType
import com.channelsurf.core.model.Daypart
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.ProgramBlock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random

/**
 * Builds one broadcast day (06:00 → 06:00 local, so 23 or 25 hours across DST) for a DAYPARTED channel.
 *
 * Variety without losing determinism: each daypart draws from an endless stream made of seeded shuffles of its
 * pool, and each day starts reading that stream where the previous day would roughly have stopped. So programs
 * rotate through the whole pool before repeating, the lineup is different every day, and every device (and the
 * backend) computes exactly the same day. A recording only joins a pool on broadcast days that start after its
 * [MediaAsset.availableFromUtcMs], so approving new material never reshuffles the day already on the air.
 *
 * Filling a daypart: full programs that fit are placed back to back; whole-tape airchecks may be cut at the
 * daypart's end (like switching away from a live feed); leftover minutes take short commercials/bumpers from the
 * filler pool; anything still left is a brief station-break card. Episodes and movies are never cut.
 * Exception: a pool that is completely empty for the day takes new recordings immediately, so a fresh lineup
 * fills in as discovery finds programs instead of waiting for the next 6 AM rollover.
 */
internal class DaypartScheduler(
    private val channel: Channel,
    allEligible: List<MediaAsset>,
    private val revision: String,
) {
    /** Only the recordings this channel could ever use (its own, borrowed and filler pools): keeps lookups fast. */
    private val eligible: List<MediaAsset> = run {
        val wanted = (channel.defaultTags + channel.fallbackTags + channel.fillerTags + channel.dayparts.flatMap { it.tags }).toSet()
        val ids = channel.dayparts.flatMap { it.assetIds }.toSet()
        allEligible.filter { a -> a.id in ids || a.tags.any { it in wanted } }
    }
    val zone: ZoneId = try { ZoneId.of(channel.timeZone) } catch (e: Exception) { ZoneId.of("America/New_York") }

    private val parts: List<Daypart> = channel.dayparts.sortedBy { it.startMinute }
    private val byId = eligible.associateBy { it.id }

    /** True when at least one daypart or the default pool can ever air something. */
    val hasProgramming: Boolean =
        poolFor(channel.defaultTags, emptySet(), emptyList(), Long.MAX_VALUE).isNotEmpty() ||
            poolFor(channel.fallbackTags, emptySet(), emptyList(), Long.MAX_VALUE).isNotEmpty() ||
            parts.any { poolFor(it.tags, it.preferTypes, it.assetIds, Long.MAX_VALUE).isNotEmpty() }

    private val dayCache = object : LinkedHashMap<Long, List<ProgramBlock>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<ProgramBlock>>?) = size > MAX_CACHED_DAYS
    }
    private val permCache = object : LinkedHashMap<String, IntArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>?) = size > 256
    }

    fun broadcastDate(utcMs: Long): LocalDate {
        val local = Instant.ofEpochMilli(utcMs).atZone(zone).toLocalDateTime()
        return if (local.hour < DAY_START_HOUR) local.toLocalDate().minusDays(1) else local.toLocalDate()
    }

    fun instantOf(date: LocalDate, minuteOfBroadcastDay: Int): Long =
        date.atStartOfDay().plusMinutes(minuteOfBroadcastDay.toLong()).atZone(zone).toInstant().toEpochMilli()

    fun blockAt(utcMs: Long): ProgramBlock {
        val blocks = day(broadcastDate(utcMs))
        var lo = 0
        var hi = blocks.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (blocks[mid].startUtcMs <= utcMs) lo = mid else hi = mid - 1
        }
        return blocks[lo]
    }

    fun day(date: LocalDate): List<ProgramBlock> = synchronized(dayCache) {
        dayCache.getOrPut(date.toEpochDay()) { buildDay(date) }
    }

    // ------------------------------------------------------------------ building

    private data class Window(val start: Long, val end: Long, val part: Daypart?)

    private fun buildDay(date: LocalDate): List<ProgramBlock> {
        val dayStart = instantOf(date, DAY_START_HOUR * 60)
        val dayEnd = instantOf(date.plusDays(1), DAY_START_HOUR * 60)
        val dow = date.dayOfWeek.value

        val windows = ArrayList<Window>()
        var cursor = dayStart
        for (p in parts.filter { dow in it.days }) {
            val s = maxOf(instantOf(date, p.startMinute), cursor)
            val e = minOf(instantOf(date, p.endMinute), dayEnd)
            if (e <= s) continue
            if (s > cursor) windows += Window(cursor, s, null)
            windows += Window(s, e, p)
            cursor = e
        }
        if (cursor < dayEnd) windows += Window(cursor, dayEnd, null)

        val out = ArrayList<ProgramBlock>()
        val fillers = fillerPool(dayStart)
        var fillerIdx = date.toEpochDay() * 40
        for (w in windows) {
            val key = w.part?.id ?: DEFAULT_KEY
            // A block with nothing of its own falls back to the channel's general pool, then to related pools
            // (like a station airing a syndicated rerun) — off the air only when there's truly nothing.
            // A thin pool (a couple of episodes) is topped up the same way, so a channel never loops one show.
            val prefer = w.part?.preferTypes ?: emptySet()
            var pool = poolFor(w.part?.tags ?: channel.defaultTags, prefer, w.part?.assetIds ?: emptyList(), dayStart)
            if (w.part != null && pool.totalMs() < MIN_POOL_MS) pool = (pool + poolFor(channel.defaultTags, prefer, emptyList(), dayStart)).distinctBy { it.id }
            if (pool.totalMs() < MIN_POOL_MS) pool = (pool + poolFor(channel.fallbackTags, prefer, emptyList(), dayStart)).distinctBy { it.id }
            fillerIdx = fill(out, w, key, pool, fillers, date.toEpochDay(), fillerIdx)
        }
        return mergeSlates(out)
    }

    private fun List<MediaAsset>.totalMs(): Long = sumOf { it.playDurationMs }

    /** Short commercials, promos and bumpers tagged for this channel's station breaks. */
    private fun fillerPool(dayStartUtcMs: Long): List<MediaAsset> {
        val tags = channel.fillerTags.toSet()
        if (tags.isEmpty()) return emptyList()
        val all = eligible.filter { a -> a.tags.any { it in tags } && a.playDurationMs in MIN_FILLER_MS..MAX_FILLER_MS }.sortedBy { it.id }
        return all.filter { it.availableFromUtcMs <= dayStartUtcMs - AVAILABILITY_LEAD_MS }.ifEmpty { all }
    }

    /** Eligible assets matching any tag (or listed explicitly), restricted to preferred types when any exist. */
    private fun poolFor(tags: List<String>, prefer: Set<ContentType>, ids: List<String>, dayStartUtcMs: Long): List<MediaAsset> {
        if (tags.isEmpty() && ids.isEmpty()) return emptyList()
        val tagSet = tags.toSet()
        val all = (eligible.filter { a -> a.tags.any { it in tagSet } } + ids.mapNotNull { byId[it] })
            .distinctBy { it.id }
            .filter { !it.contentType.isFiller } // commercials only ever air as fillers or inside tapes
            .filter { it.playDurationMs >= MIN_PROGRAM_MS }
            .sortedBy { it.id }
        // New recordings wait for the next broadcast day so the day on the air never reshuffles — unless this
        // pool had nothing at all today (e.g. a brand-new lineup), in which case they start right away.
        val available = all.filter { it.availableFromUtcMs <= dayStartUtcMs - AVAILABILITY_LEAD_MS }
        val matched = if (available.isEmpty()) all else available
        if (prefer.isEmpty()) return matched
        val preferred = matched.filter { it.contentType in prefer }
        return preferred.ifEmpty { matched }
    }

    private fun fill(
        out: MutableList<ProgramBlock>,
        w: Window,
        key: String,
        pool: List<MediaAsset>,
        fillers: List<MediaAsset>,
        epochDay: Long,
        fillerStart: Long,
    ): Long {
        var fillerIdx = fillerStart
        // No real programs for this block: say so. Never fill hours with nothing but 30-second ads.
        if (pool.isEmpty()) {
            out += slate(w.start, w.end - w.start, BlockKind.SLATE_NO_PROGRAMMING, w.part?.title)
            return fillerIdx
        }
        val avg = if (pool.isEmpty()) 1L else pool.sumOf { it.playDurationMs } / pool.size
        val perDay = if (pool.isEmpty()) 0L else ((w.end - w.start) / avg.coerceAtLeast(60_000L) + 1)
        var idx = epochDay * perDay
        var t = w.start
        var guard = 0
        var breakStart = Long.MAX_VALUE
        while (t < w.end && guard++ < MAX_ITEMS_PER_WINDOW) {
            val remaining = w.end - t
            // 1) a whole program that fits
            var pick: MediaAsset? = null
            var pickedAt = idx
            val look = minOf(pool.size, LOOKAHEAD)
            for (i in 0 until look) {
                val a = streamAt("$key|${channel.id}", pool, idx + i)
                if (a.playDurationMs <= remaining) { pick = a; pickedAt = idx + i; break }
            }
            // 2) an aircheck tape cut at the daypart boundary
            if (pick == null && remaining >= MIN_TRUNCATED_MS) {
                for (i in 0 until look) {
                    val a = streamAt("$key|${channel.id}", pool, idx + i)
                    if (a.contentType == ContentType.AIRCHECK_BLOCK) { pick = a; pickedAt = idx + i; break }
                }
            }
            if (pick != null) {
                breakStart = Long.MAX_VALUE
                val dur = minOf(pick.playDurationMs, remaining)
                out += program(pick, t, dur, w.part?.title)
                t += dur
                idx = pickedAt + 1
                continue
            }
            // 3) station-break fillers — a real break is a few minutes, not hours.
            var filler: MediaAsset? = null
            if (t - breakStart < MAX_BREAK_MS) for (i in 0 until minOf(fillers.size, LOOKAHEAD)) {
                val f = streamAt("filler|${channel.id}", fillers, fillerIdx + i)
                if (f.playDurationMs <= remaining) { filler = f; fillerIdx += i + 1; break }
            }
            if (filler != null) {
                if (breakStart == Long.MAX_VALUE) breakStart = t
                out += program(filler, t, filler.playDurationMs, w.part?.title)
                t += filler.playDurationMs
                continue
            }
            // 4) nothing fits: a short station-break card until the next daypart
            out += slate(t, remaining, if (pool.isEmpty()) BlockKind.SLATE_NO_PROGRAMMING else BlockKind.SLATE_STATION_BREAK, w.part?.title)
            t = w.end
        }
        if (t < w.end) out += slate(t, w.end - t, BlockKind.SLATE_STATION_BREAK, w.part?.title)
        return fillerIdx
    }

    /** Element [index] of the endless stream: concatenated seeded shuffles of [pool]. */
    private fun streamAt(key: String, pool: List<MediaAsset>, index: Long): MediaAsset {
        val n = pool.size
        val k = Math.floorDiv(index, n.toLong())
        val j = Math.floorMod(index, n.toLong()).toInt()
        return pool[permutation(key, pool, k)[j]]
    }

    private fun permutation(key: String, pool: List<MediaAsset>, k: Long): IntArray {
        val sig = "$key#${pool.size}#${pool.first().id}#${pool.last().id}#$k"
        synchronized(permCache) { permCache[sig]?.let { return it } }
        val perm = rawShuffle(key, pool.size, k)
        // Never air the same program twice in a row across a reshuffle boundary.
        if (pool.size > 2 && perm[0] == rawShuffle(key, pool.size, k - 1)[pool.size - 1]) {
            val tmp = perm[0]; perm[0] = perm[1]; perm[1] = tmp
        }
        synchronized(permCache) { permCache[sig] = perm }
        return perm
    }

    private fun rawShuffle(key: String, n: Int, k: Long): IntArray {
        val rnd = Random(fnv64("$key/$k"))
        val a = IntArray(n) { it }
        for (i in n - 1 downTo 1) {
            val r = rnd.nextInt(i + 1)
            val tmp = a[i]; a[i] = a[r]; a[r] = tmp
        }
        return a
    }

    private fun program(a: MediaAsset, start: Long, dur: Long, daypartTitle: String?) = ProgramBlock(
        id = ScheduleEngine.blockId(channel.id, start),
        channelId = channel.id,
        assetId = a.id,
        startUtcMs = start,
        durationMs = dur,
        sourceOffsetMs = 0,
        displayTitle = a.guideTitle,
        description = if (a.series.isNullOrBlank()) a.description else listOf(a.title, a.description).filter { it.isNotBlank() }.joinToString("\n"),
        provenance = a.provenance,
        kind = BlockKind.PROGRAM,
        revision = revision,
        daypartTitle = daypartTitle,
    )

    private fun slate(start: Long, dur: Long, kind: BlockKind, daypartTitle: String?) = ProgramBlock(
        id = ScheduleEngine.blockId(channel.id, start),
        channelId = channel.id,
        assetId = null,
        startUtcMs = start,
        durationMs = dur,
        sourceOffsetMs = 0,
        displayTitle = when (kind) {
            BlockKind.SLATE_STATION_BREAK -> "Station Break"
            BlockKind.SLATE_TECHNICAL_DIFFICULTIES -> ScheduleEngine.TECHNICAL_TITLE
            else -> daypartTitle?.let { "$it (no programs yet)" } ?: ScheduleEngine.OFF_AIR_TITLE
        },
        description = if (kind == BlockKind.SLATE_NO_PROGRAMMING) ScheduleEngine.NO_PROGRAMMING_DESCRIPTION else "",
        provenance = null,
        kind = kind,
        revision = revision,
        daypartTitle = daypartTitle,
    )

    /** Adjacent identical slates merge so the guide shows one cell. */
    private fun mergeSlates(blocks: List<ProgramBlock>): List<ProgramBlock> {
        val out = ArrayList<ProgramBlock>(blocks.size)
        for (b in blocks) {
            val last = out.lastOrNull()
            if (last != null && last.kind == b.kind && b.kind != BlockKind.PROGRAM &&
                last.daypartTitle == b.daypartTitle && last.endUtcMs == b.startUtcMs
            ) out[out.size - 1] = last.copy(durationMs = last.durationMs + b.durationMs)
            else out += b
        }
        return out
    }

    companion object {
        const val DAY_START_HOUR = 6
        const val LOOKAHEAD = 64
        const val MAX_ITEMS_PER_WINDOW = 2_000
        const val MIN_TRUNCATED_MS = 5 * 60_000L
        const val MAX_FILLER_MS = 5 * 60_000L
        /** Shortest spot used as filler (avoids thousands of 2-second "blocks"). */
        const val MIN_FILLER_MS = 10_000L
        /** A block with less than this much to choose from borrows from wider pools. */
        const val MIN_POOL_MS = 6 * 3_600_000L
        /** Longest run of commercials/bumpers between programs. */
        const val MAX_BREAK_MS = 8 * 60_000L
        const val MAX_CACHED_DAYS = 3
        /** Shortest recording that counts as a program (anything shorter is a spot, not a show). */
        const val MIN_PROGRAM_MS = 60_000L
        /** A recording approved during a broadcast day joins from the next broadcast day onward. */
        const val AVAILABILITY_LEAD_MS = 0L
        const val DEFAULT_KEY = "default"

        /** Stable 64-bit FNV-1a; identical on every JVM and Android version. */
        fun fnv64(s: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
