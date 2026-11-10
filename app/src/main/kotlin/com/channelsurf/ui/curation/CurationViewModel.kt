package com.channelsurf.ui.curation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.channelsurf.BuildConfig
import com.channelsurf.ChannelSurfApplication
import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.archive.SearchDoc
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.ReviewPolicy
import com.channelsurf.core.schedule.Eligibility
import com.channelsurf.data.archive.ArchiveOutcome
import com.channelsurf.data.catalog.CatalogAsset
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.data.local.TakedownEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val page: Int = 1,
    val numFound: Long = 0,
    val results: List<SearchDoc> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val stale: Boolean = false,
)

data class AssetRow(val item: CatalogAsset, val ineligibleReasons: List<String>) {
    val eligible: Boolean get() = ineligibleReasons.isEmpty()
}

class CurationViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ChannelSurfApplication).container
    private val era = EraPolicy()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    private val _busy = MutableStateFlow<String?>(null)
    /** Human-readable description of the long-running operation, if any. */
    val busy: StateFlow<String?> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val assets: StateFlow<List<AssetRow>> = combine(c.catalog.catalogAssets, c.settings.settings) { list, s ->
        val policy = if (s.autoApprove) ReviewPolicy.AUTOMATIC else ReviewPolicy.MANUAL
        list.map { AssetRow(it, Eligibility.reasons(it.asset, era, BuildConfig.ALLOW_TEST_ASSETS, policy = policy) + playbackReason(it) + poolReason(it)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val channels: StateFlow<List<Channel>> = c.catalog.lineup.map { it.channels.sortedBy { ch -> ch.number } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val takedowns: StateFlow<List<TakedownEntity>> = c.catalog.takedowns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** On a dayparted lineup a recording airs only once it carries a channel/daypart tag. */
    private fun poolReason(a: CatalogAsset): List<String> =
        if (a.asset.tags.isEmpty() && channels.value.none { ch -> a.asset.id in ch.playlist || a.asset.id in ch.fallbackPool }) {
            listOf("Not assigned to a channel")
        } else emptyList()

    private fun playbackReason(a: CatalogAsset): List<String> =
        if (a.playbackVerified) emptyList() else listOf("Playback not verified on this device")

    fun setQuery(q: String) = _search.update { it.copy(query = q.take(300)) }

    fun runSearch(page: Int = 1) {
        val q = _search.value.query
        _search.update { it.copy(loading = true, error = null, page = page) }
        viewModelScope.launch {
            when (val r = c.archive.search(q, page)) {
                is ArchiveOutcome.Success -> _search.update {
                    it.copy(loading = false, results = r.value.docs, numFound = r.value.numFound, stale = r.fromStaleCache)
                }
                is ArchiveOutcome.Error -> _search.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /**
     * Resolves an Archive item, probes its best playable files, adds the best reachable one to the review
     * queue (with verified alternates), then proves on-device playback and records the decoded duration.
     */
    fun addIdentifier(rawIdentifier: String) {
        val identifier = rawIdentifier.trim().removePrefix("https://archive.org/details/").substringBefore('/').substringBefore('?')
        if (!ArchiveUrls.isValidIdentifier(identifier)) return say("“$rawIdentifier” is not a valid Archive identifier")
        if (_busy.value != null) return
        viewModelScope.launch {
            try {
                _busy.value = "Reading $identifier metadata…"
                val resolved = when (val r = c.archive.resolvePlayableFiles(identifier, era, c.settings.current().dataSaver)) {
                    is ArchiveOutcome.Error -> return@launch say("$identifier: ${r.message}")
                    is ArchiveOutcome.Success -> r.value
                }
                if (resolved.candidates.isEmpty()) {
                    return@launch say("$identifier has no stream-ready MP4/H.264 or WebM files. Not added.")
                }
                _busy.value = "Checking file availability…"
                var chosen: Pair<com.channelsurf.core.archive.PlayableCandidate, com.channelsurf.data.archive.HealthCheck>? = null
                val alternates = ArrayList<String>()
                for (cand in resolved.candidates.take(4)) {
                    val url = ArchiveUrls.download(identifier, cand.file.name)
                    val h = c.archive.healthCheckUrl(url)
                    if (h.status == HealthStatus.OK && h.seekable) {
                        if (chosen == null) chosen = cand to h else if (alternates.size < 2) alternates += url
                    }
                }
                val (best, probe) = chosen ?: return@launch say("$identifier: no file is reachable with byte-range seeking. Not added.")
                val asset = c.catalog.addCandidate(resolved, best, probe, alternates)

                _busy.value = "Verifying playback on this device…"
                val v = c.verifier.verify(asset.url)
                if (v.ok) {
                    c.catalog.markPlaybackVerified(asset.id, v.durationMs, v.seekable)
                    say("Added “${asset.title}” to the review queue. Playback verified (${v.videoFormat ?: "video"}, ${v.durationMs / 60000} min).")
                } else {
                    c.catalog.markHealth(asset.id, HealthStatus.DEGRADED, probe.statusCode)
                    say("Added “${asset.title}” to the review queue, but playback verification failed: ${v.message}")
                }
            } finally {
                _busy.value = null
            }
        }
    }

    fun verifyPlayback(assetId: String) {
        if (_busy.value != null) return
        viewModelScope.launch {
            try {
                val e = c.catalog.asset(assetId) ?: return@launch
                _busy.value = "Verifying playback…"
                val v = c.verifier.verify(e.url)
                if (v.ok) {
                    c.catalog.markPlaybackVerified(assetId, v.durationMs, v.seekable)
                    say("Verified: ${v.videoFormat ?: "video"}, ${v.durationMs / 60000} min, seekable")
                } else {
                    c.catalog.markHealth(assetId, HealthStatus.UNHEALTHY, null)
                    say("Verification failed: ${v.message}")
                }
            } finally {
                _busy.value = null
            }
        }
    }

    fun saveReview(assetId: String, review: CatalogRepository.Review) = viewModelScope.launch {
        val err = c.catalog.saveReview(assetId, review)
        say(err ?: "Saved")
    }

    /** Adds a recording to a channel: by tag for dayparted channels ([daypartTag]), by playlist otherwise. */
    fun assign(assetId: String, channelId: String, fallback: Boolean, daypartTag: String? = null) = viewModelScope.launch {
        val row = assets.value.firstOrNull { it.item.asset.id == assetId }
        if (row != null && !row.item.playbackVerified) {
            return@launch say("Verify playback on this device before scheduling this recording.")
        }
        val ch = channels.value.firstOrNull { it.id == channelId }
        if (ch != null && ch.policy == com.channelsurf.core.model.SchedulePolicy.DAYPARTED) {
            val tag = daypartTag ?: (if (fallback) ch.fillerTags.firstOrNull() else ch.defaultTags.firstOrNull())
                ?: return@launch say("This channel has no tags to schedule by")
            c.catalog.addTag(assetId, tag)
            return@launch say("Scheduled on ${ch.displayName} (#$tag) from the next broadcast day")
        }
        c.catalog.assign(assetId, channelId, fallback)
        say(if (fallback) "Added to fallback pool" else "Added to channel schedule")
    }

    fun unassign(assetId: String, channelId: String, fallback: Boolean) = viewModelScope.launch { c.catalog.unassign(assetId, channelId, fallback) }

    fun removeTag(assetId: String, tag: String) = viewModelScope.launch { c.catalog.removeTag(assetId, tag) }

    fun findNewNow() {
        com.channelsurf.work.DiscoveryScheduler.runNow(getApplication())
        say("Searching the Archive for every channel in the background…")
    }

    fun move(channelId: String, fallback: Boolean, from: Int, to: Int) = viewModelScope.launch { c.catalog.move(channelId, fallback, from, to) }

    fun setChannelEnabled(channelId: String, enabled: Boolean) = viewModelScope.launch { c.catalog.setChannelEnabled(channelId, enabled) }

    fun delete(assetId: String) = viewModelScope.launch { c.catalog.deleteAsset(assetId); say("Removed from catalog") }

    fun takedown(archiveIdentifier: String, reason: String) = viewModelScope.launch {
        if (reason.isBlank()) return@launch say("Enter the takedown reason (e.g. rights-holder request reference)")
        c.catalog.takedown(archiveIdentifier, reason, requestedBy = null)
        say("$archiveIdentifier blocked and removed from all channels")
    }

    fun liftTakedown(archiveIdentifier: String) = viewModelScope.launch {
        c.catalog.liftTakedown(archiveIdentifier)
        say("Takedown lifted. Assets stay blocked until re-reviewed.")
    }

    fun dismissMessage() { _message.value = null }

    private fun say(m: String) { _message.value = m }

    companion object {
        /** Starting points for discovery. Results always go through review; nothing airs automatically. */
        val PRESET_QUERIES = listOf(
            "VHS aircheck 1990s",
            "commercials 1990s VHS",
            "saturday morning cartoons VHS",
            "television broadcast recording 1994",
            "public access television 1990s",
            "infomercial VHS",
            "local news 1990s broadcast",
            "weather channel 1990s",
        )
    }
}
