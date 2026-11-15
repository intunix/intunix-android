package com.intunix.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.LocaleList
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.intunix.sdk.internal.Answer
import com.intunix.sdk.internal.BuiltinTexts
import com.intunix.sdk.internal.ChatLabels
import com.intunix.sdk.internal.ConversationController
import com.intunix.sdk.internal.ConversationHandle
import com.intunix.sdk.internal.ConversationState
import com.intunix.sdk.internal.EnhancedProbes
import com.intunix.sdk.internal.FeedbackResponse
import com.intunix.sdk.internal.FormLanguage
import com.intunix.sdk.internal.Step
import com.intunix.sdk.internal.fillStep
import com.intunix.sdk.internal.localizeConversation
import com.intunix.sdk.internal.parseStep
import com.intunix.sdk.internal.reviewQualifies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.intunix.sdk.internal.Defaults
import com.intunix.sdk.internal.Experience
import com.intunix.sdk.internal.ExperienceResult
import com.intunix.sdk.internal.FeedbackRunner
import com.intunix.sdk.internal.Phase
import com.intunix.sdk.internal.RenderCallbacks
import com.intunix.sdk.internal.ReviewPresenter
import com.intunix.sdk.internal.isPageValid
import kotlinx.coroutines.delay

private val AUTO_SUBMIT = setOf("thumbs", "pmf", "smileys")

internal fun deviceLanguages(): List<String> =
    runCatching { LocaleList.getDefault().toLanguageTags().split(',') }.getOrDefault(emptyList())

