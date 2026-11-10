package com.channelsurf.core

import com.channelsurf.core.TestData.EPOCH
import com.channelsurf.core.TestData.HOUR
import com.channelsurf.core.TestData.MIN
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.SegmentKind
import com.channelsurf.core.schedule.DriftPolicy
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.schedule.SlateKind
import com.channelsurf.core.schedule.TuneResolution
import com.channelsurf.core.schedule.TuneResolver
import com.channelsurf.core.time.MonotonicClock
import com.channelsurf.core.time.SyncedBroadcastClock
import com.channelsurf.core.time.WallClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automates the "NON-NEGOTIABLE BROADCAST MODE" acceptance criteria that are decidable without a device.
 * Device-level counterparts live in app/src/androidTest.
 */
class BroadcastAcceptanceTest {
    private val lineup = TestData.lineup()
    private val engine = ScheduleEngine(lineup)
    private val resolver = TuneResolver(engine)

    private fun play(ch: String, t: Long) = resolver.resolve(ch, t) as TuneResolution.Play

    @Test
    fun tuneAt1400ReturnAt1410AdvancesExactlyTenMinutes() {
        val t1400 = EPOCH + 14 * HOUR
        val first = play("ch-a", t1400)
        val back = play("ch-a", t1400 + 10 * MIN)
        assertEquals(10 * MIN, back.filePositionMs - first.filePositionMs)
    }

    @Test
    fun switchAtoBtoAAdvancesAWhileTunedAway() {
        val t0 = EPOCH + 3 * HOUR + 7 * MIN
        val a1 = play("ch-a", t0)
        play("ch-b", t0 + 2 * MIN) // watch B for a while
        val a2 = play("ch-a", t0 + 5 * MIN + 1_234)
        assertEquals(5 * MIN + 1_234, a2.filePositionMs - a1.filePositionMs)
    }

    @Test
    fun processDeathAndRelaunchAfter30MinutesStaysAlignedToUtc() {
        val t0 = EPOCH + 40 * HOUR
        val before = play("ch-b", t0)
        // "Kill" everything: brand-new engine/resolver built from the persisted manifest only.
        val relaunched = TuneResolver(ScheduleEngine(TestData.lineup())).resolve("ch-b", t0 + 30 * MIN) as TuneResolution.Play
        assertEquals(engine.blockAt("ch-b", t0 + 30 * MIN), relaunched.block)
        assertTrue(relaunched.block.contains(t0 + 30 * MIN))
        assertTrue(before.block.id != relaunched.block.id) // 30-min shows: we missed one
    }

    @Test
    fun tuningDuringOriginalCommercialPlaysTheCommercial() {
        // ch-a airs the 6h tape with an embedded break at file positions 10:00–12:00.
        val tapeStart = engine.blockAt("ch-a", EPOCH).startUtcMs
        val r = play("ch-a", tapeStart + 11 * MIN)
        assertEquals(SegmentKind.COMMERCIAL, r.segment?.kind)
        assertEquals(11 * MIN, r.filePositionMs) // not skipped ahead to the next program segment
        assertEquals(tapeStart + 6 * HOUR, r.playUntilUtcMs) // the tape is one indivisible broadcast asset
    }

    @Test
    fun bufferingFor45SecondsCatchesUpToLivePosition() {
        val t0 = EPOCH + 5 * HOUR + 3 * MIN
        val r = play("ch-a", t0)
        // Player stalled at the original position for 45 s.
        val d = DriftPolicy.evaluate(r, nowUtcMs = t0 + 45_000, actualFilePositionMs = r.filePositionMs)
        d as DriftPolicy.Decision.Seek
        assertEquals(r.filePositionMs + 45_000, d.toFilePositionMs)
        assertEquals(-45_000, d.driftMs)
    }

    @Test
    fun smallDriftWithinToleranceIsLeftAlone() {
        val t0 = EPOCH + 2 * HOUR
        val r = play("ch-a", t0)
        val d = DriftPolicy.evaluate(r, t0 + 10_000, r.filePositionMs + 10_000 - 2_000)
        assertEquals(DriftPolicy.Decision.InSync, d)
    }

