package com.intunix.sdk.internal

import java.util.Locale

/** One spelling rule for every language comparison: trimmed, `_` as `-`, lower case. Not a string = "". */
internal fun norm(code: Any?): String = (code as? String)?.trim()?.replace('_', '-')?.lowercase() ?: ""
internal fun baseOf(code: Any?): String = norm(code).substringBefore('-')

internal data class LanguageInfo(val code: String, val name: String, val rtl: Boolean)

// Region variants a respondent must tell apart; looked up by full tag before the base language.
private val REGIONAL = mapOf(
    "pt-br" to "Português (Brasil)", "pt-pt" to "Português (Portugal)",
    "zh-hans" to "简体中文", "zh-hant" to "繁體中文", "zh-cn" to "简体中文", "zh-tw" to "繁體中文",
    "en-us" to "English (US)", "en-gb" to "English (UK)",
    "es-419" to "Español (Latinoamérica)", "es-es" to "Español (España)", "fr-ca" to "Français (Canada)",
)

private val NAMES = mapOf(
    "en" to "English", "bn" to "বাংলা", "hi" to "हिन्दी", "ur" to "اردو", "ar" to "العربية", "es" to "Español", "fr" to "Français",
    "de" to "Deutsch", "pt" to "Português", "id" to "Bahasa Indonesia", "tr" to "Türkçe", "zh" to "中文", "ja" to "日本語",
    "fa" to "فارسی", "he" to "עברית", "ru" to "Русский", "it" to "Italiano", "ko" to "한국어", "th" to "ไทย", "vi" to "Tiếng Việt",
    "ms" to "Bahasa Melayu", "ta" to "தமிழ்", "ne" to "नेपाली", "si" to "සිංහල",
)
private val RTL = setOf("ar", "fa", "he", "ur")

private fun displayName(code: String): String? {
    val loc = Locale.forLanguageTag(norm(code))
    val n = loc.getDisplayName(loc)
    return if (n.isNotBlank() && norm(n) != norm(code)) n.substring(0, 1).uppercase(loc) + n.substring(1) else null
}

internal fun languageInfo(code: String): LanguageInfo {
    val b = baseOf(code)
    // Table names first so the switcher label never depends on the platform's locale data.
    val name = REGIONAL[norm(code)] ?: (if (norm(code) == b) NAMES[b] else null) ?: displayName(code) ?: NAMES[b] ?: code
    return LanguageInfo(code, name, b in RTL)
}

/**
 * Exact tag, then `wanted` truncated one subtag at a time (zh-Hant-TW, zh-Hant, zh), then the
 * first offered code with the same base language. Returns the offered code's own spelling.
 */
internal fun matchLanguage(wanted: String?, offered: List<String>): String? {
    val w = norm(wanted)
    if (w.isEmpty()) return null
    fun find(pred: (String) -> Boolean) = offered.firstOrNull { pred(norm(it)) }
    val parts = w.split('-')
    for (n in parts.size downTo 1) find { it == parts.take(n).joinToString("-") }?.let { return it }
    // Chinese devices send regions (zh-TW), forms list scripts (zh-Hant): pick the script before any zh.
    if (parts[0] == "zh" && "hans" !in parts && "hant" !in parts) {
        val script = if (parts.any { it == "tw" || it == "hk" || it == "mo" }) "zh-hant" else "zh-hans"
        find { it == script || it.startsWith("$script-") }?.let { return it }
    }
    return find { it.substringBefore('-') == parts[0] }
}

/** First of remembered, developer, then device languages the form offers; else its default. */
internal fun resolveLanguage(content: org.json.JSONObject, remembered: String?, developer: String?, device: List<String>): String {
    val offered = formLanguages(content)
    for (c in listOf(remembered, developer) + device) matchLanguage(c, offered)?.let { return it }
    return offered[0]
}
