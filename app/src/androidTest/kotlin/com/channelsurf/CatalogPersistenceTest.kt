package com.channelsurf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.schedule.ScheduleValidator
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.data.catalog.ImportResult
import com.channelsurf.data.local.AppDatabase
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogPersistenceTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: CatalogRepository
    private var now = 1_767_225_600_000L
    private val fixture by lazy {
        InstrumentationRegistry.getInstrumentation().context.assets.open("manifest_valid.json").bufferedReader().readText()
    }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.inMemory(ctx)
        repo = CatalogRepository(ctx, db, DiagnosticsLog(), nowUtcMs = { now })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun bundledLineupSeedsWithNoMedia() = runTest {
        assertTrue(repo.seedIfEmpty())
        val lineup = repo.currentLineup()
        assertTrue(lineup.channels.size >= 40) // full late-90s cable lineup
        assertTrue("default manifest must not hardcode media links", lineup.assets.isEmpty())
        assertEquals(lineup.channels.size, lineup.channels.map { it.number }.distinct().size)
        assertFalse(repo.seedIfEmpty()) // idempotent
    }

    @Test
    fun importPersistsAndScheduleSurvivesReopen() = runTest {
        val r = repo.importManifest(fixture, "TEST", signatureVerified = false)
        assertTrue(r is ImportResult.Imported)
        val first = repo.currentLineup()
        // "Process death": a new repository over the same database yields the identical schedule.
        val again = CatalogRepository(InstrumentationRegistry.getInstrumentation().targetContext, db, DiagnosticsLog(), nowUtcMs = { now })
        val second = again.currentLineup()
        assertEquals(first.revision, second.revision)
        val t = now + 5_000_000
        val e1 = ScheduleEngine(first, allowTestOnly = true)
        val e2 = ScheduleEngine(second, allowTestOnly = true)
        assertEquals(e1.blockAt("ch-02-saturday-morning", t), e2.blockAt("ch-02-saturday-morning", t))
        val window = e2.blocksBetween("ch-02-saturday-morning", t - 86_400_000, t + 7 * 86_400_000)
        assertTrue(ScheduleValidator.problems(window, t - 86_400_000, t + 7 * 86_400_000).isEmpty())
    }

    @Test
    fun takedownBlocksImmediatelyAndSurvivesReimport() = runTest {
        repo.importManifest(fixture, "TEST", signatureVerified = false)
        repo.takedown("fixture-saturday-morning-1995", "Rights-holder request #42", requestedBy = "test")
        val after = repo.currentLineup()
        assertEquals(RightsStatus.BLOCKED, after.assets.getValue("sat-am-1995").rightsStatus)
        assertTrue(after.channels.none { "sat-am-1995" in it.playlist })
        // A later manifest that still lists the item cannot un-block it.
        repo.importManifest(fixture, "TEST", signatureVerified = false)
        val reimported = repo.currentLineup()
        assertEquals(RightsStatus.BLOCKED, reimported.assets.getValue("sat-am-1995").rightsStatus)
        assertTrue(reimported.channels.none { "sat-am-1995" in it.playlist })
        assertFalse(ScheduleEngine(reimported, allowTestOnly = true).hasProgramming("ch-02-saturday-morning"))
    }

    @Test
    fun exportReimportsCleanly() = runTest {
        repo.importManifest(fixture, "TEST", signatureVerified = false)
        val exported = repo.exportManifest()
        val r = repo.importManifest(exported, "TEST", signatureVerified = false)
        assertTrue(r.toString(), r is ImportResult.Imported)
        assertEquals(1, repo.currentLineup().assets.size)
    }

    @Test
    fun healthIsDeviceLocalAndDoesNotChangeRevision() = runTest {
        repo.importManifest(fixture, "TEST", signatureVerified = false)
        val before = repo.currentLineup()
        repo.markHealth("sat-am-1995", HealthStatus.UNHEALTHY, 404)
        val after = repo.lineup.first { it.assets.getValue("sat-am-1995").healthStatus == HealthStatus.UNHEALTHY }
        assertEquals(before.revision, after.revision)
    }

    @Test
    fun invalidManifestIsRejectedAndCatalogUntouched() = runTest {
        repo.importManifest(fixture, "TEST", signatureVerified = false)
        val r = repo.importManifest(fixture.replace("\"number\": 3", "\"number\": 2"), "TEST", signatureVerified = false)
        assertTrue(r is ImportResult.Rejected)
        assertEquals(2, repo.currentLineup().channels.size)
    }
}
