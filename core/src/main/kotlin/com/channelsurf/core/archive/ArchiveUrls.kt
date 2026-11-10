package com.channelsurf.core.archive

import java.net.URI

/**
 * Builds Internet Archive URLs with correct percent-encoding. Never concatenate raw file names into URLs:
 * Archive file names routinely contain spaces, '#', '?', '&', unicode and sub-directories.
 */
object ArchiveUrls {
    const val BASE = "https://archive.org"
    private val IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$")

    fun isValidIdentifier(identifier: String): Boolean = IDENTIFIER.matches(identifier)

    private fun requireIdentifier(identifier: String) {
        require(isValidIdentifier(identifier)) { "Invalid Archive identifier" }
    }

    fun details(identifier: String): String {
        requireIdentifier(identifier)
        return "$BASE/details/${encodePathSegment(identifier)}"
    }

    fun metadata(identifier: String): String {
        requireIdentifier(identifier)
        return "$BASE/metadata/${encodePathSegment(identifier)}"
    }

    /** File names may contain '/' for sub-directories; each segment is encoded separately. */
    fun download(identifier: String, fileName: String): String {
        requireIdentifier(identifier)
        require(fileName.isNotBlank()) { "Blank file name" }
        require(fileName.split('/').none { it == ".." || it == "." }) { "Path traversal in file name" }
        val path = fileName.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodePathSegment(it) }
        return "$BASE/download/${encodePathSegment(identifier)}/$path"
    }

    fun advancedSearch(
        query: String,
        page: Int,
        rows: Int = 25,
        fields: List<String> = DEFAULT_FIELDS,
        sort: String? = null,
    ): String {
        require(page >= 1) { "page must be >= 1" }
        require(rows in 1..100) { "rows must be 1..100" }
        val params = buildList {
            add("q" to query)
            fields.forEach { add("fl[]" to it) }
            sort?.let { add("sort[]" to it) }
            add("rows" to rows.toString())
            add("page" to page.toString())
            add("output" to "json")
        }
        return "$BASE/advancedsearch.php?" + params.joinToString("&") { (k, v) -> "${encodeQuery(k)}=${encodeQuery(v)}" }
    }

    val DEFAULT_FIELDS = listOf("identifier", "title", "year", "date", "collection", "licenseurl", "mediatype")

    /** RFC 3986 path-segment encoding: unreserved characters stay, everything else is UTF-8 percent-encoded. */
    fun encodePathSegment(s: String): String = percentEncode(s) { c -> isUnreserved(c) }

    fun encodeQuery(s: String): String = percentEncode(s) { c -> isUnreserved(c) }

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    private inline fun percentEncode(s: String, keep: (Char) -> Boolean): String {
        val out = StringBuilder(s.length + 16)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (b >= 0 && keep(c)) out.append(c) else {
                out.append('%')
                out.append(HEX[(b.toInt() shr 4) and 0xF])
                out.append(HEX[b.toInt() and 0xF])
            }
        }
        return out.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}

/** Only HTTPS URLs on archive.org (or explicitly approved hosts) may ever be fetched or played. */
object UrlPolicy {
    private val ARCHIVE_SUFFIX = ".archive.org"

    fun isAllowed(url: String?, extraApprovedHosts: Set<String> = emptySet()): Boolean {
        if (url.isNullOrBlank() || url.length > 4096) return false
        val uri = try { URI(url) } catch (e: Exception) { return false }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "archive.org" || host.endsWith(ARCHIVE_SUFFIX) ||
            extraApprovedHosts.any { host == it.lowercase() }
    }
}
