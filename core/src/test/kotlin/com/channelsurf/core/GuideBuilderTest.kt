package com.channelsurf.core

import com.channelsurf.core.TestData.EPOCH
import com.channelsurf.core.TestData.HOUR
import com.channelsurf.core.TestData.MIN
import com.channelsurf.core.guide.GuideBuilder
import com.channelsurf.core.schedule.ScheduleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class GuideBuilderTest {
    private val ny = ZoneId.of("America/New_York")
    private val phoenix = ZoneId.of("America/Phoenix")

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun alignsToLocalHalfHour() {
        val t = utc(2026, 11, 3, 7, 52, phoenix)
        assertEquals(utc(2026, 11, 3, 7, 30, phoenix), GuideBuilder.alignToLocalHalfHour(t, phoenix))
        val t2 = utc(2026, 11, 3, 7, 12, phoenix)
        assertEquals(utc(2026, 11, 3, 7, 0, phoenix), GuideBuilder.alignToLocalHalfHour(t2, phoenix))
    }

    @Test
    fun alignsInHalfHourOffsetZones() {
        val india = ZoneId.of("Asia/Kolkata")
        val nepal = ZoneId.of("Asia/Kathmandu") // UTC+5:45
        val t = utc(2026, 6, 1, 10, 50, nepal)
        assertEquals(utc(2026, 6, 1, 10, 30, nepal), GuideBuilder.alignToLocalHalfHour(t, nepal))
        val t2 = utc(2026, 6, 1, 10, 10, india)
        assertEquals(utc(2026, 6, 1, 10, 0, india), GuideBuilder.alignToLocalHalfHour(t2, india))
    }

    @Test
    fun fallBackDstNightRepeatsLocalLabelsButSlotsStayUtcContiguous() {
        // 2026-11-01 02:00 EDT -> 01:00 EST
        val start = utc(2026, 11, 1, 0, 30, ny)
        val labels = GuideBuilder.slots(start, 6, ny).map { it.label }
        assertEquals(listOf("12:30 AM", "1:00 AM", "1:30 AM", "1:00 AM", "1:30 AM", "2:00 AM"), labels)
        val slots = GuideBuilder.slots(start, 6, ny)
        slots.zipWithNext().forEach { (a, b) -> assertEquals(GuideBuilder.SLOT_MS, b.startUtcMs - a.startUtcMs) }
    }

    @Test
    fun springForwardSkipsTheMissingHour() {
        // 2026-03-08 02:00 EST -> 03:00 EDT
        val start = utc(2026, 3, 8, 1, 0, ny)
        assertEquals(listOf("1:00 AM", "1:30 AM", "3:00 AM", "3:30 AM"), GuideBuilder.slots(start, 4, ny).map { it.label })
    }

    @Test
    fun scheduleIsIdenticalAcrossTimeZonesOnlyLabelsDiffer() {
        val engine = ScheduleEngine(TestData.lineup())
        val now = EPOCH + 50 * HOUR + 10 * MIN
        val tokyo = ZoneId.of("Asia/Tokyo")
        val startNy = GuideBuilder.alignToLocalHalfHour(now, ny)
        val startTokyo = GuideBuilder.alignToLocalHalfHour(now, tokyo)
        assertEquals(startNy, startTokyo) // both whole-hour-offset zones align to the same instant
        val rowsNy = GuideBuilder.rows(engine, TestData.lineup().channels, startNy, startNy + 2 * HOUR, now)
        val rowsTokyo = GuideBuilder.rows(engine, TestData.lineup().channels, startTokyo, startTokyo + 2 * HOUR, now)
        assertEquals(rowsNy, rowsTokyo)
    }

    @Test
    fun midnightCrossingIsContiguous() {
        val engine = ScheduleEngine(TestData.lineup())
        val start = utc(2026, 12, 31, 23, 0, ny)
        val rows = GuideBuilder.rows(engine, TestData.lineup().channels, start, start + 2 * HOUR, start)
        rows.forEach { row ->
            assertEquals(start, row.cells.first().visibleStartUtcMs)
            assertEquals(start + 2 * HOUR, row.cells.last().visibleEndUtcMs)
            row.cells.zipWithNext().forEach { (a, b) -> assertEquals(a.visibleEndUtcMs, b.visibleStartUtcMs) }
        }
        assertEquals("Fri Jan 1", GuideBuilder.dayLabel(start + 90 * MIN, ny))
    }

    @Test
    fun cellsAreClippedAndFlaggedAndSlatesMerged() {
        val engine = ScheduleEngine(TestData.lineup())
        val start = EPOCH + 90 * MIN
        val rows = GuideBuilder.rows(engine, TestData.lineup().channels, start, start + 90 * MIN, start + 5 * MIN)
        val a = rows.first { it.channel.id == "ch-a" }
        assertEquals(1, a.cells.size) // 6-hour tape spans the window
        assertTrue(a.cells[0].continuesFromLeft && a.cells[0].continuesToRight)
        val empty = rows.first { it.channel.id == "ch-empty" }
        assertEquals(1, empty.cells.size) // hourly slates merged
        assertTrue(empty.cells[0].isOnNow)
        val b = rows.first { it.channel.id == "ch-b" }
        assertEquals(3, b.cells.size)
        assertTrue(b.cells[0].isOnNow)
    }

    @Test
    fun nowLineFraction() {
        assertEquals(0.5f, GuideBuilder.nowFraction(0, 100, 50)!!, 0.0001f)
        assertNull(GuideBuilder.nowFraction(0, 100, 100))
    }
}
