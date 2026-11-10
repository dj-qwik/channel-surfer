package com.channelsurf.core.archive

import com.channelsurf.core.model.EraPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class SearchDoc(
    val identifier: String,
    val title: String,
    val year: Int?,
    val date: String?,
    val collections: List<String>,
    val licenseUrl: String?,
)

data class SearchPage(val numFound: Long, val start: Long, val docs: List<SearchDoc>)

data class ArchiveFile(
    val name: String,
    val format: String?,
    val source: String?,
    val lengthMs: Long?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
)

data class ArchiveItem(
    val identifier: String,
    val title: String,
    val description: String?,
    val creator: String?,
    val collections: List<String>,
    val subjects: List<String>,
    val date: String?,
    val year: Int?,
    val licenseUrl: String?,
    val rights: String?,
    val isDark: Boolean,
    val files: List<ArchiveFile>,
)

sealed interface ParseResult<out T> {
    data class Ok<T>(val value: T) : ParseResult<T>
    data class NotFound(val reason: String) : ParseResult<Nothing>
    data class Malformed(val reason: String) : ParseResult<Nothing>
}

object ArchiveParsing {
    const val MAX_RESPONSE_CHARS = 8_000_000
    private val json = Json { isLenient = false }

    private fun parseRoot(body: String): JsonElement? {
        if (body.length > MAX_RESPONSE_CHARS) return null
        return try { json.parseToJsonElement(body) } catch (e: Exception) { null }
    }

    fun parseSearch(body: String): ParseResult<SearchPage> {
        val root = J.obj(parseRoot(body)) ?: return ParseResult.Malformed("Search response is not a JSON object")
        val response = J.obj(root["response"]) ?: return ParseResult.Malformed("Search response has no 'response' object")
        val docs = J.arr(response["docs"])?.mapNotNull { el ->
            val o = J.obj(el) ?: return@mapNotNull null
            val id = J.text(o["identifier"])?.takeIf { ArchiveUrls.isValidIdentifier(it) } ?: return@mapNotNull null
            SearchDoc(
                identifier = id,
                title = J.clean(J.text(o["title"]), 300) ?: id,
                year = J.int(o["year"]) ?: J.text(o["date"])?.take(4)?.toIntOrNull(),
                date = J.clean(J.text(o["date"]), 40),
                collections = J.texts(o["collection"]).mapNotNull { J.clean(it, 120) },
                licenseUrl = J.clean(J.text(o["licenseurl"]), 500),
            )
        } ?: emptyList()
        return ParseResult.Ok(SearchPage(J.long(response["numFound"]) ?: docs.size.toLong(), J.long(response["start"]) ?: 0, docs))
    }

    fun parseMetadata(body: String, requestedIdentifier: String): ParseResult<ArchiveItem> {
        val root = J.obj(parseRoot(body)) ?: return ParseResult.Malformed("Metadata response is not a JSON object")
        // The metadata API returns {} for identifiers that do not exist.
        if (root.isEmpty()) return ParseResult.NotFound("Item '$requestedIdentifier' does not exist")
        J.text(root["error"])?.let { return ParseResult.NotFound(J.clean(it, 200) ?: "error") }
        val md: JsonObject = J.obj(root["metadata"]) ?: return ParseResult.Malformed("Metadata response has no 'metadata' object")
        val identifier = J.text(md["identifier"]) ?: requestedIdentifier
        if (!ArchiveUrls.isValidIdentifier(identifier)) return ParseResult.Malformed("Invalid identifier in metadata")
        val isDark = J.text(root["is_dark"]) == "true"
        val files = J.arr(root["files"])?.mapNotNull { el ->
            val f = J.obj(el) ?: return@mapNotNull null
            val name = J.clean(J.text(f["name"]), 1000) ?: return@mapNotNull null
            ArchiveFile(
                name = name,
                format = J.clean(J.text(f["format"]), 100),
                source = J.clean(J.text(f["source"]), 40),
                lengthMs = parseLengthMs(J.text(f["length"])),
                sizeBytes = J.long(f["size"]),
                width = J.int(f["width"]),
                height = J.int(f["height"]),
            )
        } ?: emptyList()
        return ParseResult.Ok(
            ArchiveItem(
                identifier = identifier,
                title = J.clean(J.text(md["title"]), 300) ?: identifier,
                description = J.plainText(J.texts(md["description"]).joinToString("\n").ifBlank { null }),
                creator = J.clean(J.text(md["creator"]), 200),
                collections = J.texts(md["collection"]).mapNotNull { J.clean(it, 120) },
                subjects = J.texts(md["subject"]).flatMap { it.split(';') }.mapNotNull { J.clean(it, 80) },
                date = J.clean(J.text(md["date"]), 40),
                year = J.int(md["year"]),
                licenseUrl = J.clean(J.text(md["licenseurl"]), 500),
                rights = J.clean(J.text(md["rights"]), 1000),
                isDark = isDark,
                files = files,
            )
        )
    }

