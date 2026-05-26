plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.quotawatch.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quotawatch.wear"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Wear Compose
    val wearComposeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(wearComposeBom)
    implementation("androidx.wear.compose:compose-material3:1.0.0-alpha29")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Wearable Data Layer
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
