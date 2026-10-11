plugins {
    kotlin("multiplatform") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

kotlin {
    // Server-side only for now; add android(), iosX64(), etc. later without
    // touching commonMain.
    jvm()
    // Native executable for the Docker image (see Dockerfile):
    // ./gradlew :journey-engine:linkReleaseExecutableLinuxX64
    linuxX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}
