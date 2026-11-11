import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appVersionName = "0.2.6"
val appVersionCode = 12

// Version name: MAJOR.MINOR.PATCH – feature → MINOR+1 (0.3.0), bug fix → PATCH+1 (0.2.1).
// Version code: +1 for every build you install; never lower it, or Android refuses the update.
// APK file name: PlainPlayer-<version>-<buildType>.apk, e.g. PlainPlayer-0.2.0-debug.apk
base {
    archivesName.set("PlainPlayer-$appVersionName")
}

// Release signing. Android installs an update only if it is signed with the same key as the installed
// app, so every release must use ONE permanent key. The key is never part of the repository:
// - on your PC it is read from `keystore.properties` in the project root (ignored by git),
// - on GitHub Actions from repository secrets (environment variables).
// Without either, `assembleRelease` still works but produces an unsigned APK.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(environment)

android {
    namespace = "de.plainplayer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.plainplayer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "PLAINPLAYER_KEYSTORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = signingValue("storePassword", "PLAINPLAYER_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PLAINPLAYER_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PLAINPLAYER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else null
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-extractor:1.5.1")

    // JVM unit tests for the library rules (run with ./gradlew test)
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
