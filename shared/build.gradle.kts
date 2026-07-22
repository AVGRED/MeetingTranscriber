plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    jvm("desktop") {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Coroutines
            implementation(libs.kotlinx.coroutines.core)

            // Serialization
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Networking (pure Kotlin — OkHttp 4.x KMP support)
            implementation(libs.okhttp)

            // Logging (replaces android.util.Log)
            implementation(libs.napier)

            // SQLDelight coroutines extension (commonMain)
            implementation(libs.sqldelight.coroutines)

            // Gson / NanoHTTPD / ZXing 是纯 JVM 库，不能放在 commonMain，
            // 移到 androidMain 和 desktopMain 各自声明
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            // Coroutines Android dispatcher
            implementation(libs.kotlinx.coroutines.android)

            // SQLDelight Android driver
            implementation(libs.sqldelight.android.driver)

            // JVM-only libs
            implementation(libs.gson)
            implementation(libs.nanohttpd)
            implementation(libs.zxing.core)
            implementation(libs.okhttp.logging)

            // Android-specific libs
            implementation(libs.androidx.security.crypto)
            implementation(libs.androidx.core.ktx)
            implementation(libs.sherpa.onnx.android)
        }

        val desktopMain by getting {
            dependencies {
                // Coroutines Swing dispatcher
                implementation(libs.kotlinx.coroutines.swing)

                // JNA for native interop (Windows WASAPI fallback, sherpa-onnx)
                implementation(libs.jna)

                // SQLDelight JVM driver (SQLite via JDBC)
                implementation(libs.sqldelight.jvm.driver)

                // JVM-only libs
                implementation(libs.gson)
                implementation(libs.nanohttpd)
                implementation(libs.zxing.core)
                implementation(libs.okhttp.logging)

                // org.json — Desktop JVM 没有内置，需显式添加（Android SDK 自带）
                implementation(libs.org.json)

                // sherpa-onnx: 声纹识别 (JVM 桌面版 — 成员 B)
                implementation(libs.sherpa.onnx.java)
            }
        }
    }
}

android {
    namespace = "com.example.mt.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

sqldelight {
    databases {
        create("MeetingDatabase") {
            packageName.set("com.example.mt.db")
        }
    }
}
