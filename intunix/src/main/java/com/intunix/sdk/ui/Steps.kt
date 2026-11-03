package com.intunix.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intunix.sdk.internal.Answer
import com.intunix.sdk.internal.AnswerValue
import com.intunix.sdk.internal.Defaults
import com.intunix.sdk.internal.Step

internal typealias OnAnswer = (Answer?) -> Unit

private fun Answer?.num(): Int? = (this?.value as? AnswerValue.Num)?.v
private fun Answer?.choice(): String? = (this?.value as? AnswerValue.Choice)?.v

@Composable
internal fun StepView(step: Step, answer: Answer?, onChange: OnAnswer) {
    when (step.kind) {
        "nps", "enps", "ces" -> ScaleStep(step, answer, onChange)
        "star" -> StarStep(step, answer, onChange)
        "smileys" -> SmileysStep(step, answer, onChange)
        "thumbs" -> ThumbsStep(step, answer, onChange)
        "csat" -> CsatStep(step, answer, onChange)
        "pmf" -> PmfStep(step, answer, onChange)
        "followup" -> FollowUpStep(step, answer, onChange)
    }
}

@Composable
private fun Legend(low: String, high: String) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        BasicText(low, style = LegendStyle)
        Spacer(Modifier.width(12.dp))
        BasicText(high, style = LegendStyle.copy(textAlign = TextAlign.End))
    }
}

