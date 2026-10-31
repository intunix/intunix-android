package com.intunix.sdk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.intunix.sdk.internal.newId
import com.intunix.sdk.internal.parseFeedbackContent
import com.intunix.sdk.ui.FeedbackForm
import com.intunix.sdk.ui.FormHost
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
    onFinished: (() -> Unit)? = null,
) {
    val android = LocalContext.current
    var host by remember(form) { mutableStateOf<FormHost?>(null) }
    var done by remember(form) { mutableStateOf(false) }
    LaunchedEffect(form) {
        val client = Intunix.clientOrNull() ?: return@LaunchedEffect
        val exp = client.resolve(form) ?: return@LaunchedEffect
        val content = parseFeedbackContent(exp.content) ?: return@LaunchedEffect
        val ctx = client.contextFor(exp, context)
        val responseId = newId()
        host = FormHost(exp, content, client.callbacks(exp, ctx, responseId), { openExternal(android, it) }) { result ->
            client.settle(exp, ctx, result, responseId)
            done = true
            onFinished?.invoke()
        }
    }
    val h = host
    if (h != null && !done) FeedbackForm(h, modifier)
}
