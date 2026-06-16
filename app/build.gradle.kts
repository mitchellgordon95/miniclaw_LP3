import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read endpoints/secrets from local.properties (gitignored) so the token isn't in source.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, default: String): String = localProps.getProperty(key) ?: default

android {
    namespace = "ai.mytextpal.miniclaw"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.mytextpal.miniclaw"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "MINICLAW_BASE_URL", "\"${secret("miniclaw.baseUrl", "https://miniclaw.mytextpal.ai")}\"")
        buildConfigField("String", "MINICLAW_WS_URL", "\"${secret("miniclaw.wsUrl", "wss://miniclaw.mytextpal.ai/ws")}\"")
        buildConfigField("String", "MINICLAW_TOKEN", "\"${secret("miniclaw.token", "")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
