package com.intunix.sdk.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.intunix.sdk.internal.LanguageInfo
import com.intunix.sdk.internal.languageInfo

/** The language button in the form header: globe, native name, caret; opens a list of the form's languages. */
@Composable
internal fun LanguageSwitcher(current: String, options: List<LanguageInfo>, label: String, onChange: (String) -> Unit) {
    val palette = LocalPalette.current
    val brand = LocalBrand.current
    var open by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val closed = remember { booleanArrayOf(false) }
    val name = languageInfo(current).name
    // Focus stays on the button after a choice (R4); the popup holds it until it is gone.
    LaunchedEffect(open) {
        if (open) closed[0] = true
        else if (closed[0]) runCatching { focus.requestFocus() }
    }
    Box {
        Row(
            Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, palette.border, RoundedCornerShape(10.dp))
                .focusRequester(focus)
                .semantics { contentDescription = "$label, $name"; stateDescription = if (open) "Expanded" else "Collapsed" }
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlobeIcon(palette.muted, Modifier.size(16.dp))
            BasicText(name, style = text(13.sp, FontWeight.Medium, palette.scaleText).copy(localeList = LocaleList(current)))
            CaretIcon(palette.muted, Modifier.size(14.dp))
        }
        if (open) Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, with(LocalDensity.current) { 40.dp.roundToPx() }),
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            Column(Modifier.width(IntrinsicSize.Max).clip(RoundedCornerShape(12.dp)).background(palette.card).border(1.dp, palette.border, RoundedCornerShape(12.dp)).padding(4.dp)) {
                options.forEach {
                    val selected = it.code == current
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { open = false; if (!selected) onChange(it.code) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicText(
                            it.name,
                            style = TextStyle(
                                fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) brand.color else palette.text, localeList = LocaleList(it.code),
                            ),
                        )
                    }
                }
            }
        }
    }
}
