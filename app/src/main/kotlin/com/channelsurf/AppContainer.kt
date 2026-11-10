package com.channelsurf

import android.content.Context
import android.os.SystemClock
import com.channelsurf.cast.CastManager
import com.channelsurf.core.model.ReviewPolicy
import com.channelsurf.core.time.MonotonicClock
import com.channelsurf.core.time.SyncedBroadcastClock
import com.channelsurf.core.time.WallClock
import com.channelsurf.data.archive.ArchiveHttp
import com.channelsurf.data.archive.ArchiveRepository
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.data.discovery.DiscoveryService
import com.channelsurf.data.local.AppDatabase
import com.channelsurf.data.net.NetworkMonitor
import com.channelsurf.data.prefs.SettingsRepository
import com.channelsurf.data.remote.ScheduleServerClient
import com.channelsurf.data.schedule.ScheduleRepository
import com.channelsurf.diagnostics.DiagnosticsLog
import com.channelsurf.playback.PlaybackVerifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lightweight manual dependency injection. One instance per process, created by [ChannelSurfApplication].
 * Tests can construct an AppContainer with an in-memory database.
 */
class AppContainer(
    context: Context,
    val database: AppDatabase = AppDatabase.build(context),
) {
    private val appContext = context.applicationContext

    /** Process-wide scope for repositories (not for UI work). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val diagnostics = DiagnosticsLog()

    val clock = SyncedBroadcastClock(
        wall = WallClock { System.currentTimeMillis() },
        mono = MonotonicClock { SystemClock.elapsedRealtime() },
    )

    val settings = SettingsRepository.create(appContext)
    val network = NetworkMonitor(appContext, appScope)

    private val baseHttp: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(appContext.cacheDir, "http"), 20L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false) // never downgrade
        .build()

    val archiveHttp = ArchiveHttp(baseHttp, diagnostics)
    val archive = ArchiveRepository(archiveHttp, database.archiveCacheDao())
    val catalog = CatalogRepository(appContext, database, diagnostics)
    val schedule = ScheduleRepository(
        catalog = catalog,
        dao = database.scheduleDao(),
        clock = clock,
        diagnostics = diagnostics,
        scope = appScope,
        allowTestAssets = BuildConfig.ALLOW_TEST_ASSETS,
        reviewPolicy = settings.settings.map { if (it.autoApprove) ReviewPolicy.AUTOMATIC else ReviewPolicy.MANUAL },
    )
    val scheduleServer = ScheduleServerClient(baseHttp, settings, catalog, clock, diagnostics)
    val verifier = PlaybackVerifier(appContext, archiveHttp.client)
    val discovery = DiscoveryService(archive, catalog, verifier, diagnostics)
    val cast = CastManager(appContext, diagnostics)
    val roku = com.channelsurf.cast.RokuManager(appContext, appScope, diagnostics)
    val playbackBridge = com.channelsurf.playback.PlaybackBridge()
}
