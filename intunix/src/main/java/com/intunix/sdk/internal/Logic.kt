package com.intunix.sdk.internal

private val NUMERIC = setOf("nps", "enps", "star", "csat", "ces", "smileys")
private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

private fun numericValue(a: Answer): Int? = if (a.kind in NUMERIC) (a.value as? AnswerValue.Num)?.v else null

private fun scalarValue(a: Answer): Any? = when (val v = a.value) {
    is AnswerValue.Num -> if (a.kind in NUMERIC) v.v else null
    is AnswerValue.Choice -> v.v
    is AnswerValue.Text -> v.text
    is AnswerValue.One -> v.selected
    is AnswerValue.Many -> null
}

internal fun evaluateShowIf(rule: ShowIf?, answers: List<Answer>): Boolean {
    if (rule == null) return true
    val prior = answers.firstOrNull { it.stepId == rule.stepId } ?: return false
    if (rule.op == "any") return true
    val scalar = scalarValue(prior)
    return when (rule.op) {
        "eq" -> jsEquals(scalar, rule.value)
        "neq" -> !jsEquals(scalar, rule.value)
        "in" -> scalar != null && rule.values.any { jsEquals(it, scalar) }
        "lte", "gte", "lt", "gt" -> {
            val n = numericValue(prior) ?: return false
            val t = (rule.value as? Number)?.toDouble() ?: return false
            when (rule.op) { "lte" -> n <= t; "gte" -> n >= t; "lt" -> n < t; else -> n > t }
        }
        else -> false
    }
}

internal fun isAnswerValid(step: Step, answer: Answer?): Boolean {
    val required = if (step.kind != "followup") step.required != false else step.required == true
    if (answer == null) return !required
    if (answer.stepId != step.id || answer.kind != step.kind) return false
    val v = answer.value
    return when (step.kind) {
        "nps", "enps" -> v is AnswerValue.Num && v.v in 0..10
        "star" -> v is AnswerValue.Num && v.v in 1..(step.max ?: Defaults.STAR_MAX)
        "csat", "smileys" -> v is AnswerValue.Num && v.v in 1..5
        "ces" -> v is AnswerValue.Num && v.v in 1..(step.scale ?: Defaults.CES_SCALE)
        "thumbs" -> v is AnswerValue.Choice && (v.v == "up" || v.v == "down")
        "pmf" -> v is AnswerValue.Choice && v.v in setOf("very", "somewhat", "not")
        "followup" -> isFollowUpValid(step, v)
        else -> false
    }
}

private fun isFollowUpValid(step: Step, v: AnswerValue): Boolean {
    val input = step.input ?: return false
    val required = step.required == true
    return when (input.type) {
        "free", "name" -> {
            if (v !is AnswerValue.Text || v.type != input.type) return false
            val text = v.text.trim()
            if (text.isEmpty()) !required
            else if (input.type == "free" && input.maxLength != null) text.length <= input.maxLength
            else true
        }
        "email" -> {
            if (v !is AnswerValue.Text || v.type != "email") return false
            val text = v.text.trim()
            if (text.isEmpty()) !required else EMAIL.matches(text)
        }
        "selectOne" -> {
            if (v !is AnswerValue.One) return false
            if (v.selected.isEmpty()) !required else input.options.any { it.value == v.selected }
        }
        "selectMultiple" -> {
            if (v !is AnswerValue.Many) return false
            val picks = v.selected.filter { s -> input.options.any { it.value == s } }
            val min = input.minPick ?: if (required) 1 else 0
            val max = input.maxPick ?: Int.MAX_VALUE
            picks.size in min..max
        }
        else -> false
    }
}

internal fun isPageValid(steps: List<Step>, answers: List<Answer>): Boolean =
    steps.all { step -> isAnswerValid(step, answers.firstOrNull { it.stepId == step.id }) }
