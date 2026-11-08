plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.dualsub.overlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.dualsub.overlay"
        minSdk = 30          // the TV runs Android 11
        targetSdk = 30
        versionName = "0.4"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        versionCode = 5
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // ML Kit's OCR dies with SIGILL on this TV's 32-bit kernel; Tesseract only
    // needs plain NEON. The openmp build spreads one image over all cores.
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android-openmp:4.8.0")
}
