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

        // 火山引擎 / 豆包配置。密钥放在 gradle-local.properties，不提交到 git。
        buildConfigField("String", "VOLCENGINE_ASR_APP_ID", "\"${secret("VOLCENGINE_ASR_APP_ID")}\"")
        buildConfigField("String", "VOLCENGINE_ASR_API_KEY", "\"${secret("VOLCENGINE_ASR_API_KEY")}\"")
        buildConfigField("String", "VOLCENGINE_ASR_ACCESS_TOKEN", "\"${secret("VOLCENGINE_ASR_ACCESS_TOKEN")}\"")
        buildConfigField("String", "VOLCENGINE_ASR_SECRET_KEY", "\"${secret("VOLCENGINE_ASR_SECRET_KEY")}\"")
        buildConfigField("String", "VOLCENGINE_ASR_CLUSTER", "\"${secret("VOLCENGINE_ASR_CLUSTER")}\"")
        buildConfigField("String", "VOLCENGINE_ASR_RESOURCE_ID", "\"${secret("VOLCENGINE_ASR_RESOURCE_ID")}\"")
        buildConfigField("String", "VOLCENGINE_ASR_WS_URL", "\"${secret("VOLCENGINE_ASR_WS_URL")}\"")
        buildConfigField("String", "ARK_API_KEY", "\"${secret("ARK_API_KEY")}\"")
        // 火山方舟推理端点 ID（如 ep-20250101123456-xxxxx）或模型名。优先使用此字段。
        buildConfigField("String", "ARK_ENDPOINT_ID", "\"${secret("ARK_ENDPOINT_ID")}\"")
        // 兼容旧配置：若未设置 ARK_ENDPOINT_ID 则回退到此字段。
        buildConfigField("String", "ARK_MODEL", "\"${secret("ARK_MODEL")}\"")
        buildConfigField("String", "ARK_BASE_URL", "\"${secret("ARK_BASE_URL")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // --- Room 数据库 ---
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // --- 网络 ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- JSON ---
    implementation("com.google.code.gson:gson:2.11.0")

    // --- 加密 ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4@aar")
    implementation("androidx.sqlite:sqlite:2.2.0")

    // --- 协程 ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- 测试 ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
