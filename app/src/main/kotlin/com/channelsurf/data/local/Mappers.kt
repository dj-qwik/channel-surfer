package com.channelsurf.data.local

import com.channelsurf.core.model.AdOrigin
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.ContentType
import com.channelsurf.core.model.Daypart
import com.channelsurf.core.model.FixedEntry
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.ProgramBlock
import com.channelsurf.core.model.Provenance
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.model.SchedulePolicy
import com.channelsurf.core.model.Segment
import com.channelsurf.core.model.SegmentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val POOL_PRIMARY = "PRIMARY"
const val POOL_FALLBACK = "FALLBACK"

internal inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

fun ChannelEntity.toModel(entries: List<PlaylistEntryEntity>): Channel = Channel(
    id = id, number = number, displayName = displayName, genre = genre, categoryLabel = categoryLabel,
    color = color, logoAsset = logoAsset, enabled = enabled, scheduleRevision = scheduleRevision,
    epochUtcMs = epochUtcMs, policy = enumOr(policy, SchedulePolicy.CYCLIC),
    playlist = entries.filter { it.channelId == id && it.pool == POOL_PRIMARY }.sortedBy { it.position }.map { it.assetId },
    fallbackPool = entries.filter { it.channelId == id && it.pool == POOL_FALLBACK }.sortedBy { it.position }.map { it.assetId },
    fixedEntries = decodeFixed(fixedEntriesJson),
    fixedPeriodMs = fixedPeriodMs,
    timeZone = timeZone,
    dayparts = decodeDayparts(daypartsJson),
    defaultTags = splitTags(defaultTags),
    fillerTags = splitTags(fillerTags),
    discoveryQueries = decodeStrings(discoveryQueriesJson),
    fallbackTags = splitTags(fallbackTags),
    ignoreEra = ignoreEra,
)

fun Channel.toEntity(): ChannelEntity = ChannelEntity(
    id = id, number = number, displayName = displayName, genre = genre, categoryLabel = categoryLabel,
    color = color, logoAsset = logoAsset, enabled = enabled, scheduleRevision = scheduleRevision,
    epochUtcMs = epochUtcMs, policy = policy.name,
    fixedEntriesJson = if (fixedEntries.isEmpty()) null else encodeFixed(fixedEntries),
    fixedPeriodMs = fixedPeriodMs,
    timeZone = timeZone,
    daypartsJson = if (dayparts.isEmpty()) null else encodeDayparts(dayparts),
    defaultTags = defaultTags.joinToString(","),
    fillerTags = fillerTags.joinToString(","),
    discoveryQueriesJson = if (discoveryQueries.isEmpty()) null else buildJsonArray { discoveryQueries.forEach { add(JsonPrimitive(it)) } }.toString(),
    fallbackTags = fallbackTags.joinToString(","),
    ignoreEra = ignoreEra,
)

fun Channel.playlistEntities(): List<PlaylistEntryEntity> =
    playlist.mapIndexed { i, a -> PlaylistEntryEntity(id, POOL_PRIMARY, i, a) } +
        fallbackPool.mapIndexed { i, a -> PlaylistEntryEntity(id, POOL_FALLBACK, i, a) }

fun MediaAssetEntity.toModel(): MediaAsset = MediaAsset(
    id = id, archiveIdentifier = archiveIdentifier, fileName = fileName, url = url, title = title,
    description = description, creator = creator, collection = collection, category = category,
    durationMs = durationMs, sourceStartMs = sourceStartMs, seekable = seekable, codec = codec,
    container = container, aspectRatio = aspectRatio, broadcastYear = broadcastYear,
    yearVerified = yearVerified, yearEvidence = yearEvidence,
    rightsStatus = enumOr(rightsStatus, RightsStatus.REVIEW_REQUIRED),
    rightsNotes = rightsNotes,
    provenance = enumOr(provenance, Provenance.ORIGINAL_RECORDING),
    adOrigin = enumOr(adOrigin, AdOrigin.EMBEDDED_ORIGINAL),
    healthStatus = enumOr(healthStatus, HealthStatus.UNKNOWN),
    lastCheckedAtUtcMs = lastCheckedAtUtcMs,
    captionUrl = captionUrl,
    alternateUrls = alternateUrls.split('\n').filter { it.isNotBlank() },
    segments = decodeSegments(segmentsJson),
    contentType = enumOr(contentType, ContentType.OTHER),
    series = series,
    tags = splitTags(tags),
    availableFromUtcMs = availableFromUtcMs,
)

