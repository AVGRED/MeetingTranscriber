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
-keep class net.sqlcipher.** { * }
-keep class net.sqlcipher.database.** { * }

# sherpa-onnx JNI (ASR engine — JNI name resolution depends on exact class/method names)
-dontwarn com.k2fsa.sherpa.onnx.**
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# llama.cpp JNI bridge (LLM engine — native methods resolved by exact name)
-keep class com.example.meetingtranscriber.engine.llm.LlmNative { *; }
-keepclassmembers class com.example.meetingtranscriber.engine.llm.LlmNative { *; }

# llama.cpp C++ standard library (bundled .so)
-keep class com.example.meetingtranscriber.engine.llm.* { *; }