    /** Archive "length" is seconds ("1234.56") or a clock string ("1:02:03" / "02:03"). */
    fun parseLengthMs(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' in s) {
            val parts = s.split(':')
            if (parts.size !in 2..3) return null
            var total = 0.0
            for (p in parts) {
                val v = p.toDoubleOrNull() ?: return null
                if (v < 0) return null
                total = total * 60 + v
            }
            return (total * 1000).toLong().takeIf { it > 0 }
        }
        val secs = s.toDoubleOrNull() ?: return null
        if (secs <= 0 || secs.isNaN() || secs > 7 * 24 * 3600) return null
        return (secs * 1000).toLong()
    }
}

/** A file that may be playable by Media3, ranked best first. */
data class PlayableCandidate(
    val file: ArchiveFile,
    val container: String,
    val codec: String,
    val mimeType: String,
    val priority: Int,
    val aspectRatio: String?,
) {
    val lowBandwidth: Boolean get() = file.format?.contains("512Kb", ignoreCase = true) == true
}

object PlayableFileSelector {
    private val REJECT_FORMATS = setOf(
        "metadata", "thumbnail", "jpeg", "jpeg thumb", "item tile", "animated gif", "archive bittorrent",
        "subrip", "web video text tracks", "text", "png", "gif", "djvutxt", "json", "spectrogram",
        "ogg video", "mpeg2", "mpeg1", "cinepack", "quicktime", "windows media", "avi", "vbr mp3", "ogg vorbis",
        "flac", "item image", "storj upload log", "storj upload trigger",
    )
    private val REJECT_EXT = setOf(
        "jpg", "jpeg", "png", "gif", "xml", "sqlite", "torrent", "txt", "srt", "vtt", "json", "gz", "zip",
        "ogv", "mpg", "mpeg", "avi", "wmv", "mov", "mp3", "ogg", "flac", "md5", "sha1", "log", "html",
    )
    private val CAPTION_EXT = setOf("srt", "vtt")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun isRejected(f: ArchiveFile): Boolean {
        val fmt = f.format?.lowercase()
        if (fmt != null && fmt in REJECT_FORMATS) return true
        if (ext(f.name) in REJECT_EXT) return true
        if (f.name.contains("_thumb", ignoreCase = true) || f.name.endsWith("__ia_thumb.jpg")) return true
        return false
    }

