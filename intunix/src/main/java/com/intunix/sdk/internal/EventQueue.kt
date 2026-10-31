package com.intunix.sdk.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Events that failed to upload, kept on disk and replayed (if under a day old) on the next start. */
internal class OfflineStore(private val store: KeyValueStore, private val clock: () -> Long) {
    fun save(batch: List<JSONObject>) {
        val all = readAll() + batch
        store.set(KEY, JSONArray(all.takeLast(MAX)).toString())
    }

    /** Drop entries that were uploaded after being saved defensively. */
    fun remove(batch: List<JSONObject>) {
        val sent = batch.map { it.toString() }.toSet()
        val left = readAll().filter { it.toString() !in sent }
        if (left.isEmpty()) store.remove(KEY) else store.set(KEY, JSONArray(left).toString())
    }

    fun readAll(): List<JSONObject> = store.get(KEY)?.let { runCatching { JSONArray(it).objects() }.getOrNull() } ?: emptyList()

    suspend fun replay(flush: suspend (List<JSONObject>) -> Unit) {
        val pending = readAll()
        if (pending.isEmpty()) return
        store.remove(KEY)
        val cutoff = clock() - DAY_MS
        val fresh = pending.filter { (it.long("timestamp") ?: 0) >= cutoff }
        if (fresh.isEmpty()) return
        try { flush(fresh) } catch (_: Exception) { save(fresh) }
    }

    private companion object {
        const val KEY = "intunix:offline"
        const val MAX = 1000
    }
}

internal class EventQueue(
    private val scope: CoroutineScope,
    private val flushMs: Long,
    private val max: Int,
    private val offline: OfflineStore,
    private val flush: suspend (List<JSONObject>) -> Unit,
) {
    private val buf = mutableListOf<JSONObject>()
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(flushMs)
                drain()
            }
        }
    }

    fun push(e: JSONObject) {
        buf.add(e)
        if (buf.size >= max) scope.launch { drain() }
    }

    /**
     * Upload what is buffered; on failure the batch goes to disk. When the app backgrounds
     * ([persistFirst]) the batch is written to disk before the upload, so a process kill
     * mid-request (e.g. after a review click opens the browser) does not lose it.
     */
    suspend fun drain(persistFirst: Boolean = false) {
        if (buf.isEmpty()) return
        val batch = buf.toList()
        buf.clear()
        if (persistFirst) offline.save(batch)
        try {
            flush(batch)
            if (persistFirst) offline.remove(batch)
        } catch (_: Exception) {
            if (!persistFirst) offline.save(batch)
        }
    }
}
