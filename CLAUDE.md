# Word Peek: notes for Claude

Android app (package `com.wordpeek`). When you select text in any app, it shows a
definition, a Wikipedia summary and a translation in a bottom panel. Kotlin, framework
APIs only (no AndroidX), min SDK 23 (Android 6.0), target SDK 37 (Android 17).
README.md has the user-facing description and the architecture table. This file is the
working state: history, what is and isn't verified, and what's left to do.

## History you need to know

- The project started as "TapText" (`com.taptext.overlay`) in the public repo
  `badgerkin/TapText`, branch `claude/pixel-9-pro-apk-build-2rzjh3`. It was renamed to Word
  Peek, with a new repo for a clean history.
- TapText's debug signing key was committed to that public repo, so treat it as
  compromised. **Never use it for Word Peek.** Word Peek is a new app ID, so it starts from
  a fresh private key. The owner generates it; it must never be committed or pass through
  a Claude session. `.gitignore` excludes `*.jks`, `*.keystore` and `keystore.properties`.
- When moving files into the new repo, leave out `keystore/`, `dist/` (old TapText APKs)
  and `build/`. Releases go to GitHub Releases, not into git.
- The first sessions ran with Google's hosts blocked (dl.google.com, maven.google.com), so
  there was no Android SDK, Gradle/AGP, emulator or AndroidX. They built with
  `tools/build-apk.sh` (aapt2, kotlinc, ProGuard, dx, apksigner by hand). Later sessions
  have open network access, and the script was deleted once Gradle worked: the owner builds
  and signs locally with Gradle and doesn't want to depend on GitHub Actions for either.
  Code that uses `java.util.regex.Pattern` instead of Kotlin's `Regex`/`split` dates from
  the dx restriction; either is fine now.
- The owner tests on a Pixel 9 Pro running Android 17. Don't name the device in user
  docs: the app has no hardware requirements.
- Owner's writing preferences: plain, direct prose. Avoid AI-sounding filler (the user
  prompt lists banned words and patterns). Sentence-case headings.

## Verified vs not verified

Verified on the owner's phone (Android 17), with the TapText 1.2.0 build:
- Instant pop-up (the accessibility service) triggers on long-press in the Google app.
- Wikipedia and MyMemory translation lookups work.
- dictionaryapi.dev failed with an exception (cause unknown), which is why Wiktionary is now
  the primary definition source.

Only checked by building and static inspection (never run):
- The Wiktionary lookup and the "form of" link to the base word (`Lookup.wiktionary`,
  `FORM_OF_LINK`). The response format was written from memory of the REST API.
- Everything from 1.2.1 onward: Wiktionary, the HTTP error messages, Android 6 support, the
  rename.
- Android 6/7 behaviour. ProGuard was run against the API 23 android.jar: the only missing
  framework references are behind `SDK_INT` checks.
- The R8-minified release APK has never been installed, so `app/proguard-rules.pro` under
  R8 is untested at runtime (it builds; all app classes survive).

Verified by building: `./gradlew assembleDebug assembleRelease lint` passes with zero lint
issues (AGP 9.4.1, Kotlin 2.4.20, Gradle 9.8.0, JDK 21, platform android-37.0).
Signing was checked with a throwaway key: with the `WORDPEEK_KEYSTORE*` variables set,
`assembleRelease` produces `app-release.apk` with v1 and v2 signatures (`apksigner verify
--min-sdk-version 23`); without them, `app-release-unsigned.apk`. `WORDPEEK_KEY_PASSWORD`
falls back to the keystore password. Without that fallback, AGP reports the misleading
"Keystore file not set".

## Gradle build

AGP 9 compiles Kotlin itself ("built-in Kotlin"), so there's no `kotlin-android` plugin in
`app/build.gradle.kts` and no `kotlinOptions`; the JVM target follows `compileOptions`. The
root `build.gradle.kts` lists `org.jetbrains.kotlin.android` with `apply false` only to raise
the Kotlin version above the one AGP bundles. The SDK path comes from `ANDROID_HOME` or an
untracked `local.properties`.

Cloud sessions: Maven Central answers 429 (Too Many Requests) through the session proxy after
a few builds. A user-level init script that adds Google's mirror
(`https://maven-central.storage-download.googleapis.com/maven2/`) to `pluginManagement` and
`dependencyResolutionManagement` fixes it. Keep that in `~/.gradle/init.d/`, never in the repo.

## To do, in order

1. ~~**Set up the new repo.**~~ Done: `badgerkin/word-peek` holds this tree minus
   `keystore/`, `dist/` and `build/`, on top of the owner's initial commit (MIT licence).