    /** Returns playable candidates best first. Formats Media3 cannot decode reliably (Theora, MPEG-2, WMV) are excluded. */
    fun select(item: ArchiveItem, preferLowBandwidth: Boolean = false): List<PlayableCandidate> {
        val out = item.files.mapNotNull { f ->
            if (isRejected(f)) return@mapNotNull null
            val fmt = f.format?.lowercase().orEmpty()
            val e = ext(f.name)
            val aspect = aspectOf(f.width, f.height)
            when {
                fmt.startsWith("h.264") ->
                    PlayableCandidate(f, "mp4", "h264", "video/mp4", 30, aspect)
                fmt == "512kb mpeg4" ->
                    PlayableCandidate(f, "mp4", "h264", "video/mp4", 15, aspect)
                fmt == "mpeg4" ->
                    PlayableCandidate(f, "mp4", "h264", "video/mp4", 25, aspect)
                e in setOf("mp4", "m4v") && (fmt.isEmpty() || fmt.contains("mp4") || fmt.contains("mpeg4")) ->
                    PlayableCandidate(f, "mp4", "h264", "video/mp4", 20, aspect)
                fmt == "webm" || e == "webm" ->
                    PlayableCandidate(f, "webm", "vp8/vp9", "video/webm", 10, aspect)
                e == "m3u8" ->
                    PlayableCandidate(f, "hls", "h264", "application/x-mpegURL", 12, aspect)
                else -> null
            }
        }
        return if (preferLowBandwidth) {
            out.sortedWith(compareByDescending<PlayableCandidate> { it.lowBandwidth }.thenByDescending { it.priority })
        } else {
            out.sortedWith(compareByDescending<PlayableCandidate> { it.priority }.thenByDescending { it.file.height ?: 0 })
        }
    }

    fun captionCandidates(item: ArchiveItem): List<ArchiveFile> =
        item.files.filter { ext(it.name) in CAPTION_EXT }.sortedBy { if (ext(it.name) == "vtt") 0 else 1 }

    fun aspectOf(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        val r = width.toDouble() / height
        return when {
            // 720x480 / 704x480 / 640x480 / 320x240 NTSC captures are 4:3 material (DV uses non-square pixels).
            r in 1.25..1.55 -> "4:3"
            r in 1.70..1.82 -> "16:9"
            else -> "${width}x$height"
        }
    }
}

data class YearClue(val year: Int, val source: String, val excerpt: String)

data class YearSuggestion(val year: Int?, val clues: List<YearClue>, val inEra: Boolean, val conflicting: Boolean)

/**
 * Collects evidence about the ORIGINAL broadcast year. Upload dates are deliberately ignored:
 * archive upload year is not the broadcast year. A suggestion never verifies anything by itself;
 * an operator must confirm it in the review queue.
 */
object YearEvidence {
    private val YEAR = Regex("(?<![0-9])(19[4-9][0-9]|20[0-2][0-9])(?![0-9])")

    fun clues(item: ArchiveItem): List<YearClue> = buildList {
        item.year?.let { add(YearClue(it, "metadata.year", "year=$it")) }
        item.date?.let { d -> YEAR.find(d)?.let { add(YearClue(it.value.toInt(), "metadata.date", d)) } }
        YEAR.findAll(item.title).forEach { add(YearClue(it.value.toInt(), "title", item.title.take(120))) }
        item.description?.let { desc ->
            YEAR.findAll(desc).take(10).forEach { m ->
                val from = (m.range.first - 40).coerceAtLeast(0)
                val to = (m.range.last + 40).coerceAtMost(desc.length - 1)
                add(YearClue(m.value.toInt(), "description", desc.substring(from, to + 1).replace('\n', ' ')))
            }
        }
        item.subjects.forEach { s -> YEAR.findAll(s).forEach { add(YearClue(it.value.toInt(), "subject", s)) } }
    }

    fun suggest(item: ArchiveItem, era: EraPolicy = EraPolicy()): YearSuggestion {
        val clues = clues(item)
        if (clues.isEmpty()) return YearSuggestion(null, clues, inEra = false, conflicting = false)
        val counts = clues.groupingBy { it.year }.eachCount()
        val best = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }
            .thenByDescending { era.contains(it.key) }.thenBy { it.key }).first().key
        val distinctInEra = counts.keys.filter { era.contains(it) }
        return YearSuggestion(best, clues, era.contains(best), conflicting = distinctInEra.size > 1 || counts.size > 1)
    }
}
