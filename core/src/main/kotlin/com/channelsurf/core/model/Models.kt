package com.channelsurf.core.model

import com.channelsurf.core.archive.ArchiveUrls

/** Rights review state. Unknown rights are never treated as permission. */
enum class RightsStatus {
    /** Operator recorded evidence that public distribution is permitted. Eligible for air. */
    CLEARED,

    /** Test media; eligible only when the lineup is compiled with test assets allowed (debug builds). */
    TEST_ONLY,

    /** Default for anything discovered on the Archive. Never airs. */
    REVIEW_REQUIRED,

    /** Taken down / blocked. Never airs and is purged from schedules immediately. */
    BLOCKED,
}

enum class HealthStatus { UNKNOWN, OK, DEGRADED, UNHEALTHY }

/** Whether a program is an unaltered off-air recording or an assembled reconstruction. */
enum class Provenance { ORIGINAL_RECORDING, RECONSTRUCTED }

/** Where the commercials inside an asset came from. */
enum class AdOrigin { EMBEDDED_ORIGINAL, INSERTED, NONE }

enum class SchedulePolicy {
    /** One ordered playlist looping forever from the channel epoch. */
    CYCLIC,

    /** Explicit entries at fixed offsets, optionally repeating. */
    FIXED,

    /**
     * Real-cable style: each broadcast day (06:00–06:00 in the channel's feed time zone) is built from dayparts
     * (e.g. Toonami 16:00–18:00 weekdays) filled from tag-based pools with a fresh deterministic shuffle every day.
     * Newly approved recordings join automatically from the next broadcast day.
     */
    DAYPARTED,
}

/** What a recording is. Dayparts prefer full episodes and movies over whole-tape airchecks. */
enum class ContentType {
    EPISODE, MOVIE, AIRCHECK_BLOCK, MUSIC_VIDEO, COMMERCIAL, PROMO_BUMPER, SHOPPING, NEWS, SPORTS, SPECIAL, OTHER,
    /** Movie trailers: fill the time after a movie until the next one starts. */
    TRAILER;

    val isFiller: Boolean get() = this == COMMERCIAL || this == PROMO_BUMPER || this == TRAILER

    /** Starts on the half-hour grid (with breaks); music videos and shopping run back to back instead. */
    val onGrid: Boolean get() = this != MUSIC_VIDEO && this != SHOPPING && !isFiller
    val isFullProgram: Boolean get() = this == EPISODE || this == MOVIE || this == SPECIAL || this == NEWS || this == SPORTS
}

/**
 * A recurring programming block. Minutes are relative to local midnight of the broadcast day's date in the
 * channel's time zone and may run past midnight up to 30:00 (06:00 next morning), e.g. Adult Swim 23:00–05:00
 * is 1380..1740. [days] are ISO day-of-week numbers (1 = Monday) of the broadcast day.
 */
data class Daypart(
    val id: String,
    val title: String,
    val days: Set<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val tags: List<String>,
    val preferTypes: Set<ContentType> = emptySet(),
    val assetIds: List<String> = emptyList(),
)

enum class SegmentKind { PROGRAM, COMMERCIAL, STATION_ID, PROMO, BUMPER, INTERRUPTION }

/** Optional annotated sub-segment inside a long tape. [offsetMs] is a position in the media file. */
data class Segment(val offsetMs: Long, val label: String, val kind: SegmentKind)

data class EraPolicy(
    val minYear: Int = 1990,
    val maxYear: Int = 2015,
    val preferredMaxYear: Int = 2005,
) {
    fun contains(year: Int): Boolean = year in minYear..maxYear
    fun isPreferred(year: Int): Boolean = year in minYear..preferredMaxYear
}

