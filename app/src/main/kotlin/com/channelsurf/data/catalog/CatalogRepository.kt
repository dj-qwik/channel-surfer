package com.channelsurf.data.catalog

import androidx.room.withTransaction
import android.content.Context
import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.archive.PlayableCandidate
import com.channelsurf.core.manifest.ManifestCodec
import com.channelsurf.core.manifest.ManifestResult
import com.channelsurf.core.archive.ContentClassifier
import com.channelsurf.core.model.AdOrigin
import com.channelsurf.core.model.ContentType
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.Lineup
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.Provenance
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.data.archive.HealthCheck
import com.channelsurf.data.archive.ResolvedItem
import com.channelsurf.data.local.AppDatabase
import com.channelsurf.data.local.DismissedEntity
import com.channelsurf.data.local.enumOr
import com.channelsurf.data.local.ManifestRevisionEntity
import com.channelsurf.data.local.MediaAssetEntity
import com.channelsurf.data.local.POOL_FALLBACK
import com.channelsurf.data.local.POOL_PRIMARY
import com.channelsurf.data.local.TakedownEntity
import com.channelsurf.data.local.playlistEntities
import com.channelsurf.data.local.toEntity
import com.channelsurf.data.local.toModel
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

sealed interface ImportResult {
    data class Imported(val revision: String, val channels: Int, val assets: Int, val warnings: List<String>) : ImportResult
    data class Rejected(val errors: List<String>) : ImportResult
}

/** A curated asset plus local-only bookkeeping that is not part of the shared manifest. */
data class CatalogAsset(val asset: MediaAsset, val playbackVerified: Boolean, val lastStatusCode: Int?, val addedAtUtcMs: Long)

/**
 * The device's canonical channel catalog (Room). Every edit produces a new deterministic revision,
 * so schedules rebuild without an app update. Manifests can be imported and exported at any time.
 */
class CatalogRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val diagnostics: DiagnosticsLog,
    private val nowUtcMs: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.catalogDao()
    private val audit = db.auditDao()

    private val seedLock = Mutex()

    private val _takedowns = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Archive identifiers that were just taken down. Playback must stop them immediately. */
    val takedownEvents: SharedFlow<String> = _takedowns

    val lineup: Flow<Lineup> = combine(
        dao.observeChannels(), dao.observeAssets(), dao.observePlaylistEntries(),
    ) { channels, assets, entries ->
        val assetModels = assets.associate { it.id to it.toModel() }
        val channelModels = channels.map { it.toModel(entries) }
        Lineup(
            revision = editorialRevision(channelModels, assetModels.values),
            generatedAtUtcMs = nowUtcMs(),
            channels = channelModels,
            assets = assetModels,
            eraPolicy = EraPolicy(),
        )
    }.distinctUntilChanged { a, b -> a.revision == b.revision && a.assets == b.assets }

    val catalogAssets: Flow<List<CatalogAsset>> = combine(dao.observeAssets(), dao.observeChannels()) { assets, _ ->
        assets.map { CatalogAsset(it.toModel(), it.playbackVerified, it.lastStatusCode, it.addedAtUtcMs) }
            .sortedByDescending { it.addedAtUtcMs }
    }

    val takedowns = audit.observeTakedowns()

    suspend fun currentLineup(): Lineup = lineup.first()

    /** Runs several catalog writes as one change, so the schedule rebuilds once instead of once per write. */
    suspend fun <T> batch(block: suspend () -> T): T = db.withTransaction { block() }

    // ------------------------------------------------------------------ manifests

    /** First launch: install the bundled channel lineup (channels only; it ships with zero media). */
    suspend fun seedIfEmpty(): Boolean {
        seedLock.withLock {
            val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)
            val text = context.assets.open(BUNDLED_MANIFEST).bufferedReader().use { it.readText() }
            val bundledRevision = BUNDLED_REVISION.find(text.take(400))?.groupValues?.get(1) ?: ""
            val empty = dao.channelCount() == 0
            // An app update can ship a bigger lineup: merge it in, keeping everything discovery already found.
            if (!empty && prefs.getString(KEY_BUNDLED, null) == bundledRevision) return false
            val r = importManifest(text, source = "BUNDLED", signatureVerified = false, mergeExistingAssets = !empty)
            if (r is ImportResult.Rejected) diagnostics.warn("catalog.seed", "Bundled manifest rejected: ${r.errors.take(5)}")
            if (r is ImportResult.Imported) prefs.edit().putString(KEY_BUNDLED, bundledRevision).apply()
            return r is ImportResult.Imported
        }
    }

    suspend fun importManifest(
        text: String,
        source: String,
        signatureVerified: Boolean,
        /** Keep catalog assets the manifest doesn't mention (discovered recordings). */
        mergeExistingAssets: Boolean = false,
    ): ImportResult {
        val decoded = ManifestCodec.decode(text)
        if (decoded is ManifestResult.Failure) return ImportResult.Rejected(decoded.errors)
        decoded as ManifestResult.Success
        val lineup = decoded.lineup
        val blocked = audit.takedowns().map { it.archiveIdentifier }.toSet()
        val existing = dao.assets().associateBy { it.id }
        val assetEntities = lineup.assets.values.map { a ->
            val forcedBlock = a.archiveIdentifier in blocked
            val asset = if (forcedBlock) a.copy(rightsStatus = RightsStatus.BLOCKED, rightsNotes = "Blocked by local takedown") else a
            val prev = existing[a.id]
            // Device-local health/verification survive re-imports of the same media URL.
            val keepLocal = prev != null && prev.url == a.url
            asset.copy(
                healthStatus = if (keepLocal) enumOr(prev!!.healthStatus, HealthStatus.UNKNOWN) else a.healthStatus,
                lastCheckedAtUtcMs = if (keepLocal) prev!!.lastCheckedAtUtcMs else a.lastCheckedAtUtcMs,
            ).toEntity(
                lastStatusCode = if (keepLocal) prev!!.lastStatusCode else null,
                playbackVerified = keepLocal && prev!!.playbackVerified,
                addedAtUtcMs = prev?.addedAtUtcMs ?: nowUtcMs(),
            )
        }
        val incomingIds = assetEntities.map { it.id }.toSet()
        val keptEntities = if (mergeExistingAssets) existing.values.filter { it.id !in incomingIds } else emptyList()
        val allAssets = assetEntities + keptEntities
        val blockedIds = allAssets.filter { it.rightsStatus == RightsStatus.BLOCKED.name }.map { it.id }.toSet()
        val channels = lineup.channels.map { c ->
            c.copy(playlist = c.playlist.filterNot { it in blockedIds }, fallbackPool = c.fallbackPool.filterNot { it in blockedIds })
        }
        dao.replaceCatalog(channels.map { it.toEntity() }, allAssets, channels.flatMap { it.playlistEntities() })
        // Big manifests aren't kept verbatim (a Room row over ~2 MB can't be read back).
        val stored = if (text.length > MAX_STORED_MANIFEST) "" else text
        audit.insertRevision(ManifestRevisionEntity(lineup.revision.ifBlank { "unnamed-${nowUtcMs()}" }, source, nowUtcMs(), stored, signatureVerified))
        audit.trimRevisions(KEEP_REVISIONS)
        diagnostics.info("catalog.import", "Imported '${lineup.revision}' from $source: ${channels.size} channels, ${assetEntities.size} assets")
        return ImportResult.Imported(lineup.revision, channels.size, assetEntities.size, decoded.warnings)
    }

    /**
     * Exports a manifest that re-imports cleanly. Assets still missing an authoritative duration (not yet
     * playback-verified) cannot be scheduled and are left out, along with references to them.
     */
    suspend fun exportManifest(): String {
        val l = currentLineup()
        val assets = l.assets.filterValues { it.durationMs > 0 && it.sourceStartMs < it.durationMs }
        val channels = l.channels.map { c ->
            c.copy(
                playlist = c.playlist.filter { it in assets },
                fallbackPool = c.fallbackPool.filter { it in assets },
                fixedEntries = c.fixedEntries.filter { it.assetId in assets },
            )
        }
        return ManifestCodec.encode(l.copy(channels = channels, assets = assets, generatedAtUtcMs = nowUtcMs()))
    }

    suspend fun recentRevisions() = audit.recentRevisions(KEEP_REVISIONS)

    // ------------------------------------------------------------------ curation

    /**
     * Adds a resolved Archive file to the catalog. It always enters the review queue: rights REVIEW_REQUIRED
     * and broadcast year unverified, regardless of what the Archive metadata claims.
     */
    suspend fun addCandidate(
        resolved: ResolvedItem,
        candidate: PlayableCandidate,
        probe: HealthCheck,
        verifiedAlternates: List<String>,
    ): MediaAsset {
        val item = resolved.item
        val id = assetIdFor(item.identifier, candidate.file.name)
        val clues = resolved.year.clues.take(6).joinToString("; ") { "${it.year} (${it.source}: ${it.excerpt.take(60)})" }
        val cls = ContentClassifier.classify(item.title, item.description, item.subjects, candidate.file.lengthMs ?: 0)
        val asset = MediaAsset(
            id = id,
            archiveIdentifier = item.identifier,
            fileName = candidate.file.name,
            url = ArchiveUrls.download(item.identifier, candidate.file.name),
            title = item.title.take(300),
            description = item.description?.take(4000) ?: "",
            creator = item.creator,
            collection = item.collections.firstOrNull(),
            durationMs = candidate.file.lengthMs ?: 0,
            seekable = probe.seekable,
            codec = candidate.codec,
            container = candidate.container,
            aspectRatio = candidate.aspectRatio,
            broadcastYear = resolved.year.year,
            yearVerified = false,
            yearEvidence = clues.ifBlank { "No year evidence found in metadata" }.take(1000),
            rightsStatus = if (item.identifier in audit.takedowns().map { it.archiveIdentifier }) RightsStatus.BLOCKED else RightsStatus.REVIEW_REQUIRED,
            rightsNotes = buildString {
                item.licenseUrl?.let { append("Archive licenseurl: $it (verify; not proof of broadcast rights). ") }
                item.rights?.let { append("Archive rights field: ${it.take(300)}") }
            }.ifBlank { null },
            provenance = Provenance.ORIGINAL_RECORDING,
            adOrigin = AdOrigin.EMBEDDED_ORIGINAL,
            healthStatus = probe.status,
            lastCheckedAtUtcMs = nowUtcMs(),
            captionUrl = resolved.captions.firstOrNull(),
            alternateUrls = verifiedAlternates,
            contentType = cls.type,
            series = cls.series,
            availableFromUtcMs = nowUtcMs(),
        )
        dao.upsertAsset(asset.toEntity(lastStatusCode = probe.statusCode, playbackVerified = false, addedAtUtcMs = nowUtcMs()))
        diagnostics.info("curation.add", "Added ${item.identifier}/${candidate.file.name} to review queue")
        return asset
    }

    /**
     * Adds a recording found by the discovery job. It is already technically verified (seekable, decodes on this
     * device, authoritative duration) and tagged for a channel pool, and airs from the next broadcast day under the
     * AUTOMATIC review policy.
     */
    suspend fun addDiscovered(
        resolved: ResolvedItem,
        candidate: PlayableCandidate,
        probe: HealthCheck,
        verifiedAlternates: List<String>,
        durationMs: Long,
        contentType: ContentType,
        series: String?,
        tags: List<String>,
        availableFromUtcMs: Long,
        /** For one episode of a multi-episode upload: its own name. */
        titleOverride: String? = null,
    ): MediaAsset {
        val item = resolved.item
        val blocked = item.identifier in audit.takedowns().map { it.archiveIdentifier }
        val asset = MediaAsset(
            id = assetIdFor(item.identifier, candidate.file.name),
            archiveIdentifier = item.identifier,
            fileName = candidate.file.name,
            url = ArchiveUrls.download(item.identifier, candidate.file.name),
            title = (titleOverride ?: item.title).take(300),
            description = item.description?.take(4000) ?: "",
            creator = item.creator,
            collection = item.collections.firstOrNull(),
            durationMs = durationMs,
            seekable = probe.seekable,
            codec = candidate.codec,
            container = candidate.container,
            aspectRatio = candidate.aspectRatio,
            broadcastYear = resolved.year.year,
            yearVerified = false,
            yearEvidence = resolved.year.clues.take(4).joinToString("; ") { "${it.year} (${it.source})" }.ifBlank { null },
            rightsStatus = if (blocked) RightsStatus.BLOCKED else RightsStatus.REVIEW_REQUIRED,
            rightsNotes = item.licenseUrl?.let { "Archive licenseurl: $it" },
            provenance = Provenance.ORIGINAL_RECORDING,
            adOrigin = if (contentType == ContentType.AIRCHECK_BLOCK) AdOrigin.EMBEDDED_ORIGINAL else AdOrigin.NONE,
            healthStatus = HealthStatus.OK,
            lastCheckedAtUtcMs = nowUtcMs(),
            captionUrl = resolved.captions.firstOrNull(),
            alternateUrls = verifiedAlternates,
            contentType = contentType,
            series = series,
            tags = tags,
            availableFromUtcMs = availableFromUtcMs,
        )
        dao.upsertAsset(asset.toEntity(lastStatusCode = probe.statusCode, playbackVerified = true, addedAtUtcMs = nowUtcMs(), discovered = true))
        diagnostics.info("discovery.add", "${item.identifier} → ${tags.joinToString()} (${contentType.name.lowercase()})")
        return asset
    }

    /** Identifiers discovery must skip: already in the catalog, taken down, or previously rejected. */
    suspend fun knownIdentifiers(): Set<String> =
        dao.identifiers().toSet() + audit.takedowns().map { it.archiveIdentifier } + audit.dismissedIdentifiers()

    suspend fun dismiss(archiveIdentifier: String, reason: String) =
        audit.insertDismissed(DismissedEntity(archiveIdentifier, reason.take(300), nowUtcMs()))

    suspend fun assetCount(): Int = dao.assetCount()

    /** Puts an asset into a DAYPARTED channel pool by tag. It joins from the next broadcast day. */
    suspend fun addTag(assetId: String, tag: String) {
        val e = dao.asset(assetId) ?: return
        val tags = com.channelsurf.data.local.splitTags(e.tags)
        if (tag in tags) return
        dao.upsertAsset(e.copy(tags = (tags + tag).joinToString(","), availableFromUtcMs = maxOf(e.availableFromUtcMs, nowUtcMs())))
    }

    suspend fun removeTag(assetId: String, tag: String) {
        val e = dao.asset(assetId) ?: return
        dao.upsertAsset(e.copy(tags = com.channelsurf.data.local.splitTags(e.tags).filter { it != tag }.joinToString(",")))
    }

    data class Review(
        val title: String,
        val broadcastYear: Int?,
        val yearVerified: Boolean,
        val yearEvidence: String?,
        val rightsStatus: RightsStatus,
        val rightsNotes: String?,
        val provenance: Provenance,
        val adOrigin: AdOrigin,
        val sourceStartMs: Long,
        val category: String?,
        val contentType: ContentType? = null,
        val series: String? = null,
        val tags: List<String>? = null,
    )

    suspend fun saveReview(assetId: String, review: Review): String? {
        val e = dao.asset(assetId) ?: return "Asset no longer exists"
        if (review.yearVerified && review.broadcastYear == null) return "Enter the broadcast year before marking it verified."
        if (review.sourceStartMs < 0 || (e.durationMs > 0 && review.sourceStartMs >= e.durationMs)) return "Start offset must be inside the recording."
        dao.upsertAsset(
            e.copy(
                title = review.title.trim().take(300).ifBlank { e.title },
                broadcastYear = review.broadcastYear,
                yearVerified = review.yearVerified,
                yearEvidence = review.yearEvidence?.take(1000),
                rightsStatus = review.rightsStatus.name,
                rightsNotes = review.rightsNotes?.take(2000),
                provenance = review.provenance.name,
                adOrigin = review.adOrigin.name,
                sourceStartMs = review.sourceStartMs,
                category = review.category?.take(60),
                contentType = review.contentType?.name ?: e.contentType,
                series = review.series?.trim()?.take(200)?.ifBlank { null } ?: e.series,
                tags = review.tags?.joinToString(",") ?: e.tags,
            )
        )
        if (review.rightsStatus == RightsStatus.BLOCKED) dao.removeAssetFromAllPlaylists(assetId)
        return null
    }

    /** Records a successful on-device playback verification with the authoritative decoded duration. */
    suspend fun markPlaybackVerified(assetId: String, durationMs: Long, seekable: Boolean) {
        val e = dao.asset(assetId) ?: return
        dao.upsertAsset(
            e.copy(
                playbackVerified = true,
                durationMs = if (durationMs > 0) durationMs else e.durationMs,
                seekable = seekable && e.seekable,
                healthStatus = HealthStatus.OK.name,
                lastCheckedAtUtcMs = nowUtcMs(),
            )
        )
    }

    suspend fun assign(assetId: String, channelId: String, fallback: Boolean) {
        val pool = if (fallback) POOL_FALLBACK else POOL_PRIMARY
        val ids = dao.playlist(channelId, pool).map { it.assetId }
        if (assetId !in ids) dao.replacePlaylist(channelId, pool, ids + assetId)
    }

    suspend fun unassign(assetId: String, channelId: String, fallback: Boolean) {
        val pool = if (fallback) POOL_FALLBACK else POOL_PRIMARY
        dao.replacePlaylist(channelId, pool, dao.playlist(channelId, pool).map { it.assetId }.filter { it != assetId })
    }

    suspend fun move(channelId: String, fallback: Boolean, from: Int, to: Int) {
        val pool = if (fallback) POOL_FALLBACK else POOL_PRIMARY
        val ids = dao.playlist(channelId, pool).map { it.assetId }.toMutableList()
        if (from !in ids.indices || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        dao.replacePlaylist(channelId, pool, ids)
    }

    suspend fun setChannelEnabled(channelId: String, enabled: Boolean) {
        val c = dao.channels().firstOrNull { it.id == channelId } ?: return
        dao.upsertChannels(listOf(c.copy(enabled = enabled)))
    }

    /** Removes an asset; its Archive item is remembered so discovery doesn't bring it back. */
    suspend fun deleteAsset(assetId: String) {
        val e = dao.asset(assetId)
        dao.removeAssetFromAllPlaylists(assetId)
        dao.deleteAsset(assetId)
        if (e != null) dismiss(e.archiveIdentifier, "deleted by operator")
    }

    suspend fun markHealth(assetId: String, status: HealthStatus, statusCode: Int?) {
        dao.updateHealth(assetId, status.name, nowUtcMs(), statusCode)
    }

    /**
     * Takedown workflow: records the request, blocks every asset from that Archive item, removes them
     * from all schedules, and signals playback to stop immediately. Blocks persist across manifest imports.
     */
    suspend fun takedown(archiveIdentifier: String, reason: String, requestedBy: String?) {
        audit.insertTakedown(TakedownEntity(archiveIdentifier, reason.take(1000), requestedBy?.take(200), nowUtcMs()))
        dao.blockIdentifier(archiveIdentifier, "Taken down: ${reason.take(500)}")
        dao.assetsForIdentifier(archiveIdentifier).forEach { dao.removeAssetFromAllPlaylists(it.id) }
        diagnostics.warn("rights.takedown", "Blocked $archiveIdentifier: $reason")
        _takedowns.emit(archiveIdentifier)
    }

    /** Lifts a takedown record. Assets stay BLOCKED until an operator re-reviews them. */
    suspend fun liftTakedown(archiveIdentifier: String) = audit.deleteTakedown(archiveIdentifier)

    suspend fun asset(assetId: String): MediaAssetEntity? = dao.asset(assetId)

    companion object {
        const val BUNDLED_MANIFEST = "default_manifest.json"
        const val KEEP_REVISIONS = 20
        private const val KEY_BUNDLED = "bundled_revision"
        private const val MAX_STORED_MANIFEST = 400_000
        private val BUNDLED_REVISION = Regex("\"revision\"\\s*:\\s*\"([^\"]+)\"")

        fun assetIdFor(identifier: String, fileName: String): String {
            val slug = fileName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40)
            val digest = MessageDigest.getInstance("SHA-256").digest("$identifier/$fileName".toByteArray())
            val hash = digest.take(4).joinToString("") { "%02x".format(it) }
            return "$identifier:$slug:$hash".take(128)
        }

        /** Deterministic revision from editorial content only (device-local health is excluded). */
        fun editorialRevision(channels: List<com.channelsurf.core.model.Channel>, assets: Collection<MediaAsset>): String {
            val md = MessageDigest.getInstance("SHA-256")
            channels.sortedBy { it.id }.forEach { md.update(it.toString().toByteArray()) }
            assets.sortedBy { it.id }.forEach {
                md.update(it.copy(healthStatus = HealthStatus.UNKNOWN, lastCheckedAtUtcMs = null).toString().toByteArray())
            }
            return "rev-" + md.digest().take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
