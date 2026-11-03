package com.intunix.sdk.internal

import org.json.JSONArray
import org.json.JSONObject

internal data class Brand(
    val color: String,
    val logoUrl: String?,
    /** Console lock: "light", "dark", or null (follow the app). */
    val darkMode: String? = null,
    /** #rrggbb card colour in dark mode; null or invalid = default. */
    val darkBackground: String? = null,
)
internal data class ChoiceOption(val value: String, val label: String, val emoji: String?)
internal data class FollowUpInput(
    val type: String,
    val placeholder: String? = null,
    val maxLength: Int? = null,
    val options: List<ChoiceOption> = emptyList(),
    val minPick: Int? = null,
    val maxPick: Int? = null,
)
internal data class ShowIf(val stepId: String, val op: String, val value: Any?, val values: List<Any?>)

/** One question. Kind-specific fields are null when they don't apply. */
internal data class Step(
    val id: String,
    val kind: String,
    val title: String,
    val subtitle: String? = null,
    val required: Boolean? = null,
    val showIf: ShowIf? = null,
    val page: Int? = null,
    val max: Int? = null,
    val scale: Int? = null,
    val low: String? = null,
    val high: String? = null,
    val csatLabels: List<String>? = null,
    val faces: List<String>? = null,
    val pmfOptions: List<ChoiceOption>? = null,
    val input: FollowUpInput? = null,
    val icons: String? = null,
)

internal data class ThanksConfig(val enabled: Boolean?, val title: String?, val subtitle: String?, val autoCloseMs: Long?)

internal data class FeedbackContent(
    val steps: List<Step>,
    val brand: Brand,
    val thanks: ThanksConfig? = null,
    val submitLabel: String? = null,
    val nextLabel: String? = null,
    val backLabel: String? = null,
    val closable: Boolean = true,
)

internal object Defaults {
    val NPS = "Not likely" to "Extremely likely"
    val ENPS = "Unlikely" to "Very likely"
    val CES = "Very hard" to "Very easy"
    val CSAT_LABELS = listOf("Very dissatisfied", "Dissatisfied", "Neutral", "Satisfied", "Very satisfied")
    val CSAT_EMOJI = listOf("😞", "🙁", "😐", "🙂", "😄")
    val SMILEYS = listOf("😠", "🙁", "😐", "🙂", "😄")
    val PMF = listOf(
        ChoiceOption("very", "Very disappointed", "😢"),
        ChoiceOption("somewhat", "Somewhat disappointed", "😕"),
        ChoiceOption("not", "Not disappointed", "🤷"),
    )
    const val STAR_MAX = 5
    const val CES_SCALE = 7
    const val THANKS_TITLE = "Thanks for the feedback!"
    const val THANKS_SUBTITLE = "We read every response — really."
    const val THANKS_AUTO_CLOSE_MS = 2500L
    const val SUBMIT = "Submit"
    const val NEXT = "Next"
    const val BACK = "Back"
}

private val KINDS = setOf("nps", "enps", "star", "csat", "ces", "smileys", "thumbs", "pmf", "followup")

private fun parseOptions(a: JSONArray?): List<ChoiceOption>? = a?.objects()?.mapNotNull { o ->
    val v = o.str("value") ?: return@mapNotNull null
    ChoiceOption(v, o.str("label") ?: v, o.str("emoji"))
}

internal fun parseStep(o: JSONObject): Step? {
    val id = o.str("id") ?: return null
    val kind = o.str("kind")?.takeIf { it in KINDS } ?: return null
    val labels = o.opt("labels")
    val input = o.obj("input")?.let { i ->
        val type = i.str("type") ?: return@let null
        FollowUpInput(type, i.str("placeholder"), i.int("maxLength"), parseOptions(i.arr("options")) ?: emptyList(), i.int("minPick"), i.int("maxPick"))
    }
    if (kind == "followup" && input == null) return null
    val showIf = o.obj("showIf")?.let { r ->
        val sid = r.str("stepId") ?: return@let null
        val op = r.str("op") ?: return@let null
        ShowIf(sid, op, fromJson(r.opt("value")), (fromJson(r.opt("values")) as? List<*>)?.toList() ?: emptyList())
    }
    return Step(
        id = id, kind = kind, title = o.str("title") ?: "", subtitle = o.str("subtitle"),
        required = o.bool("required"), showIf = showIf, page = o.int("page"),
        max = o.int("max"), scale = o.int("scale"),
        low = (labels as? JSONObject)?.str("low"), high = (labels as? JSONObject)?.str("high"),
        csatLabels = (labels as? JSONArray)?.strings()?.takeIf { it.size == 5 },
        faces = o.arr("faces")?.strings(), pmfOptions = parseOptions(o.arr("options")),
        input = input, icons = o.str("icons"),
    )
}

internal fun parseFeedbackContent(o: JSONObject): FeedbackContent? {
    if (o.str("kind") != "feedback") return null
    val brand = o.obj("brand")
    val thanks = o.obj("thanks")?.let { ThanksConfig(it.bool("enabled"), it.str("title"), it.str("subtitle"), it.long("autoCloseMs")) }
    return FeedbackContent(
        steps = o.arr("steps")?.objects()?.mapNotNull(::parseStep) ?: emptyList(),
        brand = Brand(
            brand?.str("color") ?: "#2F9461", brand?.str("logoUrl"),
            brand?.obj("dark")?.str("mode"), brand?.obj("dark")?.str("background"),
        ),
        thanks = thanks,
        submitLabel = o.str("submitLabel"), nextLabel = o.str("nextLabel"), backLabel = o.str("backLabel"),
        closable = o.bool("closable") != false,
    )
}

internal sealed interface AnswerValue {
    /** nps, enps, star, csat, ces, smileys. */
    data class Num(val v: Int) : AnswerValue
    /** thumbs ('up' / 'down') and pmf. */
    data class Choice(val v: String) : AnswerValue
    /** followup free / email / name. */
    data class Text(val type: String, val text: String) : AnswerValue
    data class One(val selected: String) : AnswerValue
    data class Many(val selected: List<String>) : AnswerValue
}

internal data class Answer(val stepId: String, val kind: String, val value: AnswerValue)

/** Answer value in the wire shape @intunix/core sends. */
internal fun AnswerValue.toWire(): Any = when (this) {
    is AnswerValue.Num -> v
    is AnswerValue.Choice -> v
    is AnswerValue.Text -> JSONObject().put("type", type).put("text", text)
    is AnswerValue.One -> JSONObject().put("type", "selectOne").put("selected", selected)
    is AnswerValue.Many -> JSONObject().put("type", "selectMultiple").put("selected", JSONArray(selected))
}

internal fun Answer.toWire(): JSONObject = JSONObject().put("stepId", stepId).put("kind", kind).put("value", value.toWire())

internal data class FeedbackResponse(val answers: List<Answer>, val completed: Boolean, val durationMs: Long)