    @Test
    fun blockEndAdvancesToNextScheduledAsset() {
        val r = play("ch-b", EPOCH + 29 * MIN)
        assertEquals(DriftPolicy.Decision.AdvanceToNextBlock, DriftPolicy.evaluate(r, EPOCH + 30 * MIN, 30 * MIN))
        assertEquals("showB", play("ch-b", r.playUntilUtcMs).asset.id)
    }

    @Test
    fun twoDevicesAtSameInstantSeeSamePosition() {
        val instant = 1_800_000_000_000L
        val deviceA = TuneResolver(ScheduleEngine(TestData.lineup())).resolve("ch-a", instant)
        val deviceB = TuneResolver(ScheduleEngine(TestData.lineup())).resolve("ch-a", instant)
        assertEquals(deviceA, deviceB)
    }

    @Test
    fun deletedArchiveItemFallsBackWithoutScheduleDrift() {
        val t = EPOCH + 3 * HOUR + 30 * MIN + 4 * MIN // showB slot, 4 min in
        val healthy = play("ch-b", t)
        val unhealthy = TuneResolver(engine, healthOf = { if (it == "showB") HealthStatus.UNHEALTHY else HealthStatus.OK }).resolve("ch-b", t)
        unhealthy as TuneResolution.Play
        assertTrue(unhealthy.isSubstitute)
        assertEquals("filler", unhealthy.asset.id)
        assertEquals(healthy.block, unhealthy.block) // same slot, same clock
        assertEquals(4 * MIN, unhealthy.filePositionMs)
        // filler is 10 minutes: it ends at slot+10m and is NOT looped for the rest of the 30-minute slot.
        assertEquals(healthy.block.startUtcMs + 10 * MIN, unhealthy.playUntilUtcMs)
        val later = TuneResolver(engine, healthOf = { if (it == "showB") HealthStatus.UNHEALTHY else HealthStatus.OK })
            .resolve("ch-b", healthy.block.startUtcMs + 15 * MIN)
        later as TuneResolution.Slate
        assertEquals(SlateKind.TECHNICAL_DIFFICULTIES, later.kind)
        assertEquals(healthy.block.endUtcMs, later.untilUtcMs)
        // The next slot is unaffected.
        assertEquals(engine.blockAt("ch-b", healthy.block.endUtcMs), play("ch-b", healthy.block.endUtcMs).block)
    }

    @Test
    fun noFallbackPoolShowsTechnicalDifficultiesUntilSlotEnd() {
        val base = TestData.lineup()
        val e = ScheduleEngine(base.copy(channels = base.channels.map { it.copy(fallbackPool = emptyList()) }))
        val r = TuneResolver(e, healthOf = { HealthStatus.UNHEALTHY }).resolve("ch-b", EPOCH + 5 * MIN)
        r as TuneResolution.Slate
        assertEquals(SlateKind.TECHNICAL_DIFFICULTIES, r.kind)
        assertEquals(EPOCH + 30 * MIN, r.untilUtcMs)
    }

    @Test
    fun deviceClockAndTimezoneChangesDoNotMoveServerSyncedBroadcast() {
        var wall = 1_000L
        var mono = 50_000L
        val clock = SyncedBroadcastClock(WallClock { wall }, MonotonicClock { mono })
        assertEquals(1_000L, clock.nowUtcMs()) // unsynced: device wall clock
        val serverNow = EPOCH + 14 * HOUR
        assertTrue(clock.applyServerSample(serverNow, requestSentMonoMs = 49_900, responseReceivedMonoMs = 50_000))
        assertEquals(serverNow + 50, clock.nowUtcMs())
        // User sets the device clock back a day (or changes time zone): broadcast time is unaffected.
        wall -= 24 * HOUR
        mono += 10 * MIN
        assertEquals(serverNow + 50 + 10 * MIN, clock.nowUtcMs())
        val r1 = play("ch-a", clock.nowUtcMs())
        assertEquals(engine.blockAt("ch-a", serverNow + 50 + 10 * MIN), r1.block)
    }

    @Test
    fun badServerSamplesAreRejected() {
        val clock = SyncedBroadcastClock({ 0L }, { 100_000L })
        assertTrue(!clock.applyServerSample(EPOCH, 0, 10_000)) // 10 s round trip
        assertTrue(!clock.applyServerSample(EPOCH, 100, 50)) // negative RTT
        assertTrue(!clock.isServerSynced)
    }
}
