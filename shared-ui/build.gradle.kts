import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.plugins.ExtensionAware

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // Declared without applying so the Android DSL types resolve; applied below only when an SDK is configured.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

val hasAndroidSdk = gradle.extra["hasAndroidSdk"] as Boolean
if (hasAndroidSdk) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ArchiveTuneShared"
            isStatic = true
            export(project(":shared-domain"))
            export(project(":shared-data"))
        }
    }

    if (hasAndroidSdk) {
        val androidTarget = (this as ExtensionAware).extensions.getByName("androidLibrary") as KotlinMultiplatformAndroidLibraryExtension
        androidTarget.namespace = "moe.rukamori.archivetune.shared.ui"
        androidTarget.compileSdk = 37
        androidTarget.minSdk = 26
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":shared-domain"))
                api(project(":shared-data"))
                implementation(libs.compose.multiplatform.runtime)
                implementation(libs.compose.multiplatform.foundation)
                api(libs.compose.multiplatform.material3)
                implementation(libs.compose.multiplatform.ui)
                implementation(libs.compose.multiplatform.components.resources)
                implementation(libs.compose.multiplatform.runtime.saveable)
                api(libs.liquid.glass)
                api(libs.haze)
                api(libs.kotlinx.coroutines.core)
                // The host owners take the host's HttpClient; each host supplies its own engine.
                implementation(libs.ktor.client.core)
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("desktopTest").dependencies {
            implementation(libs.coroutines.test)
            implementation(libs.compose.multiplatform.ui.test)
            // The UI test renders through Skia, so it needs the native runtime for the host OS.
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "moe.rukamori.archivetune.shared.ui.resources"
    // The library default (auto) does not generate the Res accessors, so ArchiveTuneRoot would not compile.
    generateResClass = always
    // Host modules (desktopApp, iosApp, app) consume these strings across module boundaries.
    publicResClass = true
}

// Keep complete upstream notices in every platform resource bundle, not just the source checkout.
// They are checked in under src/commonMain/composeResources/files/notices because
// compose.resources.customDirectory replaces a source set's default composeResources dir
// instead of adding to it, so a generated directory would shadow the real resources.


// The screenshot test writes into the ignored repository outputs/ directory. It must run on every
// invocation, because a cached result would skip the capture that the evidence relies on.
tasks.named<Test>("desktopTest") {
    systemProperty(
        "archivetune.screenshotDir",
        rootProject.layout.projectDirectory.dir("outputs/screenshots").asFile.absolutePath,
    )
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
