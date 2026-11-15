# Intunix for Android

Show [Intunix](https://intunix.com) feedback forms in your Android app. Forms look and behave
the same as on the web (the Intunix web SDK 1.5): the same questions, question pages,
conditional questions, icon styles, cadence, targeting and review requests.

- Kotlin, Jetpack Compose, `minSdk 24`
- No third-party dependencies beyond AndroidX, Compose and kotlinx-coroutines

## Install

Add JitPack to `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then the dependency:

```kotlin
dependencies {
    implementation("com.github.intunix:intunix-android:1.7.1")
}
```

## Set up

Call `init` once, in your `Application`:

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Intunix.init(this, "ck_your_client_key", IntunixOptions(registerAll = true))
    }
}
```

| Option | Default | What it does |
|---|---|---|
| `register` | `[]` | Form ids or slugs whose triggers are live |
| `registerAll` | `false` | Load every SDK-enabled form instead of only `register` |
| `screenTriggerDelayMs` | `600` | Wait before a screen-triggered form shows |
| `flushIntervalMs` | `5000` | How often events upload |
| `disableEventApi` | `false` | Keep events on the device (they still fire triggers) |
| `debug` | `false` | Log to Logcat under the tag `Intunix` |
| `apiHost` | `https://api.intunix.com` | API origin |

Forms marked auto-activate in the Intunix console are live without registering.

## API

```kotlin
Intunix.identify("user-42", mapOf("plan" to "pro"))   // on every login
Intunix.reset()                                       // on logout
Intunix.track("checkout_completed", mapOf("total" to 49.0))
Intunix.screen("Checkout")                            // drives "App screen" triggers
Intunix.setContext(mapOf("store" to "dhaka-1"))       // sent with responses; null clears
Intunix.showForm("nps-q4")                            // id or slug; still respects cadence
Intunix.register("nps-q4") / Intunix.unregister("nps-q4")
```

Every call is safe from any thread.

### Where forms appear

| Form type in the console | On Android |
|---|---|
| Modal | Centered dialog |
| Popup, banner | Bottom sheet |
| Inline | Wherever you place `IntunixForm` |

```kotlin
@Composable
fun OrderDone() {
    IntunixForm(form = "post-order", modifier = Modifier.padding(16.dp))
}
```

In a View-based screen (or any activity, including ones that aren't `ComponentActivity`):

```kotlin
val form = IntunixFormView(context).apply {
    setForm("post-order")
    onFinished = { /* hide it */ }
    onHeightChanged = { px -> /* if you size it yourself */ }
}
container.addView(form)
```

Only one form shows at a time; others wait their turn. A form waits for the app to be in the
foreground.

### Dark mode

Forms follow your app with no extra code. A form is dark when the screen it opens
over uses a dark theme, or when the app is in night mode (including an AppCompat
`setDefaultNightMode` override). In the console, each form can be locked to light or
dark, and its dark background colour can be set. Override from code (code wins over
the console):

```kotlin
Intunix.init(this, "int_sk_xxx", IntunixOptions(theme = IntunixTheme.Auto))
Intunix.setTheme(IntunixTheme.Dark)   // any time; open forms switch at once
IntunixForm("nps-q3", theme = IntunixTheme.Light)
```

If your app has only a light theme but still runs in night mode, pass
`IntunixTheme.Light`.

### Languages

A form can be written in several languages (set up in the console). Which one a respondent
sees is chosen automatically, in this order:

1. the language the respondent picked in the form's switcher (remembered on the device);
2. your app's language: the `locale` option, `setLocale()`, or the per-form `locale`;
3. the device's language list;
4. the form's own default language.

A form with two or more languages shows a language switcher; switching keeps the answers.
Right-to-left languages (Arabic, Persian, Hebrew, Urdu) lay out right to left natively.
Responses carry the `language` they were answered in.

```kotlin
Intunix.init(this, "int_sk_xxx", IntunixOptions(locale = "bn"))   // the app's language
Intunix.setLocale("pt-BR")                                        // any time; replaces the remembered choice
IntunixForm("nps-q3", locale = "ar")                              // this form only
```

`setLocale()` replaces the remembered choice, so use it when the user picks a language in
your own settings screen. A form reads its locale when it loads; a form already on screen
keeps its language until it is shown again. `reset()` (on logout) forgets the remembered
language. Limit: a follow-up question asked before a language switch stays in the language
it was asked in.

In the View system, set `IntunixFormView.locale = "ar"` before the form loads.

### Conversational forms

Forms can be AI-enhanced (follow-up questions after an answer) or run as a chat. Both are
set in the console, so there is nothing to code and no change to existing `showForm` or
inline form calls. The assistant's name is set in the console too (default "Nia"). If the AI
is unreachable (offline, or a server error) the form carries on with its own questions. A
conversation resumes after an app restart until it ends.

### Triggers

Event (`track`), app screen (`screen`, matching exact / contains / regex), user trait
(`identify`), time after the form goes live, and manual (`showForm`). Web-only triggers (page URL,
exit intent) are ignored. A form set to show on **Web** only never shows in apps.

## What is sent

The same endpoints as the web SDK, with channel `mobile_sdk` and the header
`x-intunix-sdk: android/<version>`. Events that fail to upload are kept on the device and
retried for a day. A session ends after 30 minutes in the background.
The `language` the respondent answered in is sent with each response. Conversational
and AI-enhanced forms also send `conversation_id` and use `POST /sdk/forms/:id/turn`.

The SDK keeps its anonymous id and per-form show history in the `intunix` SharedPreferences
file. Android Auto Backup restores it after a reinstall unless your backup rules exclude it.

## Develop

```bash
./gradlew :intunix:testDebugUnitTest      # logic tests (ported from @intunix/core)
./gradlew :intunix:verifyPaparazziDebug   # screenshots of every question type
./gradlew :sample:installDebug            # sample app
```

`localize.json` (the shared language fixture) is copied from intunix-sdk; replace it, never edit it.

The sample runs against a mock API (`node examples/languages/server.mjs` in intunix-sdk) with
the `lang-chat`, `lang-popup` and `lang-inline` forms. It reaches your computer at `10.0.2.2`,
which works on the Android emulator only; on a physical device set `intunix.apiHost=http://<your computer's LAN IP>:4100`
in `local.properties`. `intunix.apiKey` can be set there too (default `ck_mock`).
