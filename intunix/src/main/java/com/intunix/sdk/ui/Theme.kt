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

/** Colours and sizes from FEEDBACK_CSS in @intunix/core (the Crisp style). */
internal object Crisp {
    val Text = Color(0xFF18181B)
    val Muted = Color(0xFF71717A)
    val Border = Color(0xFFE4E4E7)
    val ScaleText = Color(0xFF3F3F46)
    val Track = Color(0xFFECECEE)
    val MarkBorder = Color(0xFFD4D4D8)
    val Neutral = Color(0xFFA1A1AA)
    val Error = Color(0xFFDC2626)
    val Card = Color.White
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

internal fun brandColors(hex: String?): BrandColors {
    val c = parseHexColor(hex) ?: Color(0xFF2F9461)
    fun ch(x: Float) = if (x <= 0.03928f) x / 12.92f else ((x + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    val lum = 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    val on = if ((1 + 0.05f) / (lum + 0.05f) >= 4.5f) Color.White else Color(0xFF111111)
    return BrandColors(c, c.copy(alpha = 0.1f), c.copy(alpha = 0.3f), on)
}

internal val LocalBrand = staticCompositionLocalOf { brandColors(null) }
internal val LocalWide = compositionLocalOf { false }

internal fun text(size: TextUnit, weight: FontWeight = FontWeight.Normal, color: Color = Crisp.Text, lineHeight: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontSize = size, fontWeight = weight, color = color, lineHeight = lineHeight)

internal val TitleStyle = text(18.sp, FontWeight.SemiBold, lineHeight = 24.sp)
internal val SubStyle = text(14.sp, color = Crisp.Muted, lineHeight = 21.sp)
internal val LegendStyle = text(12.sp, FontWeight.Medium, Crisp.Muted)

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
