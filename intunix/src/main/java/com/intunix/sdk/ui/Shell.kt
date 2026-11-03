package com.intunix.sdk.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.intunix.sdk.internal.Experience
import com.intunix.sdk.internal.ExperienceResult
import com.intunix.sdk.internal.FeedbackContent
import com.intunix.sdk.internal.FormPresenter
import com.intunix.sdk.internal.RenderCallbacks
import com.intunix.sdk.internal.newId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

internal class PendingShow(
    val exp: Experience,
    val content: FeedbackContent,
    val callbacks: RenderCallbacks,
    val result: CompletableDeferred<ExperienceResult>,
) {
    /** One form state per show, kept across activity recreation (font scale, locale, reclaim). */
    var host: FormHost? = null
    /** The current activity's exit animation. */
    var onClosed: (() -> Unit)? = null
    /** The app screen the form opened over; its theme decides light/dark in Auto. */
    var from: java.lang.ref.WeakReference<Activity>? = null
}

internal object ShowRegistry {
    val pending = ConcurrentHashMap<String, PendingShow>()
}

/** Opens a review URL in the browser; false when nothing can open it. */
internal fun openExternal(context: Context, url: String): Boolean = try {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(i)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Shows forms in IntunixFormActivity, once the app is in the foreground. */
internal class ActivityPresenter(
    private val app: Context,
    private val foreground: StateFlow<Boolean>,
    private val currentActivity: () -> Activity?,
) : FormPresenter {
    override suspend fun present(exp: Experience, content: FeedbackContent, callbacks: RenderCallbacks): ExperienceResult {
        foreground.first { it }
        val token = newId()
        val result = CompletableDeferred<ExperienceResult>()
        val pending = PendingShow(exp, content, callbacks, result)
        ShowRegistry.pending[token] = pending
        try {
            val from = currentActivity()
            pending.from = from?.let { java.lang.ref.WeakReference(it) }
            val intent = Intent(from ?: app, IntunixFormActivity::class.java).putExtra(EXTRA_TOKEN, token)
            if (from == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            (from ?: app).startActivity(intent)
            from?.overridePendingTransition(0, 0)
            return result.await()
        } finally {
            ShowRegistry.pending.remove(token)
        }
    }

    companion object { const val EXTRA_TOKEN = "intunix.token" }
}

/** Transparent activity that frames a form: centered dialog for modal, bottom sheet otherwise. */
class IntunixFormActivity : ComponentActivity() {
    private var host: FormHost? = null
    private var pending: PendingShow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent.getStringExtra(ActivityPresenter.EXTRA_TOKEN)?.let { ShowRegistry.pending[it] }
        if (p == null || p.result.isCompleted) { finishQuietly(); return }
        pending = p
        enableEdgeToEdge()
        val visible = MutableTransitionState(false).apply { targetState = true }
        val app = applicationContext
        val h = p.host ?: FormHost(p.exp, p.content, p.callbacks, { openExternal(app, it) }) { r ->
            p.result.complete(r)
            p.onClosed?.invoke()
        }.also { p.host = it }
        p.onClosed = { visible.targetState = false }
        host = h
        setContent {
            BackHandler { h.close() }
            LaunchedEffect(visible.currentState, visible.targetState) {
                if (!visible.targetState && !visible.currentState) finishQuietly()
            }
            FormTheme(p.content.brand, hostContext = p.from?.get()) { FormFrame(p.exp.type == "modal", visible, h) }
        }
    }

    override fun onDestroy() {
        // Killed or finished without an answer (e.g. the task was swiped away): count it as dismissed.
        if (isFinishing) pending?.let { if (!it.result.isCompleted) host?.close() ?: it.result.complete(ExperienceResult("dismissed", null)) }
        super.onDestroy()
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}

@Composable
private fun FormFrame(modal: Boolean, visible: MutableTransitionState<Boolean>, host: FormHost) {
    val outside = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Box(Modifier.fillMaxSize().background(if (modal) Crisp.Backdrop else Crisp.Backdrop.copy(alpha = 0.25f))
                .clickable(outside, null) { host.close() })
        }
        if (modal) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
                AnimatedVisibility(visible, enter = fadeIn(tween(150)) + scaleIn(tween(180), initialScale = 0.96f), exit = fadeOut(tween(120))) {
                    Card(Modifier.widthIn(max = 460.dp), RoundedCornerShape(16.dp), 24) { FeedbackForm(host) }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime), contentAlignment = Alignment.BottomCenter) {
                AnimatedVisibility(visible, enter = slideInVertically(tween(220)) { it }, exit = slideOutVertically(tween(180)) { it }) {
                    Card(Modifier.widthIn(max = 720.dp), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), 20, sheet = true) { FeedbackForm(host) }
                }
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier, shape: RoundedCornerShape, pad: Int, sheet: Boolean = false, content: @Composable () -> Unit) {
    BoxWithConstraints {
        Box(
            modifier.fillMaxWidth().heightIn(max = maxHeight * if (sheet) 0.9f else 1f)
                .shadow(24.dp, shape, clip = false)
                .background(Crisp.Card, shape)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .verticalScroll(rememberScrollState())
                .then(if (sheet) Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets(0))) else Modifier)
                .padding(pad.dp),
        ) { content() }
    }
}

