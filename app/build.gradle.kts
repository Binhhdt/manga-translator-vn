plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "vn.mangatranslate.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.mangatranslate.app"
        minSdk = 26          // Android 8.0
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-giai-doan-1"
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Jetpack Compose (phiên bản do BOM quản lý)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // ML Kit OCR v2 — mô hình đóng gói sẵn trong APK, chạy offline ngay
    implementation("com.google.mlkit:text-recognition:16.0.1")          // Latin (Anh)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")  // Trung giản thể + phồn thể
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1") // Nhật
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")   // Hàn

    // ML Kit dịch trên thiết bị — mô hình ~30 MB/ngôn ngữ, tải một lần rồi dùng offline
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation("junit:junit:4.13.2")
}
