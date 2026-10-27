# R8/ProGuard rules for FAIROS VPN (release builds are shrunk + obfuscated).

# --- Xray core (libv2ray.aar), gomobile bindings and JNI ---
# These are driven via JNI from native code; keep them whole or the VPN core breaks.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep interface libv2ray.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
# Kotlin classes that implement the core's callback interfaces are called from native.
-keep class * implements libv2ray.CoreCallbackHandler { *; }

# --- Networking libs (they also ship their own consumer rules) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Credentials / Google Sign-In ---
-dontwarn com.google.android.libraries.identity.googleid.**

# --- Keep enum values() / valueOf() (used by some libraries via reflection) ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The app parses JSON manually with org.json (no reflection), so model classes need no keeps.
