package com.intunix.sdk.internal

import org.json.JSONObject

/** Where a form's language preferences come from; read only for multi-language forms. */
internal class LocaleSource(
    val developer: () -> String?,
    val remembered: () -> String?,
    val remember: (String) -> Unit,
)

/** The language state of one form: resolved at start, switchable, re-localizing the content. */
internal class FormLanguage(private val raw: JSONObject, private val source: LocaleSource?, device: List<String>) {
    val multi: Boolean = formLanguages(raw).size >= 2
    var lang: String = if (multi) resolveLanguage(raw, source?.remembered?.invoke(), source?.developer?.invoke(), device) else formLanguages(raw)[0]
        private set
    /** True when the content differs from the raw form (several languages, or a non-English one to fill). */
    val decorated: Boolean = multi || baseOf(lang) != "en"
    var content: FeedbackContent = build()
        private set
    val options: List<LanguageInfo> = formLanguages(raw).map(::languageInfo)
    val rtl: Boolean get() = decorated && languageInfo(lang).rtl

    /** Built-in texts for the chrome (buttons, page labels); null for a plain English form. */
    fun texts(): BuiltinTexts? = if (decorated) builtinTexts(lang) else null

    fun switchTo(code: String): Boolean {
        if (!multi || code == lang || options.none { it.code == code }) return false
        source?.remember?.invoke(code)
        lang = code
        content = build()
        return true
    }

    /** Multi-language forms report the language the respondent answered in. */
    fun decorate(r: FeedbackResponse): FeedbackResponse = if (multi) r.copy(language = lang) else r

    private fun build() = parseFeedbackContent(if (decorated) localizeContent(raw, lang) else raw)!!
}
