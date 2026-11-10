package com.channelsurf.core

import com.channelsurf.core.TestData.HOUR
import com.channelsurf.core.TestData.MIN
import com.channelsurf.core.manifest.ManifestCodec
import com.channelsurf.core.manifest.ManifestResult
import com.channelsurf.core.model.BlockKind
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.ContentType
import com.channelsurf.core.model.Daypart
import com.channelsurf.core.model.Lineup
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.core.model.SchedulePolicy
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.schedule.ScheduleValidator
import com.channelsurf.core.schedule.SlateKind
import com.channelsurf.core.schedule.TuneResolution
import com.channelsurf.core.schedule.TuneResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DaypartSchedulerTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, m, d, h, mi, 0, 0, ny).toInstant().toEpochMilli()

    private fun a(id: String, min: Long, type: ContentType, vararg tags: String, series: String? = null, availableFrom: Long = 0) =
        TestData.asset(id, min * MIN, rights = RightsStatus.REVIEW_REQUIRED, verified = false).copy(
            contentType = type, tags = tags.toList(), series = series, availableFromUtcMs = availableFrom,
        )

    private val weekdays = setOf(1, 2, 3, 4, 5)

    /** A Cartoon Network-style channel: Toonami weekdays 16–18, Adult Swim Sun–Thu 23:00–05:00, cartoons otherwise. */
    private fun cartoonChannel() = Channel(
        id = "ch-27-cartoon-network", number = 27, displayName = "Cartoon Network", genre = "Cartoons",
        categoryLabel = "CARTOONS", epochUtcMs = TestData.EPOCH, policy = SchedulePolicy.DAYPARTED,
        timeZone = "America/New_York",
        dayparts = listOf(
            Daypart("toonami", "Toonami", weekdays, 16 * 60, 18 * 60, listOf("toonami"), setOf(ContentType.EPISODE)),
            Daypart("adult-swim", "Adult Swim", setOf(7, 1, 2, 3, 4), 23 * 60, 29 * 60, listOf("adult-swim")),
        ),
        defaultTags = listOf("cartoon"),
        fillerTags = listOf("cn-bumpers"),
    )

    private fun lineup(extra: List<MediaAsset> = emptyList()): Lineup {
        val assets = buildList {
            (1..12).forEach { add(a("cartoon$it", 22, ContentType.EPISODE, "cartoon", series = "Cartoon Show $it")) }
            (1..6).forEach { add(a("toon$it", 23, ContentType.EPISODE, "toonami", series = "Anime $it")) }
            add(a("toonTape", 240, ContentType.AIRCHECK_BLOCK, "toonami"))
            (1..5).forEach { add(a("as$it", 11, ContentType.EPISODE, "adult-swim")) }
            (1..4).forEach { add(a("bump$it", 1, ContentType.PROMO_BUMPER, "cn-bumpers")) }
            addAll(extra)
        }.associateBy { it.id }
        return Lineup("r1", 0, listOf(cartoonChannel()), assets)
    }

    @Test
    fun dayIsGaplessAcrossDstAndIdenticalOnEveryDevice() {
        val e1 = ScheduleEngine(lineup())
        val e2 = ScheduleEngine(lineup())
        // Week containing the 2026-11-01 fall-back (25-hour broadcast day).
        val from = at(2026, 10, 29, 6)
        val to = at(2026, 11, 5, 6)
        val b1 = e1.blocksBetween("ch-27-cartoon-network", from, to)
        assertEquals(emptyList<String>(), ScheduleValidator.problems(b1, from, to))
        assertEquals(b1, e2.blocksBetween("ch-27-cartoon-network", from, to))
    }

    @Test
    fun toonamiAirsInItsWindowAndPrefersFullEpisodes() {
        val e = ScheduleEngine(lineup())
        val mon = at(2026, 11, 2, 16, 0)
        val blocks = e.blocksBetween("ch-27-cartoon-network", mon, at(2026, 11, 2, 18, 0))
        assertEquals(mon, blocks.first().startUtcMs) // the block starts exactly at 4:00 PM
        assertTrue(blocks.all { it.daypartTitle == "Toonami" })
        val programs = blocks.filter { it.kind == BlockKind.PROGRAM }.mapNotNull { it.assetId }
        assertTrue(programs.any { it.startsWith("toon") && it != "toonTape" })
        assertFalse("full episodes exist, so the aircheck tape is not used", "toonTape" in programs)
        assertTrue(blocks.filter { it.kind == BlockKind.PROGRAM && it.assetId!!.startsWith("toon") }.all { it.displayTitle.startsWith("Anime") })
        // Saturday has no Toonami: regular cartoons
        val sat = e.blocksBetween("ch-27-cartoon-network", at(2026, 11, 7, 16), at(2026, 11, 7, 18))
        assertTrue(sat.none { it.daypartTitle == "Toonami" })
    }

    @Test
    fun adultSwimRunsPastMidnightOnTheSameBroadcastDay() {
        val e = ScheduleEngine(lineup())
        val sun2am = at(2026, 11, 9, 2, 0) // Monday 2 AM belongs to Sunday's broadcast day
        assertEquals("Adult Swim", e.blockAt("ch-27-cartoon-network", sun2am).daypartTitle)
        val fri2am = at(2026, 11, 7, 2, 0) // Saturday 2 AM = Friday night: no Adult Swim on Fridays here
        assertNotEquals("Adult Swim", e.blockAt("ch-27-cartoon-network", fri2am).daypartTitle)
    }

    @Test
    fun scheduleChangesFromDayToDay() {
        val e = ScheduleEngine(lineup())
        fun dayOrder(d: Int) = e.blocksBetween("ch-27-cartoon-network", at(2026, 11, d, 6), at(2026, 11, d, 16))
            .filter { it.kind == BlockKind.PROGRAM && it.slotAssetId != null }.distinctBy { it.slotStartUtcMs }.map { it.slotAssetId }
        val tue = dayOrder(3)
        val wed = dayOrder(4)
        assertNotEquals("each day gets a different lineup", tue, wed)
        // Within a day, the 12-episode pool rotates through every show before repeating.
        assertEquals(tue.take(12).toSet().size, 12)
        tue.zipWithNext().forEach { (x, y) -> assertNotEquals(x, y) }
    }

    @Test
    fun newRecordingsJoinTomorrowWithoutDisturbingToday() {
        val now = at(2026, 11, 3, 9, 0)
        val before = ScheduleEngine(lineup())
        val withNew = ScheduleEngine(lineup((1..5).map { a("new$it", 22, ContentType.EPISODE, "cartoon", availableFrom = now) }))
        val todayStart = at(2026, 11, 3, 6)
        val todayEnd = at(2026, 11, 4, 6)
        assertEquals(
            before.blocksBetween("ch-27-cartoon-network", todayStart, todayEnd),
            withNew.blocksBetween("ch-27-cartoon-network", todayStart, todayEnd),
        )
        val tomorrow = withNew.blocksBetween("ch-27-cartoon-network", todayEnd, at(2026, 11, 5, 6)).mapNotNull { it.assetId }
        assertTrue(tomorrow.any { it.startsWith("new") })
    }

    @Test
    fun emptyChannelStartsAiringNewFindsImmediately() {
        val now = at(2026, 11, 3, 9, 0)
        val fresh = Lineup("r", 0, listOf(cartoonChannel()), listOf(a("first", 22, ContentType.EPISODE, "cartoon", availableFrom = now)).associateBy { it.id })
        val b = ScheduleEngine(fresh).blockAt("ch-27-cartoon-network", now)
        assertEquals("first", b.assetId)
    }

    @Test
    fun leftoverMinutesUseBumpersThenStationBreak() {
        val e = ScheduleEngine(lineup())
        val blocks = e.blocksBetween("ch-27-cartoon-network", at(2026, 11, 2, 6), at(2026, 11, 3, 6))
        assertTrue(blocks.any { it.assetId?.startsWith("bump") == true })
        // Episodes are split only by commercial breaks: every episode's acts add up to its whole runtime, in order.
        val assets = lineup().assets
        blocks.filter { it.kind == BlockKind.PROGRAM && assets.getValue(it.assetId!!).contentType == ContentType.EPISODE }
            .groupBy { it.slotStartUtcMs to it.assetId }
            .forEach { (k, acts) ->
                if (acts.first().startUtcMs < at(2026, 11, 2, 6) + 1 || acts.last().endUtcMs > at(2026, 11, 3, 6) - 1) return@forEach
                assertEquals(0L, acts.first().sourceOffsetMs)
                acts.zipWithNext().forEach { (x, y) -> assertEquals(x.sourceOffsetMs + x.durationMs, y.sourceOffsetMs) }
                assertEquals(assets.getValue(k.second!!).playDurationMs, acts.sumOf { it.durationMs })
            }
    }

    @Test
    fun channelWithOnlyCommercialsStaysCompactInsteadOfAWallOfAds() {
        val ads = (1..30).map { a("ad$it", 0, ContentType.COMMERCIAL, "cn-bumpers").copy(durationMs = 30_000) }
        val tiny = (1..5).map { a("blip$it", 0, ContentType.EPISODE, "cartoon").copy(durationMs = 4_000) }
        val l = Lineup("r", 0, listOf(cartoonChannel()), (ads + tiny).associateBy { it.id })
        val e = ScheduleEngine(l)
        val from = at(2026, 11, 2, 6)
        val day = e.blocksBetween("ch-27-cartoon-network", from, from + 24 * HOUR)
        assertTrue("got ${day.size} blocks", day.size < 50)
        assertTrue(day.none { it.assetId?.startsWith("blip") == true })
        assertTrue(ScheduleValidator.problems(day, from, from + 24 * HOUR).isEmpty())
    }

    @Test
    fun thinDaypartPoolIsToppedUpFromTheChannelInsteadOfLooping() {
        val only = lineup().let { l -> l.copy(assets = l.assets.filterKeys { !it.startsWith("toon") || it == "toonTape" }) }
        val e = ScheduleEngine(only)
        val from = at(2026, 11, 2, 16, 0)
        val to = at(2026, 11, 2, 18, 0)
        val window = e.blocksBetween("ch-27-cartoon-network", from, to)
        // Programs never run past the daypart's end, and the block isn't one tape over and over.
        assertTrue(window.filter { it.startUtcMs < to }.all { it.endUtcMs <= to || it.assetId == "toonTape" })
        assertTrue(window.mapNotNull { it.assetId }.toSet().size > 1)
    }

    @Test
    fun emptyPoolsShowLabelledOffAirAndResolverPlaysPrograms() {
        val empty = Lineup("r", 0, listOf(cartoonChannel()), emptyMap())
        val e = ScheduleEngine(empty)
        assertFalse(e.hasProgramming("ch-27-cartoon-network"))
        val r = TuneResolver(e).resolve("ch-27-cartoon-network", at(2026, 11, 2, 16, 30)) as TuneResolution.Slate
        assertEquals(SlateKind.NO_PROGRAMMING, r.kind)
        val full = ScheduleEngine(lineup())
        val p = TuneResolver(full).resolve("ch-27-cartoon-network", at(2026, 11, 2, 16, 30))
        assertTrue(p is TuneResolution.Play || (p as TuneResolution.Slate).kind == SlateKind.STATION_BREAK)
    }

    @Test
    fun daypartedManifestRoundTrips() {
        val l = lineup()
        val decoded = (ManifestCodec.decode(ManifestCodec.encode(l)) as ManifestResult.Success).lineup
        assertEquals(l.channels, decoded.channels)
        assertEquals(l.assets, decoded.assets)
        val overlap = ManifestCodec.encode(l.copy(channels = listOf(cartoonChannel().copy(dayparts = cartoonChannel().dayparts +
            Daypart("x", "X", setOf(1), 17 * 60, 19 * 60, listOf("cartoon"))))))
        assertTrue(ManifestCodec.decode(overlap) is ManifestResult.Failure)
        assertEquals(1, HOUR / HOUR)
    }

    @Test
    fun emptyChannelBorrowsFromRelatedPoolsAndSpecialtyChannelsIgnoreTheEra() {
        val trek = Channel(
            id = "ch-75-ds9", number = 75, displayName = "Deep Space Nine", genre = "Sci-Fi", categoryLabel = "STAR TREK",
            epochUtcMs = TestData.EPOCH, policy = SchedulePolicy.DAYPARTED, defaultTags = listOf("star-trek-ds9"),
            fallbackTags = listOf("star-trek"), ignoreEra = true,
        )
        val tos = (1..20).map { a("tos$it", 50, ContentType.EPISODE, "star-trek").copy(broadcastYear = 1967) }
        val l = Lineup("r", 0, listOf(trek), tos.associateBy { it.id })
        val e = ScheduleEngine(l)
        val from = at(2026, 11, 2, 6)
        val day = e.blocksBetween("ch-75-ds9", from, from + 24 * HOUR)
        assertTrue(day.count { it.assetId?.startsWith("tos") == true } > 20)
        assertTrue(day.none { it.kind == BlockKind.SLATE_NO_PROGRAMMING })
        // The same 1967 episodes on a regular (era) channel stay off the air.
        val era = ScheduleEngine(l.copy(channels = listOf(trek.copy(ignoreEra = false))))
        assertTrue(era.blocksBetween("ch-75-ds9", from, from + 24 * HOUR).none { it.assetId != null && it.kind == BlockKind.PROGRAM })
    }

    // ---------------------------------------------------------------- half-hour grid

    private fun localMinute(utc: Long) = java.time.Instant.ofEpochMilli(utc).atZone(java.time.ZoneId.of("America/New_York")).minute

    private fun gridLineup(extra: List<MediaAsset> = emptyList(), fillerTags: List<String> = listOf("ads")): Lineup {
        val ch = cartoonChannel().copy(dayparts = emptyList(), defaultTags = listOf("show"), fillerTags = fillerTags)
        val assets = buildList {
            (1..10).forEach { add(a("ep$it", 0, ContentType.EPISODE, "show").copy(durationMs = (22 * 60 + it * 7) * 1000L)) }
            (1..40).forEach { add(a("ad$it", 0, ContentType.COMMERCIAL, "ads").copy(durationMs = if (it % 3 == 0) 15_000 else 30_000)) }
            (1..10).forEach { add(a("tr$it", 0, ContentType.TRAILER, "movie-trailers").copy(durationMs = 140_000)) }
            addAll(extra)
        }.associateBy { it.id }
        return Lineup("g", 0, listOf(ch), assets)
    }

    @Test
    fun showsStartOnTheHourAndHalfHourWithBreaksInside() {
        val l = gridLineup()
        val e = ScheduleEngine(l)
        val from = at(2026, 11, 2, 6)
        val day = e.blocksBetween("ch-27-cartoon-network", from, from + 24 * HOUR)
        assertTrue(ScheduleValidator.problems(day, from, from + 24 * HOUR).isEmpty())
        val airings = day.filter { it.slotStartUtcMs != null }.groupBy { it.slotStartUtcMs!! }
        assertTrue(airings.size >= 46)
        airings.forEach { (start, blocks) ->
            assertTrue("starts at :00/:30", localMinute(start) % 30 == 0)
            assertTrue(localMinute(blocks.first().slotEndUtcMs!!) % 30 == 0)
            // cold open, act one, act two, tag = 4 pieces of the show, with commercials between them
            val acts = blocks.filter { it.assetId?.startsWith("ep") == true }
            assertEquals(4, acts.size)
            assertTrue(blocks.any { it.assetId?.startsWith("ad") == true })
            assertTrue(blocks.none { it.assetId?.startsWith("tr") == true })
            // the guide and banner see one show
            assertEquals(1, blocks.map { it.slotView().id }.toSet().size)
            assertEquals(30 * MIN, blocks.first().slotView().durationMs)
        }
    }

    @Test
    fun moviesRunUninterruptedThenTrailersUntilTheNextSlot() {
        val movie = a("movie1", 0, ContentType.MOVIE, "show").copy(durationMs = 97 * MIN)
        val l = gridLineup().let { g -> g.copy(assets = g.assets.filterKeys { !it.startsWith("ep") } + (movie.id to movie)) }
        val e = ScheduleEngine(l)
        val from = at(2026, 11, 2, 6)
        val day = e.blocksBetween("ch-27-cartoon-network", from, from + 24 * HOUR)
        val airing = day.filter { it.slotAssetId == "movie1" }.groupBy { it.slotStartUtcMs }.values.first { it.first().startUtcMs >= from }
        val movieBlocks = airing.filter { it.assetId == "movie1" }
        assertEquals(1, movieBlocks.size)
        assertEquals(97 * MIN, movieBlocks.single().durationMs)
        assertEquals(120 * MIN, airing.first().slotEndUtcMs!! - airing.first().slotStartUtcMs!!)
        assertTrue(airing.drop(1).filter { it.kind == BlockKind.PROGRAM }.all { it.assetId!!.startsWith("tr") })
    }

    @Test
    fun shortCartoonsShareAHalfHourAndCommercialFreeChannelsSkipMidShowBreaks() {
        val shorts = (1..8).map { a("short$it", 11, ContentType.EPISODE, "show") }
        val l = gridLineup().let { g -> g.copy(assets = g.assets.filterKeys { !it.startsWith("ep") } + shorts.associateBy { it.id }) }
        val from = at(2026, 11, 2, 6)
        val day = ScheduleEngine(l).blocksBetween("ch-27-cartoon-network", from, from + 6 * HOUR)
        val airing = day.filter { it.slotStartUtcMs != null && it.startUtcMs >= from }.groupBy { it.slotStartUtcMs }.values.first()
        assertEquals(2, airing.mapNotNull { it.assetId }.filter { it.startsWith("short") }.toSet().size)
        assertTrue(airing.first().slotTitle!!.contains(" / "))

        val free = gridLineup(fillerTags = listOf("ads", "commercial-free"))
        val freeDay = ScheduleEngine(free).blocksBetween("ch-27-cartoon-network", from, from + 6 * HOUR)
        freeDay.filter { it.slotStartUtcMs != null && it.startUtcMs >= from }.groupBy { it.slotStartUtcMs }.values.forEach { blocks ->
            assertEquals(1, blocks.count { it.assetId?.startsWith("ep") == true })
        }
    }
}
