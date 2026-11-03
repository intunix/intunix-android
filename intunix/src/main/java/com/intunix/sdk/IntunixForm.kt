package com.intunix.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.intunix.sdk.internal.newId
import com.intunix.sdk.internal.parseFeedbackContent
import com.intunix.sdk.ui.FeedbackForm
import com.intunix.sdk.ui.FormHost
import com.intunix.sdk.ui.FormTheme
import com.intunix.sdk.ui.openExternal

/**
 * A form placed in your own layout (the web SDK's inline form). Draws nothing
 * until the form loads, and nothing again once the visitor is done.
 *
 * @param form form id or slug
 * @param context values sent with the response, on top of [Intunix.setContext]
 * @param onFinished called once the visitor submits or closes the form
 */
@Composable
fun IntunixForm(
    form: String,
    modifier: Modifier = Modifier,
    context: Map<String, Any?>? = null,
    /** Light/dark for this form; null = [Intunix.setTheme] / console lock / the app. */
    theme: IntunixTheme? = null,
    onFinished: (() -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext
    // Survives rotation and list recycling, so answers stay and the impression is reported once.
    val holder = viewModel<FormHolder>(key = "intunix-form-$form") { FormHolder() }
    val latestContext by rememberUpdatedState(context)
    val latestOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(form) {
        if (holder.host != null || holder.done) return@LaunchedEffect
        val client = Intunix.clientOrNull() ?: return@LaunchedEffect
        val exp = client.resolve(form) ?: return@LaunchedEffect
        val content = parseFeedbackContent(exp.content) ?: return@LaunchedEffect
        val ctx = client.contextFor(exp, latestContext)
        val responseId = newId()
        holder.host = FormHost(exp, content, client.callbacks(exp, ctx, responseId), { openExternal(app, it) }) { result ->
            client.settle(exp, ctx, result, responseId)
            holder.done = true
            latestOnFinished?.invoke()
        }
    }
    val h = holder.host
    if (h != null && !holder.done) FormTheme(h.content.brand, theme) { FeedbackForm(h, modifier) }
}

internal class FormHolder : ViewModel() {
    var host by mutableStateOf<FormHost?>(null)
    var done by mutableStateOf(false)
}
