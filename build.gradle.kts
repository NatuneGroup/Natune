plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.aboutlibraries.android) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
    tasks.register("prepareKotlinBuildScriptModel") {}
}

subprojects {
    if (tasks.findByName("prepareKotlinBuildScriptModel") == null) {
        tasks.register("prepareKotlinBuildScriptModel") {}
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            if (project.findProperty("enableComposeCompilerReports") == "true") {
                arrayOf("reports", "metrics").forEach {
                    freeCompilerArgs.add("-P")
                    freeCompilerArgs.add(
                        "plugin:androidx.compose.compiler.plugins.kotlin:${it}Destination=${project.layout.buildDirectory}/compose_metrics",
                    )
                }
            }
        }
    }
}

// Force Gradle to fetch fresh SNAPSHOTs instead of turning off the build cache
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            cacheChangingModulesFor(0, "seconds")
        }
    }
}

// A forked test worker that leaves a non-daemon thread, a listening socket or a child process behind never exits, and
// the owning Test task then waits for it forever: on run 42267860785 `:shared-data:jvmTest` produced no report and no
// output for 32 minutes after the Device Connect suites, until the job's 45-minute limit cancelled a run in which no
// test had failed. A task-level timeout turns that silence into a named failure in minutes, and it is the only guard
// that survives a worker the tests themselves cannot interrupt.
//
// The `:shared-*` suites are model and Compose-through-Skia checks with no display, so their workers are told that
// explicitly instead of inheriting whatever toolkit detection the runner happens to do.
val sharedSuiteTimeoutMinutes = providers.gradleProperty("sharedTestTimeoutMinutes").map(String::toLong).orElse(20L)
val suiteTimeoutMinutes = providers.gradleProperty("testTimeoutMinutes").map(String::toLong).orElse(60L)
subprojects {
    tasks.withType<Test>().configureEach {
        val shared = project.path.startsWith(":shared-")
        timeout.set(java.time.Duration.ofMinutes((if (shared) sharedSuiteTimeoutMinutes else suiteTimeoutMinutes).get()))
        if (shared) {
            systemProperty("java.awt.headless", "true")
        }
    }
}
