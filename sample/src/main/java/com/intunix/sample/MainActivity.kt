package com.intunix.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intunix.sdk.Intunix
import com.intunix.sdk.IntunixForm

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        Intunix.screen("Home")
    }
}

@Composable
private fun Screen() {
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF4F4F5)).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("Intunix ${Intunix.VERSION}", style = TextStyle(fontSize = 22.sp))
        Button("Chat with Nia (popup)") { Intunix.showForm("lang-chat") }
        Button("Survey (popup)") { Intunix.showForm("lang-popup") }
        BasicText("App language", style = TextStyle(fontSize = 16.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, tag) in listOf("English" to "en", "বাংলা" to "bn", "العربية" to "ar")) {
                Box(Modifier.weight(1f)) { Button(label) { Intunix.setLocale(tag) } }
            }
        }
        Button("Track \"checkout_completed\"") { Intunix.track("checkout_completed") }
        Button("Identify demo user") { Intunix.identify("demo-user", mapOf("plan" to "pro")) }
        Button("Reset (log out)") { Intunix.reset() }
        BasicText("Inline form", style = TextStyle(fontSize = 16.sp))
        Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp)) {
            IntunixForm("lang-inline")
        }
        BasicText("Inline chat (Arabic)", style = TextStyle(fontSize = 16.sp))
        Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp)) {
            IntunixForm("lang-chat", locale = "ar")
        }
    }
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Color(0xFF2F9461), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp)) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 15.sp))
    }
}
