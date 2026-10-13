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
    // NOTE: the binaries { executable { ... } } block is required — without it
    // Gradle never creates the link*Executable* tasks (first CI run failed on
    // exactly this: "task 'linkReleaseExecutableLinuxX64' not found").
    linuxX64 {
        binaries {
            executable {
                entryPoint = "com.intelliworks.journey.main"
                baseName = "journey-engine"
            }
        }
    }

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

// Local runner for the real-LLM planner (see RunPlanner.kt):
//   ./gradlew :journey-engine:runPlanner
// or via ./scripts/run-planner.sh (loads a gitignored .env file).
// Reads LLM_PROVIDER / LLM_API_KEY / LLM_MODEL from the environment.
tasks.register<JavaExec>("runPlanner") {
    group = "application"
    description = "Plans a demo journey with a real LLM (API key via environment, never committed)."
    val jvmTarget = kotlin.targets.getByName<org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget>("jvm")
    val jvmMain = jvmTarget.compilations.getByName("main")
    mainClass.set("com.intelliworks.journey.RunPlannerKt")
    classpath = jvmMain.runtimeDependencyFiles + jvmMain.output.allOutputs
}
