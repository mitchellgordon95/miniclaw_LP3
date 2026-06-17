import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Read endpoints/secrets from local.properties (gitignored) so the token isn't in source.
// Because it's gitignored, a fresh `git worktree` does NOT inherit it — which silently shipped
// an empty token and caused runtime 403s. So: use the worktree's own copy if present, else fall
// back to the MAIN worktree's copy (resolved via the linked worktree's .git pointer file).
val localProps = Properties().apply {
    val candidates = buildList {
        add(rootProject.file("local.properties"))
        val gitFile = rootProject.file(".git")
        if (gitFile.isFile) {
            // In a linked worktree, .git is a file: "gitdir: <main>/.git/worktrees/<name>".
            val gitdir = gitFile.readText().substringAfter("gitdir:").trim()
            File(gitdir).parentFile?.parentFile?.parentFile // -> <main> repo root
                ?.let { add(File(it, "local.properties")) }
        }
    }
    candidates.firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(key: String, default: String): String = localProps.getProperty(key) ?: default

// Fail loudly at configure time rather than shipping an app that 403s at runtime.
if (secret("miniclaw.token", "").isBlank()) {
    throw GradleException(
        "miniclaw.token is empty — the app would 403 at runtime. Copy local.properties into " +
            "this checkout: cp \"\$(git rev-parse --git-common-dir)/../local.properties\" .",
    )
}

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
    // MediaSessionCompat — receives earbud media-button presses while locked/screen-off.
    implementation("androidx.media:media:1.7.0")
}
