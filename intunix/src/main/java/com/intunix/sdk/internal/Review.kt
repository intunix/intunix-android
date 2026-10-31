package com.intunix.sdk.internal

import java.net.URI

private const val MAX_URL_LENGTH = 2048
private val SCORED_RULES = setOf("nps", "csat", "ces", "star", "smileys")

/** The URL only if it is absolute http(s); anything else (javascript:, data:, relative) is null. */
internal fun safeReviewUrl(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty() || s.length > MAX_URL_LENGTH) return null
    val uri = runCatching { URI(s) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrEmpty()) return null
    return s
}

/** Whether this submission earns a review invitation. Unknown config fails closed. */
internal fun reviewQualifies(config: ReviewRequestConfig?, answers: List<Answer>): Boolean {
    if (config == null || !config.enabled) return false
    if (safeReviewUrl(config.reviewUrl) == null) return false
    val rule = config.rule ?: return false
    if (rule == "always") return true
    if (rule !in SCORED_RULES) return false
    val threshold = config.threshold?.takeUnless { it.isNaN() } ?: return false
    val hit = answers.firstOrNull { it.kind == rule } ?: return false
    val v = (hit.value as? AnswerValue.Num)?.v ?: return false
    return v >= threshold
}

internal data class ReviewEvent(
    val name: String,
    val formId: String,
    val clientResponseId: String,
    val platform: String,
    val reviewUrl: String,
    val triggerRule: String,
    val triggerThreshold: Double?,
)

internal object ReviewCopy {
    const val TITLE = "🎉 Thank you!"
    const val DESCRIPTION = "We're happy you had a great experience. Would you consider leaving a public review?"
    const val BUTTON = "Leave a Review"
    const val SKIP = "Maybe later"
}

internal class ReviewPresenter private constructor(
    private val config: ReviewRequestConfig,
    private val formId: String,
    private val clientResponseId: String,
    val reviewUrl: String,
    brandColor: String?,
    private val emit: (ReviewEvent) -> Unit,
    private val openUrl: (String) -> Boolean,
    private val onDone: () -> Unit,
) {
    val title = config.title ?: ReviewCopy.TITLE
    val description = config.description ?: ReviewCopy.DESCRIPTION
    val buttonText = config.buttonText ?: ReviewCopy.BUTTON
    val skipText = config.skipText ?: ReviewCopy.SKIP
    val themeColor = config.themeColor ?: brandColor
    val outline = config.buttonStyle == "outline"
    private var settled = false

    private fun fire(name: String) = emit(
        ReviewEvent(name, formId, clientResponseId, config.platform, reviewUrl, config.rule ?: "always",
            if (config.rule == "always") null else config.threshold),
    )

    fun onShown() = fire("\$review_request_shown")

    fun onClick() {
        if (settled) return
        settled = true
        fire("\$review_request_clicked")
        try {
            if (openUrl(reviewUrl)) fire("\$review_request_redirected")
        } catch (_: Throwable) {
        } finally {
            onDone()
        }
    }

    fun onSkip() {
        if (settled) return
        settled = true
        fire("\$review_request_skipped")
        onDone()
    }

    companion object {
        fun create(
            config: ReviewRequestConfig?, formId: String, clientResponseId: String, brandColor: String?,
            emit: (ReviewEvent) -> Unit, openUrl: (String) -> Boolean, onDone: () -> Unit,
        ): ReviewPresenter? {
            if (config == null || !config.enabled) return null
            val url = safeReviewUrl(config.reviewUrl) ?: return null
            return ReviewPresenter(config, formId, clientResponseId, url, brandColor, emit, openUrl, onDone)
        }
    }
}
