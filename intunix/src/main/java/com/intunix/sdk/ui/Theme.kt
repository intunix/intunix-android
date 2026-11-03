package com.intunix.sdk.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/** Neutral colours of one theme. Light values are FEEDBACK_CSS's; dark ones come from [darkPalette]. */
internal data class Palette(
    val text: Color, val muted: Color, val border: Color, val scaleText: Color, val track: Color,
    val markBorder: Color, val neutral: Color, val error: Color, val card: Color, val surface: Color,
    val posBg: Color, val negBg: Color, val posFg: Color, val negFg: Color, val dark: Boolean,
)

internal val LightPalette = Palette(
    text = Color(0xFF18181B), muted = Color(0xFF71717A), border = Color(0xFFE4E4E7), scaleText = Color(0xFF3F3F46),
    track = Color(0xFFECECEE), markBorder = Color(0xFFD4D4D8), neutral = Color(0xFFA1A1AA), error = Color(0xFFDC2626),
    card = Color.White, surface = Color(0xFFF4F4F5), posBg = Color(0xFFF0FDF4), negBg = Color(0xFFFEF2F2),
    posFg = Color(0xFF15803D), negFg = Color(0xFFB91C1C), dark = false,
)

const val DEFAULT_DARK_BACKGROUND = "#18181b"

/** Port of darkPalette in @intunix/core: neutrals stepped from the background toward the text colour. */
internal fun darkPalette(background: String?): Palette {
    val bg = background?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) }?.let(::parseHexColor) ?: parseHexColor(DEFAULT_DARK_BACKGROUND)!!
    val lightBg = !whiteReads(bg)
    val ink = if (lightBg) Color.Black else Color.White
    fun step(t: Float) = mix(bg, ink, t)
    return Palette(
        text = if (lightBg) Color(0xFF18181B) else Color(0xFFFAFAFA),
        muted = step(0.62f), border = step(0.16f), scaleText = step(0.82f), track = step(0.13f),
        markBorder = step(0.28f), neutral = step(0.45f),
        error = if (lightBg) Color(0xFFB91C1C) else Color(0xFFF87171),
        card = bg, surface = step(0.07f),
        posBg = Color(0x2E16A34A), negBg = Color(0x2EDC2626),
        posFg = if (lightBg) Color(0xFF15803D) else Color(0xFF4ADE80),
        negFg = if (lightBg) Color(0xFFB91C1C) else Color(0xFFF87171),
        dark = true,
    )
}

/** Brand as-is unless it would vanish on the card (< 3:1); then nudged toward the text colour. */
internal fun accentFor(brand: Color, card: Color): Color {
    if (contrast(brand, card) >= 3f) return brand
    val ink = if (whiteReads(card)) Color.White else Color.Black
    var t = 0.1f
    while (t < 1f) { val c = mix(brand, ink, t); if (contrast(c, card) >= 3f) return c; t += 0.1f }
    return ink
}

internal fun luminance(c: Color): Float {
    fun ch(x: Float) = if (x <= 0.03928f) x / 12.92f else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
}

internal fun contrast(a: Color, b: Color): Float {
    val la = luminance(a); val lb = luminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun whiteReads(c: Color) = (1 + 0.05f) / (luminance(c) + 0.05f) >= 4.5f

private fun mix(a: Color, b: Color, t: Float): Color {
    fun ch(x: Float, y: Float) = (Math.round((x + (y - x) * t) * 255f) / 255f)
    return Color(ch(a.red, b.red), ch(a.green, b.green), ch(a.blue, b.blue))
}

internal val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Colours and sizes from FEEDBACK_CSS in @intunix/core (the Crisp style), for the current theme. */
internal object Crisp {
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Border: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.border
    val ScaleText: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.scaleText
    val Track: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.track
    val MarkBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.markBorder
    val Neutral: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.neutral
    val Error: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.error
    val Card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Backdrop = Color(0x8009090B)
    /** Form width at which layouts switch to their wide form (the web container query). */
    val WideAt = 440.dp
}

internal data class BrandColors(val color: Color, val soft: Color, val ring: Color, val onColor: Color)

internal fun parseHexColor(hex: String?): Color? {
    val s = hex?.trim()?.removePrefix("#") ?: return null
    val full = if (s.length == 3) s.map { "$it$it" }.joinToString("") else s
    if (full.length != 6) return null
    val n = full.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or n)
}

internal fun brandColors(hex: String?, palette: Palette = LightPalette): BrandColors {
    val base = parseHexColor(hex) ?: Color(0xFF2F9461)
    val c = if (palette.dark) accentFor(base, palette.card) else base
    val on = if (whiteReads(c)) Color.White else Color(0xFF111111)
    return BrandColors(c, c.copy(alpha = 0.1f), c.copy(alpha = 0.3f), on)
}

internal val LocalBrand = staticCompositionLocalOf { brandColors(null) }
internal val LocalWide = compositionLocalOf { false }

@Composable
@ReadOnlyComposable
internal fun text(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Crisp.Text, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontSize = size, fontWeight = weight, color = color, lineHeight = lineHeight)

internal val TitleStyle: TextStyle @Composable @ReadOnlyComposable get() = text(18.sp, FontWeight.SemiBold, lineHeight = 24.sp)
internal val SubStyle: TextStyle @Composable @ReadOnlyComposable get() = text(14.sp, color = Crisp.Muted, lineHeight = 21.sp)
internal val LegendStyle: TextStyle @Composable @ReadOnlyComposable get() = text(12.sp, FontWeight.Medium, Crisp.Muted)

/** Answer controls: no ripple, a short press-in on touch (scale .94), radio semantics. */
@Composable
internal fun Modifier.tappable(selected: Boolean, role: Role = Role.RadioButton, enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(120), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .selectable(selected = selected, interactionSource = src, indication = null, enabled = enabled, role = role, onClick = onClick)
}

/** The pop when an icon becomes the chosen one: from → peak → rest over 320ms. Skips the first frame. */
@Composable
internal fun popScale(selected: Boolean, rest: Float = 1f, from: Float = 1f, peak: Float = 1.25f): Float {
    val a = remember { Animatable(if (selected) rest else 1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(selected) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (selected) {
            a.snapTo(from)
            a.animateTo(rest, keyframes { durationMillis = 320; peak at 150 using FastOutSlowInEasing })
        } else a.animateTo(1f, tween(120))
    }
    return a.value
}

/** CSS `box-shadow: 0 0 0 <width> <color>` around a rounded box (drawn outside the bounds). */
internal fun Modifier.ring(show: Boolean, color: Color, width: Dp = 4.dp, radius: Dp = 14.dp, gap: Dp = 0.dp) = drawBehind {
    if (!show) return@drawBehind
    val o = (width + gap).toPx()
    drawRoundRect(color, Offset(-o, -o), Size(size.width + 2 * o, size.height + 2 * o), CornerRadius(radius.toPx() + o))
}

internal fun Modifier.circleRing(show: Boolean, color: Color, width: Dp, gap: Dp = 0.dp) = drawBehind {
    if (!show) return@drawBehind
    drawCircle(color, radius = size.minDimension / 2 + (width + gap).toPx())
}

/** CSS `filter: grayscale(amount)`, drawn through a saturation colour matrix (works on every API level). */
internal fun Modifier.grayscale(amount: Float): Modifier =
    if (amount <= 0f) this
    else drawWithContent {
        val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - amount) }) }
        drawIntoCanvas { c ->
            c.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            c.restore()
        }
    }
