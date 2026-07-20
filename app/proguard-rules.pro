# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# sherpa-onnx JNI (ASR engine — JNI name resolution depends on exact class/method names)
-dontwarn com.k2fsa.sherpa.onnx.**
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# NanoHTTPD (局域网分享)
-keep class org.nanohttpd.** { *; }
-dontwarn org.nanohttpd.**

# ZXing (二维码生成)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# AndroidX Security Crypto
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# 剥离 release 热路径调试日志（interim decode 每 250ms、VAD 状态翻转、逐句累计
# 等 Log.d 在 release 存活是无谓开销）。只剥 v/d，保留 i/w/e 供现场诊断
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
