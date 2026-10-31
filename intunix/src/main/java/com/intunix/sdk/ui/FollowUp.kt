package com.intunix.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intunix.sdk.internal.Answer
import com.intunix.sdk.internal.AnswerValue
import com.intunix.sdk.internal.Step

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

@Composable
internal fun FollowUpStep(step: Step, answer: Answer?, onChange: OnAnswer) {
    val input = step.input ?: return
    when (input.type) {
        "free", "email", "name" -> {
            var text by remember(step.id) { mutableStateOf((answer?.value as? AnswerValue.Text)?.text ?: "") }
            var touched by remember(step.id) { mutableStateOf(false) }
            val invalid = input.type == "email" && touched && text.isNotBlank() && !EMAIL.matches(text.trim())
            Column {
                Field(
                    value = text,
                    placeholder = input.placeholder ?: when (input.type) { "email" -> "you@example.com"; "name" -> "Your name"; else -> "Type your answer…" },
                    multiline = input.type == "free",
                    error = invalid,
                    label = step.title,
                    keyboard = when (input.type) {
                        "email" -> KeyboardOptions(keyboardType = KeyboardType.Email)
                        "name" -> KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                        else -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                    },
                    onBlur = { touched = true },
                ) { v ->
                    val next = if (input.type == "free" && input.maxLength != null) v.take(input.maxLength) else v
                    text = next
                    onChange(Answer(step.id, "followup", AnswerValue.Text(input.type, next)))
                }
                if (input.type == "free" && input.maxLength != null) {
                    BasicText("${text.length}/${input.maxLength}", Modifier.fillMaxWidth().padding(top = 6.dp),
                        style = text(12.sp, color = Crisp.Muted).copy(textAlign = TextAlign.End))
                }
                if (invalid) BasicText("Enter a valid email address.", Modifier.padding(top = 6.dp), style = text(13.sp, color = Crisp.Error))
            }
        }
        "selectOne" -> {
            val selected = (answer?.value as? AnswerValue.One)?.selected
            Options {
                input.options.forEach { o ->
                    OptionRow(o.label, o.emoji, radio = true, checked = selected == o.value) {
                        onChange(Answer(step.id, "followup", AnswerValue.One(o.value)))
                    }
                }
            }
        }
        "selectMultiple" -> {
            val picks = (answer?.value as? AnswerValue.Many)?.selected ?: emptyList()
            val min = input.minPick ?: if (step.required == true) 1 else 0
            val max = input.maxPick
            Column {
                Options {
                    input.options.forEach { o ->
                        val on = o.value in picks
                        val full = max != null && picks.size >= max && !on
                        OptionRow(o.label, o.emoji, radio = false, checked = on, enabled = !full) {
                            val next = if (on) picks - o.value else picks + o.value
                            onChange(Answer(step.id, "followup", AnswerValue.Many(next)))
                        }
                    }
                }
                if (min > 1 && picks.size < min) {
                    BasicText("Pick at least $min.", Modifier.padding(top = 8.dp), style = text(12.5.sp, color = Crisp.Muted))
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    placeholder: String,
    multiline: Boolean,
    error: Boolean,
    label: String,
    keyboard: KeyboardOptions,
    onBlur: () -> Unit,
    onValue: (String) -> Unit,
) {
    val brand = LocalBrand.current
    var focused by remember { mutableStateOf(false) }
    val border = when { error -> Crisp.Error; focused -> brand.color; else -> Crisp.Border }
    BasicTextField(
        value = value,
        onValueChange = onValue,
        singleLine = !multiline,
        keyboardOptions = keyboard,
        textStyle = text(16.sp, lineHeight = 22.sp),
        cursorBrush = SolidColor(brand.color),
        modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = label }
            .onFocusChanged { if (focused && !it.isFocused) onBlur(); focused = it.isFocused },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = if (multiline) 120.dp else 50.dp)
                    .ring(focused, if (error) Crisp.Error.copy(alpha = 0.12f) else brand.soft)
                    .background(Crisp.Card, RoundedCornerShape(14.dp))
                    .border(1.5.dp, border, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) BasicText(placeholder, style = text(16.sp, color = Crisp.Neutral, lineHeight = 22.sp))
                inner()
            }
        },
    )
}
