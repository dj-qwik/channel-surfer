package com.channelsurf.core

import com.channelsurf.core.schedule.CatchUpPolicy
import com.channelsurf.core.schedule.CatchUpPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpPolicyTest {
    @Test
    fun smallDriftRightAfterATuneNudgesSpeedInsteadOfSeeking() {
        // 4 s behind, 3 s after tuning: old code seeked here (1.5 s tolerance) and rebuffered again.
        val a = CatchUpPolicy.decide(-4_000, 3_000, 3_000, 1f)
        assertEquals(Action.Speed(CatchUpPolicy.FASTER), a)
        assertEquals(Action.Speed(CatchUpPolicy.FAST), CatchUpPolicy.decide(-1_000, 3_000, 3_000, 1f))
        assertEquals(Action.Speed(CatchUpPolicy.SLOW), CatchUpPolicy.decide(1_000, 3_000, 3_000, 1f))
    }

    @Test
    fun onlyBigDriftSeeksAndTheBarIsHigherWhileSettling() {
        assertTrue(CatchUpPolicy.decide(-8_000, 5_000, 3_000, 1f) is Action.Speed)
        assertTrue(CatchUpPolicy.decide(-8_000, 60_000, 3_000, 1f) is Action.Seek)
        assertTrue(CatchUpPolicy.decide(-12_000, 5_000, 3_000, 1f) is Action.Seek)
        // A struggling link raises the floor further.
        assertTrue(CatchUpPolicy.decide(-8_000, 60_000, 9_000, 1f) is Action.Speed)
    }

    @Test
    fun speedHasHysteresisAndReturnsToNormalWhenCaughtUp() {
        assertEquals(Action.Speed(1f), CatchUpPolicy.decide(-400, 60_000, 3_000, 1f))
        assertEquals(Action.Speed(CatchUpPolicy.FAST), CatchUpPolicy.decide(-400, 60_000, 3_000, CatchUpPolicy.FAST))
        assertEquals(Action.Speed(1f), CatchUpPolicy.decide(-200, 60_000, 3_000, CatchUpPolicy.FAST))
    }

    @Test
    fun startupEstimatorLearnsTheConnection() {
        val e = CatchUpPolicy.StartupEstimator(1_500)
        repeat(10) { e.record(4_000) }
        assertTrue(e.estimateMs in 3_800..4_000)
        e.record(60_000)
        assertTrue(e.estimateMs <= CatchUpPolicy.StartupEstimator.MAX_MS)
        repeat(10) { e.record(500) }
        assertTrue(e.estimateMs < 700)
    }
}
