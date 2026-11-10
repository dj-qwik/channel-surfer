package com.channelsurf.core

import com.channelsurf.core.archive.ContentClassifier
import com.channelsurf.core.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentClassifierTest {
    private fun type(title: String, min: Double, desc: String? = null) =
        ContentClassifier.classify(title, desc, emptyList(), (min * 60_000).toLong()).type

    @Test
    fun classifiesTypicalArchiveUploads() {
        assertEquals(ContentType.EPISODE, type("Dexter's Laboratory - Dee Dee's Room", 22.0))
        assertEquals(ContentType.MOVIE, type("Night of the Comet (1984) TBS broadcast", 105.0))
        assertEquals(ContentType.AIRCHECK_BLOCK, type("Cartoon Network VHS tape 1998", 360.0))
        assertEquals(ContentType.AIRCHECK_BLOCK, type("Toonami block with commercials", 120.0))
        assertEquals(ContentType.PROMO_BUMPER, type("Cartoon Network bumper", 0.3))
        assertEquals(ContentType.COMMERCIAL, type("Crystal Pepsi commercial", 0.5))
        assertEquals(ContentType.AIRCHECK_BLOCK, type("1995 commercials compilation", 14.0))
        assertEquals(ContentType.SHOPPING, type("QVC 1996 jewelry hour", 60.0))
        assertEquals(ContentType.NEWS, type("WABC Eyewitness News at 6 1993", 30.0))
    }

    @Test
    fun extractsSeriesNames() {
        assertEquals("Dexter's Laboratory", ContentClassifier.seriesOf("Dexter's Laboratory - Dee Dee's Room"))
        assertEquals("Daria", ContentClassifier.seriesOf("Daria (1997) - Esteemsters"))
        assertNull(ContentClassifier.seriesOf("Just a title"))
    }
}
