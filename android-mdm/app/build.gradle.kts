plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mqttai.mdm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mqttai.mdm"
        minSdk = 26
        targetSdk = 28 // target pre-Q to allow WifiManager.setWifiEnabled()
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        aidl = true
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
    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JSON
    implementation("org.json:json:20240303")

    // Android core
    implementation("androidx.core:core-ktx:1.15.0")
}
