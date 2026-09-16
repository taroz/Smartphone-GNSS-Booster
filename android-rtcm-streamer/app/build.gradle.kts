plugins {
    id("com.android.application")
    // AGP 9.x has built-in Kotlin support (the org.jetbrains.kotlin.android plugin is not needed).
    // The Compose compiler is enabled via its dedicated plugin (the Kotlin 2.x way).
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.furo.rtcmstreamer"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.furo.rtcmstreamer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // RTKLIB (C) is cross-compiled for arm64 only (Pixel 7 Pro = arm64-v8a).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Back end: build RTKLIB's gen_rtcm3() with NDK + CMake and embed it via JNI.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    // The installed platform is android-36, so pin to the 2025-era libraries that are
    // compatible with compileSdk 36 (2026-era libraries require compileSdk 37).
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose")
    implementation("androidx.lifecycle:lifecycle-runtime-compose")

    testImplementation("junit:junit:4.13.2")
}
