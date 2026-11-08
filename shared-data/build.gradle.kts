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
        target.compilations.getByName("main").cinterops.create("archivetuneGraph") {
            // Typed Apple objects keep playback graph mutations inside the Objective-C exception boundary.
            definitionFile.set(project.file("src/nativeInterop/cinterop/archivetuneGraph.def"))
            includeDirs(rootProject.file("iosApp/Native"))
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
        androidTarget.minSdk = 24
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared-domain"))
            implementation(libs.kotlinx.coroutines.core)
            // Provider clients take an injected HttpClient; the host supplies the engine.
            implementation(libs.ktor.client.core)
            // Device Connect hosts one local WebSocket server and dials the peer over the same route.
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
            implementation(libs.kotlinx.serialization.json)
            // Public database/builder types expose Room to host adapters.
            api(libs.room.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            // The desktop YouTube transport binds the retained InnerTube client in :core, which is JVM-only.
            implementation(project(":core"))
            // Bundled SQLite (~0.6 MB native/ABI) stays off Android; only hosts that open the portable DB pull it.
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
            // TidalDns ports the original's OkHttp bootstrap-resolver + DoH client verbatim.
            implementation(libs.okhttp)
            // Device Connect discovery on the desktop uses JmDNS (Apache-2.0); see docs/CREDITS.md.
            implementation(libs.jmdns)
            // The desktop BotGuard runtime shares the pinned quickjs-kt artifact iosMain already uses.
            implementation(libs.quickjs.kt)
        }
        if (hasAndroidSdk) {
            androidMain.dependencies {
                implementation(libs.okhttp)
            }
        }
        jvmTest.dependencies {
            // Real retained-version writer/reader for synthetic DataStore compatibility/rollback tests.
            implementation("androidx.datastore:datastore-preferences-core:1.2.1")
            // The live YouTube smoke test exercises the same InnerTubeX calls the iOS resolver makes.
            implementation(libs.innertubex.desktop)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }
        // IosChallengeEvaluator runs provider JavaScript on the same pinned quickjs-kt artifact as desktop.
        // Kotlin 2.4 creates intermediate source sets lazily: getByName fails here, the accessor works.
        iosMain.dependencies {
            implementation(libs.quickjs.kt)
            // InnerTubeX expects the injected client to negotiate JSON bodies itself.
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            // The network owner configures the engine hosts inject (proxy route and proxy credentials).
            implementation(libs.ktor.client.darwin)
            implementation("androidx.sqlite:sqlite-bundled:2.6.2")
        }
        // InnerTubeX (com.github.MetrolistGroup.innertubex v0.11.2, GPL-3.0) is consumed through its
        // per-target artifacts, on the same release as the Android-side innertubex pin.
        iosArm64Main.dependencies {
            implementation(libs.innertubex.iosarm64)
        }
        iosSimulatorArm64Main.dependencies {
            implementation(libs.innertubex.iossimulatorarm64)
        }
        iosTest.dependencies {
            // The live YouTube smoke test drives the resolver over the Darwin engine the app injects.
            implementation(libs.ktor.client.darwin)
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
    val metadataSources = listOf(
        "songmetadata/SongMetadataOverrides.kt",
        "db/entities/SongMetadataOverrideEntity.kt",
        "db/SongMetadataOverrideSql.kt",
    ).map { rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/$it") }
    inputs.files(models.resolve("PersistQueue.kt"), models.resolve("MediaMetadata.kt"), finite, metadataSources)
    outputs.dir(legacyFixtureSources)
    doLast {
        val destination = legacyFixtureSources.get().asFile.apply { mkdirs() }
        listOf("PersistQueue.kt", "MediaMetadata.kt").forEach { models.resolve(it).copyTo(destination.resolve(it), overwrite = true) }
        metadataSources.forEach { it.copyTo(destination.resolve(it.name), overwrite = true) }
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

// One portable-backup test allocates an archive above the 512 MiB cap to prove the storage refusal.
tasks.withType<Test>().configureEach { maxHeapSize = "4g" }

// Reuse an unchanged retained font for valid-font restore tests; no invented/third-party fixture.
val backupFontFixture = tasks.register<Copy>("prepareBackupFontTestResource") {
    from(rootProject.file("app/src/main/res/font/looper_jost_regular.ttf")) { rename { "retained-jost-fixture.ttf" } }
    into(layout.buildDirectory.dir("generated/backup-test-resource"))
}
kotlin.sourceSets.named("jvmTest") { resources.srcDir(backupFontFixture) }

// Compile the retained pure-Kotlin PlayPlay transform on every host. The generated copy changes
// only its package and JVM rotate intrinsics; the attributed Android sources remain authoritative.
val portablePlayPlaySources = layout.buildDirectory.dir("generated/portable-playplay")
val preparePortablePlayPlay = tasks.register("preparePortablePlayPlaySources") {
    val retained = listOf("SpotifyPlayPlayPipeline.kt", "SpotifyPlayPlayLeaves.kt").map {
        rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/spotify/echo/$it")
    }
    inputs.files(retained)
    outputs.dir(portablePlayPlaySources)
    doLast {
        val destination = portablePlayPlaySources.get().asFile.apply { mkdirs() }
        retained.forEach { source ->
            val body = source.readText().substringAfter("package moe.rukamori.archivetune.spotify.echo")
                .replace("java.lang.Long.rotateLeft(", "playPlayRotateLeft(")
                .replace("java.lang.Long.rotateRight(", "playPlayRotateRight(")
                .replace("java.lang.Integer.rotateRight(", "playPlayIntRotateRight(")
            destination.resolve(source.name).writeText(
                "package moe.rukamori.archivetune.shared.data.provider.spotify\n" + body,
            )
        }
    }
}
kotlin.sourceSets.named("commonMain") { kotlin.srcDir(portablePlayPlaySources) }
tasks.matching { it.name.startsWith("compile") || it.name.startsWith("ksp") }.configureEach {
    dependsOn(preparePortablePlayPlay)
}
