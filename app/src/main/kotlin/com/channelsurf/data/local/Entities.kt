package com.channelsurf.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Channel definition. The canonical catalog lives in these tables; manifests are imported into / exported from them. */
@Entity(tableName = "channels", indices = [Index(value = ["number"], unique = true)])
data class ChannelEntity(
    @PrimaryKey val id: String,
    val number: Int,
    val displayName: String,
    val genre: String,
    val categoryLabel: String,
    val color: String,
    val logoAsset: String?,
    val enabled: Boolean,
    val scheduleRevision: String,
    val epochUtcMs: Long,
    val policy: String,
    /** FIXED entries as JSON (rarely edited on device; kept verbatim for export). */
    val fixedEntriesJson: String?,
    val fixedPeriodMs: Long?,
    val timeZone: String,
    /** DAYPARTED blocks as JSON. */
    val daypartsJson: String?,
    /** Comma-separated tags. */
    val defaultTags: String,
    val fillerTags: String,
    /** Discovery searches as a JSON array of strings. */
    val discoveryQueriesJson: String?,
    /** Related pools borrowed when this channel has nothing of its own for a block (comma-separated). */
    @ColumnInfo(defaultValue = "") val fallbackTags: String = "",
    /** Specialty channels: any year may air. */
    @ColumnInfo(defaultValue = "0") val ignoreEra: Boolean = false,
)

@Entity(tableName = "media_assets", indices = [Index("archiveIdentifier")])
data class MediaAssetEntity(
    @PrimaryKey val id: String,
    val archiveIdentifier: String,
    val fileName: String,
    val url: String,
    val title: String,
    val description: String,
    val creator: String?,
    val collection: String?,
    val category: String?,
    val durationMs: Long,
    val sourceStartMs: Long,
    val seekable: Boolean,
    val codec: String?,
    val container: String?,
    val aspectRatio: String?,
    val broadcastYear: Int?,
    val yearVerified: Boolean,
    val yearEvidence: String?,
    val rightsStatus: String,
    val rightsNotes: String?,
    val provenance: String,
    val adOrigin: String,
    val healthStatus: String,
    val lastCheckedAtUtcMs: Long?,
    val lastStatusCode: Int?,
    val captionUrl: String?,
    /** Newline-separated verified alternate rendition URLs. */
    val alternateUrls: String,
    /** Segments as JSON array. */
    val segmentsJson: String?,
    /** True once a real ExoPlayer prepare + mid-file seek succeeded on this device. */
    val playbackVerified: Boolean,
    val addedAtUtcMs: Long,
    val contentType: String,
    val series: String?,
    /** Comma-separated pool tags. */
    val tags: String,
    val availableFromUtcMs: Long,
    /** True when added by the automatic discovery job rather than an operator. */
    val discovered: Boolean,
)

/** Ordered membership of an asset in a channel's PRIMARY playlist or FALLBACK pool. */
@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["channelId", "pool", "position"],
    indices = [Index("assetId")],
)
data class PlaylistEntryEntity(
    val channelId: String,
    val pool: String,
    val position: Int,
    val assetId: String,
)

/** Materialized schedule: 24 h history and 7 days ahead, rebuilt on every catalog revision. */
@Entity(tableName = "program_blocks", indices = [Index(value = ["channelId", "startUtcMs"])])
data class ProgramBlockEntity(
    @PrimaryKey val id: String,
    val channelId: String,
    val assetId: String?,
    val startUtcMs: Long,
    val durationMs: Long,
    val sourceOffsetMs: Long,
    val displayTitle: String,
    val description: String,
    val provenance: String?,
    val kind: String,
    val revision: String,
    val daypartTitle: String?,
)

/** Every manifest revision that became active, for audit and rollback. */
@Entity(tableName = "manifest_revisions")
data class ManifestRevisionEntity(
    @PrimaryKey val revision: String,
    val source: String,
    val importedAtUtcMs: Long,
    val manifestJson: String,
    val signatureVerified: Boolean,
)

/** Takedown / removal log. Blocked identifiers stay blocked even if a later manifest re-introduces them. */
@Entity(tableName = "takedowns")
data class TakedownEntity(
    @PrimaryKey val archiveIdentifier: String,
    val reason: String,
    val requestedBy: String?,
    val atUtcMs: Long,
)

/** Archive items discovery looked at and rejected (or an operator deleted); never re-added automatically. */
@Entity(tableName = "dismissed_items")
data class DismissedEntity(
    @PrimaryKey val archiveIdentifier: String,
    val reason: String,
    val atUtcMs: Long,
)

/** HTTP cache for Archive metadata/search JSON, so the guide and curation work offline. */
@Entity(tableName = "archive_cache")
data class ArchiveCacheEntity(
    @PrimaryKey val url: String,
    val body: String,
    val fetchedAtUtcMs: Long,
    val etag: String?,
)
