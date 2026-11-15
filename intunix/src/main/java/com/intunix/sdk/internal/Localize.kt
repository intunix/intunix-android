package com.intunix.sdk.internal

import org.json.JSONArray
import org.json.JSONObject

private fun blank(s: String?) = s == null || s.isBlank()
/** Translation, else the form's own text, else the built-in fill. Null when none exists. */
private fun pick(tr: String?, own: String?, fill: String? = null): String? = if (blank(tr)) own ?: fill else tr
// shortcut: deep copy through text; forms are a few KB, so upgrade only if profiling shows it.
private fun JSONObject.copy() = JSONObject(toString())
private fun JSONObject.putIf(k: String, v: String?) = apply { if (v != null) put(k, v) }

private fun ChoiceOption.toJson(label: String = this.label) =
    JSONObject().put("value", value).put("label", label).also { o -> emoji?.let { o.put("emoji", it) } }

/** Options with each label translated by value; the one mapping for pmf steps and select inputs. */
private fun localizeOptions(opts: List<JSONObject>, tr: JSONObject?) = JSONArray(
    opts.map { o -> o.copy().putIf("label", pick(tr?.str(o.str("value") ?: ""), o.str("label"))) },
)

/** [default, ...others] in the admin's spelling; only codes equal after normalisation collapse. */
internal fun formLanguages(content: JSONObject): List<String> {
    val def = content.str("defaultLanguage")
    val out = mutableListOf(if (norm(def).isNotEmpty()) def!! else "en")
    content.arr("languages")?.let { a ->
        for (i in 0 until a.length()) {
            val l = a.opt(i) as? String ?: continue
            if (norm(l).isNotEmpty() && out.none { norm(it) == norm(l) }) out.add(l)
        }
    }
    return out
}

private fun localizeStep(step: JSONObject, tr: JSONObject?, b: BuiltinTexts?): JSONObject {
    val s = step.copy()
    s.putIf("title", pick(tr?.str("title"), step.str("title")))
    if (step.has("subtitle")) s.putIf("subtitle", pick(tr?.str("subtitle"), step.str("subtitle")))
    val trLabels = tr?.opt("labels")
    fun pair(def: Pair<String, String>, low: String?, high: String?) {
        val l = trLabels as? JSONObject
        val own = step.opt("labels") as? JSONObject
        val lo = pick(l?.str("low"), own?.str("low"), low)
        val hi = pick(l?.str("high"), own?.str("high"), high)
        if (lo != null || hi != null) s.put("labels", JSONObject().put("low", lo ?: def.first).put("high", hi ?: def.second))
    }
    val trOptions = tr?.obj("options")
    when (step.str("kind")) {
        "nps" -> pair(Defaults.NPS, b?.npsLow, b?.npsHigh)
        "enps" -> pair(Defaults.ENPS, b?.enpsLow, b?.enpsHigh)
        "ces" -> pair(Defaults.CES, b?.cesLow, b?.cesHigh)
        "csat" -> {
            val l = trLabels as? JSONArray ?: JSONArray()
            // Without stored labels, English defaults are only written when a translation needs a base.
            val base = (step.opt("labels") as? JSONArray)?.strings() ?: b?.csat
                ?: if ((0 until l.length()).any { !blank(l.opt(it) as? String) }) Defaults.CSAT_LABELS else null
            if (base != null) s.put("labels", JSONArray(base.mapIndexed { i, x -> pick(l.opt(i) as? String, x) }))
        }
        "pmf" -> {
            val own = step.arr("options")?.takeIf { it.length() > 0 } // [] counts as absent, like the renderers
            val translated = trOptions?.keys()?.asSequence()?.any { !blank(trOptions.str(it)) } == true
            val base: List<JSONObject>? = own?.objects()
                ?: b?.let { t -> Defaults.PMF.map { it.toJson(label = t.pmf[it.value]!!) } }
                ?: if (translated) Defaults.PMF.map { it.toJson() } else null
            if (base != null) s.put("options", localizeOptions(base, trOptions))
        }
        "followup" -> step.obj("input")?.let { s.put("input", localizeInput(it, tr, b)) }
    }
    return s
}