/** Form state and the rules of FeedbackExperience (React), independent of where it is drawn. */
internal class FormHost(
    val exp: Experience,
    private val cb: RenderCallbacks,
    openUrl: (String) -> Boolean,
    device: List<String> = deviceLanguages(),
    scope: CoroutineScope = MainScope(),
    private val onResult: (ExperienceResult) -> Unit,
) {
    /** A child scope, so finishing the form cancels its work and never the caller's scope. */
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = CoroutineScope(scope.coroutineContext + job)
    val language = FormLanguage(exp.content, cb.locale, device)
    var content by mutableStateOf(language.content)
        private set
    private val runner = FeedbackRunner(language.content, exp.reviewRequest)
    private var version by mutableIntStateOf(0)
    private var settled = false
    val closable = language.content.closable
    val review: ReviewPresenter? = ReviewPresenter.create(
        exp.reviewRequest, exp.id, cb.clientResponseId, language.content.brand.color, cb.onReviewEvent, openUrl,
    ) { finish() }

    private val handle = cb.conversation
    private val enhanced = handle?.config?.mode == "enhanced"
    private val probes = if (enhanced) EnhancedProbes(handle!!, if (language.multi) language.lang else null) else null
    var probing by mutableStateOf(false)
        private set

    val chat: ConversationController? = handle?.takeIf { it.config.mode == "conversational" }?.let { h ->
        ConversationController(
            language.content,
            if (language.multi) ConversationHandle(localizeConversation(h.config, language.lang), h.transport, h.conversationId, h.scope) else h,
            if (language.multi) language.lang else null,
        )
    }
    var chatReview by mutableStateOf(false)
        private set
    private var doneHandled = false
    private var unsubscribe: (() -> Unit)? = chat?.subscribe { bump(); onChatState() }

    private var impressed = false

    fun state() = version.let { runner.state() }
    fun isLastPage() = runner.isLastPage()
    fun chatState(): ConversationState = version.let { chat!!.state() }
    fun chatLabels(): ChatLabels = com.intunix.sdk.internal.chatLabels(content, handle!!.config)

    fun chatInput(): Step? = chatState().let { s -> s.inputJson?.let { parseStep(fillStep(it, language.lang)) } ?: s.input }

    /** Once per form instance, however often the view is (re)composed. */
    fun impression() {
        if (impressed) return
        impressed = true
        chat?.let { c -> handle!!.scope.launch { c.start() } }
        cb.onImpression()
    }

    private fun bump() { version++ }

    private fun finalize(outcome: String, r: FeedbackResponse) {
        if (settled) return
        settled = true
        unsubscribe?.invoke()
        if (chat?.state()?.endedByRespondent != true) chat?.dispose()
        val full = if (chat == null && handle != null) r.copy(conversationId = handle.conversationId) else r
        job.cancel()
        onResult(ExperienceResult(outcome, language.decorate(full)))
    }

    fun finish() {
        finalize("responded", chat?.result() ?: runner.finish())
        bump()
    }

    fun close() {
        if (settled) return
        if (chat != null) return closeChat(chat)
        when (runner.state().phase) {
            Phase.REVIEW -> review?.onSkip() ?: finish()
            Phase.THANKS, Phase.DONE -> finish()
            Phase.RUNNING -> {
                val r = runner.dismiss()
                cb.onDismiss()
                finalize(if (r.answers.isNotEmpty()) "responded" else "dismissed", r)
                bump()
            }
        }
    }

    private fun closeChat(c: ConversationController) {
        when {
            chatReview -> review?.onSkip() ?: finish()
            c.state().done -> finish()
            else -> {
                unsubscribe?.invoke() // ending must not start the thanks timer
                c.end()
                cb.onDismiss()
                val r = c.result()
                finalize(if (r.answers.isNotEmpty()) "responded" else "dismissed", r)
                bump()
            }
        }
    }

    private fun onChatState() {
        val c = chat ?: return
        if (!c.state().done || doneHandled) return
        doneHandled = true
        if (review != null && reviewQualifies(exp.reviewRequest, c.result().answers)) {
            chatReview = true
            review.onShown()
        } else {
            val ms = content.thanks?.autoCloseMs ?: Defaults.THANKS_AUTO_CLOSE_MS
            if (ms > 0) scope.launch { delay(ms); finish() }
        }
    }

    fun sendChat(a: Answer) {
        val c = chat ?: return
        handle!!.scope.launch { if (c.answer(a) && !settled) cb.onResponse(a) }
    }

    fun skipChat() { chat?.let { c -> handle!!.scope.launch { c.skip() } } }

    /** Thumbs / PMF / smileys submit on tap only when the whole form is that one question. */
    fun autoSubmit(): Boolean {
        val s = runner.state()
        val only = s.pageSteps.singleOrNull()?.takeIf { s.visibleSteps.size == 1 } ?: return false
        return only.kind in AUTO_SUBMIT && (only.page == null || content.steps.count { it.page == only.page } == 1)
    }

    fun onChange(stepId: String, answer: Answer?) {
        val auto = autoSubmit()
        if (answer != null) runner.record(answer) else runner.clear(stepId)
        if (auto && answer != null) submitPage() else bump()
    }

    fun submitPage() {
        if (probing) return
        val s = runner.state()
        if (!isPageValid(s.pageSteps, s.answers)) return
        s.pageSteps.forEach { step -> s.answers.firstOrNull { it.stepId == step.id }?.let(cb.onResponse) }
        val p = probes
        if (p == null) { runner.submitPage(); bump(); return }
        probing = true
        bump()
        scope.launch {
            val u = runCatching { p.afterPage(s.pageSteps, s.answers) }.getOrNull()
            probing = false
            if (settled) return@launch
            u?.drop?.forEach { runner.removeInsertedAfter(it) }
            u?.insert?.forEach { (anchor, step) -> runner.insertAfter(anchor, step) }
            runner.submitPage()
            bump()
        }
    }

    fun back() { if (probing) return; runner.back(); bump() }

    fun switchLanguage(code: String) {
        if (!language.switchTo(code)) return
        content = language.content
        runner.setContent(content)
        probes?.setLocale(code)
        if (handle != null) chat?.setLanguage(code, content, localizeConversation(handle.config, code))
        bump()
    }
}