2. ~~**Get a real toolchain.**~~ Done (see "Gradle build"). Lint found one real bug: the
   buttons used `Widget.DeviceDefault.Button.Borderless.Colored`, which only exists from API
   28, so they lost their style on Android 6/7. They now use `@style/TextButton` (Material
   below 28, DeviceDefault from 28 via `values-v28`). `tools/build-apk.sh` was deleted.
   `app/proguard-rules.pro` still has `-dontobfuscate -dontoptimize` from the ProGuard days;
   consider dropping them once a release build has been tested on a device.
3. **GitHub Actions release workflow.** A convenience, not the only way to release: local
   `./gradlew assembleRelease` with the `WORDPEEK_KEYSTORE*` variables must keep working.
   On a `v*` tag: build the release APK and sign it
   from repo secrets (keystore base64 plus the three passwords/alias). Attach it to a
   GitHub Release, and derive versionCode/versionName from the tag. Also run build and
   lint on every PR.
4. **Test the lookups against the live APIs.** Save real responses as fixtures and write
   JVM unit tests for the parsing. Things to check:
   - Wiktionary `/api/rest_v1/page/definition/{word}`: the shape of the `en` array, the
     HTML in `definition`, and whether `FORM_OF_LINK` matches (try "quantized", "ran",
     "mice").
   - **Wikipedia disambiguation pages.** The owner's screenshot showed "The term
     quantization may refer to:" as the summary. Detect `"type": "disambiguation"` in the
     summary response. Then either try a better title (e.g. the base word) or show a
     short "Several meanings: …" line instead of the dangling sentence.
   - MyMemory `langpair=Autodetect|xx`: check that "Autodetect" is honoured. It sometimes
     returns warning text as the translation (e.g. "PLEASE SELECT TWO DISTINCT
     LANGUAGES", or a quota message); filter those. The anonymous quota is 5000
     chars/day, and the `de=email` parameter raises it.
   - The app's `Locale.language` codes are passed to Wikipedia. Old codes `iw`, `in`, `ji`
     need mapping to `he`, `id`, `yi`. Chinese needs a `zh` wiki and MyMemory `zh-CN`.
5. **Emulator testing.** Check for `/dev/kvm`. If it exists, run API 23, 26, 30 and 37
   AVDs and exercise: the home screen, enabling the service, a long-press in a test app
   with a TextView and an EditText, the menu fallback, Replace, and edge-to-edge insets.
   Without KVM, use Robolectric for activities and the service's selection logic
   (`selectedText`, `isLookupSized`). Replace the README renders with real screenshots.
6. **Instant pop-up coverage.** Find out which apps send
   `TYPE_VIEW_TEXT_SELECTION_CHANGED` with usable text. Candidates: Chrome web content,
   Firefox, Kindle, WhatsApp, Reddit, Gmail. If Chrome doesn't send it, look at reading
   the selection from the focused node's `textSelectionStart/End`, or
   `AccessibilityNodeInfo.getTextSelectionStart()` on `TYPE_VIEW_SELECTED` or
   `TYPE_WINDOW_CONTENT_CHANGED`.
7. **Android 6/7 certificates.** Android < 7.1.1 doesn't trust ISRG Root X1 (Let's
   Encrypt). Check each lookup host's certificate chain. If any use Let's Encrypt, bundle
   the root: `network_security_config` (API 24+), and a custom TrustManager for API 23.
8. **Accessibility of the app itself.** The overlay window is `FLAG_NOT_FOCUSABLE`, so
   check that TalkBack can reach the panel. Add content descriptions, and make the
   language list items proper buttons.
9. **UI polish.**
   - Follow the system light/dark theme: the panel is always dark now. Use dynamic
     colour on API 31+.
   - Loading placeholders.
   - The inline language list is 13 rows long; a grid or a compact picker would fit better.
   - Consider an "English → English" option to hide translation.
   - Cache recent lookups in memory.
10. **Store readiness, if the owner wants it.**
    - Privacy policy page.
    - Play's AccessibilityService policy: prominent disclosure and consent screen before
      sending the user to settings, plus the Play Console declaration. Expect review risk:
      it isn't an accessibility tool.
    - F-Droid: needs a FOSS licence and a reproducible Gradle build, and gets the
      NonFreeNet anti-feature.
    - The icon is a placeholder ("W" in a speech bubble).

## Conventions

- Keep the app dependency-free unless there's a strong reason. Small APK and fewer
  permissions are selling points.
- Every user-visible string goes in `res/values/strings.xml`.
- Bump `versionCode` and `versionName` in `app/build.gradle.kts` (until the workflow
  derives them from tags).
- README images come from `docs/images/screens.html` (`render.js`), and the Android 6–7
  PNG icons from `docs/images/render-icons.js`. Update them when the UI or icon changes.