/** NPS / eNPS (0–10; two rows when narrow) and CES (1–5 or 1–7, one row). */
@Composable
private fun ScaleStep(step: Step, answer: Answer?, onChange: OnAnswer) {
    val brand = LocalBrand.current
    val ces = step.kind == "ces"
    val values = if (ces) (1..(step.scale ?: Defaults.CES_SCALE)).toList() else (0..10).toList()
    val defaults = when (step.kind) { "ces" -> Defaults.CES; "enps" -> Defaults.ENPS; else -> Defaults.NPS }
    val perRow = (if (!ces && !LocalWide.current) 6 else values.size).coerceAtLeast(1)
    val selected = answer.num()
    Column {
        values.chunked(perRow).forEachIndexed { r, row ->
            if (r > 0) Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { v ->
                    val on = selected == v
                    Box(
                        Modifier.weight(1f).height(46.dp)
                            .ring(on, brand.soft, radius = 12.dp)
                            .background(if (on) brand.color else Crisp.Card, RoundedCornerShape(12.dp))
                            .border(1.5.dp, if (on) brand.color else Crisp.Border, RoundedCornerShape(12.dp))
                            .tappable(on) { onChange(Answer(step.id, step.kind, AnswerValue.Num(v))) },
                        contentAlignment = Alignment.Center,
                    ) { BasicText("$v", style = text(15.sp, FontWeight.SemiBold, if (on) brand.onColor else Crisp.ScaleText)) }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Legend(step.low ?: defaults.first, step.high ?: defaults.second)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StarStep(step: Step, answer: Answer?, onChange: OnAnswer) {
    val brand = LocalBrand.current
    val max = step.max ?: Defaults.STAR_MAX
    val selected = answer.num() ?: 0
    val size = if (max == 10) 42.dp else 44.dp
    val path = if (step.icons == "heart") IconSets.HEART else IconSets.STAR
    val (fill0, stroke0) = IconSets.starColors(step.icons, starTone(selected, max))
    val fill = if (step.icons == "brand") brand.color else fill0
    val stroke = if (step.icons == "brand") brand.color else stroke0
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            Modifier.then(if (max == 10 && !LocalWide.current) Modifier.widthIn(max = 250.dp) else Modifier.fillMaxWidth()),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (n in 1..max) {
                val filled = n <= selected
                val pop = popScale(selected == n)
                Box(
                    Modifier.size(size)
                        .semantics { contentDescription = "$n of $max" }
                        .tappable(selected == n) { onChange(Answer(step.id, "star", AnswerValue.Num(n))) },
                    contentAlignment = Alignment.Center,
                ) {
                    StarIcon(path, if (filled) fill else Crisp.Border, if (filled) stroke else null,
                        Modifier.size(size).graphicsLayer { scaleX = pop; scaleY = pop })
                }
            }
        }
        val label: AnnotatedString = if (selected > 0) buildAnnotatedString {
            append("$selected ")
            withStyle(SpanStyle(color = Crisp.Muted, fontWeight = FontWeight.Medium)) { append("of $max") }
        } else AnnotatedString("")
        BasicText(label, Modifier.padding(top = 10.dp).heightIn(min = 18.dp), style = text(13.sp, FontWeight.SemiBold))
    }
}

@Composable
private fun SmileysStep(step: Step, answer: Answer?, onChange: OnAnswer) {
    val brand = LocalBrand.current
    val set = step.icons?.takeIf { it in IconSets.FACE_SVG }
    val faces = if (set != null) List(5) { "" } else step.faces?.takeIf { it.isNotEmpty() } ?: Defaults.SMILEYS
    val selected = answer.num()
    val wide = LocalWide.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val room = if (wide) minOf(maxWidth, 400.dp) else maxWidth
        val cell = minOf((room - 4.dp * (faces.size - 1)) / faces.size, 64.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            faces.forEachIndexed { i, face ->
                val level = i + 1
                val on = selected == level
                val scale = popScale(on, rest = 1.1f, from = 0.94f, peak = 1.22f)
                val dim = set != "line" && set != null && selected != null && !on
                val base = Modifier.size(cell).graphicsLayer { scaleX = scale; scaleY = scale }
                    .semantics { contentDescription = "Level $level of ${faces.size}" }
                if (set == null) {
                    Box(
                        base.ring(on, brand.soft, radius = cell / 2)
                            .background(Crisp.Card, CircleShape)
                            .border(1.5.dp, if (on) brand.color else Crisp.Border, CircleShape)
                            .tappable(on) { onChange(Answer(step.id, "smileys", AnswerValue.Num(level))) },
                        contentAlignment = Alignment.Center,
                    ) { BasicText(face, Modifier.grayscale(if (on) 0f else 0.5f), style = text(28.sp)) }
                } else {
                    Box(
                        base.circleRing(on, brand.soft, 4.dp, 5.dp)
                            .circleRing(on, brand.color, 2.dp, 3.dp)
                            .circleRing(on, Crisp.Card, 3.dp)
                            .tappable(on) { onChange(Answer(step.id, "smileys", AnswerValue.Num(level))) },
                    ) {
                        FaceIcon(set, level, if (on) brand.color else Crisp.Neutral,
                            Modifier.size(cell).grayscale(if (dim) 0.8f else 0f).alpha(if (dim) 0.55f else 1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbsStep(step: Step, answer: Answer?, onChange: OnAnswer) {
    val brand = LocalBrand.current
    val set = step.icons?.takeIf { it in IconSets.THUMB_SVG }
    val toned = set == "tone" || set == "mark"
    val selected = answer.choice()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.then(if (LocalWide.current) Modifier.widthIn(max = 320.dp) else Modifier).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for ((value, label) in listOf("up" to "Yes", "down" to "No")) {
                val on = selected == value
                val up = value == "up"
                val accent = if (toned) (if (up) IconSets.GREEN else IconSets.RED) else brand.color
                val tint = if (toned) accent.copy(alpha = 0.15f) else brand.soft
                val palette = LocalPalette.current
                val bg = if (on && toned) (if (up) palette.posBg else palette.negBg) else Crisp.Card
                val labelColor = if (!on) Crisp.Muted else if (toned) (if (up) palette.posFg else palette.negFg) else brand.color
                val pop = popScale(on)
                Column(
                    Modifier.weight(1f).height(84.dp)
                        .ring(on, tint, radius = 16.dp)
                        .background(bg, RoundedCornerShape(16.dp))
                        .border(1.5.dp, if (on) accent else Crisp.Border, RoundedCornerShape(16.dp))
                        .semantics { contentDescription = label }
                        .tappable(on) { onChange(Answer(step.id, "thumbs", AnswerValue.Choice(value))) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    val iconMod = Modifier.graphicsLayer { scaleX = pop; scaleY = pop }
                    if (set == null) BasicText(if (up) "👍" else "👎", iconMod, style = text(30.sp))
                    else ThumbIcon(set, up, on, brand.color, iconMod.size(32.dp))
                    BasicText(label, style = text(12.sp, FontWeight.SemiBold, labelColor))
                }
            }
        }
    }
}

@Composable
private fun CsatStep(step: Step, answer: Answer?, onChange: OnAnswer) {
    val brand = LocalBrand.current
    val labels = step.csatLabels ?: Defaults.CSAT_LABELS
    val set = step.icons?.takeIf { it in IconSets.FACE_SVG }
    val selected = answer.num()
    val wide = LocalWide.current

    @Composable
    fun Icon(level: Int, on: Boolean) {
        val pop = popScale(on)
        val m = Modifier.graphicsLayer { scaleX = pop; scaleY = pop }
        if (set == null) BasicText(Defaults.CSAT_EMOJI[level - 1], m, style = text(22.sp))
        else {
            val dim = set != "line" && selected != null && !on
            FaceIcon(set, level, if (on) brand.color else Crisp.Neutral, m.size(28.dp).grayscale(if (dim) 0.8f else 0f).alpha(if (dim) 0.55f else 1f))
        }
    }

    @Composable
    fun Cell(i: Int, modifier: Modifier, content: @Composable (Boolean) -> Unit) {
        val level = i + 1
        val on = selected == level
        Box(
            modifier.ring(on, brand.soft)
                .background(Crisp.Card, RoundedCornerShape(14.dp))
                .border(1.5.dp, if (on) brand.color else Crisp.Border, RoundedCornerShape(14.dp))
                .tappable(on) { onChange(Answer(step.id, "csat", AnswerValue.Num(level))) },
        ) { content(on) }
    }

    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                Cell(i, Modifier.weight(1f).heightIn(min = 92.dp)) { on ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp).heightIn(min = 68.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
                        Icon(i + 1, on)
                        BasicText(label, style = text(12.5.sp, if (on) FontWeight.SemiBold else FontWeight.Normal).copy(textAlign = TextAlign.Center))
                    }
                }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, label ->
                Cell(i, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { on ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(i + 1, on)
                        BasicText(label, style = text(15.sp, if (on) FontWeight.SemiBold else FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
private fun PmfStep(step: Step, answer: Answer?, onChange: OnAnswer) {
    val selected = answer.choice()
    Options {
        (step.pmfOptions ?: Defaults.PMF).forEach { o ->
            OptionRow(o.label, o.emoji, radio = true, checked = selected == o.value) {
                onChange(Answer(step.id, "pmf", AnswerValue.Choice(o.value)))
            }
        }
    }
}

@Composable
internal fun Options(content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** A choice row: radio or checkbox mark, optional emoji, label. */
@Composable
internal fun OptionRow(label: String, emoji: String?, radio: Boolean, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .ring(checked, brand.soft)
            .background(Crisp.Card, RoundedCornerShape(14.dp))
            .border(1.5.dp, if (checked) brand.color else Crisp.Border, RoundedCornerShape(14.dp))
            .tappable(checked, if (radio) Role.RadioButton else Role.Checkbox, enabled, onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shape = if (radio) CircleShape else RoundedCornerShape(7.dp)
        Box(
            Modifier.size(22.dp)
                .background(if (checked) brand.color else Crisp.Card, shape)
                .border(2.dp, if (checked) brand.color else Crisp.MarkBorder, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked && radio) Box(Modifier.size(10.dp).background(Crisp.Card, CircleShape))
            if (checked && !radio) CheckIcon(Color.White, Modifier.size(15.dp))
        }
        if (emoji != null) BasicText(emoji, style = text(18.sp))
        BasicText(label, Modifier.weight(1f), style = text(15.sp, lineHeight = 20.sp))
    }
}
