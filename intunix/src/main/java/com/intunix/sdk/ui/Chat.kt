package com.intunix.sdk.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intunix.sdk.internal.Answer
import com.intunix.sdk.internal.BuiltinTexts
import com.intunix.sdk.internal.ChatLabels
import com.intunix.sdk.internal.ConversationState
import com.intunix.sdk.internal.Step
import com.intunix.sdk.internal.isAnswerValid
import com.intunix.sdk.internal.isEmptyAnswer
import com.intunix.sdk.internal.submitsOnTap

/** A conversational form: the host's chat controller drawn as a message log with one input at a time. */
@Composable
internal fun ChatForm(host: FormHost, modifier: Modifier = Modifier) {
    val state = host.chatState()
    LaunchedEffect(Unit) { host.impression() }
    RtlRoot(host.language) {
        CompositionLocalProvider(LocalBrand provides brandColors(host.content.brand.color, LocalPalette.current)) {
            ChatView(
                state, host.chatInput(), host.chatLabels(), host.language.texts(), switcherFor(host, !state.done), host.closable,
                host::sendChat, host::skipChat, host::close, host::close, modifier,
                footer = if (host.chatReview) {{ ReviewCard(host.review!!) }} else null,
            )
        }
    }
}

/** Stateless chat view; `footer` replaces the Skip / End row (the review card). */
@Composable
internal fun ChatView(
    state: ConversationState,
    input: Step?,
    labels: ChatLabels,
    texts: BuiltinTexts?,
    language: (@Composable () -> Unit)?,
    closable: Boolean,
    onAnswer: (Answer) -> Unit,
    onSkip: () -> Unit,
    onEnd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalWide provides (maxWidth >= Crisp.WideAt)) {
            val pages = maxOf(state.total, 1)
            val bounded = this@BoxWithConstraints.constraints.hasBoundedHeight
            Column(Modifier.fillMaxWidth()) {
                Header(if (state.done) pages else minOf(state.answered, pages - 1), pages, true, state.done, closable, texts, language)
                Log(state, labels, if (bounded) Modifier.weight(1f, fill = false) else Modifier.heightIn(max = 360.dp))
                if (input != null && !state.pending && !state.done) ChatInput(input, labels, onAnswer)
                if (footer != null) footer()
                else Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.canSkip) ChatLink(labels.skip, onSkip)
                    Spacer(Modifier.weight(1f))
                    if (!state.done) ChatLink(labels.end, onEnd)
                }
            }
            if (closable) CloseButton(Modifier.align(Alignment.TopEnd), texts?.close ?: "Close", onClose)
        }
    }
}

@Composable
private fun Log(state: ConversationState, labels: ChatLabels, size: Modifier) {
    val brand = LocalBrand.current
    val scroll = rememberScrollState()
    // Keyed on maxValue: it grows only once the new bubble is laid out.
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().then(size).verticalScroll(scroll)
            .semantics { contentDescription = labels.log },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.messages.forEachIndexed { i, m ->
            val nia = m.role == "nia"
            if (nia && state.messages.getOrNull(i - 1)?.role != "nia") NameLabel(labels.name)
            val live = nia && i == state.messages.lastIndex
            Box(Modifier.fillMaxWidth().then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier), contentAlignment = if (nia) Alignment.CenterStart else Alignment.CenterEnd) {
                Bubble(if (nia) brand.soft else brand.color) {
                    BasicText(m.text, style = text(14.sp, color = if (nia) Crisp.Text else brand.onColor, lineHeight = 20.sp))
                }
            }
        }
        if (state.pending) {
            if (state.messages.lastOrNull()?.role != "nia") NameLabel(labels.name)
            Bubble(brand.soft, Modifier.semantics { contentDescription = labels.typing }) {
                Row(Modifier.height(20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { Box(Modifier.size(6.dp).background(Crisp.Muted, CircleShape)) }
                }
            }
        }
    }
}

@Composable
private fun NameLabel(name: String) = BasicText(name, Modifier.padding(start = 4.dp), style = text(11.sp, FontWeight.SemiBold, Crisp.Muted))

@Composable
private fun Bubble(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.widthIn(max = 280.dp).background(color, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) { content() }

@Composable
private fun ChatInput(step: Step, labels: ChatLabels, onAnswer: (Answer) -> Unit) {
    val brand = LocalBrand.current
    // A new question starts blank; a language switch keeps the same id, so the typed text stays.
    var current by remember(step.id) { mutableStateOf<Answer?>(null) }
    val tap = submitsOnTap(step)
    val sendable = current?.let { isAnswerValid(step, it) && !isEmptyAnswer(it) } == true
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        StepView(step, current) { a ->
            current = a
            if (tap && a != null) onAnswer(a)
        }
        if (!tap) Box(
            Modifier.padding(top = 12.dp).heightIn(min = 44.dp).alpha(if (sendable) 1f else 0.4f)
                .background(brand.color, RoundedCornerShape(12.dp))
                .tappable(false, Role.Button, enabled = sendable) { current?.let(onAnswer) }
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) { BasicText(labels.send, style = text(14.sp, FontWeight.SemiBold, brand.onColor)) }
    }
}

@Composable
private fun ChatLink(label: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).tappable(false, Role.Button, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        BasicText(label, style = text(14.sp, FontWeight.Medium, Crisp.Muted))
    }
}
