package com.channelsurf.core

import com.channelsurf.core.archive.ArchiveParsing
import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.archive.ParseResult
import com.channelsurf.core.archive.PlayableFileSelector
import com.channelsurf.core.archive.UrlPolicy
import com.channelsurf.core.archive.YearEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArchiveParsingTest {
    private fun item(name: String, id: String) = when (val r = ArchiveParsing.parseMetadata(TestData.resource(name), id)) {
        is ParseResult.Ok -> r.value
        else -> throw AssertionError("Expected Ok, got $r")
    }

    @Test
    fun parsesSearchFixture() {
        val r = ArchiveParsing.parseSearch(TestData.resource("archive_search.json")) as ParseResult.Ok
        assertEquals(3L, r.value.numFound)
        // the doc with an invalid identifier is dropped
        assertEquals(listOf("fixture-wxyz-1994-aircheck", "fixture-saturday-morning-1995"), r.value.docs.map { it.identifier })
        assertEquals(1994, r.value.docs[0].year)
        assertEquals("Fixture Saturday Morning 1995", r.value.docs[1].title) // title given as array
    }

    @Test
    fun selectsBestPlayableFileAndRejectsNonVideo() {
        val it = item("archive_metadata_vhs.json", "fixture-wxyz-1994-aircheck")
        val c = PlayableFileSelector.select(it)
        assertEquals(listOf("WXYZ 1994 #2 (tape).mp4", "WXYZ_1994_512kb.mp4", "WXYZ 1994.webm"), c.map { it.file.name })
        assertEquals("4:3", c.first().aspectRatio)
        assertEquals(6 * 3_600_000L + 2_500, c.first().file.lengthMs)
        assertTrue(PlayableFileSelector.select(it, preferLowBandwidth = true).first().lowBandwidth)
        assertEquals(listOf("WXYZ 1994.en.vtt", "WXYZ 1994.srt"), PlayableFileSelector.captionCandidates(it).map { it.name })
    }

    @Test
    fun missingMetadataFieldsDoNotCrash() {
        val it = item("archive_metadata_missing_fields.json", "fixture-sparse")
        assertEquals("fixture-sparse", it.title)
        assertNull(it.description)
        assertTrue(it.collections.isEmpty())
        assertTrue(PlayableFileSelector.select(it).isEmpty()) // only an .ogv and a thumbnail
    }

    @Test
    fun nonexistentAndMalformedItems() {
        assertTrue(ArchiveParsing.parseMetadata("{}", "nope") is ParseResult.NotFound)
        assertTrue(ArchiveParsing.parseMetadata("<html>busy</html>", "x") is ParseResult.Malformed)
        assertTrue(ArchiveParsing.parseSearch("{\"responseHeader\":{}}") is ParseResult.Malformed)
    }

    @Test
    fun parsesLengths() {
        assertEquals(83_500L, ArchiveParsing.parseLengthMs("83.5"))
        assertEquals(3_723_000L, ArchiveParsing.parseLengthMs("1:02:03"))
        assertEquals(123_000L, ArchiveParsing.parseLengthMs("02:03"))
        assertNull(ArchiveParsing.parseLengthMs("abc"))
        assertNull(ArchiveParsing.parseLengthMs("-3"))
        assertNull(ArchiveParsing.parseLengthMs(null))
    }

    @Test
    fun downloadUrlsAreEncodedPerSegment() {
        assertEquals(
            "https://archive.org/download/fixture-id/sub%20dir/WXYZ%201994%20%232%20%28tape%29.mp4",
            ArchiveUrls.download("fixture-id", "sub dir/WXYZ 1994 #2 (tape).mp4"),
        )
        assertEquals("https://archive.org/download/x/caf%C3%A9.mp4", ArchiveUrls.download("x", "café.mp4"))
        try { ArchiveUrls.download("x", "../etc/passwd"); fail("traversal accepted") } catch (e: IllegalArgumentException) { }
        try { ArchiveUrls.download("bad id!", "a.mp4"); fail("bad id accepted") } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun advancedSearchUrlIsEncoded() {
        val u = ArchiveUrls.advancedSearch("mediatype:movies AND VHS", page = 2, rows = 25, fields = listOf("identifier", "title"))
        assertEquals(
            "https://archive.org/advancedsearch.php?q=mediatype%3Amovies%20AND%20VHS&fl%5B%5D=identifier&fl%5B%5D=title&rows=25&page=2&output=json",
            u,
        )
    }

    @Test
    fun urlPolicyAllowsOnlyHttpsArchiveHosts() {
        assertTrue(UrlPolicy.isAllowed("https://archive.org/download/a/b.mp4"))
        assertTrue(UrlPolicy.isAllowed("https://ia800300.us.archive.org/1/items/a/b.mp4"))
        assertFalse(UrlPolicy.isAllowed("http://archive.org/download/a/b.mp4"))
        assertFalse(UrlPolicy.isAllowed("https://archive.org.evil.com/a.mp4"))
        assertFalse(UrlPolicy.isAllowed("https://evilarchive.org/a.mp4"))
        assertFalse(UrlPolicy.isAllowed("https://user@archive.org/a.mp4"))
        assertFalse(UrlPolicy.isAllowed("https://archive.org:8443/a.mp4"))
        assertFalse(UrlPolicy.isAllowed("javascript:alert(1)"))
        assertTrue(UrlPolicy.isAllowed("https://cdn.example.org/a.mp4", setOf("cdn.example.org")))
    }

    @Test
    fun yearEvidenceIgnoresUploadDateAndNeverAutoVerifies() {
        val it = item("archive_metadata_vhs.json", "fixture-wxyz-1994-aircheck")
        val s = YearEvidence.suggest(it)
        assertEquals(1994, s.year)
        assertTrue(s.inEra)
        assertTrue(s.clues.none { it.year == 2019 }) // addeddate/publicdate (upload) are never evidence
    }

    @Test
    fun descriptionHtmlIsReducedToPlainText() {
        val it = item("archive_metadata_vhs.json", "fixture-wxyz-1994-aircheck")
        assertFalse(it.description!!.contains("<"))
        assertTrue(it.description!!.contains("Saturday morning & commercials"))
    }
}
