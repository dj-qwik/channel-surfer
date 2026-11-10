package com.channelsurf

import com.channelsurf.core.manifest.ManifestCodec
import com.channelsurf.core.manifest.ManifestResult
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.data.local.playlistEntities
import com.channelsurf.data.local.toEntity
import com.channelsurf.data.local.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLogicTest {
    private val lineup = (ManifestCodec.decode(FakeArchive.fixture("manifest_valid.json")) as ManifestResult.Success).lineup

    @Test
    fun roomMappingRoundTripsChannelsAndAssets() {
        val entries = lineup.channels.flatMap { it.playlistEntities() }
        lineup.channels.forEach { c -> assertEquals(c, c.toEntity().toModel(entries)) }
        lineup.assets.values.forEach { a -> assertEquals(a, a.toEntity().toModel()) }
    }

    @Test
    fun editorialRevisionIgnoresDeviceHealth() {
        val r1 = CatalogRepository.editorialRevision(lineup.channels, lineup.assets.values)
        val sick = lineup.assets.values.map { it.copy(healthStatus = HealthStatus.UNHEALTHY, lastCheckedAtUtcMs = 42) }
        assertEquals(r1, CatalogRepository.editorialRevision(lineup.channels, sick))
        val retitled = lineup.assets.values.map { it.copy(title = it.title + "!") }
        assertNotEquals(r1, CatalogRepository.editorialRevision(lineup.channels, retitled))
    }

    @Test
    fun assetIdsAreStableAndManifestSafe() {
        val id = CatalogRepository.assetIdFor("fixture-wxyz-1994-aircheck", "sub dir/WXYZ 1994 #2 (tape).mp4")
        assertEquals(id, CatalogRepository.assetIdFor("fixture-wxyz-1994-aircheck", "sub dir/WXYZ 1994 #2 (tape).mp4"))
        assertTrue(id.length <= 128)
        assertTrue(Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$").matches(id))
    }
}