data class MediaAsset(
    val id: String,
    val archiveIdentifier: String,
    val fileName: String,
    val url: String,
    val title: String,
    val description: String = "",
    val creator: String? = null,
    val collection: String? = null,
    val category: String? = null,
    val durationMs: Long,
    /** Position in the file where the broadcast material starts (skips leader/black on the tape). */
    val sourceStartMs: Long = 0,
    val seekable: Boolean,
    val codec: String? = null,
    val container: String? = null,
    val aspectRatio: String? = null,
    val broadcastYear: Int? = null,
    val yearVerified: Boolean = false,
    val yearEvidence: String? = null,
    val rightsStatus: RightsStatus = RightsStatus.REVIEW_REQUIRED,
    val rightsNotes: String? = null,
    val provenance: Provenance = Provenance.ORIGINAL_RECORDING,
    val adOrigin: AdOrigin = AdOrigin.EMBEDDED_ORIGINAL,
    val healthStatus: HealthStatus = HealthStatus.UNKNOWN,
    val lastCheckedAtUtcMs: Long? = null,
    val captionUrl: String? = null,
    /** Verified alternate renditions of the same recording (e.g. a 512Kb MPEG4 derivative), best first. */
    val alternateUrls: List<String> = emptyList(),
    val segments: List<Segment> = emptyList(),
    val contentType: ContentType = ContentType.OTHER,
    /** Show name for episodes ("Dexter's Laboratory"); the guide shows it as the title. */
    val series: String? = null,
    /** Pool tags (e.g. "toonami", "music-video"). DAYPARTED channels draw programs by tag. */
    val tags: List<String> = emptyList(),
    /** Not scheduled on broadcast days that start before this instant (keeps today's schedule stable). */
    val availableFromUtcMs: Long = 0,
) {
    val guideTitle: String get() = series?.takeIf { it.isNotBlank() } ?: title

    /** Airable length: the part of the file after [sourceStartMs]. */
    val playDurationMs: Long get() = (durationMs - sourceStartMs).coerceAtLeast(0)
    val archivePageUrl: String get() = ArchiveUrls.details(archiveIdentifier)

    /** The annotated segment that contains [filePositionMs], if the asset is annotated. */
    fun segmentAt(filePositionMs: Long): Segment? =
        segments.lastOrNull { it.offsetMs <= filePositionMs }
}

/** One entry of a FIXED schedule. [startOffsetMs] is relative to the channel epoch (or period start). */
data class FixedEntry(
    val startOffsetMs: Long,
    val durationMs: Long,
    val assetId: String,
    val sourceOffsetMs: Long = 0,
    val displayTitle: String? = null,
    val description: String? = null,
)

data class Channel(
    val id: String,
    val number: Int,
    val displayName: String,
    val genre: String,
    /** Generic, network-inspired category label shown in the info banner (e.g. "KIDS", "MUSIC"). */
    val categoryLabel: String,
    val color: String = "#1748A5",
    val logoAsset: String? = null,
    val enabled: Boolean = true,
    val scheduleRevision: String = "",
    /** Stable UTC anchor of the channel timeline. Never changes when content changes. */
    val epochUtcMs: Long,
    val policy: SchedulePolicy = SchedulePolicy.CYCLIC,
    /** CYCLIC: ordered asset ids. */
    val playlist: List<String> = emptyList(),
    /** Assets used to fill a slot whose scheduled asset is unhealthy. */
    val fallbackPool: List<String> = emptyList(),
    /** FIXED: explicit entries. */
    val fixedEntries: List<FixedEntry> = emptyList(),
    /** FIXED: repeat period (e.g. one week). Null means a one-shot schedule. */
    val fixedPeriodMs: Long? = null,
    /** DAYPARTED: the feed's time zone (most cable networks ran an Eastern feed). */
    val timeZone: String = "America/New_York",
    val dayparts: List<Daypart> = emptyList(),
    /** DAYPARTED: pool for hours not covered by a daypart. */
    val defaultTags: List<String> = emptyList(),
    /** Short commercials/bumpers used to fill the end of a daypart, like a real station break. */
    val fillerTags: List<String> = emptyList(),
    /** Archive searches the discovery job runs for this channel; results go to the review queue. */
    val discoveryQueries: List<String> = emptyList(),
    /** Related pools this channel borrows from when it has nothing of its own for a block, instead of going off the air. */
    val fallbackTags: List<String> = emptyList(),
    /** Specialty channels (Star Trek 24/7 etc.): recordings of any year may air. */
    val ignoreEra: Boolean = false,
) {
    val displayNumber: String get() = number.toString().padStart(2, '0')
}

