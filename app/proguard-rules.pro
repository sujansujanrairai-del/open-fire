# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

-keep class com.blindtechnexus.openfire.** { *; }
-dontwarn com.blindtechnexus.openfire.**

# Keep Compose runtime & UI essentials
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**


# ---------------------------------------------------------------------------
# Native audio engine
# ---------------------------------------------------------------------------
# The JNI bridge binds every method through RegisterNatives against the exact
# class and method names of OpenFireAudioEngine, and it constructs
# NativeStreamInfo with one fixed constructor. Neither may be renamed or
# removed, even though nothing in Kotlin calls them directly.
-keep class com.blindtechnexus.openfire.audio.native.OpenFireAudioEngine { *; }
-keep class com.blindtechnexus.openfire.audio.native.NativeStreamInfo { *; }
# The constructor is covered by the `{ *; }` above: a member specification is
# written in Java syntax, not in JVM descriptors, so spelling it out here with
# `(IIIIZIII)V` made R8 reject the whole rules file.
-dontwarn com.blindtechnexus.openfire.audio.native.**
