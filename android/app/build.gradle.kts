plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.littlesavage.homevoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.littlesavage.homevoice"
        minSdk = 33 // Pixel Tablet ships Android 13+
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        release {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            isMinifyEnabled = false
        }
        // Personal sideload build: signed with this machine's existing development
        // key for upgrade compatibility, without debug receivers or tooling.
        create("share") {
            initWith(getByName("release"))
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += "release"
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.onnxruntime.android)
}
