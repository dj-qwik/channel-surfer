package com.channelsurf.core

import com.channelsurf.core.TestData.EPOCH
import com.channelsurf.core.TestData.HOUR
import com.channelsurf.core.TestData.MIN
import com.channelsurf.core.model.BlockKind
import com.channelsurf.core.model.FixedEntry
import com.channelsurf.core.model.ReviewPolicy
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.model.SchedulePolicy
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.schedule.ScheduleValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEngineTest {
    private val engine = ScheduleEngine(TestData.lineup())

    @Test
    fun moduloMathPlacesInstantInCorrectBlock() {
        // ch-b cycle = showA(30m) + showB(30m) = 60 min
        val t = EPOCH + 5 * HOUR + 45 * MIN
        val b = engine.blockAt("ch-b", t)
        assertEquals("showB", b.assetId)
        assertEquals(EPOCH + 5 * HOUR + 30 * MIN, b.startUtcMs)
        assertEquals(30 * MIN, b.durationMs)
    }

    @Test
    fun instantsBeforeEpochUsePositiveModulo() {
        val t = EPOCH - 10 * MIN // 50 minutes into the previous cycle -> showB
        val b = engine.blockAt("ch-b", t)
        assertEquals("showB", b.assetId)
        assertEquals(EPOCH - 30 * MIN, b.startUtcMs)
        assertTrue(b.contains(t))
    }

    @Test
    fun exactBoundaryBelongsToNextBlock() {
        val b = engine.blockAt("ch-b", EPOCH + 30 * MIN)
        assertEquals("showB", b.assetId)
        assertEquals(EPOCH + 30 * MIN, b.startUtcMs)
    }

    @Test
    fun scheduleIsDeterministicAcrossEngineInstances() {
        val other = ScheduleEngine(TestData.lineup())
        val t = 1_793_000_123_456L
        assertEquals(engine.blockAt("ch-a", t), other.blockAt("ch-a", t))
        assertEquals(engine.blocksBetween("ch-b", t, t + 7 * 24 * HOUR), other.blocksBetween("ch-b", t, t + 7 * 24 * HOUR))
    }

    @Test
    fun sevenDaysAheadAndOneDayHistoryAreGapless() {
        val now = EPOCH + 123 * HOUR + 17 * MIN
        val from = now - 24 * HOUR
        val to = now + 7 * 24 * HOUR
        for (ch in listOf("ch-a", "ch-b", "ch-empty")) {
            val blocks = engine.blocksBetween(ch, from, to)
            assertEquals("channel $ch", emptyList<String>(), ScheduleValidator.problems(blocks, from, to))
        }
    }

    @Test
    fun emptyChannelProducesHourlyNoProgrammingSlates() {
        val b = engine.blockAt("ch-empty", EPOCH + 90 * MIN)
        assertEquals(BlockKind.SLATE_NO_PROGRAMMING, b.kind)
        assertEquals(EPOCH + HOUR, b.startUtcMs)
        assertEquals(HOUR, b.durationMs)
        assertNull(b.assetId)
        assertFalse(engine.hasProgramming("ch-empty"))
    }

    @Test
    fun emptyCatalogStillProducesAGuide() {
        val lineup = TestData.lineup().copy(assets = emptyMap(), channels = TestData.lineup().channels.map { it.copy(playlist = emptyList(), fallbackPool = emptyList()) })
        val e = ScheduleEngine(lineup)
        lineup.channels.forEach { c ->
            val blocks = e.blocksBetween(c.id, EPOCH, EPOCH + 3 * HOUR)
            assertTrue(blocks.all { it.kind == BlockKind.SLATE_NO_PROGRAMMING })
            assertTrue(ScheduleValidator.problems(blocks, EPOCH, EPOCH + 3 * HOUR).isEmpty())
        }
    }

    @Test
    fun ineligibleAssetsAreNeverScheduled() {
        val base = TestData.lineup()
        val assets = base.assets.toMutableMap()
        assets["showA"] = assets.getValue("showA").copy(rightsStatus = RightsStatus.REVIEW_REQUIRED)
        assets["showB"] = assets.getValue("showB").copy(broadcastYear = 2019)
        val e = ScheduleEngine(base.copy(assets = assets, reviewPolicy = ReviewPolicy.MANUAL))
        val blocks = e.blocksBetween("ch-b", EPOCH, EPOCH + 24 * HOUR)
        assertTrue(blocks.all { it.kind == BlockKind.SLATE_NO_PROGRAMMING })
        // Automatic policy: unreviewed rights are fine, but a detected out-of-era year still keeps showB off air.
        val auto = ScheduleEngine(base.copy(assets = assets, reviewPolicy = ReviewPolicy.AUTOMATIC))
        val aired = auto.blocksBetween("ch-b", EPOCH, EPOCH + 24 * HOUR).mapNotNull { it.assetId }.toSet()
        assertEquals(setOf("showA"), aired)
    }

    @Test
    fun shortAssetsCycleWithoutGaps() {
        val base = TestData.lineup()
        val tiny = TestData.asset("tiny", 7_000)
        val lineup = base.copy(
            assets = base.assets + ("tiny" to tiny),
            channels = base.channels + TestData.channel("ch-tiny", 9, listOf("tiny")),
        )
        val e = ScheduleEngine(lineup)
        val blocks = e.blocksBetween("ch-tiny", EPOCH, EPOCH + 70_000)
        assertEquals(10, blocks.size)
        assertTrue(ScheduleValidator.problems(blocks, EPOCH, EPOCH + 70_000).isEmpty())
    }

    @Test
    fun sourceStartMsShortensAiredDuration() {
        val base = TestData.lineup()
        val a = TestData.asset("lead", 20 * MIN, sourceStartMs = 2 * MIN)
        val lineup = base.copy(assets = base.assets + ("lead" to a), channels = base.channels + TestData.channel("ch-lead", 9, listOf("lead")))
        val b = ScheduleEngine(lineup).blockAt("ch-lead", EPOCH)
        assertEquals(18 * MIN, b.durationMs)
    }

    @Test
    fun fixedScheduleRepeatsWeeklyAndFillsGapsWithSlates() {
        val base = TestData.lineup()
        val week = 7 * 24 * HOUR
        val ch = TestData.channel("ch-fixed", 20, emptyList()).copy(
            policy = SchedulePolicy.FIXED,
            fixedPeriodMs = week,
            fixedEntries = listOf(
                FixedEntry(startOffsetMs = 9 * HOUR, durationMs = 30 * MIN, assetId = "showA", displayTitle = "Morning Show"),
                // Slot longer than the media: remainder must be an honest technical-difficulties slate.
                FixedEntry(startOffsetMs = 10 * HOUR, durationMs = 45 * MIN, assetId = "showB"),
            ),
        )
        val e = ScheduleEngine(base.copy(channels = base.channels + ch))
        val b1 = e.blockAt("ch-fixed", EPOCH + week * 3 + 9 * HOUR + 5 * MIN)
        assertEquals("Morning Show", b1.displayTitle)
        assertEquals(EPOCH + week * 3 + 9 * HOUR, b1.startUtcMs)
        val tech = e.blockAt("ch-fixed", EPOCH + 10 * HOUR + 40 * MIN)
        assertEquals(BlockKind.SLATE_TECHNICAL_DIFFICULTIES, tech.kind)
        assertEquals(EPOCH + 10 * HOUR + 30 * MIN, tech.startUtcMs)
        val from = EPOCH + 2 * week
        val blocks = e.blocksBetween("ch-fixed", from, from + week)
        assertTrue(ScheduleValidator.problems(blocks, from, from + week).isEmpty())
    }

    @Test
    fun validatorDetectsGapsAndOverlaps() {
        val blocks = engine.blocksBetween("ch-b", EPOCH, EPOCH + 2 * HOUR)
        val gap = listOf(blocks[0], blocks[2])
        assertTrue(ScheduleValidator.problems(gap, blocks[0].startUtcMs, blocks[2].endUtcMs).any { it.startsWith("Gap") })
        val overlap = listOf(blocks[0], blocks[1].copy(startUtcMs = blocks[1].startUtcMs - 1))
        assertTrue(ScheduleValidator.problems(overlap, blocks[0].startUtcMs, blocks[1].endUtcMs).any { it.startsWith("Overlap") })
    }
}
