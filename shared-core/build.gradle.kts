import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

// Build opt-in keeps unproven native packaging out of shipped binaries; the persisted runtime flag
// is independently off. Gobley's 0.3.7 Cargo plugin uses the legacy KotlinAndroidTarget/AGP DSL.
val nativeEnabled = providers.gradleProperty("natune.rustCore").orNull == "true"
val hasAndroidSdk = gradle.extra["hasAndroidSdk"] as Boolean
if (hasAndroidSdk) apply(plugin = "com.android.kotlin.multiplatform.library")
val generated = layout.buildDirectory.dir("generated/bindings")
val rustInputs = rootProject.fileTree("native/core") { exclude("target/**", "benchmark-results/**") }
fun nativeTask(name: String, operation: String, vararg arguments: String) = tasks.register<Exec>(name) {
    inputs.files(rustInputs, rootProject.file("scripts/build_rust_core.py"))
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build_rust_core.py", operation, *arguments)
    onlyIf { nativeEnabled }
}
val generateBindings = nativeTask("generateRustBindings", "bindings")
generateBindings.configure { outputs.dir(generated) }
val jvmTarget = providers.gradleProperty("natune.rustJvmTarget").orNull
val jvmLibrary = if (jvmTarget == null) nativeTask("buildRustJvm", "jvm")
    else nativeTask("buildRustJvm", "jvm", "--target", jvmTarget)
jvmLibrary.configure { outputs.dir(layout.buildDirectory.dir("generated/rustResources")) }
val androidLibraries = nativeTask("buildRustAndroid", "android", "--abis",
    providers.gradleProperty("natune.rustAndroidAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64,x86"))
androidLibraries.configure { outputs.dir(layout.buildDirectory.dir("generated/androidJniLibs")) }
val retainedAndroidParser = tasks.register<Copy>("prepareRetainedAndroidTtmlTestSource") {
    from(rootProject.file("lyrics/betterlyrics/src/main/kotlin/moe/koiverse/rukamori/betterlyrics/TTMLParser.kt"))
    into(layout.buildDirectory.dir("generated/retained-android-test"))
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    if (hasAndroidSdk) {
        val androidTarget = (this as ExtensionAware).extensions.getByName("androidLibrary") as KotlinMultiplatformAndroidLibraryExtension
        androidTarget.namespace = "moe.rukamori.archivetune.shared.core"
        androidTarget.compileSdk = 37
        androidTarget.minSdk = 24
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(if (nativeEnabled) "src/enabledMain/kotlin" else "src/disabledMain/kotlin")
            if (nativeEnabled) kotlin.srcDir(generated.map { it.dir("commonMain/kotlin") })
            dependencies {
                implementation(project(":shared-domain"))
                implementation(libs.kotlinx.coroutines.core)
                if (nativeEnabled) implementation(libs.atomicfu)
            }
        }
        commonTest {
            if (nativeEnabled) kotlin.srcDir("src/enabledTest/kotlin")
            dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            }
        }
        if (nativeEnabled) {
            jvmMain {
                kotlin.srcDir(generated.map { it.dir("jvmMain/kotlin") })
                resources.srcDir(layout.buildDirectory.dir("generated/rustResources"))
                dependencies { implementation(libs.jna) }
            }
            nativeMain { kotlin.srcDir(generated.map { it.dir("nativeMain/kotlin") }) }
            jvmTest {
                kotlin.srcDir("src/bridgeJvmTest/kotlin")
                kotlin.srcDir(retainedAndroidParser)
                dependencies { implementation(libs.kotlinx.serialization.json) }
            }
            if (hasAndroidSdk) androidMain {
                kotlin.srcDir(generated.map { it.dir("androidMain/kotlin") })
                dependencies { implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}") { artifact { type = "aar"; extension = "aar" } } }
            }
        }
    }
    if (nativeEnabled) targets.withType<KotlinNativeTarget>().configureEach {
        val rustTarget = if (name == "iosArm64") "aarch64-apple-ios" else "aarch64-apple-ios-sim"
        val buildLibrary = nativeTask("buildRust${name.replaceFirstChar(Char::uppercase)}", "interop", "--target", rustTarget)
        buildLibrary.configure { outputs.file(layout.buildDirectory.file("generated/interop/$rustTarget.def")) }
        compilations.getByName("main").cinterops.create("natune_ffi") {
            definitionFile.set(layout.buildDirectory.file("generated/interop/$rustTarget.def"))
            header(generated.map { it.file("nativeInterop/cinterop/headers/natune_ffi/natune_ffi.h") })
            tasks.named(interopProcessingTaskName) { dependsOn(generateBindings, buildLibrary) }
        }
    }
}

if (nativeEnabled) {
    tasks.matching { it.name == "compileTestKotlinJvm" }.configureEach { dependsOn(retainedAndroidParser) }
    tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach { dependsOn(generateBindings) }
    tasks.matching { it.name == "jvmProcessResources" }.configureEach { dependsOn(jvmLibrary) }
    tasks.withType<Test>().configureEach {
        dependsOn(jvmLibrary)
        systemProperty("natune.repo", rootProject.projectDir.absolutePath)
        testLogging.showStandardStreams = true
    }
}
