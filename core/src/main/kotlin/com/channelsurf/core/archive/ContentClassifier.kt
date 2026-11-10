package com.channelsurf.core.archive

import com.channelsurf.core.model.ContentType

data class Classification(val type: ContentType, val series: String?, val title: String)

/**
 * Best-effort typing of an Archive recording from its title, description, subjects and decoded length, so
 * schedules can prefer full episodes and movies and use short spots as station-break filler.
 */
object ContentClassifier {
    private val COMMERCIAL = Regex("\\b(commercials?|tv ads?|advertisements?|adverts?)\\b", RegexOption.IGNORE_CASE)
    private val BUMPER = Regex("\\b(bumpers?|promos?|idents?|station id|interstitials?|next bumpers?)\\b", RegexOption.IGNORE_CASE)
    private val MUSIC = Regex("\\b(music video|official video|videoclip)\\b", RegexOption.IGNORE_CASE)
    private val SHOPPING = Regex("\\b(qvc|hsn|home shopping|infomercials?|shop at home)\\b", RegexOption.IGNORE_CASE)
    private val NEWS = Regex("\\b(newscast|news at|evening news|nightly news|headline news|local news|news broadcast)\\b", RegexOption.IGNORE_CASE)
    private val SPORTS = Regex("\\b(nfl|nba|mlb|nhl|ncaa|wcw|wwf|super bowl|world series|sportscenter|game \\d)\\b", RegexOption.IGNORE_CASE)
    private val AIRCHECK = Regex("\\b(aircheck|vhs tape|tape \\d|recorded off|off[- ]air|marathon|block|full broadcast|with commercials)\\b", RegexOption.IGNORE_CASE)
    private val MOVIE = Regex("\\b(movie|film|feature|tv movie|made[- ]for[- ]tv)\\b", RegexOption.IGNORE_CASE)
    private val EPISODE = Regex("\\b(s\\d{1,2}e\\d{1,3}|episode|ep\\.? ?\\d+|season \\d+)\\b", RegexOption.IGNORE_CASE)
    private val SERIES_SPLIT = Regex("\\s+[-–—:|]\\s+")

    fun classify(title: String, description: String?, subjects: List<String>, durationMs: Long): Classification {
        val text = listOf(title, description.orEmpty().take(600), subjects.joinToString(" ")).joinToString(" ")
        val min = durationMs / 60_000.0
        val type = when {
            SHOPPING.containsMatchIn(text) -> ContentType.SHOPPING
            min in 0.01..6.0 && MUSIC.containsMatchIn(text) -> ContentType.MUSIC_VIDEO
            min in 0.01..3.0 && BUMPER.containsMatchIn(text) -> ContentType.PROMO_BUMPER
            min in 0.01..3.0 && COMMERCIAL.containsMatchIn(text) -> ContentType.COMMERCIAL
            min > 150 || (min > 45 && AIRCHECK.containsMatchIn(text) && !EPISODE.containsMatchIn(title)) -> ContentType.AIRCHECK_BLOCK
            min in 3.0..20.0 && COMMERCIAL.containsMatchIn(text) -> ContentType.AIRCHECK_BLOCK // a reel of many ads
            min in 0.01..2.0 -> ContentType.PROMO_BUMPER
            NEWS.containsMatchIn(text) -> ContentType.NEWS
            SPORTS.containsMatchIn(text) && min > 20 -> ContentType.SPORTS
            min in 70.0..150.0 && !EPISODE.containsMatchIn(title) -> ContentType.MOVIE
            MOVIE.containsMatchIn(text) && min > 60 -> ContentType.MOVIE
            min in 9.0..70.0 -> ContentType.EPISODE
            min in 2.0..9.0 && MUSIC.containsMatchIn(text) -> ContentType.MUSIC_VIDEO
            else -> ContentType.OTHER
        }
        val series = if (type == ContentType.EPISODE) seriesOf(title) else null
        return Classification(type, series, title)
    }

    /** "Dexter's Laboratory - Dee Dee's Room" → "Dexter's Laboratory". */
    fun seriesOf(title: String): String? {
        val parts = title.split(SERIES_SPLIT, limit = 2)
        if (parts.size < 2) return null
        val s = parts[0].trim().replace(Regex("\\s*\\(?(19|20)\\d\\d\\)?$"), "").trim()
        return s.takeIf { it.length in 2..80 && !EPISODE.matches(it) }
    }
}
