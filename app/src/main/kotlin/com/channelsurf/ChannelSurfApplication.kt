package com.channelsurf

import android.app.Application
import com.channelsurf.work.DiscoveryScheduler
import com.channelsurf.work.RefreshScheduler
import kotlinx.coroutines.launch

class ChannelSurfApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.cast.initialize()
        container.appScope.launch {
            container.catalog.seedIfEmpty()
            val s = container.settings.current()
            RefreshScheduler.ensureScheduled(this@ChannelSurfApplication, s.wifiOnly)
            if (s.autoDiscovery) {
                DiscoveryScheduler.ensureScheduled(this@ChannelSurfApplication, s.wifiOnly)
                // First launch: fill the empty lineup right away instead of waiting for the daily run.
                if (container.catalog.assetCount() == 0) DiscoveryScheduler.runNow(this@ChannelSurfApplication)
            }
        }
    }
}
