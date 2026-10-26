import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.plugins.ExtensionAware

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Provider models are wire formats; the compiler plugin generates their serializers.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
    // Declared without applying so the Android DSL types resolve; applied below only when an SDK is configured.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

val hasAndroidSdk = gradle.extra["hasAndroidSdk"] as Boolean
if (hasAndroidSdk) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main").cinterops.create("archivetuneAudio") {
            // The definition owns the headers and the moe.rukamori.archivetune.nativeaudio package.
            definitionFile.set(project.file("src/nativeInterop/cinterop/archivetuneAudio.def"))
            includeDirs(rootProject.file("iosApp/Native"), rootProject.file("desktopApp/native/include"))
        }
        target.compilations.getByName("main").cinterops.create("archivetuneTelegram") {
            // The header declares only the at_telegram_client_* wrapper, so TDLib headers are not needed here.
            definitionFile.set(project.file("src/nativeInterop/cinterop/archivetuneTelegram.def"))
            includeDirs(rootProject.file("iosApp/Native"))
        }
    }

    if (hasAndroidSdk) {
        val androidTarget = (this as ExtensionAware).extensions.getByName("androidLibrary") as KotlinMultiplatformAndroidLibraryExtension
        androidTarget.namespace = "moe.rukamori.archivetune.shared.data"
        androidTarget.compileSdk = 37
        androidTarget.minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared-domain"))
            implementation(libs.kotlinx.coroutines.core)
            // Provider clients take an injected HttpClient; the host supplies the engine.
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            // Public database/builder types expose Room to host adapters.
            api(libs.room.runtime)
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            // The desktop YouTube transport binds the retained InnerTube client in :core, which is JVM-only.
            implementation(project(":core"))
        }
        jvmTest.dependencies {
            // Real retained-version writer/reader for synthetic DataStore compatibility/rollback tests.
            implementation("androidx.datastore:datastore-preferences-core:1.2.1")
        }
        // IosChallengeEvaluator runs provider JavaScript on the same pinned quickjs-kt artifact as desktop.
        getByName("iosMain").dependencies {
            implementation(libs.quickjs.kt)
        }
    }
}

dependencies {
    add("kspJvm", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
    if (hasAndroidSdk) add("kspAndroid", libs.room.compiler)
}

ksp {
    arg("room.schemaLocation", "$projectDir/src/commonMain/resources/room-schemas")
}

// Test-only: compile the REAL retained serialization classes, not manually duplicated layouts.
// Their unrelated Android conversion functions resolve against small test-only support types.
val legacyFixtureSources = layout.buildDirectory.dir("generated/legacy-serialization-test")
val prepareLegacyFixtureSources = tasks.register("prepareLegacySerializationTestSources") {
    val models = rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/models")
    val finite = rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/playback/queues/YouTubePlaylistQueue.kt")
    inputs.files(models.resolve("PersistQueue.kt"), models.resolve("MediaMetadata.kt"), finite)
    outputs.dir(legacyFixtureSources)
    doLast {
        val destination = legacyFixtureSources.get().asFile.apply { mkdirs() }
        listOf("PersistQueue.kt", "MediaMetadata.kt").forEach { models.resolve(it).copyTo(destination.resolve(it), overwrite = true) }
        val source = finite.readText()
        val declarations = source.substringAfter("enum class FinitePlaylistPhase").substringBefore("/**")
        destination.resolve("FinitePlaylistSerialization.kt").writeText(
            source.substringBefore("package ") + "package moe.rukamori.archivetune.playback.queues\n\n" +
                "import java.io.Serializable\nimport moe.rukamori.archivetune.models.MediaMetadata\n\n" +
                "enum class FinitePlaylistPhase" + declarations,
        )
    }
}
kotlin.sourceSets.named("jvmTest") { kotlin.srcDir(legacyFixtureSources) }
tasks.matching { it.name == "compileTestKotlinJvm" }.configureEach { dependsOn(prepareLegacyFixtureSources) }

// Reuse an unchanged retained font for valid-font restore tests; no invented/third-party fixture.
val backupFontFixture = tasks.register<Copy>("prepareBackupFontTestResource") {
    from(rootProject.file("app/src/main/res/font/looper_jost_regular.ttf")) { rename { "retained-jost-fixture.ttf" } }
    into(layout.buildDirectory.dir("generated/backup-test-resource"))
}
kotlin.sourceSets.named("jvmTest") { resources.srcDir(backupFontFixture) }
