package com.channelsurf.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Restrained analog artifacts. They are drawn as overlays and never touch the decoded video. */
enum class AnalogEffects(val tuneStaticMs: Long, val noiseAlpha: Float, val scanlines: Boolean) {
    OFF(250, 0f, false),
    SUBTLE(400, 0.55f, false),
    FULL(650, 0.85f, true),
}

enum class ThemeMode { SYSTEM, CLASSIC_DARK }

/** How the 4:3 picture fills a wide screen. */
enum class PictureMode(val label: String) {
    /** Fill the screen edge to edge, trimming a little off the top and bottom. */
    FILL("FILL"),
    /** Original 4:3 with black side bars, like the broadcast. */
    ORIGINAL("4:3"),
    /** Stretch to fill without cropping (wide-TV "stretch" mode). */
    STRETCH("STRETCH"),
}

data class Settings(
    val analogEffects: AnalogEffects = AnalogEffects.SUBTLE,
    val captions: Boolean = false,
    /** Never stream over metered networks. */
    val wifiOnly: Boolean = false,
    /** Prefer low-bandwidth renditions (512Kb derivatives) where verified. */
    val dataSaver: Boolean = false,
    val autoPlay: Boolean = true,
    val theme: ThemeMode = ThemeMode.CLASSIC_DARK,
    val mobileDataWarningAcknowledged: Boolean = false,
    val contentNoticeAcknowledged: Boolean = false,
    val scheduleServerUrl: String = "",
    val scheduleServerPublicKey: String = "",
    /** Air discovered recordings without a manual rights/year sign-off (default). */
    val autoApprove: Boolean = true,
    /** Periodically search the Archive for new recordings for every channel. */
    val autoDiscovery: Boolean = true,
    val pictureMode: PictureMode = PictureMode.FILL,
    /** Keep the channel playing (picture-in-picture, then audio) when leaving the app. */
    val backgroundPlay: Boolean = true,
    /** Guide time zoom: how many half-hour columns are visible. 0 = fit the screen. */
    val guideSlots: Int = 0,
    /** Guide text size multiplier (bigger rows and names). */
    val guideTextScale: Float = 1f,
)

/**
 * "Watch this": at [startUtcMs] the app switches to [channelId] by itself. [daily] repeats every day.
 */
data class PlannedTune(
    val id: String,
    val channelId: String,
    val startUtcMs: Long,
    val label: String,
    val daily: Boolean = false,
) {
    fun encode(): String = listOf(id, channelId, startUtcMs.toString(), if (daily) "1" else "0", label.replace('\t', ' ').replace('\n', ' ')).joinToString("\t")

    companion object {
        fun decode(line: String): PlannedTune? {
            val p = line.split('\t')
            if (p.size < 5) return null
            return PlannedTune(p[0], p[1], p[2].toLongOrNull() ?: return null, p.drop(4).joinToString(" "), p[3] == "1")
        }
    }
}

/** Persisted viewing state. Deliberately contains NO playhead: returning to a channel always joins live. */
data class PlaybackBookmark(
    val lastChannelId: String? = null,
    val previousChannelId: String? = null,
    val lastTuneUtcMs: Long = 0,
    val favoriteChannelIds: Set<String> = emptySet(),
)

class SettingsRepository(private val store: DataStore<Preferences>) {

