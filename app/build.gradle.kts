import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release 签名：口令与 keystore 路径放在项目根 keystore.properties（不入库）。
// 文件缺失时 release 构建回退为未签名（保证 CI 与其他机器仍可 assembleDebug）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.classguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.classguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "2.2"

        ndk {
            // 真机为 arm64，模拟器为 x86_64；Android 8+ 设备几乎全是 arm64
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val path = keystoreProps.getProperty("storeFile")
            if (path != null) {
                storeFile = rootProject.file(path)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 模型与 sherpa-onnx 依赖反射/native，关闭混淆避免破坏识别链路
            isShrinkResources = false
            if (keystoreProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // sherpa-onnx 离线语音识别：Kotlin API 源码（com.k2fsa.sherpa.onnx）在源码树中，
    // native so 库在 src/main/jniLibs/{arm64-v8a,x86_64}，全部随 APK 本地打包

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    // 课堂转写（v2.2）：仅本地存储
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    // JVM 单测里使用真实的 org.json（android.jar 里是 stub）
    testImplementation(libs.json)
}
