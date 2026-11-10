package com.channelsurf

import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.net.BackoffPolicy
import com.channelsurf.core.net.FailureClass
import com.channelsurf.data.archive.ArchiveHttp
import com.channelsurf.data.archive.ArchiveOutcome
import com.channelsurf.data.archive.ArchiveRepository
import com.channelsurf.data.archive.HttpResult
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveHttpTest {
    private val url = ArchiveUrls.metadata("fixture-wxyz-1994-aircheck")
    private val fastBackoff = BackoffPolicy(baseDelayMs = 1, maxDelayMs = 2, maxAttempts = 4)

    private fun http(fake: FakeArchive) = ArchiveHttp(fake.client, DiagnosticsLog(), fastBackoff)

    @Test
    fun retries429HonoringRetryAfterThenSucceeds() = runTest {
        val fake = FakeArchive().enqueue(429, headers = mapOf("Retry-After" to "0")).enqueue(503).enqueue(200, "{\"ok\":true}")
        val r = http(fake).getText(url)
        assertTrue(r is HttpResult.Ok)
        assertEquals(3, fake.requests.size)
    }

    @Test
    fun permanent404IsNotRetried() = runTest {
        val fake = FakeArchive().enqueue(404).enqueue(200, "{}")
        val r = http(fake).getText(url) as HttpResult.Failed
        assertEquals(FailureClass.PERMANENT_MEDIA, r.failure)
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun persistent5xxGivesUpAfterBoundedAttempts() = runTest {
        val fake = FakeArchive().apply { repeat(10) { enqueue(503) } }
        val r = http(fake).getText(url) as HttpResult.Failed
        assertEquals(503, r.code)
        assertEquals(fastBackoff.maxAttempts, fake.requests.size)
    }

    @Test
    fun oversizedResponsesAreRejected() = runTest {
        val fake = FakeArchive().enqueue(200, "x".repeat(5_000))
        val r = ArchiveHttp(fake.client, DiagnosticsLog(), fastBackoff).getText(url, maxChars = 1_000) as HttpResult.Failed
        assertEquals(FailureClass.CLIENT, r.failure)
    }

    @Test
    fun nonArchiveUrlsAreNeverRequested() = runTest {
        val fake = FakeArchive().enqueue(200, "{}")
        val r = http(fake).getText("https://example.com/x.json") as HttpResult.Failed
        assertEquals(FailureClass.CLIENT, r.failure)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun probeDetectsByteRangeSeeking() = runTest {
        val media = ArchiveUrls.download("fixture-id", "tape.mp4")
        val fake = FakeArchive()
            .enqueue(206, "ab", mapOf("Content-Range" to "bytes 0-1/123456789", "Content-Type" to "video/mp4"))
            .enqueue(200, "abcdef", mapOf("Content-Type" to "video/mp4"))
            .enqueue(404)
        val h = http(fake)
        val ok = h.probe(media)
        assertTrue(ok.ok && ok.supportsRange)
        assertEquals(123_456_789L, ok.contentLength)
        assertEquals("bytes=0-1", fake.requestHeaders.first()["Range"])
        val noRange = h.probe(media)
        assertTrue(noRange.ok)
        assertFalse(noRange.supportsRange)
        val gone = h.probe(media)
        assertEquals(FailureClass.PERMANENT_MEDIA, gone.failure)
    }

    @Test
    fun repositoryResolvesPlayableFilesFromMockMetadata() = runTest {
        val fake = FakeArchive().enqueue(200, FakeArchive.fixture("archive_metadata_vhs.json"))
        val repo = ArchiveRepository(http(fake), InMemoryArchiveCache())
        val r = repo.resolvePlayableFiles("fixture-wxyz-1994-aircheck", EraPolicy()) as ArchiveOutcome.Success
        assertEquals("WXYZ 1994 #2 (tape).mp4", r.value.candidates.first().file.name)
        assertEquals(1994, r.value.year.year)
        assertEquals(2, r.value.captions.size)
    }

    @Test
    fun repositoryFallsBackToStaleCacheWhenArchiveIsDown() = runTest {
        var now = 0L
        val cache = InMemoryArchiveCache()
        val fake = FakeArchive().enqueue(200, FakeArchive.fixture("archive_metadata_vhs.json")).apply { repeat(5) { enqueue(503) } }
        val repo = ArchiveRepository(http(fake), cache, nowUtcMs = { now })
        assertTrue(repo.metadata("fixture-wxyz-1994-aircheck") is ArchiveOutcome.Success)
        now += ArchiveRepository.METADATA_TTL_MS + 1
        val stale = repo.metadata("fixture-wxyz-1994-aircheck") as ArchiveOutcome.Success
        assertTrue(stale.fromStaleCache)
    }

    @Test
    fun missingItemIsAPermanentError() = runTest {
        val fake = FakeArchive().enqueue(200, "{}")
        val r = ArchiveRepository(http(fake), InMemoryArchiveCache()).metadata("fixture-gone") as ArchiveOutcome.Error
        assertTrue(r.permanent)
    }

    @Test
    fun searchParsesMockResults() = runTest {
        val fake = FakeArchive().enqueue(200, FakeArchive.fixture("archive_search.json"))
        val r = ArchiveRepository(http(fake), InMemoryArchiveCache()).search("vhs aircheck") as ArchiveOutcome.Success
        assertEquals(2, r.value.docs.size)
        assertTrue(fake.requests.first().startsWith("https://archive.org/advancedsearch.php?q="))
    }

    @Test
    fun healthCheckMapsStatusesToHealth() = runTest {
        val media = ArchiveUrls.download("fixture-id", "tape.mp4")
        val fake = FakeArchive()
            .enqueue(206, "ab", mapOf("Content-Range" to "bytes 0-1/1000", "Content-Type" to "video/mp4"))
            .enqueue(403)
        val repo = ArchiveRepository(http(fake), InMemoryArchiveCache())
        assertEquals(HealthStatus.OK, repo.healthCheckUrl(media).status)
        assertEquals(HealthStatus.UNHEALTHY, repo.healthCheckUrl(media).status)
    }
}
