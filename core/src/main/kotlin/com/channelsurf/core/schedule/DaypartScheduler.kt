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
        val wanted = (channel.defaultTags + channel.fallbackTags + channel.fillerTags + channel.dayparts.flatMap { it.tags } + TRAILER_TAG).toSet()
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
        return nameUpNext(mergeSlates(out))
    }

    /** "Coming up next" cards name the show that follows. */
    private fun nameUpNext(blocks: List<ProgramBlock>): List<ProgramBlock> = blocks.mapIndexed { i, b ->
        if (b.displayTitle != UP_NEXT) b
        else {
            val next = blocks.getOrNull(i + 1)
            b.copy(displayTitle = if (next != null) "$UP_NEXT: ${next.headline}" else UP_NEXT)
        }
    }

    private fun List<MediaAsset>.totalMs(): Long = sumOf { it.playDurationMs }

    /** Short commercials, promos and bumpers tagged for this channel's station breaks. */
    private fun fillerPool(dayStartUtcMs: Long): List<MediaAsset> {
        val tags = channel.fillerTags.toSet()
        if (tags.isEmpty()) return emptyList()
        val all = eligible.filter { a ->
            a.tags.any { it in tags } && a.playDurationMs in MIN_FILLER_MS..MAX_FILLER_MS &&
                (a.contentType != ContentType.TRAILER || TRAILER_TAG in tags)
        }.sortedBy { it.id }
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
        val cursor = FillerCursor(fillers, fillerStart)
        // No real programs for this block: say so. Never fill hours with nothing but 30-second ads.
        if (pool.isEmpty()) {
            out += slate(w.start, w.end - w.start, BlockKind.SLATE_NO_PROGRAMMING, w.part?.title)
            return cursor.index
        }
        val avg = pool.sumOf { it.playDurationMs } / pool.size
        val perDay = ((w.end - w.start) / avg.coerceAtLeast(60_000L) + 1)
        var idx = epochDay * perDay
        var t = w.start
        var guard = 0
        val streamKey = "$key|${channel.id}"
        while (t < w.end && guard++ < MAX_ITEMS_PER_WINDOW) {
            val remaining = w.end - t
            val look = minOf(pool.size, LOOKAHEAD)
            // 1) the next program whose whole airing (show + breaks, rounded up to the half hour) fits
            var pick: MediaAsset? = null
            var pickedAt = idx
            for (i in 0 until look) {
                val a = streamAt(streamKey, pool, idx + i)
                val need = if (a.contentType.onGrid) slotEndFor(t, a.playDurationMs) - t else a.playDurationMs
                if (need <= remaining) { pick = a; pickedAt = idx + i; break }
            }
            if (pick != null) {
                idx = pickedAt + 1
                if (!pick.contentType.onGrid) {
                    // Music videos, shopping: back to back, like MTV or QVC.
                    out += program(pick, t, pick.playDurationMs, w.part?.title)
                    t += pick.playDurationMs
                    continue
                }
                val programs = arrayListOf(pick)
                var slotEnd = slotEndFor(t, pick.playDurationMs)
                // Two 11-minute cartoons share a half hour, the way Nicktoons and Cartoon Network ran them.
                if (pick.contentType == ContentType.EPISODE && pick.playDurationMs <= PAIR_MAX_MS) {
                    for (i in 0 until look) {
                        val b = streamAt(streamKey, pool, idx + i)
                        if (b.id != pick.id && b.contentType == ContentType.EPISODE &&
                            pick.playDurationMs + b.playDurationMs + MIN_PAIR_BREAKS_MS <= slotEnd - t
                        ) { programs += b; idx += i + 1; break }
                    }
                }
                emitAiring(out, programs, t, slotEnd, w.part?.title, cursor)
                t = slotEnd
                continue
            }
            // 2) an aircheck tape cut at the daypart boundary
            if (remaining >= MIN_TRUNCATED_MS) {
                var tape: MediaAsset? = null
                for (i in 0 until look) {
                    val a = streamAt(streamKey, pool, idx + i)
                    if (a.contentType == ContentType.AIRCHECK_BLOCK) { tape = a; idx += i + 1; break }
                }
                if (tape != null) {
                    out += program(tape, t, minOf(tape.playDurationMs, remaining), w.part?.title)
                    t += minOf(tape.playDurationMs, remaining)
                    continue
                }
            }
            // 3) too little time left for any show: a commercial break / promos, then an "up next" card
            val leftover = fillBreak(out, t, minOf(remaining, MAX_BREAK_MS), w.part?.title, cursor, null)
            t += minOf(remaining, MAX_BREAK_MS) - leftover
            if (leftover > 0 || t < w.end) {
                out += slate(t, w.end - t, BlockKind.SLATE_STATION_BREAK, w.part?.title)
                t = w.end
            }
        }
        if (t < w.end) out += slate(t, w.end - t, BlockKind.SLATE_STATION_BREAK, w.part?.title)
        return cursor.index
    }

    /** The end of a show's airing: its runtime rounded up to the next :00 or :30 (local time). */
    private fun slotEndFor(start: Long, durationMs: Long): Long {
        val end = start + durationMs
        val offset = zone.rules.getOffset(Instant.ofEpochMilli(end)).totalSeconds * 1000L
        val local = end + offset
        val aligned = Math.floorDiv(local + GRID_MS - 1 - GRID_GRACE_MS, GRID_MS) * GRID_MS
        return maxOf(aligned - offset, end)
    }

    private class FillerCursor(val pool: List<MediaAsset>, var index: Long)

    /**
     * Lays out one airing on the grid. A TV episode gets commercial breaks where the network put them (after
     * the cold open, at the middle, before the closing tag) and one between shows; a movie or a whole recorded
     * broadcast plays straight through, followed by trailers or commercials. Whatever can't be filled with
     * whole spots becomes a short "Coming up next" card right before the next show.
     */
    private fun emitAiring(
        out: MutableList<ProgramBlock>, programs: List<MediaAsset>, start: Long, end: Long, daypart: String?, cursor: FillerCursor,
    ) {
        val main = programs.first()
        val slotTitle = programs.joinToString(" / ") { it.guideTitle }
        val slotOut = ArrayList<ProgramBlock>()
        val commercialFree = COMMERCIAL_FREE in channel.fillerTags
        val runtime = programs.sumOf { it.playDurationMs }
        val pad = (end - start - runtime).coerceAtLeast(0)

        // Plan: each program split at its break points; one more break after each program.
        data class Piece(val asset: MediaAsset, val from: Long, val to: Long)
        val pieces = ArrayList<Piece>()
        val breakAfter = ArrayList<Long>() // weight of the break following each piece
        for (p in programs) {
            val cuts = if (commercialFree || main.contentType != ContentType.EPISODE && main.contentType != ContentType.SPECIAL) emptyList()
            else breakPoints(p)
            var from = 0L
            for (c in cuts) {
                pieces += Piece(p, from, c); breakAfter += 1L; from = c
            }
            pieces += Piece(p, from, p.playDurationMs); breakAfter += 2L // the break between shows runs longer
        }
        val totalWeight = breakAfter.sum().coerceAtLeast(1)
        var t = start
        var carry = 0L
        for ((i, piece) in pieces.withIndex()) {
            val len = piece.to - piece.from
            slotOut += program(piece.asset, t, len, daypart, sourceOffsetMs = piece.from)
            t += len
            val last = i == pieces.lastIndex
            val target = if (last) end - t else pad * breakAfter[i] / totalWeight + carry
            if (target <= 0) continue
            val isMovieTail = last && (main.contentType == ContentType.MOVIE)
            val left = fillBreak(slotOut, t, target, daypart, cursor, if (isMovieTail) trailers else null)
            t += target - left
            carry = left
        }
        if (t < end) slotOut += slate(t, end - t, BlockKind.SLATE_STATION_BREAK, daypart).copy(displayTitle = UP_NEXT)
        for (b in slotOut) out += b.copy(slotStartUtcMs = start, slotEndUtcMs = end, slotTitle = slotTitle, slotAssetId = main.id)
    }

    /**
     * Where a broadcast episode's commercial breaks go, as fractions of its runtime, nudged a little per
     * episode so they don't all land on the same second. Same episode, same cuts, every time.
     */
    private fun breakPoints(a: MediaAsset): List<Long> {
        val d = a.playDurationMs
        val fractions = when {
            d < 8 * 60_000L -> emptyList()
            d <= 15 * 60_000L -> listOf(0.5)
            d <= 35 * 60_000L -> listOf(0.10, 0.50, 0.93)           // cold open | act one | act two | tag
            d <= 75 * 60_000L -> listOf(0.07, 0.27, 0.48, 0.69, 0.93) // hour drama: teaser + four acts + tag
            else -> (1 until (d / (13 * 60_000L)).toInt()).map { it * 13.0 * 60_000 / d }
        }
        val rnd = Random(fnv64("cuts|${a.id}"))
        return fractions.map { f -> (d * (f + (rnd.nextDouble() - 0.5) * 0.04)).toLong().coerceIn(30_000L, d - 30_000L) }
            .distinct().sorted()
    }

    /** Movie trailers for the time after a movie. */
    private val trailers: List<MediaAsset> by lazy {
        eligible.filter { it.contentType == ContentType.TRAILER && TRAILER_TAG in it.tags && it.playDurationMs in MIN_FILLER_MS..MAX_FILLER_MS }
            .sortedBy { it.id }
    }

    /**
     * Fills up to [target] ms with whole spots (commercials, promos, bumpers; or [preferred], e.g. trailers).
     * Returns the time it couldn't fill.
     */
    private fun fillBreak(
        out: MutableList<ProgramBlock>, start: Long, target: Long, daypart: String?, cursor: FillerCursor, preferred: List<MediaAsset>?,
    ): Long {
        var t = start
        var left = target
        // After a movie: trailers only (commercials just when there are no trailers at all).
        val sources = if (!preferred.isNullOrEmpty()) listOf(preferred) else listOfNotNull(cursor.pool.takeIf { it.isNotEmpty() })
        var guard = 0
        var trailerIdx = (start / 1000) xor fnv64(channel.id)
        // One spot per upload per break, so a break isn't five Barbie ads from the same collection in a row.
        val usedSources = HashSet<String>()
        while (left >= MIN_FILLER_MS && guard++ < 200) {
            var placed = false
            for (src in sources) {
                val fromPool = src === cursor.pool
                val n = minOf(src.size, LOOKAHEAD)
                val base = if (fromPool) cursor.index else trailerIdx
                var fallback = -1
                for (i in 0 until n) {
                    val f = streamAt(if (fromPool) "filler|${channel.id}" else "trailer|${channel.id}", src, base + i)
                    if (f.playDurationMs > left) continue
                    if (f.archiveIdentifier in usedSources) { if (fallback < 0) fallback = i; continue }
                    fallback = i
                    break
                }
                if (fallback >= 0) {
                    val f = streamAt(if (fromPool) "filler|${channel.id}" else "trailer|${channel.id}", src, base + fallback)
                    out += program(f, t, f.playDurationMs, daypart)
                    t += f.playDurationMs
                    left -= f.playDurationMs
                    usedSources += f.archiveIdentifier
                    if (fromPool) cursor.index += fallback + 1 else trailerIdx += fallback + 1
                    placed = true
                    break
                }
            }
            if (!placed) break
        }
        return left
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

    private fun program(a: MediaAsset, start: Long, dur: Long, daypartTitle: String?, sourceOffsetMs: Long = 0) = ProgramBlock(
        id = ScheduleEngine.blockId(channel.id, start),
        channelId = channel.id,
        assetId = a.id,
        startUtcMs = start,
        durationMs = dur,
        sourceOffsetMs = sourceOffsetMs,
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
                last.daypartTitle == b.daypartTitle && last.endUtcMs == b.startUtcMs &&
                last.slotStartUtcMs == b.slotStartUtcMs && last.displayTitle == b.displayTitle
            ) out[out.size - 1] = last.copy(durationMs = last.durationMs + b.durationMs)
            else out += b
        }
        return out
    }

    companion object {
        const val DAY_START_HOUR = 6
        /** Programs start on the half hour. */
        const val GRID_MS = 30 * 60_000L
        /** A show running at most this far past a half hour still ends there (its last break just gets shorter). */
        const val GRID_GRACE_MS = 0L
        /** Episodes this short can share a half hour with another. */
        const val PAIR_MAX_MS = 14 * 60_000L
        const val MIN_PAIR_BREAKS_MS = 2 * 60_000L
        const val UP_NEXT = com.channelsurf.core.model.ScheduleIds.UP_NEXT
        const val TRAILER_TAG = "movie-trailers"
        /** A channel whose filler tags include this runs shows without commercial breaks (HBO, PBS). */
        const val COMMERCIAL_FREE = "commercial-free"
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