private fun localizeInput(input: JSONObject, tr: JSONObject?, b: BuiltinTexts?): JSONObject {
    val type = input.str("type")
    if (type == "selectOne" || type == "selectMultiple") {
        val opts = input.arr("options") ?: return input
        return input.copy().put("options", localizeOptions(opts.objects(), tr?.obj("options")))
    }
    val fill = if (type == "free") b?.answer else null
    val own = input.str("placeholder")
    val placeholder = if (own == null && fill == null) null else pick(tr?.str("placeholder"), own, fill)
    return if (placeholder == null) input else input.copy().put("placeholder", placeholder)
}

/** Built-in fill for one step outside a form, e.g. a follow-up question a conversation sent. English returns it untouched. */
internal fun fillStep(step: JSONObject, lang: String): JSONObject {
    val code = matchLanguage(lang, BUILTIN_LANGUAGES)
    return if (code != null && baseOf(code) != "en") localizeStep(step, null, builtinTexts(code)) else step
}

internal fun localizeContent(content: JSONObject, lang: String): JSONObject {
    val offered = formLanguages(content)
    val code = matchLanguage(lang, offered) ?: offered[0]
    val isDefault = code == offered[0]
    val builtinCode = matchLanguage(code, BUILTIN_LANGUAGES)
    // English output never changes: the built-in fill is for other languages only.
    val b = if (builtinCode != null && baseOf(builtinCode) != "en") builtinTexts(builtinCode) else null
    // Nothing to swap and nothing to fill: every existing form comes back untouched.
    if (isDefault && b == null) return content

    val translations = content.obj("translations")
    val key = translations?.keys()?.asSequence()?.firstOrNull { norm(it) == norm(code) }
    val tr = if (isDefault || key == null) null else key?.let { translations?.obj(it) }

    val out = content.copy()
    out.remove("languages"); out.remove("defaultLanguage"); out.remove("translations")
    content.arr("steps")?.let { steps ->
        out.put("steps", JSONArray(steps.objects().map { localizeStep(it, tr?.obj("steps")?.obj(it.str("id") ?: ""), b) }))
    }
    out.putIf("submitLabel", pick(tr?.str("submitLabel"), content.str("submitLabel"), b?.submit))
    out.putIf("nextLabel", pick(tr?.str("nextLabel"), content.str("nextLabel"), b?.next))
    out.putIf("backLabel", pick(tr?.str("backLabel"), content.str("backLabel"), b?.back))
    content.obj("header")?.let { h ->
        out.put("header", h.copy()
            .putIf("title", pick(tr?.obj("header")?.str("title"), h.str("title")))
            .putIf("subtitle", pick(tr?.obj("header")?.str("subtitle"), h.str("subtitle"))))
    }
    val th = content.obj("thanks")
    val tTitle = pick(tr?.obj("thanks")?.str("title"), th?.str("title"), b?.thanksTitle)
    val tSub = pick(tr?.obj("thanks")?.str("subtitle"), th?.str("subtitle"), b?.thanksSubtitle)
    if (th != null || tTitle != null || tSub != null) out.put("thanks", (th?.copy() ?: JSONObject()).putIf("title", tTitle).putIf("subtitle", tSub))
    val fills = mapOf("sendLabel" to b?.send, "skipLabel" to b?.skip, "endLabel" to b?.end, "typingLabel" to b?.typing, "logLabel" to b?.log)
    val chat = content.obj("chat")?.copy() ?: JSONObject()
    for ((k, fill) in fills) chat.putIf(k, pick(tr?.obj("chat")?.str(k), content.obj("chat")?.str(k), fill))
    if (chat.length() > 0) out.put("chat", chat)
    return out
}
