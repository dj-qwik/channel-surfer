package com.channelsurf.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.channelsurf.ChannelSurfApplication
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.data.remote.SyncResult
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Periodic metadata refresh: sync server time + signed manifest (if configured), re-check the health of
 * airable Archive files, prune caches, and re-materialize the 7-day schedule window.
 */
class RefreshWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as ChannelSurfApplication).container
        c.catalog.seedIfEmpty()

        when (val r = c.scheduleServer.sync()) {
            is SyncResult.Failed -> c.diagnostics.warn("refresh.server", r.message)
            is SyncResult.Synced -> c.diagnostics.info("refresh.server", r.message)
            SyncResult.NotConfigured -> Unit
        }

        val now = System.currentTimeMillis()
        val lineup = c.catalog.currentLineup()
        val scheduled = lineup.channels.flatMap { it.playlist + it.fallbackPool + it.fixedEntries.map { f -> f.assetId } }.toSet()
        val due = lineup.assets.values.filter { a ->
            a.id in scheduled && a.rightsStatus != RightsStatus.BLOCKED &&
                (a.lastCheckedAtUtcMs == null || now - a.lastCheckedAtUtcMs!! > RECHECK_AFTER_MS || a.healthStatus != HealthStatus.OK)
        }
        var unhealthy = 0
        for (a in due.take(MAX_CHECKS_PER_RUN)) {
            if (isStopped) break
            val h = c.archive.healthCheck(a)
            c.catalog.markHealth(a.id, h.status, h.statusCode)
            if (h.status == HealthStatus.UNHEALTHY) unhealthy++
            delay(500) // be polite to archive.org
        }
        if (due.isNotEmpty()) c.diagnostics.info("refresh.health", "Checked ${minOf(due.size, MAX_CHECKS_PER_RUN)} files, $unhealthy unhealthy")

        c.archive.pruneCache()
        c.schedule.refresh()
        return Result.success()
    }

    companion object {
        const val RECHECK_AFTER_MS = 24 * 3_600_000L
        const val MAX_CHECKS_PER_RUN = 60
    }
}

object RefreshScheduler {
    private const val PERIODIC = "channel-surf-refresh"
    private const val NOW = "channel-surf-refresh-now"

    fun ensureScheduled(context: Context, wifiOnly: Boolean, replace: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req,
        )
    }

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }
}