/** Mirrors the layout for right-to-left languages; a plain English form keeps its tree untouched. */
@Composable
internal fun RtlRoot(language: FormLanguage, content: @Composable () -> Unit) {
    if (!language.decorated) content()
    else CompositionLocalProvider(LocalLayoutDirection provides if (language.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)
}

internal fun switcherFor(host: FormHost, show: Boolean): (@Composable () -> Unit)? =
    if (show && host.language.multi) {
        { LanguageSwitcher(host.language.lang, host.language.options, host.language.texts()!!.language, host::switchLanguage) }
    } else null

/** The form itself (header, questions, footer, thanks, review card). The host decides the frame. */
@Composable
internal fun FeedbackForm(host: FormHost, modifier: Modifier = Modifier) {
    val content = host.content
    val texts = host.language.texts()
    val live = host.state()
    // Keep drawing the last page while the frame animates out; DONE has nothing to show.
    val last = remember { mutableStateOf(live) }
    if (live.phase != Phase.DONE) last.value = live
    val state = last.value
    LaunchedEffect(Unit) { host.impression() }
    LaunchedEffect(live.phase) {
        when (live.phase) {
            Phase.THANKS -> {
                val ms = content.thanks?.autoCloseMs ?: Defaults.THANKS_AUTO_CLOSE_MS
                if (ms > 0) { delay(ms); host.finish() }
            }
            Phase.REVIEW -> host.review?.onShown()
            Phase.DONE -> host.finish()
            else -> {}
        }
    }
    if (state.phase == Phase.DONE) return

    RtlRoot(host.language) { CompositionLocalProvider(LocalBrand provides brandColors(content.brand.color, LocalPalette.current)) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            CompositionLocalProvider(LocalWide provides (maxWidth >= Crisp.WideAt)) {
                val ending = state.phase == Phase.THANKS || state.phase == Phase.REVIEW
                Column(Modifier.fillMaxWidth()) {
                    Header(state.pageIndex, state.pageCount, state.paged, ending, host.closable, texts, switcherFor(host, state.phase == Phase.RUNNING))
                    when {
                        state.phase == Phase.REVIEW && host.review != null -> ReviewCard(host.review)
                        state.phase == Phase.THANKS -> Thanks(
                            content.thanks?.title ?: texts?.thanksTitle ?: Defaults.THANKS_TITLE,
                            content.thanks?.subtitle ?: texts?.thanksSubtitle ?: Defaults.THANKS_SUBTITLE,
                        )
                        state.pageSteps.isNotEmpty() -> {
                            state.pageSteps.forEachIndexed { i, step ->
                                if (i > 0) Spacer(Modifier.height(28.dp))
                                BasicText(step.title, Modifier.semantics { heading() }.padding(bottom = 6.dp), style = TitleStyle)
                                if (!step.subtitle.isNullOrEmpty()) BasicText(step.subtitle, Modifier.padding(bottom = 16.dp), style = SubStyle)
                                else Spacer(Modifier.height(8.dp))
                                StepView(step, state.answers.firstOrNull { it.stepId == step.id }) { host.onChange(step.id, it) }
                            }
                            if (!host.autoSubmit()) {
                                Footer(
                                    showBack = state.pageIndex > 0,
                                    backLabel = content.backLabel ?: texts?.back ?: Defaults.BACK,
                                    nextLabel = if (host.isLastPage()) content.submitLabel ?: texts?.submit ?: Defaults.SUBMIT else content.nextLabel ?: texts?.next ?: Defaults.NEXT,
                                    nextEnabled = isPageValid(state.pageSteps, state.answers) && !host.probing,
                                    onBack = host::back,
                                    onNext = host::submitPage,
                                )
                            }
                        }
                    }
                }
                if (host.closable) CloseButton(Modifier.align(Alignment.TopEnd), texts?.close ?: "Close", host::close)
            }
        }
    } }
}

@Composable
internal fun Header(
    pageIndex: Int, pageCount: Int, paged: Boolean, ending: Boolean, closable: Boolean,
    texts: BuiltinTexts? = null, language: (@Composable () -> Unit)? = null,
) {
    val brand = LocalBrand.current
    if (pageCount <= 1 && language == null) { Spacer(Modifier.height(if (closable) 30.dp else 0.dp)); return }
    val label = if (texts != null) {
        if (ending) texts.done else (if (paged) texts.pageOf else texts.questionOf).replace("{n}", "${pageIndex + 1}").replace("{total}", "$pageCount")
    } else if (ending) "Done" else "${if (paged) "Page" else "Question"} ${pageIndex + 1} of $pageCount"
    Column {
        if (language != null) {
            Row(Modifier.fillMaxWidth().padding(end = if (closable) 28.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pageCount > 1) BasicText(label, Modifier.weight(1f).heightIn(min = 24.dp).padding(top = 4.dp), style = text(12.sp, FontWeight.SemiBold, Crisp.Muted))
                else Spacer(Modifier.weight(1f))
                language()
            }
        } else {
            BasicText(label, Modifier.heightIn(min = 24.dp).padding(end = 36.dp, top = 4.dp), style = text(12.sp, FontWeight.SemiBold, Crisp.Muted))
        }
        Spacer(Modifier.height(10.dp))
        if (pageCount > 1) Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(pageCount) { i ->
                Box(Modifier.weight(1f).height(4.dp).background(if (ending || i <= pageIndex) brand.color else Crisp.Track, RoundedCornerShape(4.dp)))
            }
        }
    }
}

