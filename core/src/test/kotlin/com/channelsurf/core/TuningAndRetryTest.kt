package com.channelsurf.core

import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.net.BackoffPolicy
import com.channelsurf.core.net.FailureClass
import com.channelsurf.core.net.HttpFailures
import com.channelsurf.core.net.RetryAfter
import com.channelsurf.core.schedule.Eligibility
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.security.ManifestSignature
import com.channelsurf.core.tuning.ChannelNavigator
import com.channelsurf.core.tuning.NumberEntry
import com.channelsurf.core.tuning.TuneHistory
import com.channelsurf.core.tuning.TuneSequencer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TuningAndRetryTest {
    private val nav = ChannelNavigator(TestData.lineup().channels)

    @Test
    fun channelUpDownWraps() {
        assertEquals("ch-b", nav.up("ch-a")?.id)
        assertEquals("ch-a", nav.up("ch-empty")?.id)
        assertEquals("ch-empty", nav.down("ch-a")?.id)
        assertEquals("ch-a", nav.up(null)?.id)
        assertNull(ChannelNavigator(emptyList()).up("x"))
    }

    @Test
    fun disabledChannelsAreSkipped() {
        val n = ChannelNavigator(TestData.lineup().channels.map { if (it.id == "ch-b") it.copy(enabled = false) else it })
        assertEquals("ch-empty", n.up("ch-a")?.id)
        assertNull(n.byNumber(3))
    }

    @Test
    fun previousChannelToggle() {
        var h = TuneHistory()
        h = h.tuned("ch-a").tuned("ch-b")
        assertEquals("ch-a", h.previousId)
        h = h.tuned(h.previousId!!)
        assertEquals("ch-a", h.currentId)
        assertEquals("ch-b", h.previousId)
        assertEquals(h, h.tuned("ch-a")) // re-tuning same channel does not lose LAST
    }

    @Test
    fun numberEntryCommitsAfterTimeout() {
        val e = NumberEntry(timeoutMs = 1_500)
        assertNull(e.press(1, 1_000))
        assertEquals("1-", e.display)
        assertNull(e.press(2, 2_000))
        assertNull(e.commitIfDue(3_499))
        assertEquals(12, e.commitIfDue(3_500))
        assertFalse(e.isActive)
    }

    @Test
    fun numberEntryCommitsImmediatelyAtThreeDigits() {
        val e = NumberEntry()
        e.press(1, 0); e.press(0, 10)
        assertEquals(102, e.press(2, 20))
        assertNull(e.commitNow())
    }

    @Test
    fun tuneSequencerDiscardsStaleWork() {
        val s = TuneSequencer()
        val first = s.begin()
        val second = s.begin()
        assertFalse(s.isCurrent(first))
        assertTrue(s.isCurrent(second))
    }

    @Test
    fun rapidChannelChangesOnlyLastWins() {
        val s = TuneSequencer()
        val applied = mutableListOf<Int>()
        val pending = (1..20).map { ch -> s.begin() to ch }
        // completions arrive out of order
        pending.shuffled(Random(7)).forEach { (token, ch) -> if (s.isCurrent(token)) applied += ch }
        assertEquals(listOf(20), applied)
    }

    @Test
    fun httpFailureClassification() {
        assertEquals(FailureClass.PERMANENT_MEDIA, HttpFailures.classify(404))
        assertEquals(FailureClass.PERMANENT_MEDIA, HttpFailures.classify(403))
        assertEquals(FailureClass.TRANSIENT, HttpFailures.classify(429))
        assertEquals(FailureClass.TRANSIENT, HttpFailures.classify(503))
        assertEquals(FailureClass.CLIENT, HttpFailures.classify(400))
    }

    @Test
    fun retryAfterParsing() {
        assertEquals(120_000L, RetryAfter.parseDelayMs("120", 0))
        val now = java.time.ZonedDateTime.parse("2026-11-03T15:00:00Z").toInstant().toEpochMilli()
        assertEquals(30_000L, RetryAfter.parseDelayMs("Tue, 03 Nov 2026 15:00:30 GMT", now))
        assertNull(RetryAfter.parseDelayMs("soon", now))
        assertNull(RetryAfter.parseDelayMs(null, now))
    }

    @Test
    fun backoffIsBoundedJitteredAndHonorsRetryAfter() {
        val p = BackoffPolicy(baseDelayMs = 1_000, maxDelayMs = 8_000, maxAttempts = 4, random = Random(1))
        for (attempt in 1..10) {
            val d = p.delayMs(attempt)
            val ceiling = minOf(8_000L, 1_000L shl (attempt - 1))
            assertTrue("attempt $attempt delay $d", d in ceiling / 2..ceiling)
        }
        assertEquals(42_000L, p.delayMs(1, retryAfterMs = 42_000))
        assertEquals(p.maxRetryAfterMs, p.delayMs(1, retryAfterMs = Long.MAX_VALUE))
        assertTrue(p.shouldRetry(3))
        assertFalse(p.shouldRetry(4))
    }

    @Test
    fun eligibilityRequiresRightsVerifiedEraAndSeekability() {
        val era = EraPolicy()
        assertTrue(Eligibility.isEligible(TestData.asset("ok", 1000), era))
        assertTrue(Eligibility.reasons(TestData.asset("r", 1000, rights = RightsStatus.REVIEW_REQUIRED), era).any { it.contains("Rights") })
        assertTrue(Eligibility.reasons(TestData.asset("y", 1000, verified = false), era).any { it.contains("not verified") })
        assertTrue(Eligibility.reasons(TestData.asset("o", 1000, year = 1989), era).any { it.contains("outside 1990–2015") })
        assertTrue(Eligibility.reasons(TestData.asset("o2", 1000, year = 2016), era).any { it.contains("outside") })
        assertTrue(Eligibility.reasons(TestData.asset("s", 1000, seekable = false), era).any { it.contains("seekable") })
        assertFalse(Eligibility.isEligible(TestData.asset("t", 1000, rights = RightsStatus.TEST_ONLY), era))
        assertTrue(Eligibility.isEligible(TestData.asset("t", 1000, rights = RightsStatus.TEST_ONLY), era, allowTestOnly = true))
        assertTrue(Eligibility.reasons(TestData.asset("b", 1000, rights = RightsStatus.BLOCKED), era).any { it.contains("Blocked") })
    }

    @Test
    fun manifestSignatureRoundTrip() {
        val kp = ManifestSignature.generateKeyPair()
        val payload = "{\"revision\":\"r1\"}".toByteArray()
        val sig = ManifestSignature.sign(payload, kp.private)
        val pub = ManifestSignature.publicKeyFromBase64(ManifestSignature.publicKeyToBase64(kp.public))
        assertTrue(ManifestSignature.verify(payload, sig, pub))
        assertFalse(ManifestSignature.verify("{\"revision\":\"r2\"}".toByteArray(), sig, pub))
        assertFalse(ManifestSignature.verify(payload, null, pub))
        assertFalse(ManifestSignature.verify(payload, "not-base64!!", pub))
    }
}
