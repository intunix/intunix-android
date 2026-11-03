package com.intunix.sdk.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.intunix.sdk.IntunixTheme
import com.intunix.sdk.internal.Brand

/** The developer's preference ([com.intunix.sdk.Intunix.setTheme]); open forms recompose when it changes. */
internal object ThemeState {
    var preference by mutableStateOf(IntunixTheme.Auto)
}

/** Developer Light/Dark → console lock → what the app shows. */
internal fun resolveDark(pref: IntunixTheme, lock: String?, hostDark: Boolean): Boolean = when (pref) {
    IntunixTheme.Light -> false
    IntunixTheme.Dark -> true
    IntunixTheme.Auto -> when (lock) { "light" -> false; "dark" -> true; else -> hostDark }
}

/**
 * Whether the app screen around the form is dark: its theme is a dark one, or its
 * configuration is in night mode (which includes an AppCompat night-mode override).
 */
internal fun isHostDark(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 29) {
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.isLightTheme, tv, true) && tv.data == 0) return true
    }
    return (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** Provides the light or dark palette for a form. [hostContext] is the app screen the form sits on. */
@Composable
internal fun FormTheme(brand: Brand, theme: IntunixTheme? = null, hostContext: Context? = null, body: @Composable () -> Unit) {
    val ctx = hostContext ?: LocalContext.current
    val uiMode = LocalConfiguration.current.uiMode
    val hostDark = remember(ctx, uiMode) { isHostDark(ctx) }
    val dark = resolveDark(theme ?: ThemeState.preference, brand.darkMode, hostDark)
    val palette = remember(dark, brand.darkBackground) { if (dark) darkPalette(brand.darkBackground) else LightPalette }
    CompositionLocalProvider(LocalPalette provides palette) { body() }
}