@Composable
internal fun CloseButton(modifier: Modifier, label: String, onClose: () -> Unit) {
    Box(
        modifier.size(36.dp).semantics { contentDescription = label }.tappable(false, Role.Button, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) { BasicText("×", style = text(22.sp, color = Crisp.Muted)) }
}

@Composable
private fun Footer(showBack: Boolean, backLabel: String, nextLabel: String, nextEnabled: Boolean, onBack: () -> Unit, onNext: () -> Unit) {
    val brand = LocalBrand.current
    val wide = LocalWide.current
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showBack) {
            Box(
                Modifier.height(50.dp).then(if (wide) Modifier.padding(0.dp) else Modifier.size(50.dp))
                    .border(1.5.dp, Crisp.Border, RoundedCornerShape(14.dp))
                    .semantics { contentDescription = backLabel }
                    .tappable(false, Role.Button, onClick = onBack)
                    .padding(horizontal = if (wide) 18.dp else 0.dp),
                contentAlignment = Alignment.Center,
            ) {
                val arrow = if (LocalLayoutDirection.current == LayoutDirection.Rtl) "→" else "←"
                BasicText(if (wide) "$arrow  $backLabel" else arrow, style = text(15.sp, FontWeight.SemiBold, Crisp.ScaleText)) }
        }
        if (wide) Spacer(Modifier.weight(1f))
        Box(
            Modifier.height(50.dp).then(if (wide) Modifier.widthIn(min = 120.dp) else Modifier.weight(1f))
                .alpha(if (nextEnabled) 1f else 0.4f)
                .background(brand.color, RoundedCornerShape(14.dp))
                .tappable(false, Role.Button, enabled = nextEnabled, onClick = onNext)
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) { BasicText(nextLabel, style = text(15.sp, FontWeight.SemiBold, brand.onColor)) }
    }
}

@Composable
private fun Thanks(title: String, subtitle: String) {
    val brand = LocalBrand.current
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, start = 4.dp, end = 4.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).background(brand.soft, CircleShape), contentAlignment = Alignment.Center) {
            CheckIcon(brand.color, Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        BasicText(title, style = TitleStyle.copy(textAlign = TextAlign.Center))
        if (subtitle.isNotEmpty()) BasicText(subtitle, Modifier.padding(top = 6.dp), style = SubStyle.copy(textAlign = TextAlign.Center))
    }
}

@Composable
internal fun ReviewCard(p: ReviewPresenter) {
    val brand = LocalBrand.current
    val accent = parseHexColor(p.themeColor) ?: brand.color
    val on = brandColors(p.themeColor ?: "").onColor.takeIf { parseHexColor(p.themeColor) != null } ?: brand.onColor
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(p.title, style = TitleStyle.copy(textAlign = TextAlign.Center))
        BasicText(p.description, Modifier.padding(top = 6.dp), style = SubStyle.copy(textAlign = TextAlign.Center))
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.fillMaxWidth().height(50.dp)
                .then(if (p.outline) Modifier.border(1.5.dp, accent, RoundedCornerShape(14.dp)) else Modifier.background(accent, RoundedCornerShape(14.dp)))
                .semantics { contentDescription = "${p.buttonText} (opens in your browser)" }
                .tappable(false, Role.Button) { p.onClick() },
            contentAlignment = Alignment.Center,
        ) { BasicText(p.buttonText, style = text(15.sp, FontWeight.SemiBold, if (p.outline) accent else on)) }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 6.dp).tappable(false, Role.Button) { p.onSkip() },
            contentAlignment = Alignment.Center,
        ) { BasicText(p.skipText, style = text(14.sp, FontWeight.Medium, Crisp.Muted)) }
    }
}
