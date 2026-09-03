pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // The SDK's Gradle plugin compiles with a JDK 17 toolchain; this fetches one if the
    // machine only has Android Studio's bundled JDK 21.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // The SDK pulls the LP3 keyboard from JitPack.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("light-sdk/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "light-books"

// Light's SDK is the pristine `light-sdk` submodule; Books is the `tool/` module
// built against it, which is the shape a LightOS tool takes.
val sdkDir = file("light-sdk")
if (!File(sdkDir, "sdk").exists()) {
    error("The light-sdk submodule is empty. Run: git submodule update --init")
}

includeBuild("light-sdk/plugin")

include(":lint-rules")
project(":lint-rules").projectDir = file("light-sdk/lint-rules")

include(":sdk:shared")
project(":sdk").projectDir = file("light-sdk/sdk")
project(":sdk:shared").projectDir = file("light-sdk/sdk/shared")

include(":sdk:ui")
project(":sdk:ui").projectDir = file("light-sdk/sdk/ui")

include(":sdk:client")
project(":sdk:client").projectDir = file("light-sdk/sdk/client")

include(":sdk:server")
project(":sdk:server").projectDir = file("light-sdk/sdk/server")

include(":sdk:emulator")
project(":sdk:emulator").projectDir = file("light-sdk/sdk/emulator")

include(":tool")
