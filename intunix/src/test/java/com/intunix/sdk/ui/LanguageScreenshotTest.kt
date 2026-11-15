package com.intunix.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.intunix.sdk.internal.Answer
import com.intunix.sdk.internal.AnswerValue
import com.intunix.sdk.internal.ChatMessage
import com.intunix.sdk.internal.ConversationConfig
import com.intunix.sdk.internal.ConversationState
import com.intunix.sdk.internal.ExperienceResult
import com.intunix.sdk.internal.FormLanguage
import com.intunix.sdk.internal.LocaleSource
import com.intunix.sdk.internal.RenderCallbacks
import com.intunix.sdk.internal.chatLabels
import com.intunix.sdk.internal.fillStep
import com.intunix.sdk.internal.parseExperience
import com.intunix.sdk.internal.parseStep
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** The popup form of the languages example in Bengali and Arabic, and the chat view. */
class LanguageScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2600, softButtons = false), maxPercentDifference = 0.1)

    private val popup = JSONObject(
        """{"id":"f","type":"popup","triggers":[],"content":{"kind":"feedback","brand":{"color":"#2F9461"},
        "defaultLanguage":"en","languages":["en","bn","ar"],"steps":[
          {"id":"better","kind":"followup","title":"What could we do better?","page":1,"input":{"type":"free"}},
          {"id":"nps","kind":"nps","title":"How likely are you to recommend us?","required":true,"page":2},
          {"id":"csat","kind":"csat","title":"How satisfied are you?","required":true,"page":2}],
        "translations":{
          "bn":{"steps":{"better":{"title":"আমরা আর কী ভালো করতে পারি?"},"nps":{"title":"বন্ধু বা সহকর্মীকে আমাদের কথা আপনি কতটা সুপারিশ করবেন?","labels":{"low":"মোটেই না","high":"অবশ্যই করব"}},
                         "csat":{"title":"আমাদের সেবায় আপনি কতটা সন্তুষ্ট?"}}},
          "ar":{"steps":{"better":{"title":"ماذا يمكننا أن نفعل بشكل أفضل؟"},"nps":{"title":"ما مدى احتمال أن توصي بنا لصديق أو زميل؟","labels":{"low":"غير محتمل إطلاقًا","high":"محتمل جدًا"}},
                         "csat":{"title":"ما مدى رضاك عن خدمتنا؟"}}}}}}""",
    )

    private fun host(lang: String): FormHost {
        val cb = RenderCallbacks({}, {}, {}, "r", {}, LocaleSource({ lang }, { null }, {}))
        return FormHost(parseExperience(popup)!!, cb, { true }, listOf("en")) { _: ExperienceResult -> }.also {
            it.submitPage()
            it.onChange("nps", Answer("nps", "nps", AnswerValue.Num(8)))
        }
    }

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot {
        CompositionLocalProvider(LocalBrand provides brandColors("#2F9461")) {
            Box(Modifier.background(Color.White).padding(20.dp)) { content() }
        }
    }

    @Test fun switcherBengali() = host("bn").let { h -> shot { FeedbackForm(h) } }
    @Test fun rtlArabic() = host("ar").let { h -> shot { FeedbackForm(h) } }

    private fun nia(t: String) = ChatMessage("nia", t)
    private val csat = parseStep(fillStep(JSONObject("""{"id":"csat","kind":"csat","title":"?"}"""), "ar"))!!
    private fun chatState(messages: List<ChatMessage>, input: com.intunix.sdk.internal.Step?, pending: Boolean = false) = ConversationState(
        messages, input, null, answered = 1, total = 3, pending = pending, done = false, endedByRespondent = false, local = false, canSkip = input != null,
    )

    @Test fun chatArabic() {
        val lang = FormLanguage(popup.getJSONObject("content"), LocaleSource({ "ar" }, { null }, {}), listOf("en"))
        val labels = chatLabels(lang.content, ConversationConfig("conversational", "Ava", null, null, null))
        val state = chatState(
            listOf(nia("مرحبًا، أنا Ava. سأطرح عليك ثلاثة أسئلة سريعة."), nia("ما مدى احتمال أن توصي بنا لصديق أو زميل؟"), ChatMessage("respondent", "8"), nia("ما مدى رضاك عن خدمتنا؟")),
            csat,
        )
        shot {
            RtlRoot(lang) {
                ChatView(state, csat, labels, lang.texts(), { LanguageSwitcher(lang.lang, lang.options, "اللغة") {} }, true, {}, {}, {}, {})
            }
        }
    }

    @Test fun chatEnglishTyping() {
        val content = FormLanguage(popup.getJSONObject("content"), LocaleSource({ "en" }, { null }, {}), listOf("en")).content
        val labels = chatLabels(content, ConversationConfig("conversational", "Ava", null, null, null))
        val state = chatState(listOf(nia("Hi, I'm Ava."), ChatMessage("respondent", "9")), null, pending = true)
        shot { ChatView(state, null, labels, null, null, true, {}, {}, {}, {}) }
    }
}
