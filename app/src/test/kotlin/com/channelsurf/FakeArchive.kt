package com.channelsurf

import com.channelsurf.data.local.ArchiveCacheDao
import com.channelsurf.data.local.ArchiveCacheEntity
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Mock Internet Archive: an OkHttp application interceptor that answers from a queue of canned responses,
 * so tests never depend on archive.org uptime or the network.
 */
class FakeArchive {
    data class Canned(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private val queue = ConcurrentLinkedQueue<Canned>()
    val requests = ConcurrentLinkedQueue<String>()
    val requestHeaders = ConcurrentLinkedQueue<Map<String, String>>()

    fun enqueue(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) = apply { queue += Canned(code, body, headers) }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        requests += req.url.toString()
        requestHeaders += req.headers.toMultimap().mapValues { it.value.joinToString(",") }
        val c = queue.poll() ?: Canned(500, "no canned response")
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(c.code)
            .message("HTTP ${c.code}")
            .apply { c.headers.forEach { (k, v) -> header(k, v) } }
            .body(c.body.toResponseBody((c.headers["Content-Type"] ?: "application/json").toMediaType()))
            .build()
    }).build()

    companion object {
        fun fixture(name: String): String =
            FakeArchive::class.java.classLoader!!.getResource("fixtures/$name")!!.readText()
    }
}

class InMemoryArchiveCache : ArchiveCacheDao() {
    val map = HashMap<String, ArchiveCacheEntity>()
    override suspend fun get(url: String) = map[url]
    override suspend fun put(e: ArchiveCacheEntity) { map[e.url] = e }
    override suspend fun pruneBefore(beforeUtcMs: Long) { map.values.removeAll { it.fetchedAtUtcMs < beforeUtcMs } }
}
