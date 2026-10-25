plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.demo.imsdc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.demo.imsdc"
        minSdk = 26          // Android 8.0+ (DTLS/SCTP support)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        manifestPlaceholders["mainPermissionName"] = "com.demo.imsdc.main_permission"
        manifestPlaceholders["getFeaturePermissionName"] = "com.demo.imsdc.GET_FEATURE"
        manifestPlaceholders["fileProviderAuthority"] = "com.demo.imsdc.fileprovider"
        manifestPlaceholders["sdkProviderAuthority"] = "com.demo.imsdc.provider"
        manifestPlaceholders["providerPermissionName"] = "com.demo.imsdc.provider.PERMISSION"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Cho phép cleartext HTTP (SIP server trong LAN)
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // ─── Android core ────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // ─── WebRTC Android (Google's official build) ────────────────
    // Chỉ dùng DataChannel, không cần Audio/Video
    implementation("io.github.webrtc-sdk:android:104.5112.09")

    // ─── GSMA IMS Data Channel SDK ──────────────────────────────
    implementation(project(":ims-dc-sdk"))

    // ─── SIP via Android SIP API (JAIN-SIP wrapped) ─────────────
    // Dùng Android built-in SIP API (android.net.sip) — có từ API 9
    // Hoặc dùng MjSip / JAIN-SIP trực tiếp cho control tốt hơn
    // PoC dùng raw UDP socket + manual SIP parsing để tránh dependency phức tạp
    // (Không cần thêm dependency, viết SIP stack tối giản bằng Kotlin)

    // ─── Coroutines ──────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ─── JSON ────────────────────────────────────────────────────
    implementation("org.json:json:20231013")

    // Desugaring dependency
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.2.2")
}
