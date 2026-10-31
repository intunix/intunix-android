package com.intunix.sdk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.intunix.sdk.internal.Answer
import com.intunix.sdk.internal.AnswerValue
import com.intunix.sdk.internal.ExperienceResult
import com.intunix.sdk.internal.RenderCallbacks
import com.intunix.sdk.internal.parseExperience
import com.intunix.sdk.internal.parseFeedbackContent
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Renders each question type at phone width (360dp) the way the bottom sheet frames it. */
class FormScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = 1080, screenHeight = 2600, softButtons = false), maxPercentDifference = 0.1)

    private fun host(steps: String, vararg answers: Answer): FormHost {
        val raw = JSONObject("""{"id":"f","type":"popup","content":{"kind":"feedback","brand":{"color":"#2F9461"},"steps":[$steps]},"triggers":[]}""")
        val exp = parseExperience(raw)!!
        val cb = RenderCallbacks({}, {}, {}, "r", {})
        return FormHost(exp, parseFeedbackContent(exp.content)!!, cb, { true }) { _: ExperienceResult -> }.also { h ->
            answers.forEach { h.onChange(it.stepId, it) }
        }
    }

    private fun shot(h: FormHost) = paparazzi.snapshot {
        Box(Modifier.background(Color.White).padding(20.dp)) { FeedbackForm(h) }
    }

    private fun n(id: String, kind: String, v: Int) = Answer(id, kind, AnswerValue.Num(v))
    private val extra = """,{"id":"z","kind":"nps","title":"Second question"}"""

    @Test fun nps() = shot(host("""{"id":"a","kind":"nps","title":"How likely are you to recommend us to a friend?","subtitle":"0 = not likely, 10 = very likely"}$extra""", n("a", "nps", 9)))
    @Test fun starsAndCes() = shot(host("""{"id":"s","kind":"star","title":"Rate out of 10","max":10,"icons":"ramp","page":1},{"id":"c","kind":"ces","title":"How easy was it?","page":1}$extra""", n("s", "star", 7), n("c", "ces", 6)))
    @Test fun smileyMood() = shot(host("""{"id":"m","kind":"smileys","title":"How do you feel?","icons":"mood","page":1},{"id":"e","kind":"smileys","title":"Emoji smileys","page":1},{"id":"l","kind":"smileys","title":"Line smileys","icons":"line","page":1}$extra""", n("m", "smileys", 4), n("e", "smileys", 2), n("l", "smileys", 5)))
    @Test fun csatPastel() = shot(host("""{"id":"c","kind":"csat","title":"How satisfied are you?","icons":"pastel"}$extra""", n("c", "csat", 4)))
    @Test fun thumbs() = shot(host("""{"id":"t","kind":"thumbs","title":"Was this helpful?","icons":"tone","page":1},{"id":"u","kind":"thumbs","title":"Mark set","icons":"mark","page":1},{"id":"v","kind":"thumbs","title":"Emoji","page":1}$extra""",
        Answer("t", "thumbs", AnswerValue.Choice("up")), Answer("u", "thumbs", AnswerValue.Choice("down")), Answer("v", "thumbs", AnswerValue.Choice("up"))))
    @Test fun choices() = shot(host("""{"id":"p","kind":"pmf","title":"How would you feel if you could no longer use it?","page":1},
        {"id":"o","kind":"followup","title":"Pick all that apply","page":1,"input":{"type":"selectMultiple","options":[{"value":"a","label":"Pricing and plans"},{"value":"b","label":"Onboarding experience was confusing for our whole team"},{"value":"c","label":"Speed","emoji":"⚡"}]}},
        {"id":"f","kind":"followup","title":"Anything else?","page":1,"input":{"type":"free","maxLength":500}}$extra""",
        Answer("p", "pmf", AnswerValue.Choice("somewhat")), Answer("o", "followup", AnswerValue.Many(listOf("b", "c")))))
    @Test fun thanks() = shot(host("""{"id":"t","kind":"thumbs","title":"Was this helpful?"}""", Answer("t", "thumbs", AnswerValue.Choice("up"))))
}
