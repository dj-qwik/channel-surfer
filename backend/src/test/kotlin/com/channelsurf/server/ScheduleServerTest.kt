package com.channelsurf.server

import com.channelsurf.core.manifest.ManifestCodec
import com.channelsurf.core.manifest.ManifestResult
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.security.ManifestSignature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class ScheduleServerTest {
    private val manifest = """
        {"schemaVersion":1,"revision":"srv-test","channels":[
          {"id":"ch-02","number":2,"displayName":"Two","epochUtcMs":1767225600000,"playlist":["a","b"]}],
         "assets":[
          {"id":"a","archiveIdentifier":"fixture-a","fileName":"a.mp4","url":"https://archive.org/download/fixture-a/a.mp4","title":"A","durationMs":1800000,"seekable":true,"broadcastYear":1993,"yearVerified":true,"rightsStatus":"CLEARED"},
          {"id":"b","archiveIdentifier":"fixture-b","fileName":"b.mp4","url":"https://archive.org/download/fixture-b/b.mp4","title":"B","durationMs":1200000,"seekable":true,"broadcastYear":1998,"yearVerified":true,"rightsStatus":"CLEARED"}]}
    """.trimIndent()

    private fun get(url: String): Triple<Int, String, Map<String, List<String>>> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return Triple(code, body, c.headerFields.filterKeys { it != null })
    }

    @Test
    fun servesTimeSignedManifestAndScheduleMatchingClientEngine() {
        val file = File.createTempFile("manifest", ".json").apply { writeText(manifest); deleteOnExit() }
        val kp = ManifestSignature.generateKeyPair()
        val fixedNow = 1_767_225_600_000L + 5_000_000
        val server = ScheduleServer(file, kp.private, clock = { fixedNow })
        val port = server.start(0)
        try {
            val base = "http://127.0.0.1:$port"
            val (tc, tb, _) = get("$base/v1/time")
            assertEquals(200, tc)
            assertEquals(fixedNow, Json.parseToJsonElement(tb).jsonObject["utcMs"]!!.jsonPrimitive.long)

            val (mc, mb, headers) = get("$base/v1/manifest")
            assertEquals(200, mc)
            val sig = headers.entries.first { it.key.equals(ManifestSignature.HEADER_SIGNATURE, true) }.value.first()
            assertTrue(ManifestSignature.verify(mb.toByteArray(), sig, kp.public))
            val clientLineup = (ManifestCodec.decode(mb) as ManifestResult.Success).lineup

            val (sc, sb, _) = get("$base/v1/schedule?channelId=ch-02&fromUtcMs=$fixedNow&toUtcMs=${fixedNow + 7_200_000}")
            assertEquals(200, sc)
            val serverStarts = Json.parseToJsonElement(sb).jsonObject["blocks"]!!.jsonArray.map { it.jsonObject["startUtcMs"]!!.jsonPrimitive.long }
            val clientStarts = ScheduleEngine(clientLineup).blocksBetween("ch-02", fixedNow, fixedNow + 7_200_000).map { it.startUtcMs }
            assertEquals(clientStarts, serverStarts)

            assertEquals(404, get("$base/v1/schedule?channelId=nope").first)
            assertEquals(400, get("$base/v1/schedule?channelId=ch-02&fromUtcMs=0&toUtcMs=999999999999").first)
            assertEquals(200, get("$base/v1/now?channelId=ch-02").first)
        } finally {
            server.stop()
        }
    }
}
