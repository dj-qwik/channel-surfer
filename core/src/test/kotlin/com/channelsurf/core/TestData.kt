package com.channelsurf.core

import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.Lineup
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.model.SchedulePolicy
import com.channelsurf.core.model.Segment
import com.channelsurf.core.model.SegmentKind

/** Mock catalog used by tests. Identifiers are fixtures, not real Archive items. */
object TestData {
    const val MIN = 60_000L
    const val HOUR = 3_600_000L
    /** 2026-01-01T00:00:00Z */
    const val EPOCH = 1_767_225_600_000L

    fun asset(
        id: String,
        durationMs: Long,
        year: Int = 1994,
        rights: RightsStatus = RightsStatus.CLEARED,
        verified: Boolean = true,
        seekable: Boolean = true,
        sourceStartMs: Long = 0,
        segments: List<Segment> = emptyList(),
    ) = MediaAsset(
        id = id,
        archiveIdentifier = "fixture-$id",
        fileName = "$id.mp4",
        url = ArchiveUrls.download("fixture-$id", "$id.mp4"),
        title = "Title $id",
        description = "Description $id",
        durationMs = durationMs,
        sourceStartMs = sourceStartMs,
        seekable = seekable,
        codec = "h264",
        container = "mp4",
        aspectRatio = "4:3",
        broadcastYear = year,
        yearVerified = verified,
        rightsStatus = rights,
        segments = segments,
    )

    fun channel(
        id: String,
        number: Int,
        playlist: List<String>,
        fallback: List<String> = emptyList(),
        epoch: Long = EPOCH,
    ) = Channel(
        id = id, number = number, displayName = "Channel $number", genre = "Test", categoryLabel = "TEST",
        epochUtcMs = epoch, policy = SchedulePolicy.CYCLIC, playlist = playlist, fallbackPool = fallback,
    )

    /** A: 6 h tape with an embedded commercial break at 10:00–12:00; B: two 30-min shows. */
    fun lineup(): Lineup {
        val tape = asset(
            "tape94", 6 * HOUR,
            segments = listOf(
                Segment(0, "Cold open", SegmentKind.PROGRAM),
                Segment(10 * MIN, "Original commercial break", SegmentKind.COMMERCIAL),
                Segment(12 * MIN, "Act 2", SegmentKind.PROGRAM),
            ),
        )
        val showA = asset("showA", 30 * MIN, year = 1991)
        val showB = asset("showB", 30 * MIN, year = 1997)
        val filler = asset("filler", 10 * MIN, year = 1995)
        val assets = listOf(tape, showA, showB, filler).associateBy { it.id }
        return Lineup(
            revision = "r1",
            generatedAtUtcMs = EPOCH,
            channels = listOf(
                channel("ch-a", 2, listOf("tape94"), fallback = listOf("filler")),
                channel("ch-b", 3, listOf("showA", "showB"), fallback = listOf("filler")),
                channel("ch-empty", 4, emptyList()),
            ),
            assets = assets,
        )
    }

    fun resource(name: String): String =
        TestData::class.java.classLoader.getResource("fixtures/$name")?.readText()
            ?: error("Missing fixture $name")
}
