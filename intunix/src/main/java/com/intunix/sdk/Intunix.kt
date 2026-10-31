package com.intunix.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.intunix.sdk.internal.ClientConfig
import com.intunix.sdk.internal.IntunixClient
import com.intunix.sdk.internal.Logger
import com.intunix.sdk.internal.PrefsStore
import com.intunix.sdk.ui.ActivityPresenter
import com.intunix.sdk.ui.IntunixFormActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/** Options for [Intunix.init]. Names follow the web SDK's init options. */
data class IntunixOptions(
    /** API origin. Leave as is unless Intunix gave you another host. */
    val apiHost: String = "https://api.intunix.com",
    /** Forms (ids or slugs) whose triggers should be live. Forms flagged auto-activate in the console are live anyway. */
    val register: List<String> = emptyList(),
    /** Load the whole SDK-enabled catalog instead of only [register]. */
    val registerAll: Boolean = false,
    /** How often buffered events upload. */
    val flushIntervalMs: Long = 5_000,
    val maxQueueSize: Int = 100,
    /** Wait before a screen-triggered form shows, so it does not pop the instant a screen opens. */
    val screenTriggerDelayMs: Long = 600,
    /** Keep tracked events on the device (they still fire event triggers). */
    val disableEventApi: Boolean = false,
    /** Log to Logcat under the tag "Intunix". */
    val debug: Boolean = false,
)

/**
 * Intunix feedback forms for Android. Call [init] once, in Application.onCreate.
 * Every other call is safe from any thread and before init finishes loading.
 */
object Intunix {
    const val VERSION = BuildConfig.SDK_VERSION

    @Volatile private var client: IntunixClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val foreground = MutableStateFlow(false)
    private var started = 0
    private var resumed: WeakReference<Activity>? = null

    @JvmStatic
    @JvmOverloads
    fun init(context: Context, apiKey: String, options: IntunixOptions = IntunixOptions()) {
        val app = context.applicationContext as Application
        main {
            if (client != null) return@main
            val log = Logger { level, msg ->
                if (options.debug) when (level) { 'e' -> Log.e(TAG, msg); 'w' -> Log.w(TAG, msg); else -> Log.d(TAG, msg) }
            }
            val cfg = ClientConfig(
                apiKey = apiKey,
                apiHost = options.apiHost.trimEnd('/'),
                registerAll = options.registerAll,
                register = options.register,
                flushIntervalMs = options.flushIntervalMs,
                maxQueueSize = options.maxQueueSize,
                screenTriggerDelayMs = options.screenTriggerDelayMs,
                disableEventApi = options.disableEventApi,
                sdkVersion = VERSION,
            )
            val c = IntunixClient(cfg, PrefsStore(app), ActivityPresenter(app, foreground) { resumed?.get() }, scope, log)
            client = c
            app.registerActivityLifecycleCallbacks(lifecycle)
            c.start()
        }
    }

    /** Identify the signed-in user. Call on every login. */
    @JvmStatic
    @JvmOverloads
    fun identify(userId: String, traits: Map<String, Any?> = emptyMap()) = main { client?.identify(userId, traits) }

    /** Call on logout: forgets the user, rotates the anonymous id and ends the session. */
    @JvmStatic
    fun reset() = main { client?.reset() }

    @JvmStatic
    @JvmOverloads
    fun track(event: String, properties: Map<String, Any?> = emptyMap()) = main { client?.track(event, properties) }

    /** Tell Intunix which screen is showing. Drives "App screen" triggers and screen targeting. */
    @JvmStatic
    fun screen(name: String) = main { client?.screen(name) }

    /** Merge values into the session context sent with responses; null clears it. */
    @JvmStatic
    fun setContext(context: Map<String, Any?>?) = main { client?.setContext(context) }

    @JvmStatic
    fun clearContext() = main { client?.setContext(null) }

    /** Show a form now (still subject to its cadence and targeting). Accepts the form id or slug. */
    @JvmStatic
    @JvmOverloads
    fun showForm(idOrSlug: String, context: Map<String, Any?>? = null) = main { client?.showForm(idOrSlug, context) }

    /** Make a form's triggers live. */
    @JvmStatic
    fun register(idOrSlug: String) = main { client?.register(idOrSlug) }

    @JvmStatic
    fun unregister(idOrSlug: String) = main { client?.unregister(idOrSlug) }

    internal fun clientOrNull() = client

    private fun main(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else scope.launch { block() }
    }

    private const val TAG = "Intunix"

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) {
            if (started++ == 0) { foreground.value = true; client?.onForeground() }
        }
        override fun onActivityStopped(a: Activity) {
            if (--started <= 0) { started = 0; foreground.value = false; client?.onBackground() }
        }
        override fun onActivityResumed(a: Activity) { if (a !is IntunixFormActivity) resumed = WeakReference(a) }
        override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}
