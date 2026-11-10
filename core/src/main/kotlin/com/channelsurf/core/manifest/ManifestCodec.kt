package com.channelsurf.core.manifest

import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.archive.UrlPolicy
import com.channelsurf.core.model.AdOrigin
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.ContentType
import com.channelsurf.core.model.Daypart
import com.channelsurf.core.model.ReviewPolicy
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.model.FixedEntry
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.Lineup
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.Provenance
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.model.SchedulePolicy
import com.channelsurf.core.model.Segment
import com.channelsurf.core.model.SegmentKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface ManifestResult {
    data class Success(val lineup: Lineup, val warnings: List<String>) : ManifestResult
    data class Failure(val errors: List<String>) : ManifestResult
}

/**
 * Versioned, strictly validated channel manifest (import/export). Unknown top-level keys are rejected,
 * every string is length-capped, every URL must pass [UrlPolicy], and cross references must resolve.
 * Nothing in a manifest is ever rendered as HTML or executed.
 */
object ManifestCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_BYTES = 24_000_000
    const val MAX_CHANNELS = 200
    const val MAX_ASSETS = 40_000
    private val CHANNEL_ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private val ASSET_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val TOP_LEVEL_KEYS = setOf("schemaVersion", "revision", "generatedAtUtcMs", "eraPolicy", "reviewPolicy", "channels", "assets")
    private val TAG = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun decode(text: String, extraApprovedHosts: Set<String> = emptySet()): ManifestResult {
        if (text.length > MAX_BYTES) return ManifestResult.Failure(listOf("Manifest exceeds ${MAX_BYTES / 1_000_000} MB"))
        val root = try { Json.parseToJsonElement(text) } catch (e: Exception) {
            return ManifestResult.Failure(listOf("Not valid JSON: ${e.message?.take(160)}"))
        } as? JsonObject ?: return ManifestResult.Failure(listOf("Manifest must be a JSON object"))
        return Decoder(extraApprovedHosts).decode(root)
    }

    private class Decoder(private val extraHosts: Set<String>) {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()

        fun decode(root: JsonObject): ManifestResult {
            (root.keys - TOP_LEVEL_KEYS).forEach { errors += "Unknown top-level key '$it'" }
            val schema = long(root, "schemaVersion", "manifest")
            if (schema != null && schema != SCHEMA_VERSION.toLong()) errors += "Unsupported schemaVersion $schema (expected $SCHEMA_VERSION)"
            val revision = str(root, "revision", "manifest", max = 64) ?: ""
            val generatedAt = optLong(root, "generatedAtUtcMs") ?: 0L
            val era = (root["eraPolicy"] as? JsonObject)?.let { e ->
                EraPolicy(
                    minYear = optLong(e, "minYear")?.toInt() ?: 1990,
                    maxYear = optLong(e, "maxYear")?.toInt() ?: 2015,
                    preferredMaxYear = optLong(e, "preferredMaxYear")?.toInt() ?: 2005,
                ).also { if (it.minYear > it.maxYear) errors += "eraPolicy.minYear > maxYear" }
            } ?: EraPolicy()

            val assetsJson = root["assets"] as? JsonArray ?: JsonArray(emptyList()).also {
                if (root["assets"] != null) errors += "'assets' must be an array"
            }
            val channelsJson = root["channels"] as? JsonArray ?: run {
                errors += "'channels' must be an array"; JsonArray(emptyList())
            }
            if (assetsJson.size > MAX_ASSETS) errors += "Too many assets (${assetsJson.size} > $MAX_ASSETS)"
            if (channelsJson.size > MAX_CHANNELS) errors += "Too many channels (${channelsJson.size} > $MAX_CHANNELS)"

            val assets = LinkedHashMap<String, MediaAsset>()
            assetsJson.take(MAX_ASSETS).forEachIndexed { i, el ->
                val a = decodeAsset(el, "assets[$i]") ?: return@forEachIndexed
                if (assets.put(a.id, a) != null) errors += "Duplicate asset id '${a.id}'"
            }

            val channels = ArrayList<Channel>()
            channelsJson.take(MAX_CHANNELS).forEachIndexed { i, el ->
                decodeChannel(el, "channels[$i]", assets)?.let { channels += it }
            }
            channels.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { errors += "Duplicate channel id '$it'" }
            channels.groupBy { it.number }.filter { it.value.size > 1 }.forEach { (n, list) ->
                errors += "Duplicate channel number $n (${list.joinToString { it.id }})"
            }

            val reviewPolicy = (root["reviewPolicy"] as? JsonPrimitive)?.content?.let { enumOf<ReviewPolicy>(it, "manifest.reviewPolicy") }
                ?: ReviewPolicy.AUTOMATIC
            return if (errors.isEmpty()) {
                ManifestResult.Success(Lineup(revision, generatedAt, channels.sortedBy { it.number }, assets, era, reviewPolicy), warnings)
            } else ManifestResult.Failure(errors)
        }

        fun decodeChannel(el: JsonElement, at: String, assets: Map<String, MediaAsset>): Channel? {
            val o = el as? JsonObject ?: run { errors += "$at must be an object"; return null }
            val id = str(o, "id", at, max = 64)
            if (id != null && !CHANNEL_ID.matches(id)) errors += "$at.id '$id' must match ${CHANNEL_ID.pattern}"
            val number = long(o, "number", at)
            if (number != null && number !in 1..999) errors += "$at.number must be 1..999"
            val name = str(o, "displayName", at, max = 40)
            val genre = optStr(o, "genre", at, 60) ?: ""
            val category = optStr(o, "categoryLabel", at, 24) ?: genre.uppercase().take(24)
            val color = optStr(o, "color", at, 7) ?: "#1748A5"
            if (!COLOR.matches(color)) errors += "$at.color must be #RRGGBB"
            val epoch = long(o, "epochUtcMs", at)
            val policy = enumOf<SchedulePolicy>(optStr(o, "policy", at, 16) ?: "CYCLIC", "$at.policy")
            val playlist = idList(o, "playlist", at, assets)
            val fallback = idList(o, "fallbackPool", at, assets)
            val period = optLong(o, "fixedPeriodMs")
            if (period != null && period <= 0) errors += "$at.fixedPeriodMs must be > 0"
            val fixed = (o["fixedEntries"] as? JsonArray)?.mapIndexedNotNull { i, fe ->
                val f = fe as? JsonObject ?: run { errors += "$at.fixedEntries[$i] must be an object"; return@mapIndexedNotNull null }
                val p = "$at.fixedEntries[$i]"
                val start = long(f, "startOffsetMs", p) ?: return@mapIndexedNotNull null
                val dur = long(f, "durationMs", p) ?: return@mapIndexedNotNull null
                val assetId = str(f, "assetId", p, 128) ?: return@mapIndexedNotNull null
                if (assetId !in assets) errors += "$p.assetId '$assetId' does not exist"
                if (dur <= 0) errors += "$p.durationMs must be > 0"
                if (start < 0) errors += "$p.startOffsetMs must be >= 0"
                if (period != null && start + dur > period) errors += "$p extends past fixedPeriodMs"
                FixedEntry(
                    startOffsetMs = start, durationMs = dur, assetId = assetId,
                    sourceOffsetMs = optLong(f, "sourceOffsetMs")?.coerceAtLeast(0) ?: 0,
                    displayTitle = optStr(f, "displayTitle", p, 200),
                    description = optStr(f, "description", p, 2000),
                )
            } ?: emptyList()
            fixed.sortedBy { it.startOffsetMs }.zipWithNext().forEach { (a, b) ->
                if (b.startOffsetMs < a.startOffsetMs + a.durationMs) errors += "$at.fixedEntries overlap at offset ${b.startOffsetMs}"
            }
            if (policy == SchedulePolicy.FIXED && fixed.isEmpty()) warnings += "$at is FIXED with no entries (will be off air)"
            val timeZone = optStr(o, "timeZone", at, 64) ?: "America/New_York"
            try { java.time.ZoneId.of(timeZone) } catch (e: Exception) { errors += "$at.timeZone '$timeZone' is not a valid zone" }
            val dayparts = (o["dayparts"] as? JsonArray)?.mapIndexedNotNull { i, d -> decodeDaypart(d, "$at.dayparts[$i]", assets) } ?: emptyList()
            dayparts.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { errors += "$at has duplicate daypart id '$it'" }
            (1..7).forEach { day ->
                dayparts.filter { day in it.days }.sortedBy { it.startMinute }.zipWithNext().forEach { (a, b) ->
                    if (b.startMinute < a.endMinute) errors += "$at dayparts '${a.id}' and '${b.id}' overlap on day $day"
                }
            }
            val defaultTags = tagList(o, "defaultTags", at)
            val fillerTags = tagList(o, "fillerTags", at)
            val fallbackTags = tagList(o, "fallbackTags", at)
            val queries = (o["discoveryQueries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.take(120) }?.take(12) ?: emptyList()
            if (id == null || number == null || name == null || epoch == null || policy == null) return null
            return Channel(
                id = id, number = number.toInt(), displayName = name, genre = genre, categoryLabel = category,
                color = color, logoAsset = optStr(o, "logoAsset", at, 120),
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                scheduleRevision = optStr(o, "scheduleRevision", at, 64) ?: "",
                epochUtcMs = epoch, policy = policy, playlist = playlist, fallbackPool = fallback,
                fixedEntries = fixed, fixedPeriodMs = period,
                timeZone = timeZone, dayparts = dayparts, defaultTags = defaultTags, fillerTags = fillerTags,
                discoveryQueries = queries, fallbackTags = fallbackTags,
                ignoreEra = (o["ignoreEra"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }

        fun decodeDaypart(el: JsonElement, at: String, assets: Map<String, MediaAsset>): Daypart? {
            val o = el as? JsonObject ?: run { errors += "$at must be an object"; return null }
            val id = str(o, "id", at, 40) ?: return null
            val title = str(o, "title", at, 60) ?: return null
            val days = (o["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }?.toSet() ?: (1..7).toSet()
            if (days.any { it !in 1..7 }) errors += "$at.days must be ISO day numbers 1..7"
            val start = long(o, "startMinute", at)?.toInt() ?: return null
            val end = long(o, "endMinute", at)?.toInt() ?: return null
            if (start < 360 || end > 1800 || end <= start) errors += "$at must satisfy 360 <= startMinute < endMinute <= 1800 (06:00–30:00)"
            val prefer = (o["preferTypes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let { v -> enumOf<ContentType>(v, "$at.preferTypes") } }?.toSet() ?: emptySet()
            val ids = (o["assetIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.onEach { if (it !in assets) errors += "$at.assetIds references missing asset '$it'" } ?: emptyList()
            return Daypart(id, title, days, start, end, tagList(o, "tags", at), prefer, ids)
        }

        private fun tagList(o: JsonObject, key: String, at: String): List<String> {
            val arr = o[key] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { (it as? JsonPrimitive)?.content }.onEach {
                if (!TAG.matches(it)) errors += "$at.$key tag '$it' must be lowercase letters, digits and dashes"
            }.take(30)
        }

        fun decodeAsset(el: JsonElement, at: String): MediaAsset? {
            val o = el as? JsonObject ?: run { errors += "$at must be an object"; return null }
            val id = str(o, "id", at, 128)
            if (id != null && !ASSET_ID.matches(id)) errors += "$at.id '$id' has invalid characters"
            val ident = str(o, "archiveIdentifier", at, 100)
            if (ident != null && !ArchiveUrls.isValidIdentifier(ident)) errors += "$at.archiveIdentifier is not a valid identifier"
            val fileName = str(o, "fileName", at, 1000)
            val url = str(o, "url", at, 4096)
            if (url != null && !UrlPolicy.isAllowed(url, extraHosts)) errors += "$at.url must be HTTPS on archive.org or an approved host"
            val title = str(o, "title", at, 300)
            val duration = long(o, "durationMs", at)
            if (duration != null && duration <= 0) errors += "$at.durationMs must be > 0"
            val sourceStart = optLong(o, "sourceStartMs") ?: 0
            if (duration != null && (sourceStart < 0 || sourceStart >= duration)) errors += "$at.sourceStartMs must be within the media"
            val year = optLong(o, "broadcastYear")?.toInt()
            if (year != null && year !in 1900..2100) errors += "$at.broadcastYear is implausible"
            val caption = optStr(o, "captionUrl", at, 4096)
            if (caption != null && !UrlPolicy.isAllowed(caption, extraHosts)) errors += "$at.captionUrl host not approved"
            val alternates = (o["alternateUrls"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
            alternates.filterNot { UrlPolicy.isAllowed(it, extraHosts) }.forEach { _ -> errors += "$at.alternateUrls contains an unapproved host" }
            val segments = (o["segments"] as? JsonArray)?.mapIndexedNotNull { i, s ->
                val so = s as? JsonObject ?: return@mapIndexedNotNull null
                val p = "$at.segments[$i]"
                val off = long(so, "offsetMs", p) ?: return@mapIndexedNotNull null
                if (duration != null && (off < 0 || off >= duration)) errors += "$p.offsetMs outside media"
                Segment(off, optStr(so, "label", p, 120) ?: "", enumOf<SegmentKind>(optStr(so, "kind", p, 20) ?: "PROGRAM", "$p.kind") ?: SegmentKind.PROGRAM)
            }?.sortedBy { it.offsetMs } ?: emptyList()
            val rights = enumOf<RightsStatus>(optStr(o, "rightsStatus", at, 20) ?: "REVIEW_REQUIRED", "$at.rightsStatus")
            if (id == null || ident == null || fileName == null || url == null || title == null || duration == null || rights == null) return null
            return MediaAsset(
                id = id, archiveIdentifier = ident, fileName = fileName, url = url, title = title,
                description = optStr(o, "description", at, 4000) ?: "",
                creator = optStr(o, "creator", at, 200), collection = optStr(o, "collection", at, 120),
                category = optStr(o, "category", at, 60),
                durationMs = duration, sourceStartMs = sourceStart,
                seekable = (o["seekable"] as? JsonPrimitive)?.booleanOrNull ?: false,
                codec = optStr(o, "codec", at, 40), container = optStr(o, "container", at, 20),
                aspectRatio = optStr(o, "aspectRatio", at, 20),
                broadcastYear = year,
                yearVerified = (o["yearVerified"] as? JsonPrimitive)?.booleanOrNull ?: false,
                yearEvidence = optStr(o, "yearEvidence", at, 1000),
                rightsStatus = rights,
                rightsNotes = optStr(o, "rightsNotes", at, 2000),
                provenance = enumOf<Provenance>(optStr(o, "provenance", at, 30) ?: "ORIGINAL_RECORDING", "$at.provenance") ?: Provenance.ORIGINAL_RECORDING,
                adOrigin = enumOf<AdOrigin>(optStr(o, "adOrigin", at, 30) ?: "EMBEDDED_ORIGINAL", "$at.adOrigin") ?: AdOrigin.EMBEDDED_ORIGINAL,
                healthStatus = enumOf<HealthStatus>(optStr(o, "healthStatus", at, 20) ?: "UNKNOWN", "$at.healthStatus") ?: HealthStatus.UNKNOWN,
                lastCheckedAtUtcMs = optLong(o, "lastCheckedAtUtcMs"),
                captionUrl = caption,
                alternateUrls = alternates,
                segments = segments,
                contentType = enumOf<ContentType>(optStr(o, "contentType", at, 20) ?: "OTHER", "$at.contentType") ?: ContentType.OTHER,
                series = optStr(o, "series", at, 200),
                tags = tagList(o, "tags", at),
                availableFromUtcMs = optLong(o, "availableFromUtcMs") ?: 0,
            )
        }

        private fun idList(o: JsonObject, key: String, at: String, assets: Map<String, MediaAsset>): List<String> {
            val arr = o[key] ?: return emptyList()
            if (arr !is JsonArray) { errors += "$at.$key must be an array"; return emptyList() }
            return arr.mapNotNull { e ->
                val v = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (v == null) { errors += "$at.$key entries must be strings"; null }
                else { if (v !in assets) errors += "$at.$key references missing asset '$v'"; v }
            }
        }

        private inline fun <reified E : Enum<E>> enumOf(v: String, at: String): E? =
            enumValues<E>().firstOrNull { it.name == v } ?: run { errors += "$at has invalid value '$v'"; null }

        private fun str(o: JsonObject, key: String, at: String, max: Int): String? {
            val p = o[key] as? JsonPrimitive
            if (p == null || p is JsonNull || !p.isString) { errors += "$at.$key is required (string)"; return null }
            return sanitize(p.content, max, "$at.$key")
        }

        private fun optStr(o: JsonObject, key: String, at: String, max: Int): String? {
            val p = o[key] ?: return null
            if (p is JsonNull) return null
            if (p !is JsonPrimitive || !p.isString) { errors += "$at.$key must be a string"; return null }
            return sanitize(p.content, max, "$at.$key")
        }

        private fun sanitize(s: String, max: Int, at: String): String? {
            if (s.length > max) { errors += "$at exceeds $max characters"; return null }
            return s.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
        }

        private fun long(o: JsonObject, key: String, at: String): Long? {
            val v = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if (v == null) errors += "$at.$key is required (integer)"
            return v
        }

        private fun optLong(o: JsonObject, key: String): Long? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    }

    fun encode(lineup: Lineup): String = json.encodeToString(JsonElement.serializer(), toJson(lineup))

    fun toJson(lineup: Lineup): JsonObject = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("revision", lineup.revision)
        put("generatedAtUtcMs", lineup.generatedAtUtcMs)
        put("reviewPolicy", lineup.reviewPolicy.name)
        put("eraPolicy", buildJsonObject {
            put("minYear", lineup.eraPolicy.minYear)
            put("maxYear", lineup.eraPolicy.maxYear)
            put("preferredMaxYear", lineup.eraPolicy.preferredMaxYear)
        })
        put("channels", buildJsonArray {
            lineup.channels.sortedBy { it.number }.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id); put("number", c.number); put("displayName", c.displayName)
                    put("genre", c.genre); put("categoryLabel", c.categoryLabel); put("color", c.color)
                    c.logoAsset?.let { put("logoAsset", it) }
                    put("enabled", c.enabled); put("scheduleRevision", c.scheduleRevision)
                    put("epochUtcMs", c.epochUtcMs); put("policy", c.policy.name)
                    put("playlist", buildJsonArray { c.playlist.forEach { add(JsonPrimitive(it)) } })
                    put("fallbackPool", buildJsonArray { c.fallbackPool.forEach { add(JsonPrimitive(it)) } })
                    if (c.fixedEntries.isNotEmpty()) put("fixedEntries", buildJsonArray {
                        c.fixedEntries.forEach { f ->
                            add(buildJsonObject {
                                put("startOffsetMs", f.startOffsetMs); put("durationMs", f.durationMs)
                                put("assetId", f.assetId); put("sourceOffsetMs", f.sourceOffsetMs)
                                f.displayTitle?.let { put("displayTitle", it) }
                                f.description?.let { put("description", it) }
                            })
                        }
                    })
                    c.fixedPeriodMs?.let { put("fixedPeriodMs", it) }
                    put("timeZone", c.timeZone)
                    if (c.dayparts.isNotEmpty()) put("dayparts", buildJsonArray {
                        c.dayparts.forEach { d ->
                            add(buildJsonObject {
                                put("id", d.id); put("title", d.title)
                                put("days", buildJsonArray { d.days.sorted().forEach { add(JsonPrimitive(it)) } })
                                put("startMinute", d.startMinute); put("endMinute", d.endMinute)
                                put("tags", strings(d.tags))
                                if (d.preferTypes.isNotEmpty()) put("preferTypes", strings(d.preferTypes.map { it.name }.sorted()))
                                if (d.assetIds.isNotEmpty()) put("assetIds", strings(d.assetIds))
                            })
                        }
                    })
                    if (c.defaultTags.isNotEmpty()) put("defaultTags", strings(c.defaultTags))
                    if (c.fillerTags.isNotEmpty()) put("fillerTags", strings(c.fillerTags))
                    if (c.discoveryQueries.isNotEmpty()) put("discoveryQueries", strings(c.discoveryQueries))
                    if (c.fallbackTags.isNotEmpty()) put("fallbackTags", strings(c.fallbackTags))
                    if (c.ignoreEra) put("ignoreEra", true)
                })
            }
        })
        put("assets", buildJsonArray {
            lineup.assets.values.sortedBy { it.id }.forEach { a ->
                add(buildJsonObject {
                    put("id", a.id); put("archiveIdentifier", a.archiveIdentifier); put("fileName", a.fileName)
                    put("url", a.url); put("title", a.title); put("description", a.description)
                    a.creator?.let { put("creator", it) }; a.collection?.let { put("collection", it) }
                    a.category?.let { put("category", it) }
                    put("durationMs", a.durationMs); put("sourceStartMs", a.sourceStartMs); put("seekable", a.seekable)
                    a.codec?.let { put("codec", it) }; a.container?.let { put("container", it) }
                    a.aspectRatio?.let { put("aspectRatio", it) }
                    a.broadcastYear?.let { put("broadcastYear", it) }
                    put("yearVerified", a.yearVerified)
                    a.yearEvidence?.let { put("yearEvidence", it) }
                    put("rightsStatus", a.rightsStatus.name)
                    a.rightsNotes?.let { put("rightsNotes", it) }
                    put("provenance", a.provenance.name); put("adOrigin", a.adOrigin.name)
                    put("healthStatus", a.healthStatus.name)
                    a.lastCheckedAtUtcMs?.let { put("lastCheckedAtUtcMs", it) }
                    a.captionUrl?.let { put("captionUrl", it) }
                    if (a.alternateUrls.isNotEmpty()) put("alternateUrls", buildJsonArray { a.alternateUrls.forEach { add(JsonPrimitive(it)) } })
                    put("contentType", a.contentType.name)
                    a.series?.let { put("series", it) }
                    if (a.tags.isNotEmpty()) put("tags", strings(a.tags))
                    if (a.availableFromUtcMs != 0L) put("availableFromUtcMs", a.availableFromUtcMs)
                    if (a.segments.isNotEmpty()) put("segments", buildJsonArray {
                        a.segments.forEach { s ->
                            add(buildJsonObject { put("offsetMs", s.offsetMs); put("label", s.label); put("kind", s.kind.name) })
                        }
                    })
                })
            }
        })
    }

    private fun strings(list: List<String>) = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }
}
