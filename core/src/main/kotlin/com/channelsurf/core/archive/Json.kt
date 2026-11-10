package com.channelsurf.core.archive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient accessors for the Archive's irregular JSON: a field may be a string, a number,
 * an array of strings, or missing. Never throws.
 */
internal object J {
    fun text(e: JsonElement?): String? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> e.content.takeIf { it.isNotBlank() }
        is JsonArray -> e.mapNotNull { text(it) }.firstOrNull()
        is JsonObject -> null
    }

    fun texts(e: JsonElement?): List<String> = when (e) {
        null, JsonNull -> emptyList()
        is JsonPrimitive -> listOfNotNull(e.content.takeIf { it.isNotBlank() })
        is JsonArray -> e.flatMap { texts(it) }
        is JsonObject -> emptyList()
    }

    fun long(e: JsonElement?): Long? = text(e)?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
    fun int(e: JsonElement?): Int? = long(e)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

    fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
    fun arr(e: JsonElement?): JsonArray? = e as? JsonArray

    /** Removes control characters and caps length; metadata is untrusted input. */
    fun clean(s: String?, max: Int = 4000): String? =
        s?.replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
            ?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    /** Archive descriptions often contain HTML. Strip tags and decode a few entities for plain-text display. */
    fun plainText(html: String?, max: Int = 4000): String? {
        if (html == null) return null
        val noTags = html.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]*>"), "")
        val decoded = noTags.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        return clean(decoded.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n"), max)
    }
}
