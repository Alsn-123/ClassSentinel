plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.classguard.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.classguard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        ndk {
            // 真机为 arm64，模拟器为 x86_64；Android 8+ 设备几乎全是 arm64
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    testImplementation(libs.junit)
}
