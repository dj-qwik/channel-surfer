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
import androidx.work.workDataOf
import com.channelsurf.ChannelSurfApplication
import java.util.concurrent.TimeUnit

/**
 * Daily Archive discovery: finds, verifies and tags new recordings for every channel so the lineup keeps
 * changing. Each run has a time budget; if channels remain it chains a follow-up run that continues from there.
 */
class DiscoveryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as ChannelSurfApplication).container
        val s = c.settings.current()
        if (!s.autoDiscovery && !inputData.getBoolean(KEY_FORCED, false)) return Result.success()
        c.catalog.seedIfEmpty()
        val start = inputData.getInt(KEY_START, 0)
        val report = c.discovery.run(startChannelIndex = start, preferLowBandwidth = s.dataSaver)
        c.diagnostics.info(
            "discovery.run",
            "Searched ${report.channelsSearched} channels from #$start: +${report.added} added, ${report.skipped} skipped",
        )
        if (!report.finished && !isStopped) DiscoveryScheduler.continueFrom(applicationContext, report.nextChannelIndex, s.wifiOnly)
        c.schedule.refresh()
        return Result.success()
    }

    companion object {
        const val KEY_START = "start"
        const val KEY_FORCED = "forced"
    }
}

object DiscoveryScheduler {
    private const val PERIODIC = "channel-surf-discovery"
    private const val ONCE = "channel-surf-discovery-now"

    private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun ensureScheduled(context: Context, wifiOnly: Boolean, replace: Boolean = false) {
        val req = PeriodicWorkRequestBuilder<DiscoveryWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints(wifiOnly))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP, req,
        )
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)

    fun runNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<DiscoveryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(DiscoveryWorker.KEY_FORCED to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
    }

    fun continueFrom(context: Context, index: Int, wifiOnly: Boolean) {
        val req = OneTimeWorkRequestBuilder<DiscoveryWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setInputData(workDataOf(DiscoveryWorker.KEY_START to index, DiscoveryWorker.KEY_FORCED to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$ONCE-$index", ExistingWorkPolicy.REPLACE, req)
    }
}
