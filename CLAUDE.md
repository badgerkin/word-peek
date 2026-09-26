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
  there was no Android SDK, Gradle/AGP, emulator or AndroidX. `tools/build-apk.sh` is the
  workaround built for that (details below). The owner is moving to a session with
  unrestricted network access to fix this properly.
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
- The Gradle build has **never** been run. The AGP pinned in `build.gradle` (8.2.2) is too
  old for compileSdk 37, and there's no Gradle wrapper.
- The release build's `isMinifyEnabled = true` with `app/proguard-rules.pro` under R8.

## Build today (workaround path)

`tools/build-apk.sh`: aapt2 → kotlinc → ProGuard (shrink only) → dx → zipalign →
apksigner, using the Ubuntu packages `aapt apksigner zipalign dalvik-exchange`, kotlinc 2.2
from GitHub, ProGuard 7.10 jars from Maven Central, and platform jars from
`raw.githubusercontent.com/Sable/android-platforms/master/android-NN/android.jar`.
Quirks, all consequences of the old toolchain:
- Ubuntu's aapt2 can't read the API 36+ resource table, so resources link against API 34
  (`LINK_JAR`). The APK manifest therefore says `compileSdkVersion=34`. That's cosmetic.
- dx can't handle `invokedynamic` below min SDK 26. So kotlinc uses
  `-Xlambdas=class -Xsam-conversions=class`, ProGuard strips the unused stdlib, and the code
  avoids `kotlin.text.Regex` and `String.split` (they pull in stdlib indy lambdas). It uses
  `java.util.regex.Pattern` instead. If dx fails with "invokedynamic requires
  --min-sdk-version >= 26", a newly used stdlib function is the cause.
- Signing uses the `WORDPEEK_KEYSTORE*` environment variables, the same ones Gradle reads.

## To do, in order

1. ~~**Set up the new repo.**~~ Done: `badgerkin/word-peek` holds this tree minus
   `keystore/`, `dist/` and `build/`, on top of the owner's initial commit (MIT licence).
2. **Get a real toolchain.** With open network: install Android cmdline-tools, platform 37,
   build-tools, and a JDK 17/21. Update AGP and the Kotlin plugin to current releases, and
   add the Gradle wrapper. Make `./gradlew assembleDebug assembleRelease` pass.
   - Kotlin 2.x with AGP may use `kotlin { jvmToolchain(17) }` / `compilerOptions` instead
     of the deprecated `kotlinOptions`.
   - D8 desugars lambdas, so the Regex/split restriction only applies to the dx path. Once
     Gradle works, decide whether to keep `tools/build-apk.sh` as a fallback or delete it.
     If it's deleted, the Pattern-based code can stay; it's fine either way.
   - Run Android lint (`./gradlew lint`) and fix what it reports, especially NewApi.
3. **GitHub Actions release workflow.** On a `v*` tag: build the release APK and sign it
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
- Bump `versionCode` and `versionName` in both `app/build.gradle.kts` and
  `tools/build-apk.sh` (until the workflow derives them from tags).
- README images come from `docs/images/screens.html` (`render.js`), and the Android 6–7
  PNG icons from `docs/images/render-icons.js`. Update them when the UI or icon changes.
