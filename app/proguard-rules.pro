# Shrink only: remove unused code (mostly from the Kotlin stdlib) but keep names and bytecode as written.
-dontobfuscate
-dontoptimize

# Entry points declared in AndroidManifest.xml.
-keep public class * extends android.app.Activity
-keep public class * extends android.accessibilityservice.AccessibilityService

# Views inflated from layout XML by class name.
-keep public class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# The stdlib refers to JDK classes that aren't in android.jar; the code paths that use them aren't called.
-dontwarn kotlin.**
# Compile-time nullability annotations; nothing reads them at runtime.
-dontwarn org.jetbrains.annotations.**
-dontnote