fun MediaAsset.toEntity(
    lastStatusCode: Int? = null,
    playbackVerified: Boolean = false,
    addedAtUtcMs: Long = System.currentTimeMillis(),
    discovered: Boolean = false,
): MediaAssetEntity = MediaAssetEntity(
    id = id, archiveIdentifier = archiveIdentifier, fileName = fileName, url = url, title = title,
    description = description, creator = creator, collection = collection, category = category,
    durationMs = durationMs, sourceStartMs = sourceStartMs, seekable = seekable, codec = codec,
    container = container, aspectRatio = aspectRatio, broadcastYear = broadcastYear,
    yearVerified = yearVerified, yearEvidence = yearEvidence, rightsStatus = rightsStatus.name,
    rightsNotes = rightsNotes, provenance = provenance.name, adOrigin = adOrigin.name,
    healthStatus = healthStatus.name, lastCheckedAtUtcMs = lastCheckedAtUtcMs, lastStatusCode = lastStatusCode,
    captionUrl = captionUrl, alternateUrls = alternateUrls.joinToString("\n"),
    segmentsJson = if (segments.isEmpty()) null else encodeSegments(segments),
    playbackVerified = playbackVerified, addedAtUtcMs = addedAtUtcMs,
    contentType = contentType.name, series = series, tags = tags.joinToString(","),
    availableFromUtcMs = availableFromUtcMs, discovered = discovered,
)

fun ProgramBlock.toEntity() = ProgramBlockEntity(
    id = id, channelId = channelId, assetId = assetId, startUtcMs = startUtcMs, durationMs = durationMs,
    sourceOffsetMs = sourceOffsetMs, displayTitle = displayTitle, description = description,
    provenance = provenance?.name, kind = kind.name, revision = revision, daypartTitle = daypartTitle,
)

private fun encodeSegments(s: List<Segment>): String = buildJsonArray {
    s.forEach { add(buildJsonObject { put("offsetMs", it.offsetMs); put("label", it.label); put("kind", it.kind.name) }) }
}.toString()

private fun decodeSegments(json: String?): List<Segment> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val off = (o["offsetMs"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            Segment(off, (o["label"] as? JsonPrimitive)?.content ?: "", enumOr((o["kind"] as? JsonPrimitive)?.content, SegmentKind.PROGRAM))
        }
    } catch (e: Exception) { emptyList() }
}

private fun encodeFixed(entries: List<FixedEntry>): String = buildJsonArray {
    entries.forEach { f ->
        add(buildJsonObject {
            put("startOffsetMs", f.startOffsetMs); put("durationMs", f.durationMs); put("assetId", f.assetId)
            put("sourceOffsetMs", f.sourceOffsetMs)
            f.displayTitle?.let { put("displayTitle", it) }
            f.description?.let { put("description", it) }
        })
    }
}.toString()

private fun decodeFixed(json: String?): List<FixedEntry> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
            FixedEntry(
                startOffsetMs = l("startOffsetMs") ?: return@mapNotNull null,
                durationMs = l("durationMs") ?: return@mapNotNull null,
                assetId = (o["assetId"] as? JsonPrimitive)?.content ?: return@mapNotNull null,
                sourceOffsetMs = l("sourceOffsetMs") ?: 0,
                displayTitle = (o["displayTitle"] as? JsonPrimitive)?.content,
                description = (o["description"] as? JsonPrimitive)?.content,
            )
        }
    } catch (e: Exception) { emptyList() }
}

fun splitTags(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

private fun decodeStrings(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.content }
    } catch (e: Exception) { emptyList() }
}

private fun encodeDayparts(parts: List<Daypart>): String = buildJsonArray {
    parts.forEach { d ->
        add(buildJsonObject {
            put("id", d.id); put("title", d.title)
            put("days", buildJsonArray { d.days.sorted().forEach { add(JsonPrimitive(it)) } })
            put("startMinute", d.startMinute); put("endMinute", d.endMinute)
            put("tags", buildJsonArray { d.tags.forEach { add(JsonPrimitive(it)) } })
            put("preferTypes", buildJsonArray { d.preferTypes.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) } })
            put("assetIds", buildJsonArray { d.assetIds.forEach { add(JsonPrimitive(it)) } })
        })
    }
}.toString()

private fun decodeDayparts(json: String?): List<Daypart> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        (Json.parseToJsonElement(json) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content
            fun strs(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            Daypart(
                id = str("id") ?: return@mapNotNull null,
                title = str("title") ?: "",
                days = strs("days").mapNotNull { it.toIntOrNull() }.toSet(),
                startMinute = str("startMinute")?.toIntOrNull() ?: return@mapNotNull null,
                endMinute = str("endMinute")?.toIntOrNull() ?: return@mapNotNull null,
                tags = strs("tags"),
                preferTypes = strs("preferTypes").map { enumOr(it, ContentType.OTHER) }.toSet(),
                assetIds = strs("assetIds"),
            )
        }
    } catch (e: Exception) { emptyList() }
}
