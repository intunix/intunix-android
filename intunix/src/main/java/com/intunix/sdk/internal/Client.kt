package com.intunix.sdk.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal data class RenderCallbacks(
    val onImpression: () -> Unit,
    val onResponse: (Answer) -> Unit,
    val onDismiss: () -> Unit,
    val clientResponseId: String,
    val onReviewEvent: (ReviewEvent) -> Unit,
)

internal data class ExperienceResult(val outcome: String, val response: FeedbackResponse?)

/** Puts a form on screen and suspends until the visitor is done with it. */
internal fun interface FormPresenter {
    suspend fun present(exp: Experience, content: FeedbackContent, callbacks: RenderCallbacks): ExperienceResult
}

internal data class ClientConfig(
    val apiKey: String,
    val apiHost: String,
    val registerAll: Boolean,
    val register: List<String>,
    val flushIntervalMs: Long,
    val maxQueueSize: Int,
    val screenTriggerDelayMs: Long,
    val disableEventApi: Boolean,
    val sdkVersion: String,
)

internal fun interface Logger { fun log(level: Char, msg: String) }

/**
 * Port of @intunix/core IntunixClient. Confined to the main thread: every public
 * call arrives through Intunix, which hops onto `scope` (Dispatchers.Main).
 */
internal class IntunixClient(
    private val cfg: ClientConfig,
    store: KeyValueStore,
    private val presenter: FormPresenter,
    private val scope: CoroutineScope,
    private val log: Logger,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val http = Http(cfg.apiHost, cfg.apiKey, "android/${cfg.sdkVersion}")
    private val store = store
    private val anon = AnonId(store)
    private val session = SessionManager(store, clock)
    private val profile = ProfileStore(store, clock)
    private val shown = ShownStore(store)
    private val offline = OfflineStore(store, clock)
    private val queue = EventQueue(scope, cfg.flushIntervalMs, cfg.maxQueueSize, offline, ::flush)
    private val triggers = TriggerEvaluator(scope, cfg.screenTriggerDelayMs) { enqueueShow(it.id) }

    private var experiences = listOf<Experience>()
    private var batchShown = false
    private var loading: CompletableDeferred<Unit>? = null
    private var user: GateUser? = null
    private val context = LinkedHashMap<String, Any?>()
    private val registeredIds = linkedSetOf<String>()
    private val ready = CompletableDeferred<Unit>()

    private var showing = false
    private var activeId: String? = null
    private val showQueue = ArrayDeque<String>()
    private val pendingShowContext = mutableMapOf<String, Map<String, Any?>>()

    fun start() {
        scope.launch {
            offline.replay(::flush)
            session.onNewSession {
                profile.onSessionStart()
                context.clear()
                scope.launch { loadExperiences(); rearm() }
            }
            session.id()
            loadExperiences()
            queue.start()
            triggers.attach(experiences)
            registerAutoActivate()
            ready.complete(Unit)
            if (!cfg.registerAll) cfg.register.forEach { register(it) }
            log.log('d', "ready, ${experiences.size} forms")
        }
    }

    /** App came to the foreground: renew (or start) the session. */
    fun onForeground() { session.id() }

    /** App went to the background: upload buffered events now. */
    fun onBackground() { scope.launch { queue.drain() } }

    // ---- catalog ----------------------------------------------------------

    private fun formsParam(): String = if (cfg.registerAll) "all" else cfg.register.sorted().joinToString(",")

    private suspend fun loadExperiences(force: Boolean = false) {
        loading?.let { it.await(); return }
        val d = CompletableDeferred<Unit>()
        loading = d
        try { doLoad(force) } finally { loading = null; d.complete(Unit) }
    }

    private suspend fun doLoad(force: Boolean) {
        val wanted = formsParam()
        if (wanted.isEmpty()) { experiences = emptyList(); batchShown = false; return }
        val cached = store.get(CACHE_KEY)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (cached != null) {
            experiences = cached.arr("data")?.objects()?.mapNotNull(::parseExperience) ?: emptyList()
            batchShown = cached.obj("caps")?.bool("batchShown") == true
        }
        val sid = session.id()
        if (cached != null && cached.str("sid") == sid && cached.str("set") == wanted && !force) return
        try {
            val res = retry {
                http.request("GET", "/sdk/experiences?forms=${java.net.URLEncoder.encode(wanted, "UTF-8")}",
                    etag = if (force) null else cached?.str("etag"))
            }
            if (res.status == 304) {
                cached?.put("sid", sid)?.put("set", wanted)?.let { store.set(CACHE_KEY, it.toString()) }
                return
            }
            val data = JSONObject(res.body ?: "{}").obj("data") ?: JSONObject()
            val raw = data.arr("experiences") ?: JSONArray()
            val caps = data.obj("capabilities") ?: JSONObject()
            experiences = raw.objects().mapNotNull(::parseExperience)
            batchShown = caps.bool("batchShown") == true
            store.set(CACHE_KEY, JSONObject().put("etag", res.etag ?: JSONObject.NULL).put("data", raw)
                .put("caps", caps).put("sid", sid).put("set", wanted).toString())
            triggers.attach(experiences)
            registerAutoActivate()
        } catch (e: Exception) {
            log.log('w', "catalog load failed: ${e.message}")
            if (cached == null) experiences = emptyList()
        }
    }

    private fun find(idOrSlug: String) = experiences.firstOrNull { it.id == idOrSlug || it.slug == idOrSlug }

    private val fetched = mutableMapOf<String, Experience>()

    internal suspend fun resolve(idOrSlug: String): Experience? {
        ready.await()
        find(idOrSlug)?.let { return it }
        fetched.values.firstOrNull { it.id == idOrSlug || it.slug == idOrSlug }?.let { return it }
        return try {
            val res = retry { http.request("GET", "/sdk/forms/${enc(idOrSlug)}") }
            JSONObject(res.body ?: "{}").obj("data")?.obj("experience")?.let(::parseExperience)?.also { fetched[it.id] = it }
        } catch (e: Exception) {
            log.log('w', "form $idOrSlug not found: ${e.message}")
            null
        }
    }

    // ---- registration -----------------------------------------------------

    private fun registerAutoActivate() {
        for (exp in experiences) if (exp.autoActivate && registeredIds.add(exp.id)) triggers.register(exp)
    }

    private fun rearm() = registeredIds.forEach { id -> (find(id) ?: fetched[id])?.let(triggers::register) }

    fun register(idOrSlug: String) = scope.launch {
        val exp = resolve(idOrSlug) ?: return@launch
        triggers.register(exp)
        registeredIds.add(exp.id)
    }

    fun unregister(idOrSlug: String) {
        val exp = find(idOrSlug) ?: fetched[idOrSlug] ?: return
        registeredIds.remove(exp.id)
        triggers.unregister(exp.id)
    }

    // ---- identity & context -----------------------------------------------

    fun identify(userId: String, traits: Map<String, Any?>) {
        val prevAnon = anon.get()
        val first = user?.id != userId
        user = GateUser(userId, traits)
        if (first) {
            shown.mergeIdentity(prevAnon, userId)
            profile.mergeIdentity(prevAnon, userId)
        }
        if (ready.isCompleted) triggers.reevaluateUserTriggers(traits)
    }

    fun reset() {
        user = null
        anon.reset()
        session.reset()
        context.clear()
    }

    fun setContext(patch: Map<String, Any?>?) { if (patch == null) context.clear() else context.putAll(patch) }

    // ---- events & screens -------------------------------------------------

    fun track(name: String, properties: Map<String, Any?>) {
        val e = JSONObject()
            .put("name", name)
            .put("properties", toJson(properties))
            .put("timestamp", clock())
            .put("sessionId", session.id())
            .putOpt("userId", user?.id)
            .put("anonymousId", anon.get())
        queue.push(e)
        if (ready.isCompleted) triggers.onEvent(name, properties)
    }

    fun screen(name: String) {
        if (ready.isCompleted) triggers.onScreen(name) else scope.launch { ready.await(); triggers.onScreen(name) }
    }

    private suspend fun flush(batch: List<JSONObject>) {
        if (cfg.disableEventApi) return
        retry { http.request("POST", "/sdk/events", JSONObject().put("batch", JSONArray(batch)).put("channel", CHANNEL).toString()) }
    }

    // ---- showing ----------------------------------------------------------

    fun showForm(idOrSlug: String, ctx: Map<String, Any?>?) = scope.launch {
        val exp = resolve(idOrSlug) ?: return@launch
        if (ctx != null) pendingShowContext[exp.id] = ctx
        enqueueShow(exp.id)
    }

    private fun enqueueShow(id: String) {
        if (activeId == id || id in showQueue) return
        showQueue.addLast(id)
        pump()
    }

    private fun mergedContext(id: String): Map<String, Any?> = LinkedHashMap(context).apply { pendingShowContext[id]?.let(::putAll) }

    private fun isEligible(exp: Experience, ctx: Map<String, Any?>): Boolean {
        if (!checkPlatform(exp)) return false
        if (!checkTargeting(exp, user, ctx, triggers.currentScreen)) return false
        val now = clock()
        if (!checkEligibility(exp, profile.read(), now)) return false
        val key = identityKey(exp, user?.id ?: anon.get(), user?.traits ?: emptyMap(), ctx)
        return checkCadence(normalizeCadence(exp.cadence), shown.get(exp.id, key), profile.outcomes(exp.id, key), now, session.id())
    }

    private fun pump() {
        if (showing) return
        val id = showQueue.removeFirstOrNull() ?: return
        val exp = find(id) ?: fetched[id]
        val ctx = mergedContext(id)
        val content = exp?.let { parseFeedbackContent(it.content) }
        if (exp == null || content == null || !isEligible(exp, ctx)) {
            pendingShowContext.remove(id)
            pump()
            return
        }
        showing = true
        activeId = id
        scope.launch {
            try {
                val responseId = newId()
                val result = presenter.present(exp, content, callbacks(exp, ctx, responseId))
                settle(exp, ctx, result, responseId)
            } catch (e: Exception) {
                log.log('e', "render failed: ${e.message}")
                track("\$sdk_error", mapOf("id" to id, "message" to e.message))
            } finally {
                pendingShowContext.remove(id)
                showing = false
                activeId = null
                pump()
            }
        }
    }

    internal fun callbacks(exp: Experience, ctx: Map<String, Any?>, responseId: String) = RenderCallbacks(
        onImpression = {
            track("\$experience_impression", mapOf("id" to exp.id))
            if (batchShown) track("\$form_shown", mapOf("form_id" to exp.id, "context" to ctx)) else reportShown(exp.id)
        },
        onResponse = { a -> track("\$experience_response", mapOf("id" to exp.id, "answer" to a.toWire())) },
        onDismiss = {
            track("\$experience_dismissed", mapOf("id" to exp.id))
            if (batchShown) track("\$form_dismissed", mapOf("form_id" to exp.id, "context" to ctx))
        },
        clientResponseId = responseId,
        onReviewEvent = { e ->
            track(e.name, buildMap {
                put("form_id", e.formId); put("client_response_id", e.clientResponseId); put("platform", e.platform)
                put("review_url", e.reviewUrl); put("trigger_rule", e.triggerRule)
                e.triggerThreshold?.let { put("trigger_threshold", it) }
            })
        },
    )

    /** Record the show for cadence and upload the answers. Also used by the inline IntunixForm. */
    internal fun settle(exp: Experience, ctx: Map<String, Any?>, result: ExperienceResult, responseId: String) {
        val key = identityKey(exp, user?.id ?: anon.get(), user?.traits ?: emptyMap(), ctx)
        shown.markShown(exp.id, key, clock(), session.id())
        profile.recordOutcome(exp.id, key, result.outcome)
        val fb = result.response ?: return
        if (fb.answers.isEmpty()) return
        val response = JSONObject()
        fb.answers.forEach { a -> response.put(a.stepId, JSONObject().put("kind", a.kind).put("value", a.value.toWire())) }
        val body = JSONObject()
            .put("response", response)
            .put("channel", CHANNEL)
            .putOpt("user_id", user?.id)
            .put("session_id", session.id())
            .put("user_traits", toJson(user?.traits ?: emptyMap<String, Any?>()))
            .put("duration_ms", fb.durationMs)
            .put("completed", fb.completed)
            .put("context", toJson(ctx))
            .put("anonymous_id", anon.get())
            .put("client_response_id", responseId)
        scope.launch {
            try {
                retry { http.request("POST", "/sdk/forms/${enc(exp.id)}/response", body.toString()) }
            } catch (e: Exception) {
                log.log('e', "response submit failed: ${e.message}")
            }
        }
    }

    internal fun contextFor(exp: Experience, perCall: Map<String, Any?>?): Map<String, Any?> =
        LinkedHashMap(context).apply { perCall?.let(::putAll) }

    private fun reportShown(id: String) = scope.launch {
        try {
            retry { http.request("POST", "/sdk/forms/${enc(id)}/shown", JSONObject().put("channel", CHANNEL).toString()) }
        } catch (e: Exception) {
            log.log('w', "shown report failed: ${e.message}")
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object { const val CACHE_KEY = "intunix:experiences:v2" }
}