/** How recordings become eligible to air. */
enum class ReviewPolicy {
    /**
     * Default. Discovered recordings air without a manual sign-off unless BLOCKED; a detected broadcast year
     * outside the era still excludes an item, an unknown year does not.
     */
    AUTOMATIC,

    /** Every recording needs rights CLEARED and an operator-verified broadcast year. */
    MANUAL,
}

data class Lineup(
    val revision: String,
    val generatedAtUtcMs: Long,
    val channels: List<Channel>,
    val assets: Map<String, MediaAsset>,
    val eraPolicy: EraPolicy = EraPolicy(),
    val reviewPolicy: ReviewPolicy = ReviewPolicy.AUTOMATIC,
) {
    val enabledChannels: List<Channel> get() = channels.filter { it.enabled }.sortedBy { it.number }
    fun channel(id: String): Channel? = channels.firstOrNull { it.id == id }

    companion object {
        fun empty(revision: String = "empty") = Lineup(revision, 0, emptyList(), emptyMap())
    }
}

enum class BlockKind { PROGRAM, SLATE_NO_PROGRAMMING, SLATE_TECHNICAL_DIFFICULTIES, SLATE_STATION_BREAK }

/** A contiguous span of a channel timeline. Blocks of one channel never overlap and never leave gaps. */
data class ProgramBlock(
    val id: String,
    val channelId: String,
    val assetId: String?,
    val startUtcMs: Long,
    val durationMs: Long,
    /** Extra offset into the asset (after the asset's own sourceStartMs) where this block begins. */
    val sourceOffsetMs: Long,
    val displayTitle: String,
    val description: String,
    val provenance: Provenance?,
    val kind: BlockKind,
    val revision: String,
    /** Programming block name shown in the guide, e.g. "TOONAMI". */
    val daypartTitle: String? = null,
    /**
     * The half-hour-grid airing this block belongs to (a show plus its commercial breaks, or a movie plus the
     * trailers after it). The guide and the info banner show the airing, not the individual act or commercial.
     */
    val slotStartUtcMs: Long? = null,
    val slotEndUtcMs: Long? = null,
    val slotTitle: String? = null,
    val slotAssetId: String? = null,
) {
    val endUtcMs: Long get() = startUtcMs + durationMs
    fun contains(utcMs: Long): Boolean = utcMs >= startUtcMs && utcMs < endUtcMs

    /** The program name viewers should see right now: the show, even during its commercials. */
    val headline: String get() = slotTitle ?: displayTitle

    /** This block's whole airing as one block (for the guide and banner); itself when it isn't part of one. */
    fun slotView(description: String = this.description): ProgramBlock {
        val s = slotStartUtcMs ?: return this
        val e = slotEndUtcMs ?: return this
        return copy(
            id = ScheduleIds.slotId(channelId, s), assetId = slotAssetId ?: assetId, startUtcMs = s, durationMs = e - s,
            sourceOffsetMs = 0, displayTitle = slotTitle ?: displayTitle, description = description, kind = BlockKind.PROGRAM,
        )
    }
}

object ScheduleIds {
    /** Title prefix of the short card before the next show: "Up Next: Seinfeld". */
    const val UP_NEXT = "Up Next"
    fun slotId(channelId: String, startUtcMs: Long) = "slot:$channelId:$startUtcMs"
}
