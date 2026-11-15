package com.intunix.sdk.internal

import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

internal data class ConversationConfig(
    /** "enhanced" | "conversational". */
    val mode: String,
    val assistantName: String?,
    val welcomeMessage: String?,
    val completionMessage: String?,
    /** Language code -> (welcome, completion). */
    val translations: Map<String, Pair<String?, String?>>?,
)

internal fun parseConversationConfig(o: JSONObject?): ConversationConfig? {
    val mode = o?.str("mode")?.takeIf { it == "enhanced" || it == "conversational" } ?: return null
    val tr = o.obj("translations")?.let { t ->
        t.keys().asSequence().mapNotNull { k -> t.obj(k)?.let { k to (it.str("welcomeMessage") to it.str("completionMessage")) } }.toMap()
    }
    return ConversationConfig(mode, o.str("assistantName"), o.str("welcomeMessage"), o.str("completionMessage"), tr)
}

/** The config in [lang]: translated messages fall back per field to the default; `translations` is dropped. */
internal fun localizeConversation(config: ConversationConfig, lang: String): ConversationConfig {
    val translations = config.translations ?: return config
    val tr = matchLanguage(lang, translations.keys.toList())?.let { translations[it] }
    return config.copy(
        translations = null,
        welcomeMessage = pick(tr?.first, config.welcomeMessage),
        completionMessage = pick(tr?.second, config.completionMessage),
    )
}

internal data class TurnRequest(
    val conversationId: String,
    val turn: Int,
    /** "answer" | "skip" | "end" | "resume". */
    val action: String,
    val answer: Answer? = null,
    val locale: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("conversation_id", conversationId).put("turn", turn).put("action", action).apply {
        answer?.let { put("answer", it.toWire()) }
        locale?.let { put("locale", it) }
    }
}

internal data class ChatMessage(val role: String /* "nia" | "respondent" */, val text: String)

internal data class TurnReply(
    val messages: List<String>,
    val input: Step?,
    val inputJson: JSONObject?,
    val answered: Int,
    val total: Int,
    val done: Boolean,
    val nextTurn: Int,
    val busy: Boolean,
    val transcript: List<ChatMessage>?,
    val answers: List<JSONObject>?,
    val skipped: List<String>?,
)

/** Shape check of a turn reply's `data`, as the web SDK does it. */
internal fun isValidTurnReply(o: Any?): Boolean {
    if (o !is JSONObject) return false
    val p = o.obj("progress")
    val i = o.opt("input")
    val messages = o.arr("messages")
    return o.opt("next_turn") is Number && messages != null && o.opt("done") is Boolean &&
        p != null && p.opt("answered") is Number && p.opt("total") is Number &&
        (!o.has("answers") || o.opt("answers") is JSONArray) &&
        (!o.has("skipped") || (o.opt("skipped") as? JSONArray)?.let { s -> (0 until s.length()).all { s.opt(it) is String } } == true) &&
        (0 until messages.length()).all { (messages.opt(it) as? JSONObject)?.str("text") != null } &&
        (i == JSONObject.NULL || (i is JSONObject && i.str("id") != null && i.str("kind") != null && (i.str("kind") != "followup" || i.obj("input") != null)))
}

/** Null when the reply is invalid or its input is a step this SDK cannot show. */
internal fun parseTurnReply(o: JSONObject): TurnReply? {
    if (!isValidTurnReply(o)) return null
    val inputJson = o.obj("input")
    val input = inputJson?.let { parseStep(it) ?: return null }
    val p = o.obj("progress")!!
    return TurnReply(
        messages = o.arr("messages")!!.objects().mapNotNull { it.str("text") },
        input = input, inputJson = inputJson,
        answered = p.int("answered") ?: 0, total = p.int("total") ?: 0,
        done = o.bool("done") == true, nextTurn = o.int("next_turn") ?: 0, busy = o.bool("busy") == true,
        transcript = o.arr("transcript")?.objects()?.mapNotNull { m ->
            val role = m.str("role")?.takeIf { it == "nia" || it == "respondent" } ?: return@mapNotNull null
            m.str("text")?.let { ChatMessage(role, it) }
        },
        answers = o.arr("answers")?.objects(),
        skipped = o.arr("skipped")?.strings(),
    )
}

/** Resolves with the reply's `data`; throws on anything else. */
internal fun interface ConversationTransport {
    suspend fun send(req: TurnRequest): JSONObject
}

/** POST /sdk/forms/{id}/turn, no retry (the controller owns timeouts and fallbacks). */
internal fun sdkTransport(http: Http, formId: String) = ConversationTransport { req ->
    val res = http.request("POST", "/sdk/forms/${enc(formId)}/turn", req.toJson().toString())
    val data = JSONObject(res.body ?: error("empty reply")).opt("data")
    if (!isValidTurnReply(data)) error("malformed turn reply")
    data as JSONObject
}

