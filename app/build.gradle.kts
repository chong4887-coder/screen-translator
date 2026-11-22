plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screentrans.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screentrans.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
        // 요즘 폰(64비트 ARM)용만 넣어 용량 줄이기
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // 고정된 서명 키: 새 버전을 지우지 않고 덮어 설치(업데이트)할 수 있게
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // 폰 안에서 글자 찾기 (일본어 인식기는 영어도 함께 읽음)
    // 인식 모델은 앱에 넣지 않고 구글 Play 서비스가 폰에 받아 둠 (앱 용량 대폭 감소)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    // 무료 폰 내장 번역 (일본어/영어 → 한국어)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
