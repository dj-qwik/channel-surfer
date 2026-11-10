package com.channelsurf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.channelsurf.data.prefs.AnalogEffects
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPersistenceTest {
    private val container get() =
        (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ChannelSurfApplication).container

    @Test
    fun lastChannelAndFavoritesPersistButNoPlayheadExists() = runTest {
        val s = container.settings
        s.recordTune("ch-05-music-television", "ch-02-saturday-morning", 1_767_225_600_000L)
        val bm = s.currentBookmark()
        assertEquals("ch-05-music-television", bm.lastChannelId)
        assertEquals("ch-02-saturday-morning", bm.previousChannelId)
        val favBefore = "ch-05-music-television" in bm.favoriteChannelIds
        s.toggleFavorite("ch-05-music-television")
        assertEquals(!favBefore, "ch-05-music-television" in s.currentBookmark().favoriteChannelIds)
        s.toggleFavorite("ch-05-music-television") // restore
        // The bookmark type has no position field at all: returning to a channel always joins live.
        assertTrue(com.channelsurf.data.prefs.PlaybackBookmark::class.java.declaredFields.none { it.name.contains("position", ignoreCase = true) })
    }

    @Test
    fun settingsRoundTrip() = runTest {
        val s = container.settings
        val before = s.current().analogEffects
        s.setAnalogEffects(AnalogEffects.FULL)
        assertEquals(AnalogEffects.FULL, s.current().analogEffects)
        s.setAnalogEffects(before)
    }
}
