import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.devdeck.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devdeck.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-devdeck"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val voskModelZip = file("src/main/assets/vosk/vosk-model-small-en-us-0.15.zip")
tasks.register("downloadVoskModel") {
    outputs.file(voskModelZip)
    doLast {
        if (voskModelZip.exists() && voskModelZip.length() > 1_000_000) return@doLast
        voskModelZip.parentFile.mkdirs()
        val url = URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL()
        url.openStream().use { input ->
            voskModelZip.outputStream().use { output -> input.copyTo(output) }
        }
        if (!voskModelZip.exists() || voskModelZip.length() < 1_000_000) {
            throw GradleException("Vosk model download failed or file is too small: $voskModelZip")
        }
    }
}
tasks.named("preBuild").configure { dependsOn("downloadVoskModel") }

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.8")

    // MediaPipe LLM Inference (The core of DevDeck)
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    // Offline speech-to-text (no network in the recognition path)
    implementation("com.alphacephei:vosk-android:0.3.47")

    // JSON Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Networking for WebSocket (adb reverse tcp:8765)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Text Recognition
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")

    // Security
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
