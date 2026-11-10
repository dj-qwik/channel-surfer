package com.channelsurf.core

import com.channelsurf.core.manifest.ManifestCodec
import com.channelsurf.core.manifest.ManifestResult
import com.channelsurf.core.schedule.ScheduleEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManifestCodecTest {
    private fun ok(text: String) = when (val r = ManifestCodec.decode(text)) {
        is ManifestResult.Success -> r
        is ManifestResult.Failure -> throw AssertionError("Expected success but got ${r.errors}")
    }

    private fun errors(text: String) = when (val r = ManifestCodec.decode(text)) {
        is ManifestResult.Success -> throw AssertionError("Expected failure")
        is ManifestResult.Failure -> r.errors
    }

    @Test
    fun validFixtureDecodes() {
        val r = ok(TestData.resource("manifest_valid.json"))
        assertEquals(2, r.lineup.channels.size)
        assertEquals("fixture-2026-11-03", r.lineup.revision)
        assertEquals(1995, r.lineup.assets.getValue("sat-am-1995").broadcastYear)
    }

    @Test
    fun roundTripIsLossless() {
        val original = TestData.lineup()
        val text = ManifestCodec.encode(original)
        val decoded = ok(text).lineup
        assertEquals(original.assets, decoded.assets)
        assertEquals(original.channels.sortedBy { it.number }, decoded.channels)
        // and the schedule it produces is identical
        val t = TestData.EPOCH + 1234567
        assertEquals(ScheduleEngine(original).blockAt("ch-a", t), ScheduleEngine(decoded).blockAt("ch-a", t))
    }

    @Test
    fun duplicateChannelNumbersAreRejected() {
        val e = errors(TestData.resource("manifest_duplicate_numbers.json"))
        assertTrue(e.toString(), e.any { it.startsWith("Duplicate channel number 2") })
    }

    @Test
    fun nonArchiveAndNonHttpsUrlsAreRejected() {
        val bad = TestData.resource("manifest_valid.json")
            .replace("https://archive.org/download/fixture-saturday-morning-1995/", "http://evil.example.com/")
        assertTrue(errors(bad).any { it.contains("url must be HTTPS on archive.org") })
    }

    @Test
    fun missingReferencesAndUnknownKeysAreRejected() {
        val bad = TestData.resource("manifest_valid.json")
            .replace("\"playlist\": [\"sat-am-1995\"]", "\"playlist\": [\"does-not-exist\"]")
            .replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"script\": \"<script>alert(1)</script>\",")
        val e = errors(bad)
        assertTrue(e.any { it.contains("missing asset 'does-not-exist'") })
        assertTrue(e.any { it.contains("Unknown top-level key 'script'") })
    }

    @Test
    fun wrongSchemaVersionAndGarbageAreRejected() {
        assertTrue(errors(TestData.resource("manifest_valid.json").replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")).any { it.contains("schemaVersion") })
        assertTrue(errors("not json").first().startsWith("Not valid JSON"))
        assertTrue(errors("[]").first().contains("JSON object"))
    }

    @Test
    fun oversizedManifestIsRejected() {
        val huge = "{\"x\":\"" + "a".repeat(ManifestCodec.MAX_BYTES) + "\"}"
        assertTrue(errors(huge).first().contains("exceeds"))
    }

    @Test
    fun overlongStringsAreRejected() {
        val bad = TestData.resource("manifest_valid.json").replace("\"displayName\": \"Saturday Morning\"", "\"displayName\": \"${"X".repeat(41)}\"")
        assertTrue(errors(bad).any { it.contains("displayName exceeds 40") })
    }

    @Test
    fun emptyCatalogManifestIsValid() {
        val text = """{"schemaVersion":1,"revision":"empty","channels":[{"id":"ch-02","number":2,"displayName":"Saturday Morning","epochUtcMs":1767225600000}],"assets":[]}"""
        val r = ok(text)
        assertEquals(1, r.lineup.channels.size)
        assertTrue(r.lineup.assets.isEmpty())
    }
}
