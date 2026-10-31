package com.intunix.sdk.internal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

internal const val API_PATH = "/nikonudge/api/v1"
internal const val CHANNEL = "mobile_sdk"

internal class HttpError(val status: Int) : Exception("intunix http $status")

internal data class HttpResponse(val status: Int, val body: String?, val etag: String?)

internal class Http(private val apiHost: String, private val apiKey: String, private val sdkHeader: String) {
    fun url(path: String) = "$apiHost$API_PATH$path"

    /** Blocking request; throws HttpError on non-2xx (304 is returned, not thrown). */
    suspend fun request(method: String, path: String, body: String? = null, etag: String? = null): HttpResponse =
        withContext(Dispatchers.IO) {
            val conn = URL(url(path)).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("content-type", "application/json")
                conn.setRequestProperty("x-client-key", apiKey)
                conn.setRequestProperty("x-intunix-sdk", sdkHeader)
                if (etag != null) conn.setRequestProperty("if-none-match", etag)
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                if (status == 304) return@withContext HttpResponse(304, null, etag)
                if (status !in 200..299) throw HttpError(status)
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                HttpResponse(status, text, conn.getHeaderField("etag"))
            } finally {
                conn.disconnect()
            }
        }
}

/** Two retries with jittered backoff; 4xx never retries. Same policy as @intunix/core retry(). */
internal suspend fun <T> retry(retries: Int = 2, baseMs: Long = 500, maxMs: Long = 10_000, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: HttpError) {
            if (e.status in 400..499 || attempt == retries) throw e
        } catch (e: Exception) {
            if (attempt == retries) throw e
        }
        val d = minOf(maxMs, baseMs shl attempt)
        delay((d * (0.5 + Random.nextDouble() * 0.5)).toLong())
        attempt++
    }
}
