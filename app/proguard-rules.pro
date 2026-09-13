# Proguard rules for GameTrans Android
-keep class com.gametrans.app.** { *; }

# Google ML Kit (OCR & Translate)
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# OkHttp & Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Coroutines
-dontwarn kotlinx.coroutines.**
