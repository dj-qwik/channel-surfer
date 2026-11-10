package com.channelsurf.data.schedule

import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.ProgramBlock
import com.channelsurf.core.model.ReviewPolicy
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.schedule.ScheduleValidator
import com.channelsurf.core.schedule.TuneResolution
import com.channelsurf.core.schedule.TuneResolver
import com.channelsurf.core.time.BroadcastClock
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.data.local.ScheduleDao
import com.channelsurf.data.local.toEntity
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Schedule queries for the player and the guide. The engine is rebuilt whenever the catalog revision or
 * device-local asset health changes; the 7-day-ahead / 24-hour-history window is materialized into Room
 * so the guide and diagnostics are readable offline and auditable.
 */
class ScheduleRepository(
    private val catalog: CatalogRepository,
    private val dao: ScheduleDao,
    private val clock: BroadcastClock,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val allowTestAssets: Boolean,
    /** AUTOMATIC or MANUAL review policy, from settings. */
    reviewPolicy: Flow<ReviewPolicy>,
) {
    /** assetId -> verified alternate URL to use after a decoder/codec failure on this device. */
    private val urlOverrides = ConcurrentHashMap<String, String>()

    /** assetId -> wall time it failed on this device; applied instantly, before Room/engine catch up. */
    private val sessionUnhealthy = ConcurrentHashMap<String, Long>()
    private val sessionBlockedIdentifiers = ConcurrentHashMap.newKeySet<String>()

    /**
     * The first lineup builds at once; after that, bursts of catalog changes (discovery adding a season of
     * episodes) settle for a few seconds and rebuild the schedule ONCE. Each rebuild holds a full copy of the
     * ~15k-recording lineup, so rebuilding per write ran the app out of memory.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val engine: StateFlow<ScheduleEngine?> = run {
        var first = true
        combine(catalog.lineup, reviewPolicy.distinctUntilChanged()) { lineup, policy -> lineup to policy }
            .debounce { if (first) { first = false; 0L } else REBUILD_SETTLE_MS }
            .conflate()
            .map { (lineup, policy) -> ScheduleEngine(lineup.copy(reviewPolicy = policy), allowTestOnly = allowTestAssets) }
    }
        .flowOn(Dispatchers.Default)
        .conflate()
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            engine.filterNotNull().distinctUntilChangedBy { it.revision }.collect { refresh(it) }
        }
    }

    suspend fun awaitEngine(): ScheduleEngine = engine.filterNotNull().first()

    private fun resolver(e: ScheduleEngine) = TuneResolver(
        e,
        healthOf = { id -> healthOf(e, id) },
        urlOverride = { id -> urlOverrides[id] },
    )

    /** ScheduleRepository.current(channelId, nowUtc): what is on the air right now. */
    suspend fun current(channelId: String, nowUtcMs: Long = clock.nowUtcMs()): TuneResolution {
        val e = awaitEngine()
        return withContext(Dispatchers.Default) { resolver(e).resolve(channelId, nowUtcMs) }
    }

    /** ScheduleRepository.window(channelId, from, to): gapless blocks for the guide. */
    suspend fun window(channelId: String, fromUtcMs: Long, toUtcMs: Long): List<ProgramBlock> {
        val e = awaitEngine()
        return withContext(Dispatchers.Default) { e.blocksBetween(channelId, fromUtcMs, toUtcMs) }
    }

    private fun healthOf(e: ScheduleEngine, id: String): HealthStatus {
        val asset = e.lineup.assets[id] ?: return HealthStatus.UNKNOWN
        if (asset.archiveIdentifier in sessionBlockedIdentifiers) return HealthStatus.UNHEALTHY
        val markedAt = sessionUnhealthy[id]
        if (markedAt != null) {
            // A newer successful health check clears the session mark.
            val recheckedOk = asset.healthStatus == HealthStatus.OK && (asset.lastCheckedAtUtcMs ?: 0) > markedAt
            if (!recheckedOk) return HealthStatus.UNHEALTHY
            sessionUnhealthy.remove(id)
        }
        return asset.healthStatus
    }

    fun markUnhealthyNow(assetId: String) { sessionUnhealthy[assetId] = System.currentTimeMillis() }
    fun blockIdentifierNow(archiveIdentifier: String) { sessionBlockedIdentifiers += archiveIdentifier }

    fun useAlternateUrl(assetId: String, url: String) { urlOverrides[assetId] = url }
    fun clearAlternateUrl(assetId: String) { urlOverrides.remove(assetId) }

    /** ScheduleRepository.refresh(): re-materialize 24 h of history and 7 days ahead for the current revision. */
    suspend fun refresh(e: ScheduleEngine? = null) {
        val eng = e ?: awaitEngine()
        withContext(Dispatchers.Default) {
            val now = clock.nowUtcMs()
            val from = now - HISTORY_MS
            val to = now + AHEAD_MS
            val blocks = ArrayList<ProgramBlock>()
            eng.lineup.channels.forEach { c ->
                val list = eng.blocksBetween(c.id, from, to)
                val problems = ScheduleValidator.problems(list, from, to)
                if (problems.isNotEmpty()) diagnostics.warn("schedule.validate", "${c.id}: ${problems.take(3)}")
                blocks += list
            }
            dao.replaceWindow(eng.revision, from, blocks.map { it.toEntity() })
            diagnostics.info("schedule.refresh", "Materialized ${blocks.size} blocks for revision ${eng.revision}")
        }
    }

    suspend fun materializedBlockCount(): Int = dao.count()

    companion object {
        const val HISTORY_MS = 2 * 3_600_000L
        const val AHEAD_MS = 24 * 3_600_000L
        const val REBUILD_SETTLE_MS = 4_000L
    }
}