/** [scope] outlives the form, so a detached `end` still goes out. */
internal class ConversationHandle(
    val config: ConversationConfig,
    val transport: ConversationTransport,
    val conversationId: String,
    val scope: CoroutineScope,
)

/** Wire shape to model; the inverse of [toWire]. Null when the value does not look like any answer. */
internal fun parseAnswer(o: JSONObject): Answer? {
    val id = o.str("stepId") ?: return null
    val kind = o.str("kind") ?: return null
    val v = o.opt("value")
    val value: AnswerValue = when {
        v is Number && v.toDouble() == v.toInt().toDouble() -> AnswerValue.Num(v.toInt())
        v is String -> AnswerValue.Choice(v)
        v is JSONObject -> when (val type = v.str("type")) {
            "free", "email", "name" -> AnswerValue.Text(type, v.str("text") ?: return null)
            "selectOne" -> AnswerValue.One(v.str("selected") ?: return null)
            "selectMultiple" -> AnswerValue.Many(
                (v.arr("selected") ?: return null).let { a -> if ((0 until a.length()).all { a.opt(it) is String }) a.strings() else return null },
            )
            else -> return null
        }
        else -> return null
    }
    return Answer(id, kind, value)
}

private fun plain(v: AnswerValue): String = when (v) {
    is AnswerValue.Num -> v.v.toString()
    is AnswerValue.Choice -> v.v
    is AnswerValue.Text -> v.text
    is AnswerValue.One -> v.selected
    is AnswerValue.Many -> v.selected.joinToString(",")
}

/** What the respondent "said", for their chat bubble. */
internal fun answerLabel(step: Step, a: Answer): String {
    val v = a.value
    val label: String? = when (a.kind) {
        "nps", "enps", "ces" -> plain(v)
        "star" -> "${plain(v)} / ${if (step.kind == "star") step.max ?: Defaults.STAR_MAX else 5}"
        "csat" -> (v as? AnswerValue.Num)?.let { (step.csatLabels ?: Defaults.CSAT_LABELS).getOrNull(it.v - 1) }
        "smileys" -> (v as? AnswerValue.Num)?.let { (step.faces?.takeIf { f -> f.isNotEmpty() } ?: Defaults.SMILEYS).getOrNull(it.v - 1) }
        "thumbs" -> if ((v as? AnswerValue.Choice)?.v == "up") "👍" else "👎"
        "pmf" -> (v as? AnswerValue.Choice)?.let { c -> (step.pmfOptions?.takeIf { o -> o.isNotEmpty() } ?: Defaults.PMF).firstOrNull { it.value == c.v }?.label ?: c.v }
        "followup" -> {
            val opts = if (step.kind == "followup") step.input?.options ?: emptyList() else emptyList()
            fun name(s: String) = opts.firstOrNull { it.value == s }?.label ?: s
            when (v) {
                is AnswerValue.Text -> v.text.trim()
                is AnswerValue.One -> name(v.selected)
                is AnswerValue.Many -> v.selected.joinToString(", ", transform = ::name)
                else -> null
            }
        }
        else -> null
    }
    return label?.takeIf { it.isNotEmpty() } ?: plain(v)
}

/** True when choosing a value is the whole answer (no Send button needed). */
internal fun submitsOnTap(step: Step): Boolean = step.kind != "followup" || step.input?.type == "selectOne"

internal data class ChatLabels(val name: String, val send: String, val skip: String, val end: String, val typing: String, val log: String)

private val NAME_OK = Regex("^[\\p{L}\\p{M}\\p{N} .'-]+$")

// Same rule as the server: only spaces trim and collapse; a newline or tab makes the name invalid.
internal fun assistantName(raw: String?): String {
    val n = (raw ?: "").trim(' ').replace(Regex(" {2,}"), " ")
    return if (n.isNotEmpty() && n.codePointCount(0, n.length) <= 40 && NAME_OK.matches(n)) n else "Nia"
}

/** The chat's visible texts, with `{name}` filled in. */
internal fun chatLabels(content: FeedbackContent, config: ConversationConfig): ChatLabels {
    val name = assistantName(config.assistantName)
    val c = content.chat
    fun f(s: String?, d: String) = (s ?: d).replace("{name}", name)
    return ChatLabels(
        name, f(c?.sendLabel, Defaults.CHAT_SEND), f(c?.skipLabel, Defaults.CHAT_SKIP), f(c?.endLabel, Defaults.CHAT_END),
        f(c?.typingLabel, Defaults.CHAT_TYPING), f(c?.logLabel, Defaults.CHAT_LOG),
    )
}
