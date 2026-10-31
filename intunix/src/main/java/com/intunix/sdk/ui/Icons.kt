package com.intunix.sdk.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * The same SVG geometry as @intunix/core icons.ts (faceIconSvg, thumbIconSvg,
 * STAR_PATH, HEART_PATH), drawn with Compose so phones match the web pixel for pixel.
 */
internal object IconSets {
    val FACE_SVG = setOf("mood", "pastel", "line")
    val THUMB_SVG = setOf("line", "tone", "mark")

    const val STAR = "M12 2.5l2.9 6.3 6.9.7-5.2 4.6 1.5 6.8L12 17.3l-6.1 3.6 1.5-6.8L2.2 9.5l6.9-.7z"
    const val HEART = "M12 20.6l-1.4-1.3C5.4 14.6 2 11.5 2 7.7 2 4.6 4.4 2.2 7.5 2.2c1.7 0 3.4.8 4.5 2.1 1.1-1.3 2.8-2.1 4.5-2.1 3.1 0 5.5 2.4 5.5 5.5 0 3.8-3.4 6.9-8.6 11.6z"
    const val THUMB = "M7 10v12M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2a3.13 3.13 0 0 1 3 3.88Z"
    const val CHECK = "M9 16.2l-3.5-3.5L4 14.2l5 5 11-11-1.5-1.5z"

    val VIVID = listOf(0xFFF87171, 0xFFFB923C, 0xFFFACC15, 0xFFA3E635, 0xFF4ADE80).map(::Color)
    val PASTEL = listOf(0xFFFEE2E2, 0xFFFFEDD5, 0xFFFEF9C3, 0xFFECFCCB, 0xFFDCFCE7).map(::Color)
    val DEEP = listOf(0xFFB91C1C, 0xFFC2410C, 0xFFA16207, 0xFF4D7C0F, 0xFF15803D).map(::Color)
    val MOOD_INK = Color(0xFF3F2A14)
    private val MOUTH = listOf("M15 35q9-9 18 0", "M16 34q8-5 16 0", "M16 32h16", "M16 30q8 6 16 0", "M14 28q10 12 20 0z")

    val GREEN = Color(0xFF16A34A)
    val RED = Color(0xFFDC2626)

    private val cache = HashMap<String, Path>()
    fun path(d: String): Path = cache.getOrPut(d) { PathParser().parsePathString(d).toPath() }

    fun mouth(i: Int) = path(MOUTH[i])
    val EYES_HAPPY get() = path("M13 21q4-5 8 0M27 21q4-5 8 0")
    val BROWS get() = path("M12 13l7 3.5M36 13l-7 3.5")

    fun starColors(set: String?, tone: String?): Pair<Color, Color> = when (set) {
        "brand" -> Color.Unspecified to Color.Unspecified
        "heart" -> Color(0xFFF43F5E) to Color(0xFFE11D48)
        "ramp" -> when (tone) {
            "low" -> Color(0xFFEF4444) to Color(0xFFDC2626)
            "mid" -> Color(0xFFF59E0B) to Color(0xFFD97706)
            else -> Color(0xFF22C55E) to Color(0xFF16A34A)
        }
        else -> Color(0xFFFBBF24) to Color(0xFFF59E0B)
    }
}

/** Colour band for the 'ramp' star set: low ≤40% of max, mid ≤60%, else high. */
internal fun starTone(count: Int, max: Int): String? {
    if (count <= 0 || max <= 0) return null
    val r = count.toDouble() / max
    return if (r <= 0.4) "low" else if (r <= 0.6) "mid" else "high"
}

private inline fun DrawScope.inViewBox(box: Float, crossinline block: DrawScope.() -> Unit) {
    val s = size.minDimension / box
    scale(s, s, pivot = Offset.Zero) { block() }
}

/** A face for satisfaction level 1–5 in the mood / pastel / line set. `ink` colours the line set. */
@Composable
internal fun FaceIcon(set: String, level: Int, ink: Color, modifier: Modifier = Modifier) {
    val i = (level - 1).coerceIn(0, 4)
    Canvas(modifier) {
        inViewBox(48f) {
            val (bg, line) = when (set) {
                "mood" -> IconSets.VIVID[i] to IconSets.MOOD_INK
                "pastel" -> IconSets.PASTEL[i] to IconSets.DEEP[i]
                else -> Color.Transparent to ink
            }
            val stroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (set == "line") drawCircle(line, 21.5f, Offset(24f, 24f), style = Stroke(2.5f))
            else drawCircle(bg, 22f, Offset(24f, 24f))
            if (i == 0) drawPath(IconSets.BROWS, line, style = stroke)
            if (i == 4) drawPath(IconSets.EYES_HAPPY, line, style = stroke)
            else { drawCircle(line, 2.8f, Offset(17f, 20f)); drawCircle(line, 2.8f, Offset(31f, 20f)) }
            val mouth = IconSets.mouth(i)
            if (i == 4) drawPath(mouth, line, style = Fill)
            drawPath(mouth, line, style = stroke)
        }
    }
}

/** Thumb in the line / tone / mark set. */
@Composable
internal fun ThumbIcon(set: String, up: Boolean, selected: Boolean, brand: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        inViewBox(24f) {
            val tone = if (up) IconSets.GREEN else IconSets.RED
            if (set == "mark") {
                drawCircle(if (selected) tone else Crisp.Neutral, 10f, Offset(12f, 12f))
                val d = if (up) "M7.5 12.5l3 3 6-6.5" else "M8.5 8.5l7 7M15.5 8.5l-7 7"
                drawPath(IconSets.path(d), Color.White, style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                return@inViewBox
            }
            val color = if (set == "tone") tone else if (selected) brand else Crisp.Muted
            withTransform({ if (!up) scale(1f, -1f, pivot = Offset(12f, 12f)) }) {
                val p = IconSets.path(IconSets.THUMB)
                if (set == "tone" && selected) drawPath(p, if (up) Color(0xFFBBF7D0) else Color(0xFFFECACA))
                drawPath(p, color, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
internal fun StarIcon(path: String, fill: Color, stroke: Color?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        inViewBox(24f) {
            val p = IconSets.path(path)
            drawPath(p, fill)
            if (stroke != null) drawPath(p, stroke, style = Stroke(0.8f, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun CheckIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) { inViewBox(24f) { drawPath(IconSets.path(IconSets.CHECK), color) } }
}
