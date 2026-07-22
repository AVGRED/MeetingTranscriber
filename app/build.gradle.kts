plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 加载 gradle-local.properties（不提交到 git，存放真实密钥）
fun secret(key: String): String {
    (project.findProperty(key) as? String)?.let { return it }
    val file = rootProject.file("gradle-local.properties")
    if (!file.exists()) return ""
    return file.readLines()
        .firstOrNull { it.trimStart().startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim() ?: ""
}

android {
    namespace = "com.example.meetingtranscriber"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.meetingtranscriber"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 阿里云通义听悟配置 (BuildConfig 常量)
        buildConfigField("String", "ALIYUN_ACCESS_KEY_ID", "\"${secret("ALIYUN_ACCESS_KEY_ID")}\"")
        buildConfigField("String", "ALIYUN_ACCESS_KEY_SECRET", "\"${secret("ALIYUN_ACCESS_KEY_SECRET")}\"")
        buildConfigField("String", "ALIYUN_TINGWU_APP_KEY", "\"${secret("ALIYUN_TINGWU_APP_KEY")}\"")
        // DashScope / 通义千问 LLM 摘要 (Phase 2 启用)
        buildConfigField("String", "DASHSCOPE_API_KEY", "\"${secret("DASHSCOPE_API_KEY")}\"")

        // 火山方舟 / 豆包 LLM 配置（Deprecated fallback 仍需要）
        buildConfigField("String", "ARK_API_KEY", "\"${secret("ARK_API_KEY")}\"")
        // 火山方舟推理端点 ID（如 ep-20250101123456-xxxxx）或模型名。优先使用此字段。
        buildConfigField("String", "ARK_ENDPOINT_ID", "\"${secret("ARK_ENDPOINT_ID")}\"")
        // 豆包/火山引擎 ASR 配置
        buildConfigField("String", "VOLCENGINE_ASR_API_KEY", "\"${secret("VOLCENGINE_ASR_API_KEY")}\"")
        buildConfigField("String", "VOLCENGINE_ASR_ACCESS_TOKEN", "\"${secret("VOLCENGINE_ASR_ACCESS_TOKEN")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

}

dependencies {
    // --- AndroidX Core ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // --- Lifecycle ---
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")

    // --- UI ---
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // --- Room 数据库 ---
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // --- 网络 ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- 声纹识别 (CAM++, sherpa-onnx) ---
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // --- JSON ---
    implementation("com.google.code.gson:gson:2.11.0")

    // --- 加密 ---
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4@aar")
    implementation("androidx.sqlite:sqlite:2.2.0")

    // --- 协程 ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // --- 局域网分享 ---
    implementation("com.google.zxing:core:3.5.3")      // 二维码位图生成（纯 Java，无需相机）
    implementation("org.nanohttpd:nanohttpd:2.3.1")    // 局域网 HTTP 服务

    // --- 测试 ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
