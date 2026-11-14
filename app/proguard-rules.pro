# Keep Vosk and JNA native bridges intact for Release builds
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# Keep ML Kit Vision & Text Recognition classes
-keep class com.google.mlkit.vision.** { *; }
-dontwarn com.google.mlkit.vision.**

# Keep OkHttp / JSON serialization helpers
-dontwarn okhttp3.**
-dontwarn okio.**
