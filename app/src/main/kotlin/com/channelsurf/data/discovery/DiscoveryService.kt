package com.channelsurf.data.discovery

import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.archive.ContentClassifier
import com.channelsurf.core.archive.PlayableCandidate
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.ContentType
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.data.archive.ArchiveOutcome
import com.channelsurf.data.archive.ArchiveRepository
import com.channelsurf.data.archive.HealthCheck
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.diagnostics.DiagnosticsLog
import com.channelsurf.playback.PlaybackVerifier
import kotlinx.coroutines.CancellationException

data class DiscoveryReport(
    val channelsSearched: Int,
    val added: Int,
    val skipped: Int,
    val nextChannelIndex: Int,
    val finished: Boolean,
)

/**
 * Keeps the lineup fresh without manual work. For each channel it runs one of the channel's discovery
 * searches (rotating query and result page week to week), then for each new Archive item:
 *  - picks the best stream-ready file and proves byte-range seeking,
 *  - plays it on the device to get the authoritative duration (skips anything that won't decode),
 *  - skips items whose detected broadcast year falls outside 1990–2005,
 *  - classifies it (episode, movie, aircheck tape, commercial…) and tags it for the daypart whose search found it.
 * Added items air from the next broadcast day (today's schedule never reshuffles); channels with nothing
 * on today pick them up immediately. Taken-down and previously
 * rejected items are never re-added.
 */
