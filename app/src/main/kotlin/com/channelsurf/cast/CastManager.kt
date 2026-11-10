package com.channelsurf.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.channelsurf.diagnostics.DiagnosticsLog
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

data class CastDevice(val id: String, val name: String, val description: String?, val selected: Boolean)

data class CastStatus(
    /** Google Play services + Cast framework are usable on this device. */
    val available: Boolean = false,
    val connecting: Boolean = false,
    val connectedDeviceName: String? = null,
) {
    val connected: Boolean get() = connectedDeviceName != null
}

/**
 * Cast device discovery and session control with an in-app, cable-styled picker (no AppCompat dialogs).
 * All methods must be called on the main thread. Degrades to "unavailable" without Google Play services.
 */
class CastManager(context: Context, private val diagnostics: DiagnosticsLog) {
    private val appContext = context.applicationContext
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var discovering = false

    @Volatile var castContext: CastContext? = null
        private set

    private val _status = MutableStateFlow(CastStatus())
    val status: StateFlow<CastStatus> = _status

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices

    /** Invoked once the CastContext exists, so the player layer can attach a CastPlayer. */
    private val readyListeners = ArrayList<(CastContext) -> Unit>()

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = publishRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = publishRoutes()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) { _status.value = _status.value.copy(connecting = true) }
        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResuming(session: CastSession, sessionId: String) { _status.value = _status.value.copy(connecting = true) }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) = disconnected("start failed ($error)")
        override fun onSessionResumeFailed(session: CastSession, error: Int) = disconnected("resume failed ($error)")
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = disconnected(null)
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /** Initializes the Cast framework asynchronously. Safe to call repeatedly. */
    fun initialize() {
        if (castContext != null) return
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (gms != ConnectionResult.SUCCESS) {
            diagnostics.info("cast", "Google Play services unavailable ($gms); casting disabled")
            return
        }
        try {
            CastContext.getSharedInstance(appContext, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx ->
                    castContext = ctx
                    selector = ctx.mergedSelector ?: MediaRouteSelector.Builder()
                        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                        .build()
                    router = MediaRouter.getInstance(appContext)
                    ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                    _status.value = CastStatus(available = true)
                    ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { connected(it) }
                    readyListeners.forEach { it(ctx) }
                    readyListeners.clear()
                    if (discovering) startDiscovery()
                }
                .addOnFailureListener { e -> diagnostics.warn("cast", "Cast unavailable: ${e.javaClass.simpleName}") }
        } catch (e: Exception) {
            diagnostics.warn("cast", "Cast init failed: ${e.javaClass.simpleName}")
        }
    }

    fun whenReady(block: (CastContext) -> Unit) {
        castContext?.let(block) ?: readyListeners.add(block)
    }

    /** Active scan while the picker is open (costs battery, so stop when it closes). */
    fun startDiscovery() {
        discovering = true
        val r = router ?: return
        val s = selector ?: return
        r.addCallback(s, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        publishRoutes()
    }

    fun stopDiscovery() {
        discovering = false
        router?.removeCallback(routerCallback)
    }

    fun select(deviceId: String) {
        val r = router ?: return
        val route = r.routes.firstOrNull { it.id == deviceId } ?: return
        _status.value = _status.value.copy(connecting = true)
        r.selectRoute(route) // the Cast framework starts the session for Cast routes
    }

    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private fun publishRoutes() {
        val r = router ?: return
        val s = selector ?: return
        _devices.value = r.routes
            .filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(s) }
            .map { CastDevice(it.id, it.name, it.description, it.isSelected) }
            .sortedBy { it.name.lowercase() }
    }

    private fun connected(session: CastSession) {
        val name = session.castDevice?.friendlyName ?: "TV"
        _status.value = CastStatus(available = true, connecting = false, connectedDeviceName = name)
        diagnostics.info("cast", "Casting to $name")
    }

    private fun disconnected(reason: String?) {
        _status.value = CastStatus(available = castContext != null)
        reason?.let { diagnostics.warn("cast", "Cast session $it") }
        publishRoutes()
    }
}
