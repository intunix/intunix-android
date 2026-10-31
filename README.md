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
    implementation("com.github.intunix:intunix-android:1.5.6")
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

### Triggers

Event (`track`), app screen (`screen`, matching exact / contains / regex), user trait
(`identify`), time after the form goes live, and manual (`showForm`). Web-only triggers (page URL,
exit intent) are ignored. A form set to show on **Web** only never shows in apps.

## What is sent

The same endpoints as the web SDK, with channel `mobile_sdk` and the header
`x-intunix-sdk: android/<version>`. Events that fail to upload are kept on the device and
retried for a day. A session ends after 30 minutes in the background.

The SDK keeps its anonymous id and per-form show history in the `intunix` SharedPreferences
file. Android Auto Backup restores it after a reinstall unless your backup rules exclude it.

## Develop

```bash
./gradlew :intunix:testDebugUnitTest      # logic tests (ported from @intunix/core)
./gradlew :intunix:verifyPaparazziDebug   # screenshots of every question type
./gradlew :sample:installDebug            # sample app
```

For the sample app, put `intunix.apiKey=ck_...` and `intunix.form=<slug>` in `local.properties`.