class DiscoveryService(
    private val archive: ArchiveRepository,
    private val catalog: CatalogRepository,
    private val verifier: PlaybackVerifier,
    private val diagnostics: DiagnosticsLog,
    private val nowUtcMs: () -> Long = System::currentTimeMillis,
) {
    private val era = EraPolicy()

    suspend fun run(
        startChannelIndex: Int = 0,
        perChannel: Int = 6,
        maxNew: Int = 60,
        timeBudgetMs: Long = 9 * 60_000L,
        preferLowBandwidth: Boolean = false,
    ): DiscoveryReport {
        val started = nowUtcMs()
        val lineup = catalog.currentLineup()
        // Emptiest channels first, so stations that are mostly off the air fill in before full ones get more.
        val stock = lineup.channels.associate { c ->
            val tags = (c.defaultTags + c.dayparts.flatMap { it.tags }).toSet()
            c.id to lineup.assets.values.sumOf { a -> if (!a.contentType.isFiller && a.tags.any { it in tags }) a.playDurationMs else 0L }
        }
        val channels = lineup.enabledChannels.filter { it.discoveryQueries.isNotEmpty() }
            .sortedWith(compareBy({ stock[it.id] ?: 0L }, { it.number }))
        if (channels.isEmpty()) return DiscoveryReport(0, 0, 0, 0, true)
        val known = catalog.knownIdentifiers().toMutableSet()
        val day = nowUtcMs() / DAY_MS
        var added = 0
        var skipped = 0
        var searched = 0
        var i = startChannelIndex.coerceIn(0, channels.size)
        while (i < channels.size) {
            if (added >= maxNew || nowUtcMs() - started > timeBudgetMs) break
            val ch = channels[i]
            i++
            searched++
            // A starved channel (under a day of programs) runs every one of its searches; others rotate daily.
            val starved = (stock[ch.id] ?: 0L) < STARVED_MS
            val queries = if (starved) ch.discoveryQueries
            else listOf(ch.discoveryQueries[Math.floorMod(day + ch.number, ch.discoveryQueries.size.toLong()).toInt()])
            var addedHere = 0
            for (query in queries) {
                if (addedHere >= perChannel || added >= maxNew || nowUtcMs() - started > timeBudgetMs) break
                val page = 1 + Math.floorMod(day / ch.discoveryQueries.size, 3L).toInt()
                val results = when (val r = archive.search(query, page, titleOnly = true, rows = 40)) {
                    is ArchiveOutcome.Success -> r.value.docs
                    is ArchiveOutcome.Error -> { diagnostics.warn("discovery", "${ch.displayName}: ${r.message}"); continue }
                }
                for (doc in results) {
                    if (addedHere >= perChannel || added >= maxNew) break
                    if (doc.identifier in known) continue
                    known += doc.identifier
                    if (!ch.ignoreEra && doc.year != null && !era.contains(doc.year!!)) { catalog.dismiss(doc.identifier, "outside era (${doc.year})"); skipped++; continue }
                    try {
                        if (addOne(doc.identifier, ch, query, preferLowBandwidth)) { added++; addedHere++ } else skipped++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        skipped++
                        diagnostics.warn("discovery", "${doc.identifier}: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        if (added > 0) diagnostics.info("discovery", "Added $added new recordings")
        return DiscoveryReport(searched, added, skipped, i, finished = i >= channels.size)
    }

    private suspend fun addOne(identifier: String, ch: Channel, query: String, lowBandwidth: Boolean): Boolean {
        val resolved = when (val r = archive.resolvePlayableFiles(identifier, era, lowBandwidth)) {
            is ArchiveOutcome.Success -> r.value
            is ArchiveOutcome.Error -> { if (r.permanent) catalog.dismiss(identifier, r.message); return false }
        }
        if (!ch.ignoreEra) resolved.year.year?.let { y ->
            if (!era.contains(y)) { catalog.dismiss(identifier, "outside era ($y)"); return false }
        }
        if (resolved.candidates.isEmpty()) { catalog.dismiss(identifier, "no stream-ready files"); return false }

        var chosen: Pair<PlayableCandidate, HealthCheck>? = null
        val alternates = ArrayList<String>()
        for (c in resolved.candidates.take(3)) {
            val h = archive.healthCheckUrl(ArchiveUrls.download(identifier, c.file.name))
            if (h.status == HealthStatus.OK && h.seekable) {
                if (chosen == null) chosen = c to h else alternates += ArchiveUrls.download(identifier, c.file.name)
            }
        }
        val (best, probe) = chosen ?: run { catalog.dismiss(identifier, "no seekable file"); return false }

        // The Archive's own length for its H.264 files is reliable; only play-test files it can't vouch for.
        val knownLength = best.file.lengthMs?.takeIf { it > 0 && best.codec.contains("264") }
        val durationMs = knownLength ?: run {
            val v = verifier.verify(ArchiveUrls.download(identifier, best.file.name), timeoutMs = 20_000)
            if (!v.ok) { catalog.dismiss(identifier, "does not play: ${v.message}"); return false }
            v.durationMs
        }

        val item = resolved.item
        val cls = ContentClassifier.classify(item.title, item.description, item.subjects, durationMs)
        val tags = tagsFor(ch, query, cls.type)
        catalog.addDiscovered(
            resolved = if (ch.ignoreEra) resolved.copy(year = resolved.year.copy(year = null)) else resolved,
            candidate = best, probe = probe, verifiedAlternates = alternates,
            durationMs = durationMs, contentType = cls.type, series = cls.series, tags = tags,
            availableFromUtcMs = nowUtcMs(),
        )
        addSiblingEpisodes(resolved, best, probe, ch, query, cls.series ?: item.title, if (ch.ignoreEra) null else resolved.year.year)
        return true
    }

    /**
     * A season upload holds many episodes; take them all (up to a limit) instead of only the first file, using the
     * Archive's own lengths. Same item and server, so the first file's seek test stands for the rest.
     */
    private suspend fun addSiblingEpisodes(
        resolved: com.channelsurf.data.archive.ResolvedItem, best: PlayableCandidate, probe: HealthCheck,
        ch: Channel, query: String, series: String, year: Int?,
    ) {
        fun base(n: String) = n.substringBeforeLast('.').removeSuffix(".ia").removeSuffix("_512kb")
        val seen = hashSetOf(base(best.file.name))
        val siblings = resolved.candidates
            .filter { it.codec.contains("264") && !it.lowBandwidth && (it.file.lengthMs ?: 0) in MIN_EPISODE_MS..MAX_EPISODE_MS }
            .filter { seen.add(base(it.file.name)) }
            .take(MAX_SIBLINGS)
        if (siblings.isEmpty()) return
        // One database change for the whole season, not one per episode.
        catalog.batch { addSiblings(siblings, resolved, probe, ch, query, series, year) }
    }

    private suspend fun addSiblings(
        siblings: List<PlayableCandidate>, resolved: com.channelsurf.data.archive.ResolvedItem, probe: HealthCheck,
        ch: Channel, query: String, series: String, year: Int?,
    ) {
        fun base(n: String) = n.substringBeforeLast('.').removeSuffix(".ia").removeSuffix("_512kb")
        for (c in siblings) {
            val dur = c.file.lengthMs ?: continue
            val name = base(c.file.name.substringAfterLast('/')).replace(Regex("[._]+"), " ").replace(Regex("\\[[^\\]]*\\]"), " ").replace(Regex("\\s+"), " ").trim()
            val type = ContentClassifier.classify(name, null, emptyList(), dur).type.let { if (it == ContentType.OTHER) ContentType.EPISODE else it }
            catalog.addDiscovered(
                resolved = resolved.copy(year = resolved.year.copy(year = year)),
                candidate = c, probe = probe, verifiedAlternates = emptyList(),
                durationMs = dur, contentType = type, series = series, tags = tagsFor(ch, query, type),
                availableFromUtcMs = nowUtcMs(), titleOverride = name,
            )
        }
    }

    companion object {
        const val WEEK_MS = 7 * 86_400_000L
        const val DAY_MS = 86_400_000L
        const val MAX_SIBLINGS = 120
        const val MIN_EPISODE_MS = 8 * 60_000L
        const val MAX_EPISODE_MS = 4 * 3_600_000L
        /** A channel with less than a day of programs is searched hard. */
        const val STARVED_MS = 24 * 3_600_000L

        /**
         * Tags a find for the daypart whose name appears in the search that found it (e.g. a "toonami" search
         * feeds the Toonami block), else the channel's main pool. Short spots become station-break filler.
         */
        fun tagsFor(ch: Channel, query: String, type: ContentType): List<String> {
            if (type == ContentType.PROMO_BUMPER) return listOfNotNull(ch.fillerTags.firstOrNull())
            if (type == ContentType.COMMERCIAL) return listOf("commercials-90s")
            val q = query.lowercase()
            val daypart = ch.dayparts.firstOrNull { d ->
                val words = d.title.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.length > 2 }
                words.isNotEmpty() && words.all { it in q } || d.tags.any { it.replace('-', ' ') in q }
            }
            val primary = daypart?.tags?.firstOrNull() ?: ch.defaultTags.firstOrNull()
            return listOfNotNull(primary, ch.defaultTags.firstOrNull()).distinct()
        }
    }
}
