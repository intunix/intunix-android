package com.intunix.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
    val form = BuildConfig.FORM
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF4F4F5)).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText("Intunix ${Intunix.VERSION}", style = TextStyle(fontSize = 22.sp))
        if (form.isEmpty()) BasicText("Set intunix.apiKey and intunix.form in local.properties.", style = TextStyle(color = Color.Gray))
        Button("Show form as a popup") { Intunix.showForm(form) }
        Button("Track \"checkout_completed\"") { Intunix.track("checkout_completed") }
        Button("Identify demo user") { Intunix.identify("demo-user", mapOf("plan" to "pro")) }
        Button("Reset (log out)") { Intunix.reset() }
        BasicText("Inline form", style = TextStyle(fontSize = 16.sp))
        Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp)) {
            if (form.isNotEmpty()) IntunixForm(form)
        }
    }
}

@Composable
private fun Button(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(Color(0xFF2F9461), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp)) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 15.sp))
    }
}
