plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "mg.yas.ussd"
    compileSdk = 34
    defaultConfig {
        applicationId = "mg.yas.ussd"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "4.1"
    }
    // Clé debug fixe (dans le dépôt) : chaque build a la même signature, donc Android accepte la mise à jour sans désinstaller.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
