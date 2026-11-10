package com.channelsurf.diagnostics

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Local-only playback telemetry. Records what is needed to debug Archive playback (item, file, status,
 * codec, seek outcome) and nothing personal. Never leaves the device unless the operator exports it.
 */
class DiagnosticsLog(private val capacity: Int = 400) {

    enum class Level { INFO, WARN, ERROR }

    data class Event(
        val atUtcMs: Long,
        val level: Level,
        val kind: String,
        val message: String,
        val channelId: String? = null,
        val archiveIdentifier: String? = null,
        val fileName: String? = null,
        val statusCode: Int? = null,
        val codec: String? = null,
        val seekOutcome: String? = null,
    ) {
        fun line(): String = buildString {
            append(level).append(' ').append(kind).append(": ").append(message)
            channelId?.let { append(" ch=").append(it) }
            archiveIdentifier?.let { append(" item=").append(it) }
            fileName?.let { append(" file=").append(it) }
            statusCode?.let { append(" http=").append(it) }
            codec?.let { append(" codec=").append(it) }
            seekOutcome?.let { append(" seek=").append(it) }
        }
    }

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    fun log(e: Event) {
        when (e.level) {
            Level.INFO -> Log.i(TAG, e.line())
            Level.WARN -> Log.w(TAG, e.line())
            Level.ERROR -> Log.e(TAG, e.line())
        }
        _events.update { (it + e).takeLast(capacity) }
    }

    fun info(kind: String, message: String, atUtcMs: Long = System.currentTimeMillis()) =
        log(Event(atUtcMs, Level.INFO, kind, message))

    fun warn(kind: String, message: String, atUtcMs: Long = System.currentTimeMillis()) =
        log(Event(atUtcMs, Level.WARN, kind, message))

    fun export(): String = _events.value.joinToString("\n") { "${java.time.Instant.ofEpochMilli(it.atUtcMs)} ${it.line()}" }

    fun clear() { _events.value = emptyList() }

    companion object { const val TAG = "ChannelSurf" }
}