    private object K {
        val effects = stringPreferencesKey("analog_effects")
        val captions = booleanPreferencesKey("captions")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val dataSaver = booleanPreferencesKey("data_saver")
        val autoPlay = booleanPreferencesKey("auto_play")
        val theme = stringPreferencesKey("theme")
        val mobileAck = booleanPreferencesKey("mobile_data_ack")
        val noticeAck = booleanPreferencesKey("content_notice_ack")
        val serverUrl = stringPreferencesKey("schedule_server_url")
        val serverKey = stringPreferencesKey("schedule_server_public_key")
        val autoApprove = booleanPreferencesKey("auto_approve")
        val autoDiscovery = booleanPreferencesKey("auto_discovery")
        val pictureMode = stringPreferencesKey("picture_mode")
        val backgroundPlay = booleanPreferencesKey("background_play")
        val guideSlots = androidx.datastore.preferences.core.intPreferencesKey("guide_slots")
        val guideTextScale = androidx.datastore.preferences.core.floatPreferencesKey("guide_text_scale")
        val planned = stringPreferencesKey("planned_tunes")
        val lastChannel = stringPreferencesKey("last_channel_id")
        val previousChannel = stringPreferencesKey("previous_channel_id")
        val lastTune = longPreferencesKey("last_tune_utc_ms")
        val favorites = stringSetPreferencesKey("favorite_channel_ids")
    }

    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<Settings> = prefs.map { p ->
        Settings(
            analogEffects = AnalogEffects.entries.firstOrNull { it.name == p[K.effects] } ?: AnalogEffects.SUBTLE,
            captions = p[K.captions] ?: false,
            wifiOnly = p[K.wifiOnly] ?: false,
            dataSaver = p[K.dataSaver] ?: false,
            autoPlay = p[K.autoPlay] ?: true,
            theme = ThemeMode.entries.firstOrNull { it.name == p[K.theme] } ?: ThemeMode.CLASSIC_DARK,
            mobileDataWarningAcknowledged = p[K.mobileAck] ?: false,
            contentNoticeAcknowledged = p[K.noticeAck] ?: false,
            scheduleServerUrl = p[K.serverUrl] ?: "",
            scheduleServerPublicKey = p[K.serverKey] ?: "",
            autoApprove = p[K.autoApprove] ?: true,
            autoDiscovery = p[K.autoDiscovery] ?: true,
            pictureMode = PictureMode.entries.firstOrNull { it.name == p[K.pictureMode] } ?: PictureMode.FILL,
            backgroundPlay = p[K.backgroundPlay] ?: true,
            guideSlots = p[K.guideSlots] ?: 0,
            guideTextScale = p[K.guideTextScale] ?: 1f,
        )
    }

    val plannedTunes: Flow<List<PlannedTune>> = prefs.map { p ->
        (p[K.planned] ?: "").lines().mapNotNull { if (it.isBlank()) null else PlannedTune.decode(it) }.sortedBy { it.startUtcMs }
    }

    suspend fun setPlannedTunes(list: List<PlannedTune>) = store.edit {
        it[K.planned] = list.sortedBy { t -> t.startUtcMs }.joinToString("\n") { t -> t.encode() }
    }

    suspend fun setGuideSlots(v: Int) = store.edit { it[K.guideSlots] = v }
    suspend fun setGuideTextScale(v: Float) = store.edit { it[K.guideTextScale] = v }

    val bookmark: Flow<PlaybackBookmark> = prefs.map { p ->
        PlaybackBookmark(
            lastChannelId = p[K.lastChannel],
            previousChannelId = p[K.previousChannel],
            lastTuneUtcMs = p[K.lastTune] ?: 0,
            favoriteChannelIds = p[K.favorites] ?: emptySet(),
        )
    }

    suspend fun current(): Settings = settings.first()
    suspend fun currentBookmark(): PlaybackBookmark = bookmark.first()

    suspend fun setAnalogEffects(v: AnalogEffects) = store.edit { it[K.effects] = v.name }
    suspend fun setCaptions(v: Boolean) = store.edit { it[K.captions] = v }
    suspend fun setWifiOnly(v: Boolean) = store.edit { it[K.wifiOnly] = v }
    suspend fun setDataSaver(v: Boolean) = store.edit { it[K.dataSaver] = v }
    suspend fun setAutoPlay(v: Boolean) = store.edit { it[K.autoPlay] = v }
    suspend fun setTheme(v: ThemeMode) = store.edit { it[K.theme] = v.name }
    suspend fun setAutoApprove(v: Boolean) = store.edit { it[K.autoApprove] = v }
    suspend fun setAutoDiscovery(v: Boolean) = store.edit { it[K.autoDiscovery] = v }
    suspend fun setPictureMode(v: PictureMode) = store.edit { it[K.pictureMode] = v.name }
    suspend fun setBackgroundPlay(v: Boolean) = store.edit { it[K.backgroundPlay] = v }
    suspend fun acknowledgeMobileData() = store.edit { it[K.mobileAck] = true }
    suspend fun acknowledgeContentNotice() = store.edit { it[K.noticeAck] = true }

    suspend fun setScheduleServer(url: String, publicKey: String) = store.edit {
        it[K.serverUrl] = url.trim()
        it[K.serverKey] = publicKey.trim()
    }

    suspend fun recordTune(channelId: String, previousId: String?, atUtcMs: Long) = store.edit {
        it[K.lastChannel] = channelId
        if (previousId != null) it[K.previousChannel] = previousId else it.remove(K.previousChannel)
        it[K.lastTune] = atUtcMs
    }

    suspend fun toggleFavorite(channelId: String) = store.edit {
        val cur = it[K.favorites] ?: emptySet()
        it[K.favorites] = if (channelId in cur) cur - channelId else cur + channelId
    }

    companion object {
        private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "channel_surf_settings")
        fun create(context: Context) = SettingsRepository(context.applicationContext.dataStore)
    }
}
