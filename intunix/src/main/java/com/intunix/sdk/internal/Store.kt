package com.intunix.sdk.internal

import android.content.Context
import org.json.JSONObject

internal interface KeyValueStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

internal class MemoryStore : KeyValueStore {
    private val map = HashMap<String, String>()
    override fun get(key: String) = map[key]
    override fun set(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

internal class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("intunix", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

internal fun newId(): String = java.util.UUID.randomUUID().toString()

internal class AnonId(private val store: KeyValueStore) {
    fun get(): String = store.get(KEY) ?: newId().also { store.set(KEY, it) }
    fun reset(): String = newId().also { store.set(KEY, it) }
    private companion object { const val KEY = "intunix:anon" }
}

/** A session ends after 30 minutes without activity; minting a new one notifies listeners. */
internal class SessionManager(private val store: KeyValueStore, private val clock: () -> Long) {
    private val callbacks = mutableListOf<(String) -> Unit>()

    fun id(): String {
        val now = clock()
        store.get(KEY)?.let { raw ->
            runCatching {
                val o = JSONObject(raw)
                if (o.getLong("exp") > now) {
                    val id = o.getString("id")
                    write(id, now)
                    return id
                }
            }
        }
        val id = newId()
        write(id, now)
        for (cb in callbacks.toList()) runCatching { cb(id) }
        return id
    }

    fun reset() = store.remove(KEY)

    fun onNewSession(cb: (String) -> Unit): () -> Unit {
        callbacks.add(cb)
        return { callbacks.remove(cb) }
    }

    private fun write(id: String, now: Long) = store.set(KEY, JSONObject().put("id", id).put("exp", now + TTL_MS).toString())

    companion object {
        const val KEY = "intunix:session"
        const val TTL_MS = 30 * 60 * 1000L
    }
}

internal data class ProfileData(
    val firstSeenAt: Long,
    val sessionCount: Int,
    val lastSeenAt: Long,
    val outcomes: MutableMap<String, MutableMap<String, OutcomeRecord>>,
)

private fun splitUid(key: String): Pair<String, String> {
    val i = key.indexOf('|')
    return if (i == -1) key to "" else key.substring(0, i) to key.substring(i)
}

internal class ProfileStore(private val store: KeyValueStore, private val clock: () -> Long) {
    fun read(): ProfileData = load()

    fun onSessionStart() {
        val p = load()
        save(p.copy(sessionCount = p.sessionCount + 1, lastSeenAt = clock()))
    }

    fun recordOutcome(formId: String, key: String, outcome: String) {
        val p = load()
        val forForm = p.outcomes.getOrPut(formId) { mutableMapOf() }
        val prev = forForm[key] ?: OutcomeRecord()
        val now = clock()
        forForm[key] = prev.copy(
            showCount = prev.showCount + 1,
            lastOutcome = outcome,
            lastOutcomeAt = now,
            respondedAt = if (outcome == "responded") now else prev.respondedAt,
        )
        save(p)
    }

    fun outcomes(formId: String, key: String): OutcomeRecord? = load().outcomes[formId]?.get(key)

    fun mergeIdentity(fromUid: String, toUid: String) {
        val p = load()
        for (forForm in p.outcomes.values) {
            for ((key, rec) in forForm.entries.toList()) {
                val (uid, suffix) = splitUid(key)
                if (uid != fromUid) continue
                val newKey = toUid + suffix
                forForm.remove(key)
                val target = forForm[newKey]
                forForm[newKey] = if (target == null) rec else {
                    val newer = if ((rec.lastOutcomeAt ?: 0) > (target.lastOutcomeAt ?: 0)) rec else target
                    OutcomeRecord(
                        respondedAt = listOfNotNull(target.respondedAt, rec.respondedAt).maxOrNull(),
                        showCount = maxOf(target.showCount, rec.showCount),
                        lastOutcome = newer.lastOutcome,
                        lastOutcomeAt = newer.lastOutcomeAt,
                    )
                }
            }
        }
        save(p)
    }

    private fun fresh(): ProfileData { val now = clock(); return ProfileData(now, 0, now, mutableMapOf()) }

    private fun load(): ProfileData {
        val raw = store.get(KEY) ?: return fresh()
        return runCatching {
            val o = JSONObject(raw)
            val outcomes = mutableMapOf<String, MutableMap<String, OutcomeRecord>>()
            o.getJSONObject("outcomes").let { all ->
                for (form in all.keys()) {
                    val m = mutableMapOf<String, OutcomeRecord>()
                    val f = all.getJSONObject(form)
                    for (k in f.keys()) {
                        val r = f.getJSONObject(k)
                        m[k] = OutcomeRecord(r.long("respondedAt"), r.int("showCount") ?: 0, r.str("lastOutcome"), r.long("lastOutcomeAt"))
                    }
                    outcomes[form] = m
                }
            }
            ProfileData(o.getLong("firstSeenAt"), o.getInt("sessionCount"), o.getLong("lastSeenAt"), outcomes)
        }.getOrElse { fresh() }
    }

    private fun save(p: ProfileData) {
        val outcomes = JSONObject()
        p.outcomes.forEach { (form, m) ->
            val f = JSONObject()
            m.forEach { (k, r) ->
                f.put(k, JSONObject().put("showCount", r.showCount)
                    .putOpt("respondedAt", r.respondedAt).putOpt("lastOutcome", r.lastOutcome).putOpt("lastOutcomeAt", r.lastOutcomeAt))
            }
            outcomes.put(form, f)
        }
        store.set(KEY, JSONObject().put("firstSeenAt", p.firstSeenAt).put("sessionCount", p.sessionCount)
            .put("lastSeenAt", p.lastSeenAt).put("outcomes", outcomes).toString())
    }

    private companion object { const val KEY = "intunix:profile" }
}

/** Per form, per identity key: when it was last shown and how often. */
internal class ShownStore(private val store: KeyValueStore) {
    fun get(formId: String, key: String): ShownRecord? = read()[formId]?.get(key)

    fun markShown(formId: String, key: String, now: Long, sessionId: String) {
        val all = read()
        val m = all.getOrPut(formId) { mutableMapOf() }
        m[key] = ShownRecord(now, (m[key]?.count ?: 0) + 1, sessionId)
        write(all)
    }

    fun mergeIdentity(fromUid: String, toUid: String) {
        val all = read()
        for (m in all.values) {
            for ((key, rec) in m.entries.toList()) {
                val (uid, suffix) = splitUid(key)
                if (uid != fromUid) continue
                val newKey = toUid + suffix
                m.remove(key)
                val t = m[newKey]
                m[newKey] = if (t == null) rec else ShownRecord(
                    maxOf(t.lastShownAt, rec.lastShownAt),
                    maxOf(t.count, rec.count),
                    if (rec.lastShownAt > t.lastShownAt) rec.sessionId else t.sessionId,
                )
            }
        }
        write(all)
    }

    private fun read(): MutableMap<String, MutableMap<String, ShownRecord>> {
        val raw = store.get(KEY) ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, MutableMap<String, ShownRecord>>()
            for (form in o.keys()) {
                val f = o.getJSONObject(form)
                out[form] = f.keys().asSequence().associateWith { k ->
                    val r = f.getJSONObject(k)
                    ShownRecord(r.getLong("lastShownAt"), r.getInt("count"), r.str("sessionId"))
                }.toMutableMap()
            }
            out
        }.getOrElse { mutableMapOf() }
    }

    private fun write(all: Map<String, Map<String, ShownRecord>>) {
        val o = JSONObject()
        all.forEach { (form, m) ->
            val f = JSONObject()
            m.forEach { (k, r) -> f.put(k, JSONObject().put("lastShownAt", r.lastShownAt).put("count", r.count).putOpt("sessionId", r.sessionId)) }
            o.put(form, f)
        }
        store.set(KEY, o.toString())
    }

    private companion object { const val KEY = "intunix:shown" }
}
