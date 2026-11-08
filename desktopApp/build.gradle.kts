import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// The desktop host is a JVM-only Kotlin Multiplatform module: it has no Android or iOS code, so it
// declares only the desktop JVM target and consumes the shared UI module.
kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared-ui"))
            implementation(project(":shared-domain"))
            implementation(project(":shared-data"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.multiplatform.components.resources)
            implementation(libs.material.kolor)
            // The graph's proxy-aware HTTP client and the retained CIO provider/upload clients are pure JVM.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.okhttp)
            implementation(libs.quickjs.kt)
            implementation(libs.gson)
            implementation(libs.jsoup)
            implementation("org.json:json:20250517")
            // The YouTube fallback tiers. InnerTubeX expects JSON content negotiation on its client. :core and the
            // NewPipe extractor already reach the package through shared-data; they are declared here so the NewPipe
            // tier can drive the extractor directly, which adds nothing to the package.
            implementation(libs.innertubex.desktop)
            // InnerTubeX delegates the signature and `n` transforms to QuackJS. Nothing here calls it directly; the
            // explicit declaration pins the 0.1.4 build that InnerTubeX v0.11.5 is compiled against instead of
            // leaving the version to whatever the transitive graph resolves.
            implementation(libs.quackjs)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            // The Hyprland visibility watcher parses the compositor's IPC replies with it.
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core"))
            implementation(libs.metrolist.extractor)
            implementation(compose.desktop.currentOs)
            implementation(compose.components.resources)
            // Material icons for the player queue menu's semantic icon binding. The CMP artifact is required: the
            // androidx coordinates pull androidx.compose.ui:ui:1.6.0, whose stale Application_desktopKt shadows the
            // real entry point on the runtime classpath.
            implementation(compose.materialIconsExtended)
            // Pure-JVM D-Bus client behind the Linux MPRIS and StatusNotifierItem exports; MIT-licensed,
            // notice in desktopApp/packaging/notices. The native-unixsocket transport needs no JNI.
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.native.unixsocket)
            implementation("net.java.dev.jna:jna:5.17.0")
            // The Win32 and X11 bindings the per-operating-system window frame is written against:
            // WinDef/WinUser/Advapi32Util/WinReg for the Windows frame and X11 for the Linux drag. The core
            // artifact above carries none of them, so the frame did not compile without this.
            implementation("net.java.dev.jna:jna-platform:5.17.0")
            // The TDLight binding behind the Telegram file adapter. Integration registers this project in the root
            // settings; its native library and notices are staged by stageDesktopNativeResources.
            implementation(project(":desktopApp:tdlight-jvm"))
            // The Spotify catalog client (GraphQL with REST fallback, web-player token). It brings its own OkHttp engine.
            implementation(project(":spotifycore"))
            implementation(project(":canvas"))
            implementation(project(":musixmatch"))
            implementation("com.github.RouHim:jaudiotagger:1.4.31")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
    }
}

// Reuse the retained, platform-independent PlayPlay implementation verbatim. Android auth, Media3 and the
// WebView code are deliberately not in this source set; the desktop adapter owns its separate streaming session.
val desktopPlayPlaySources = layout.buildDirectory.dir("generated/retained-playplay")
val prepareDesktopPlayPlaySources = tasks.register<Sync>("prepareDesktopPlayPlaySources") {
    val themeSource = rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/ui/theme/Theme.kt")
    inputs.file(themeSource)
    from(rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/spotify/echo")) {
        include("SpotifyEchoProto.kt", "SpotifyPlayPlayLicenseRequest.kt", "SpotifyPlayPlayRecordDecoder.kt",
            "SpotifyPlayPlayPipeline.kt", "SpotifyPlayPlayLeaves.kt")
    }
    from(rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/spotify/librespot")) {
        include("SpotifyLibrespotNative.kt")
        filter { line: String -> line.replace("System.loadLibrary(\"natune_librespot\")",
            "moe.rukamori.archivetune.desktop.DesktopLibrespotLibrary.load()") }
    }
    from(rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/playback")) {
        include("AudioTagger.kt")
        filter { line: String -> line.replace("import timber.log.Timber",
            "import moe.rukamori.archivetune.desktop.DesktopTagLog as Timber") }
    }
    from(rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/lyrics")) {
        include("YouTubeLyricsProvider.kt", "YouTubeSubtitleLyricsProvider.kt")
        filter { line: String ->
            when {
                line == "import android.content.Context" || line.contains("override fun isEnabled(context:") -> ""
                line.startsWith("package ") -> line + "\n\nimport moe.rukamori.archivetune.shared.data.lyrics.LyricsProvider"
                else -> line.replace("duration: Int,", "durationSec: Int, isrc: String?,").replace("album, duration)", "album, durationSec)")
            }
        }
    }
    from(rootProject.file("modules/src/main/kotlin/moe/rukamori/archivetune/modules")) {
        include("*.kt")
        exclude("ModuleRuntimeStorage.kt")
        filter { line: String ->
            when {
                line == "import android.content.Context" -> "import moe.rukamori.archivetune.modules.ModuleHostEnvironment as Context"
                line == "import android.util.Base64" -> "import moe.rukamori.archivetune.modules.ModuleBase64 as Base64"
                line == "import android.net.Uri" -> "import moe.rukamori.archivetune.modules.ModuleUri as Uri"
                line == "import android.graphics.BitmapFactory" -> "import moe.rukamori.archivetune.modules.ModuleBitmapFactory as BitmapFactory"
                line == "import android.graphics.Bitmap" -> "import moe.rukamori.archivetune.modules.ModuleBitmap as Bitmap"
                line == "import android.os.SystemClock" -> ""
                line.contains("context.packageManager.getPackageInfo") -> "    private val version = context.versionName"
                else -> line.replace("context.applicationContext", "context")
                    .replace("SystemClock.elapsedRealtime()", "(System.nanoTime() / 1_000_000L)")
                    .replace("applicationContext.assets.open(name)", "applicationContext.openAsset(name)")
            }
        }
    }
    from(rootProject.file("app/src/main/kotlin/moe/rukamori/archivetune/playback/smart")) {
        include("TransitionPolicy.kt", "TransitionPlanner.kt")
        filter { line: String -> if (line.startsWith("package ")) line + "\nimport moe.rukamori.archivetune.shared.domain.automix.*" else line }
    }
    into(desktopPlayPlaySources)
    doLast {
        val source = themeSource.readText()
        val notice = source.substring(0, source.indexOf("package "))
        val model = source.substring(source.indexOf("data class ThemeSeedPalette("), source.indexOf("@OptIn(ExperimentalMaterial3ExpressiveApi", source.indexOf("data class ThemeSeedPalette(")))
        val scheme = source.substring(source.indexOf("private fun exactPaletteColorScheme("), source.indexOf("private fun Int.toComposeColor()"))
            .replace("private fun exactPaletteColorScheme", "fun desktopSeedColorScheme")
        val codec = source.substring(source.indexOf("@Serializable\ndata class ThemeExportV1"))
            .replace("Color(android.graphics.Color.parseColor(withHash))", "desktopThemeColor(withHash)")
        val imports = """
            import androidx.compose.material3.ColorScheme
            import androidx.compose.ui.graphics.Color
            import androidx.compose.ui.graphics.toArgb
            import com.materialkolor.PaletteStyle
            import com.materialkolor.dynamicColorScheme
            import com.materialkolor.ktx.toHct
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.encodeToString
            import kotlinx.serialization.json.*
            import moe.rukamori.archivetune.modules.ModuleBase64 as Base64
            import moe.rukamori.archivetune.desktop.desktopThemeColor
        """.trimIndent()
        desktopPlayPlaySources.get().asFile.resolve("DesktopThemeSeeds.kt").writeText(
            notice + "package moe.rukamori.archivetune.ui.theme\n\n" + imports + "\n\n" + model + scheme + codec)
    }
}
kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(desktopPlayPlaySources); resources.srcDir(rootProject.file("modules/src/main/assets")) }
tasks.matching { it.name == "compileKotlinJvm" }.configureEach { dependsOn(prepareDesktopPlayPlaySources) }

// The public Discord application id, which DesktopDiscordPresence.APPLICATION_ID must also hold. macOS hands the
// OAuth callback to a bundle only when its CFBundleURLSchemes names discord-<id>, so the macOS block below reads it.
val desktopDiscordApplicationId = "1165706613961789445"

// Provider credentials for the desktop host are injected at build time and never committed (see
// docs/build-credentials.md). The generated DesktopBuildCredentials object is read through DesktopCredentials,
// which lets an environment variable or system property of the same name override it at run time.
val desktopCredentialNames = listOf(
    "ARCHIVETUNE_TIDAL_CLIENT_ID",
    "ARCHIVETUNE_TIDAL_CLIENT_SECRET",
    "ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID",
    "ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET",
    "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_ID",
    "ARCHIVETUNE_TIDAL_DEVICE_CLIENT_SECRET",
    "ARCHIVETUNE_JIOSAAVN_DES_KEY",
    "ARCHIVETUNE_LASTFM_API_KEY",
    "ARCHIVETUNE_LASTFM_API_SECRET",
    // The built-in Lyprio lyrics server pair; both values must be present for the host to offer it.
    "ARCHIVETUNE_LYPRIO_BASE_URL",
    "ARCHIVETUNE_LYPRIO_HMAC_KEY",
    "ARCHIVETUNE_LISTENER_PRESENCE_URL",
    "ARCHIVETUNE_TELEGRAM_API_ID",
    "ARCHIVETUNE_TELEGRAM_API_HASH",
    "ARCHIVETUNE_ARCHIVEPOOL_BASE_URL",
    "ARCHIVETUNE_SOURCE_PROVIDER_URL",
    "ARCHIVETUNE_SOURCE_PROVIDER_KEY",
    "ARCHIVETUNE_POOL_CLIENT_KEY",
    "ARCHIVETUNE_FEEDBACK_API_URL",
    "ARCHIVETUNE_FEEDBACK_INGEST_KEY",
    "ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID",
    "ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET",
    "ARCHIVETUNE_SPOTIFY_DESKTOP_CLIENT_ID",
    "ARCHIVETUNE_SPOTIFY_WEB_CLIENT_ID",
    "ARCHIVETUNE_SPOTIFY_AUDIO_IV",
    // The registered public PKCE client id for the Librespot OAuth flow (repository variable, not a secret).
    "ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID",
    // The YouTube device-code pair, under the names Android's BuildConfig reads, so no client literal is committed.
    // Without it the code sign-in reads as not configured.
    "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID",
    "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET",
    // The Amazon Turnstile sitekey and the origin it is locked to, under the names Android's BuildConfig reads; without
    // both, sign-in is not configured.
    "ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY",
    "ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN",
    // The BotGuard attestation pair, under the names Android's BuildConfig reads; without both, web-client
    // PO token generation and resolver minting report not configured and send untokened requests.
    "ARCHIVETUNE_POTOKEN_REQUEST_KEY",
    "ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY",
)
val desktopLegacyCredentialNames = mapOf(
    "ARCHIVETUNE_LASTFM_API_KEY" to arrayOf("LASTFM_API_KEY"),
    "ARCHIVETUNE_LASTFM_API_SECRET" to arrayOf("LASTFM_SECRET"),
    "ARCHIVETUNE_LYPRIO_BASE_URL" to arrayOf("LYPRIO_BASE_URL"),
    "ARCHIVETUNE_LYPRIO_HMAC_KEY" to arrayOf("LYPRIO_HMAC_KEY"),
    "ARCHIVETUNE_TELEGRAM_API_ID" to arrayOf("TELEGRAM_API_ID"),
    "ARCHIVETUNE_TELEGRAM_API_HASH" to arrayOf("TELEGRAM_API_HASH"),
    "ARCHIVETUNE_ARCHIVEPOOL_BASE_URL" to arrayOf("ARCHIVEPOOL_BASE_URL"),
    "ARCHIVETUNE_SOURCE_PROVIDER_URL" to arrayOf("SOURCE_PROVIDER_URL"),
    "ARCHIVETUNE_SOURCE_PROVIDER_KEY" to arrayOf("SOURCE_PROVIDER_KEY"),
    "ARCHIVETUNE_POOL_CLIENT_KEY" to arrayOf("POOL_CLIENT_KEY"),
    "ARCHIVETUNE_FEEDBACK_API_URL" to arrayOf("FEEDBACK_API_URL"),
    "ARCHIVETUNE_FEEDBACK_INGEST_KEY" to arrayOf("FEEDBACK_INGEST_KEY"),
)
val desktopProviderCredentials = desktopCredentialNames.associateWith { name ->
    ProviderCredentials.resolve(project, name, *desktopLegacyCredentialNames[name].orEmpty())
}
val desktopProviderCredentialsDirectory = layout.buildDirectory.dir("generated/providerCredentials")
val generateDesktopProviderCredentials = tasks.register("generateProviderCredentials") {
    // Fingerprints, not values, are task inputs, so a credential never enters Gradle's task history.
    inputs.property(
        "providerCredentialFingerprints",
        desktopProviderCredentials.mapValues { ProviderCredentials.fingerprint(it.value) }.toString(),
    )
    outputs.dir(desktopProviderCredentialsDirectory)
    outputs.doNotCacheIf("generated source embeds provider credentials") { true }
    doLast {
        ProviderCredentials.writeKotlinObject(
            desktopProviderCredentialsDirectory.get().asFile,
            "moe.rukamori.archivetune.desktop",
            "DesktopBuildCredentials",
            desktopProviderCredentials,
        )
    }
}
kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(desktopProviderCredentialsDirectory) }
tasks.matching { it.name == "compileKotlinJvm" }.configureEach { dependsOn(generateDesktopProviderCredentials) }

// The package identity is named once because the RPM rebuild below must reproduce exactly what Compose packages.
val desktopPackageName = "Natune"
val desktopPackageVersion =
    providers.gradleProperty("desktopPackageVersion").orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
require(Regex("""\d+\.\d+\.\d+""").matches(desktopPackageVersion)) {
    "desktopPackageVersion must be a numeric X.Y.Z package version."
}
val desktopDisplayVersion =
    providers.gradleProperty("desktopDisplayVersion").orNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("VERSION_NAME_OVERRIDE")?.trim()?.takeIf { it.isNotEmpty() }
        ?: desktopPackageVersion

// The monotonic build number the updater compares against a channel manifest's versionCode. CI
// stamps it from the release workflow (dev builds allocate from the nightly epoch floor, main from
// the stable floor); a plain `runDistributable` build keeps 0 and the updater stays honestly
// unavailable instead of offering a downgrade.
val desktopVersionCode =
    providers.gradleProperty("desktopVersionCode").orNull?.trim()?.toIntOrNull()
        ?: System.getenv("VERSION_CODE_OVERRIDE")?.trim()?.toIntOrNull()
        ?: 0

// The build identity a feedback report names, read from git the way the Android build reads it. Nothing secret here.
val desktopForkRevision = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "rev-parse", "--short=12", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("unknown").filter { it.isLetterOrDigit() }.ifEmpty { "unknown" }
val desktopForkBranch = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "branch", "--show-current")
    }.standardOutput.asText.get().trim().ifEmpty { "detached" }
}.getOrDefault("unknown").filter { it.isLetterOrDigit() || it in "/._-" }
val desktopForkDirty = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "status", "--porcelain", "--untracked-files=no")
    }.standardOutput.asText.get().isNotBlank()
}.getOrDefault(false)
// The in-app News feed, with the Android build's precedence: local.properties, then the NATUNE_NEWS_URL
// environment/CI variable, then the baked-in default. Unlike a provider credential, an explicitly blank value is kept:
// it disables the feed and hides the News entry.
val desktopNatuneNewsUrl = run {
    val localProperties = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { localProperties.load(it) }
    (localProperties.getProperty("NATUNE_NEWS_URL") ?: System.getenv("NATUNE_NEWS_URL") ?: "https://natune.pages.dev/api/news")
        .trim().trimEnd('/')
}
require(desktopNatuneNewsUrl.none { it == '"' || it == '\\' || it == '$' || it.isWhitespace() }) {
    "NATUNE_NEWS_URL must be a plain URL."
}
val desktopBuildIdentityDirectory = layout.buildDirectory.dir("generated/buildIdentity")
val generateDesktopBuildIdentity = tasks.register("generateBuildIdentity") {
    val revision = desktopForkRevision
    val branch = desktopForkBranch
    val dirty = desktopForkDirty
    val version = desktopDisplayVersion
    val code = desktopVersionCode
    val newsUrl = desktopNatuneNewsUrl
    val output = desktopBuildIdentityDirectory
    inputs.property("revision", revision)
    inputs.property("branch", branch)
    inputs.property("dirty", dirty)
    inputs.property("version", version)
    inputs.property("versionCode", code)
    inputs.property("natuneNewsUrl", newsUrl)
    outputs.dir(output)
    doLast {
        val directory = output.get().asFile.resolve("moe/rukamori/archivetune/desktop")
        directory.mkdirs()
        directory.resolve("DesktopBuildIdentity.kt").writeText(
            """
            |package moe.rukamori.archivetune.desktop
            |
            |internal object DesktopBuildIdentity {
            |    const val VERSION_NAME = "$version"
            |    const val VERSION_CODE = $code
            |    const val FORK_REVISION = "$revision"
            |    const val FORK_BRANCH = "$branch"
            |    const val FORK_DIRTY = $dirty
            |    const val NATUNE_NEWS_URL = "$newsUrl"
            |}
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(desktopBuildIdentityDirectory) }
tasks.matching { it.name == "compileKotlinJvm" }.configureEach { dependsOn(generateDesktopBuildIdentity) }

compose.desktop {
    application {
        mainClass = "moe.rukamori.archivetune.desktop.MainKt"
        // Lets DesktopRuntime reach the X11 toolkit's window-class field on Linux, and LinuxWindowDrag the AWT
        // accessor and toolkit that own the display lock and the X11 window id. Both are module-internal JDK
        // packages; without the second open the drag logs a diagnostic and the caption keeps the system frame.
        jvmArgs += listOf(
            "--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion
            description = "Natune music player"
            vendor = "Natune contributors"
            copyright = "GPL-3.0; contributors: see git history"
            licenseFile.set(rootProject.file("LICENSE"))
            // jdk.security.auth carries com.sun.security.auth.module.UnixSystem, which dbus-java's SASL EXTERNAL
            // handshake reads the peer's uid with. Without it every D-Bus client fails with
            // NoClassDefFoundError at connect, so system integration, the tray and the portal appearance theme are
            // all unavailable in the packaged app.
            modules("java.sql", "java.net.http", "jdk.unsupported", "jdk.crypto.ec", "jdk.security.auth")
            appResourcesRootDir.set(layout.buildDirectory.dir("distribution-resources"))
            linux {
                iconFile.set(project.file("packaging/archivetune.png"))
                shortcut = true
                appCategory = "AudioVideo"
                debMaintainer = "Natune contributors"
                menuGroup = "AudioVideo"
            }
            windows {
                iconFile.set(project.file("packaging/archivetune.ico"))
                shortcut = true
                menu = true
                perUserInstall = true
                upgradeUuid = "3e762519-f005-4fcf-8201-59ef24b5528e"
            }
            macOS {
                iconFile.set(project.file("packaging/archivetune.icns"))
                bundleID = "moe.rukamori.archivetune"
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key><array><dict>
                        <key>CFBundleURLName</key><string>ArchiveTune</string>
                        <key>CFBundleURLSchemes</key><array>
                        <string>archivetune</string><string>discord-$desktopDiscordApplicationId</string>
                        </array>
                        </dict></array>
                        <key>NSMicrophoneUsageDescription</key><string>Natune uses the microphone for audio recognition.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

// The playback engine is native code. The FFmpeg, OpenSSL, libxml2 and zlib libraries come from the
// prefix that native/scripts/build-native-deps.sh writes under build/native. CMake builds the engine
// against that prefix and never downloads anything. Without the prefix the engine is not built, and
// the playback tests fail with a message that names the script.
val nativeRoot = layout.buildDirectory.dir("native")
val nativePrefix = nativeRoot.map { it.dir("prefix") }
val nativeMiniaudioDir = nativeRoot.map { it.dir("src/miniaudio-0.11.25") }
val nativeEngineBuildDir = nativeRoot.map { it.dir("engine-build") }
val nativeEngineLibrary = nativeEngineBuildDir.map { it.file(System.mapLibraryName("archivetune_engine")) }
val hostIsLinux = System.getProperty("os.name").startsWith("Linux")
val hostIsWindows = System.getProperty("os.name").startsWith("Windows")
val hostIsMacos = !hostIsLinux && !hostIsWindows
val hostArch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"

/**
 * Why this invocation may build no playback engine, or an empty string when the engine is required.
 *
 * The engine is a package requirement, not an optimisation: a build without `libarchivetune_engine` installs,
 * starts and then plays nothing, and the only signal is a player error on the first press of play. Two legs
 * legitimately carry no media closure, and they say so on the command line — the unit-test workflow passes
 * `-PdesktopEngineTests=false` because it excludes exactly the suites that decode through the engine, and a
 * developer iterating on UI can pass `-PdesktopEngineBuild=skip`.
 */
val nativeEngineSkipReason = providers.provider {
    when {
        providers.gradleProperty("desktopEngineBuild").orNull?.trim() == "skip" ->
            "-PdesktopEngineBuild=skip was passed, so this run needs no playback engine"
        providers.gradleProperty("desktopEngineTests").orNull?.trim() == "false" ->
            "-PdesktopEngineTests=false excluded the engine suites, so this run needs no media closure"
        else -> ""
    }
}

/** The native dependency prefix CMake configures against; the engine cannot be built without its `lib` directory. */
val nativePrefixDirectory = nativePrefix.map { it.asFile }

/**
 * Refuses staged resources that cannot play. Every host stages the engine beside the media closure it links: `$ORIGIN`
 * resolves the ELF one, `@loader_path` the Apple one, and on Windows the runtime preloads that same directory. A package
 * missing any of them installs, starts, and then fails the first play, so the package is refused here instead.
 */
fun requireStagedPlaybackEngine(directory: File) {
    val engine = File(directory, System.mapLibraryName("archivetune_engine"))
    check(engine.isFile) {
        "$directory carries no ${engine.name}: this package would install and then play nothing. Run the host's " +
            "stage-*-package script (it installs a built engine) before packaging."
    }
    val staged = directory.listFiles().orEmpty().map { it.name.lowercase() }
    val missing = listOf(
        "the FFmpeg closure" to staged.any { "avformat" in it },
        "the codec library" to staged.any { "avcodec" in it },
        "the resampler" to staged.any { "swresample" in it || "avutil" in it },
        "the TLS library" to staged.any { "ssl" in it || "crypto" in it },
    ).filterNot { it.second }.map { it.first }
    check(missing.isEmpty()) {
        "$directory is missing ${missing.joinToString(", ")} beside ${engine.name}; the engine links them, so this " +
            "package could not decode. Rebuild the native dependencies and stage the closure again."
    }
}

/**
 * The engine build's input gate. Every task that needs the engine depends on it, so a run with no media closure
 * either opted out on the command line or stops here naming the script that produces the prefix. Replacing the old
 * silent `onlyIf` is the point: an engine-less build used to be a successful build.
 */
val verifyNativeEnginePrefix = tasks.register("verifyNativeEnginePrefix") {
    group = "build"
    description = "Refuses a build that needs the desktop playback engine and has no native dependency prefix."
    doFirst {
        val prefix = nativePrefixDirectory.get()
        if (prefix.resolve("lib").isDirectory) return@doFirst
        val skip = nativeEngineSkipReason.get()
        if (skip.isNotEmpty()) {
            logger.lifecycle("verifyNativeEnginePrefix: continuing without the playback engine ($skip)")
            return@doFirst
        }
        throw GradleException(
            "The desktop playback engine needs its native dependency prefix at $prefix, which does not exist. " +
                "Run bash desktopApp/native/scripts/build-native-deps.sh first (Windows: build-native-deps.ps1) " +
                "and then build the engine. A package built without it installs and then plays nothing. Pass " +
                "-PdesktopEngineBuild=skip only for a run that needs no playback.",
        )
    }
}

// Windows per-monitor DPI v2 lives in the launcher manifest, not in JVM code: Windows reads it once at process start.
// jpackage writes the JDK launcher manifest, which declares system DPI awareness, so a mixed-DPI desktop keeps one scale
// for every monitor. Stamping the committed manifest into the app image's launcher, before packageMsi and the EXE/ZIP
// staging wrap that image, is the supported way to change it. Opt-in: AWT's per-monitor relayout and the Compose window
// sizing it causes still need one run on real mixed-DPI hardware (docs/engineering/desktop-windows.md).
val windowsLauncherManifestFile = layout.projectDirectory.file("packaging/windows/launcher.manifest")
val windowsLauncherManifestMode = providers.gradleProperty("desktopWindowsLauncherManifest").orElse("")
val windowsAppImageLauncher =
    layout.buildDirectory.file("compose/binaries/main/app/$desktopPackageName/$desktopPackageName.exe")

val windowsMtExeOverride = providers.gradleProperty("mtExe").orNull?.trim()?.takeIf(String::isNotEmpty)

/** The Windows SDK's manifest tool, newest kit first; [overridePath] wins so a bare SDK can still be pointed at. */
fun findWindowsMt(overridePath: String?): File? {
    if (overridePath != null) return File(overridePath).takeIf { it.isFile }
    val roots = listOfNotNull(
        System.getenv("WindowsSdkDir")?.let(::File),
        File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "Windows Kits/10/bin"),
        File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Windows Kits/10/bin"),
    )
    return roots.asSequence()
        .filter { it.isDirectory }
        .flatMap { root -> root.listFiles().orEmpty().sortedByDescending { kit -> kit.name }.asSequence() }
        .map { kit -> File(File(kit, "x64"), "mt.exe") }
        .firstOrNull { it.isFile }
}

val stampWindowsLauncherManifest = tasks.register("stampWindowsLauncherManifest") {
    group = "distribution"
    description = "Embeds packaging/windows/launcher.manifest (PerMonitorV2) into the Windows app-image launcher."
    dependsOn("createDistributable")
    onlyIf { windowsLauncherManifestMode.get().equals("per-monitor-v2", ignoreCase = true) }
    doLast {
        val launcher = windowsAppImageLauncher.get().asFile
        if (!launcher.isFile) throw GradleException("The Windows app-image launcher is missing: $launcher")
        val mt = findWindowsMt(windowsMtExeOverride) ?: throw GradleException(
            "mt.exe was not found: install the Windows SDK or pass -PmtExe=<path> to embed the PMv2 manifest.",
        )
        val process = ProcessBuilder(
            mt.absolutePath, "-nologo", "-manifest", windowsLauncherManifestFile.asFile.absolutePath,
            "-outputresource:${launcher.absolutePath};#1",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) throw GradleException("mt.exe could not embed $windowsLauncherManifestFile: $output")
        logger.lifecycle("Embedded ${windowsLauncherManifestFile.asFile.name} into ${launcher.name}")
    }
}
// The MSI (and the EXE/ZIP that wrap the same app image) must be built after the launcher is stamped.
tasks.matching { it.name == "packageMsi" }.configureEach { dependsOn(stampWindowsLauncherManifest) }

val hostNativeName = when {
    hostIsLinux -> "linux-$hostArch"
    hostIsWindows -> "windows-$hostArch"
    else -> "macos-$hostArch"
}
val hostNativeDirectory = layout.buildDirectory.dir("distribution-resources/$hostNativeName/native")
// Homebrew CMake/Python may be arm64 even when the packaging JDK runs under Rosetta.
val nativeArchitectureArguments = if (hostIsMacos) {
    listOf("-DCMAKE_OSX_ARCHITECTURES=${if (hostArch == "x64") "x86_64" else "arm64"}")
} else emptyList()

val configureNativeEngine = tasks.register<Exec>("configureNativeEngine") {
    group = "build"
    description = "Configures the desktop playback engine against the native dependency prefix."
    dependsOn(verifyNativeEnginePrefix)
    // verifyNativeEnginePrefix already refused the run when the engine is required and the prefix is absent, so this
    // gate only keeps CMake off a directory that has none, for the legs that opted out of the engine build.
    onlyIf { nativePrefix.get().asFile.resolve("lib").isDirectory }
    outputs.file(nativeEngineBuildDir.map { it.file("CMakeCache.txt") })
    inputs.file(file("native/CMakeLists.txt"))
    val engineArguments = mutableListOf(
        "cmake",
        "-S", file("native").absolutePath,
        "-B", nativeEngineBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DAE_PREFIX=${nativePrefix.get().asFile.absolutePath}",
        "-DAE_MINIAUDIO_DIR=${nativeMiniaudioDir.get().asFile.absolutePath}",
    )
    // The engine's C/pthread sources need the MinGW toolchain that built the FFmpeg closure; MSVC
    // stays reserved for archivetune_system and archivetune_browser, which need cppwinrt/WebView2.
    if (System.getProperty("os.name").startsWith("Windows")) {
        engineArguments += listOf("-G", "MinGW Makefiles")
    }
    engineArguments += nativeArchitectureArguments
    commandLine(*engineArguments.toTypedArray())
}

val nativeSystemBuildDir = nativeRoot.map { it.dir("system-build") }
val configureNativeSystem = tasks.register<Exec>("configureNativeSystem") {
    onlyIf { !hostIsLinux }
    inputs.dir(file("native/system"))
    outputs.file(nativeSystemBuildDir.map { it.file("CMakeCache.txt") })
    commandLine(listOf("cmake", "-S", file("native/system").absolutePath, "-B", nativeSystemBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release") + nativeArchitectureArguments)
}
val buildNativeSystem = tasks.register<Exec>("buildNativeSystem") {
    dependsOn(configureNativeSystem)
    onlyIf { !hostIsLinux }
    inputs.dir(file("native/system"))
    outputs.dir(nativeSystemBuildDir)
    commandLine("cmake", "--build", nativeSystemBuildDir.get().asFile.absolutePath, "--config", "Release", "--parallel", "1")
}
// The cookie-only sign-in window and the Linux file chooser live in archivetune_browser, a library built from
// native/browser against the platform's own browser engine: WebView2 on Windows, WebKitGTK 4.1 on Linux and WKWebView
// on macOS. The playback engine never links it. A full desktop package requires it, because a package without it would
// silently drop native cookie sign-in. The package prerequisites are recorded in native/browser/CMakeLists.txt.
val nativeBrowserBuildDir = nativeRoot.map { it.dir("browser-build") }
val webView2Sdk = providers.gradleProperty("desktopWebView2Sdk").orNull
val configureNativeBrowser = tasks.register<Exec>("configureNativeBrowser") {
    group = "build"
    description = "Configures the cookie-only sign-in browser library against the platform browser engine."
    inputs.dir(file("native/browser"))
    outputs.file(nativeBrowserBuildDir.map { it.file("CMakeCache.txt") })
    doFirst {
        // WebView2 cannot be substituted: a Windows package without the official SDK would have no sign-in window.
        if (System.getProperty("os.name").startsWith("Windows")) {
            check(webView2Sdk != null) {
                "Provide -PdesktopWebView2Sdk=<extracted Microsoft.Web.WebView2 1.0.4258.31 SDK>; there is no Chrome fallback."
            }
        }
    }
    val webView2Arguments = webView2Sdk?.let { listOf("-DAE_WEBVIEW2_SDK=$it") }.orEmpty()
    val arguments = listOf(
        "cmake", "-S", file("native/browser").absolutePath,
        "-B", nativeBrowserBuildDir.get().asFile.absolutePath, "-DCMAKE_BUILD_TYPE=Release",
    ) + nativeArchitectureArguments + webView2Arguments
    commandLine(*arguments.toTypedArray())
}
val buildNativeBrowser = tasks.register<Exec>("buildNativeBrowser") {
    group = "build"
    description = "Builds archivetune_browser from desktopApp/native/browser."
    dependsOn(configureNativeBrowser)
    inputs.dir(file("native/browser"))
    outputs.dir(nativeBrowserBuildDir)
    commandLine("cmake", "--build", nativeBrowserBuildDir.get().asFile.absolutePath, "--config", "Release", "--parallel", "2")
}
// CMake's install rule owns the library name, the $ORIGIN rpath and the destination, so the package copies what the
// browser build declares. The Linux script also writes this directory, so the install runs after it, never beside it.
val installNativeBrowser = tasks.register<Exec>("installNativeBrowser") {
    group = "distribution"
    description = "Installs archivetune_browser into the host native resources that the desktop package copies."
    dependsOn(buildNativeBrowser)
    // The host scripts also write this directory, so the install runs after them, never beside them.
    mustRunAfter("stageLinuxNativeResources", "stageMacosNativeResources", "stageWindowsNativeResources")
    inputs.dir(nativeBrowserBuildDir)
    // Owning the shared directory lets Gradle's stale-output cleanup delete the already staged engine/FFmpeg closure.
    outputs.file(hostNativeDirectory.map { it.file(System.mapLibraryName("archivetune_browser")) })
    commandLine("cmake", "--install", nativeBrowserBuildDir.get().asFile.absolutePath, "--config", "Release",
        "--prefix", hostNativeDirectory.get().asFile.absolutePath)
}
val desktopNativeDirectoryOverride = providers.gradleProperty("desktopNativeDirectory")
val stageDesktopNativeResources = tasks.register("stageDesktopNativeResources") {
    group = "distribution"
    description = "Stages actual host-native libraries and required notices; refuses incomplete packages."
    dependsOn(buildNativeSystem, installNativeBrowser, verifyNativeEnginePrefix)
    if (hostIsLinux) dependsOn("stageLinuxNativeResources")
    // The in-repo recipes stage the closure from build/native; -PdesktopNativeDirectory keeps the
    // override for a foreign media closure that was not produced by them.
    if (hostIsMacos && !desktopNativeDirectoryOverride.isPresent) dependsOn("stageMacosNativeResources")
    if (hostIsWindows && !desktopNativeDirectoryOverride.isPresent) dependsOn("stageWindowsNativeResources")
    // Recheck the closure on every package; a stale resource directory is not a native build.
    doLast {
        if (!hostIsLinux && desktopNativeDirectoryOverride.isPresent) {
            val input = file(desktopNativeDirectoryOverride.get())
            check(input.resolve(System.mapLibraryName("archivetune_engine")).isFile) { "Host playback engine missing" }
            val systemName = System.mapLibraryName("archivetune_system")
            val system = nativeSystemBuildDir.get().asFile.walkTopDown().firstOrNull { it.name == systemName && it.isFile }
                ?: error("Host native OS adapter missing")
            val host = if (hostIsWindows) "windows" else "macos"
            val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
            copy { from(input); from(system); into(layout.buildDirectory.dir("distribution-resources/$host-$arch/native")) }
            copy { from(rootProject.file("LICENSE")); into(layout.buildDirectory.dir("distribution-resources/common/notices")) }
        } else if (!hostIsLinux) {
            // The stage script already installed the engine and its media closure with the platform's own
            // closure audit; what remains here is the OS adapter the engine never links.
            val engineName = System.mapLibraryName("archivetune_engine")
            check(hostNativeDirectory.get().asFile.resolve(engineName).isFile) {
                "Host playback engine missing at ${hostNativeDirectory.get().asFile.resolve(engineName)}; run the host's stage-*-package.py first."
            }
            val systemName = System.mapLibraryName("archivetune_system")
            val system = nativeSystemBuildDir.get().asFile.walkTopDown().firstOrNull { it.name == systemName && it.isFile }
                ?: error("Host native OS adapter missing")
            copy { from(system); into(hostNativeDirectory) }
        }
        // Every host ends here with the engine and the media closure it links inside the staged resources. A package
        // without them installs, starts and plays nothing, so the build refuses it here instead of shipping it.
        requireStagedPlaybackEngine(hostNativeDirectory.get().asFile)
        copy { from("packaging/notices"); into(layout.buildDirectory.dir("distribution-resources/common/notices")) }
        val librespot = layout.buildDirectory.file("generated/librespot/$hostNativeName/native/${System.mapLibraryName("natune_librespot")}").get().asFile
        hostNativeDirectory.get().asFile.resolve(System.mapLibraryName("natune_librespot")).delete()
        if (librespot.isFile) {
            providers.exec { commandLine("python", rootProject.file("native/librespot/desktop-artifact.py"), "validate", hostNativeName) }.result.get().assertNormalExitValue()
            copy { from(librespot); into(hostNativeDirectory) }
            copy { from(rootProject.file("native/librespot/LICENSE.librespot")); into(layout.buildDirectory.dir("distribution-resources/common/notices")) }
        } else {
            logger.warn("Librespot JNI unavailable; desktop playback uses the configured source fallback.")
        }
        // TDLight is desktop-only. Its native library comes from the pinned source through the host's recipe, and its notice
        // must carry the verbatim Boost license from that tree. A package missing either is refused, not shipped with a
        // placeholder, because the Telegram path would fail at run time with no packaging-time signal.
        val tdlightStage = layout.buildDirectory.dir("tdlight-jvm/stage").get().asFile
        val tdlightLibrary = tdlightStage.resolve("$hostNativeName/native/${System.mapLibraryName("tdjni")}")
        check(tdlightLibrary.isFile) {
            // Release CI uses build-tdjni.sh on every host, including MSYS2 MinGW on Windows x64.
            when (hostNativeName) {
                "windows-x64", "linux-x64", "linux-arm64", "macos-arm64", "macos-x64" ->
                    "Missing $tdlightLibrary; run bash desktopApp/tdlight-jvm/native/build-tdjni.sh first (MSYS2 MINGW64 on Windows)."
                else -> "Missing $tdlightLibrary; no TDLight recipe covers $hostNativeName."
            }
        }
        val tdlightLicense = tdlightStage.resolve("notices/TDLight-Boost-1.0-LICENSE.txt")
        check(tdlightLicense.isFile) { "Missing $tdlightLicense; run bash desktopApp/tdlight-jvm/native/build-tdjni.sh first." }
        copy { from(tdlightLibrary); into(hostNativeDirectory) }
        copy {
            from(file("tdlight-jvm/notices"))
            from(tdlightLicense)
            into(layout.buildDirectory.dir("distribution-resources/common/notices"))
        }
    }
}
tasks.register<Exec>("stageLinuxNativeResources") {
    dependsOn("buildNativeEngine")
    onlyIf { hostIsLinux }
    commandLine("python3", file("native/scripts/stage-linux-package.py").absolutePath)
}
tasks.register<Exec>("stageMacosNativeResources") {
    dependsOn("buildNativeEngine")
    onlyIf { hostIsMacos }
    commandLine("python3", file("native/scripts/stage-macos-package.py").absolutePath, "--arch", hostArch)
}
tasks.register<Exec>("stageWindowsNativeResources") {
    dependsOn("buildNativeEngine")
    onlyIf { hostIsWindows }
    // MSYS2 Python on PATH (installed next to the mingw-w64 toolchain by setup-msys2); objdump too.
    commandLine("python", file("native/scripts/stage-windows-package.py").absolutePath)
}
tasks.matching { it.name in setOf("prepareAppResources", "createDistributable", "packageDeb", "packageRpm", "packageMsi", "packageDmg") }.configureEach {
    dependsOn(stageDesktopNativeResources)
    // Compose's jpackage task does not track generated resource additions on its own.
    inputs.dir(layout.buildDirectory.dir("distribution-resources")).withPropertyName("desktopNativeResources")
}

// Compose 1.12.2 exposes no Linux dependency property and passes none to jpackage, so nothing in its packages declares the
// packages that the browser library links against: WebKitGTK 4.1, GTK3 and libsoup3, linked directly by native/browser.
// Without them an installed package cannot load that library, and the cookie sign-in window fails. This task adds the three
// packages to the Depends field of each .deb that packageDeb wrote. The .rpm is handled by addLinuxBrowserRpmRequires below.
val linuxBrowserDebDepends = listOf("libwebkit2gtk-4.1-0", "libgtk-3-0", "libsoup-3.0-0")
val addLinuxBrowserDebDepends = tasks.register("addLinuxBrowserDebDepends") {
    group = "distribution"
    description = "Adds the WebKitGTK 4.1, GTK3 and libsoup3 packages to the Depends field of the built .deb."
    onlyIf { hostIsLinux }
    doLast {
        val binaries = layout.buildDirectory.dir("compose/binaries").get().asFile
        val packages = binaries.walkTopDown().filter { it.isFile && it.extension == "deb" }.toList()
        check(packages.isNotEmpty()) { "packageDeb wrote no .deb under $binaries" }
        val work = layout.buildDirectory.dir("tmp/addLinuxBrowserDebDepends").get().asFile
        packages.forEach { deb ->
            val tree = work.resolve(deb.nameWithoutExtension)
            tree.deleteRecursively()
            // dpkg-deb creates only the last path component of its target.
            work.mkdirs()
            runHostTool("dpkg-deb", "--raw-extract", deb.absolutePath, tree.absolutePath)
            val control = tree.resolve("DEBIAN/control")
            control.writeText(withDependsFields(control.readText(), linuxBrowserDebDepends))
            val desktopEntries = tree.walkTopDown()
                .filter { it.isFile && it.extension == "desktop" && it.parentFile.name == "lib" }.toList()
            check(desktopEntries.isNotEmpty()) { "${deb.name} carries no .desktop entry to register archivetune://" }
            desktopEntries.forEach(::registerUriSchemeHandler)
            runHostTool("dpkg-deb", "--root-owner-group", "--build", tree.absolutePath, deb.absolutePath)
        }
    }
}
tasks.matching { it.name == "packageDeb" }.configureEach {
    finalizedBy(addLinuxBrowserDebDepends)
}

// Compose's packageRpm writes no Requires either. jpackage can declare them with --linux-package-deps, but Compose does not
// pass that option through, so the RPM is rebuilt from the app image that createDistributable already wrote, with the same
// metadata plus the three requirements. Each name is then read back with rpm -qp, and the rebuilt package replaces Compose's
// file only when every name is present. A rejected option or value format therefore stops the build instead of shipping a
// browser library that cannot load. The value format is checked on the first packaging run and is not assumed final.
val linuxBrowserRpmRequires = listOf("webkit2gtk4.1", "gtk3", "libsoup3")
val addLinuxBrowserRpmRequires = tasks.register("addLinuxBrowserRpmRequires") {
    group = "distribution"
    description = "Rebuilds the .rpm from the app image with the WebKitGTK 4.1, GTK3 and libsoup3 requirements."
    onlyIf { hostIsLinux }
    dependsOn("createDistributable")
    doLast {
        val binaries = layout.buildDirectory.dir("compose/binaries").get().asFile
        val packages = binaries.walkTopDown().filter { it.isFile && it.extension == "rpm" }.toList()
        check(packages.isNotEmpty()) { "packageRpm wrote no .rpm under $binaries" }
        val appImage = binaries.resolve("main/app/$desktopPackageName")
        check(appImage.resolve("bin/$desktopPackageName").isFile) { "createDistributable wrote no app image under $appImage" }
        // Compose runs the JDK that runs Gradle, so jpackage comes from the same installation.
        val jpackage = File(System.getProperty("java.home"), "bin/jpackage")
        check(jpackage.isFile) { "jpackage was not found at $jpackage" }
        val work = layout.buildDirectory.dir("tmp/addLinuxBrowserRpmRequires").get().asFile
        work.deleteRecursively()
        // A resource-dir file named <name>.desktop replaces jpackage's template.desktop; jpackage still fills in the
        // upper-case placeholders, so only the %u argument and the scheme MimeType differ from its default entry.
        val resources = layout.buildDirectory.dir("tmp/linuxDesktopResources").get().asFile
        resources.mkdirs()
        resources.resolve("$desktopPackageName.desktop").writeText(
            listOf(
                "[Desktop Entry]",
                "Name=APPLICATION_NAME",
                "Comment=APPLICATION_DESCRIPTION",
                "Exec=APPLICATION_LAUNCHER %u",
                "Icon=APPLICATION_ICON",
                "Terminal=false",
                "Type=Application",
                "Categories=DEPLOY_BUNDLE_CATEGORY",
                "MimeType=x-scheme-handler/archivetune;",
            ).joinToString("\n", postfix = "\n"),
        )
        runHostTool(
            jpackage.absolutePath,
            "--type", "rpm",
            "--app-image", appImage.absolutePath,
            "--dest", work.absolutePath,
            "--name", desktopPackageName,
            "--app-version", desktopPackageVersion,
            "--description", "Natune music player",
            "--vendor", "Natune contributors",
            "--copyright", "GPL-3.0; contributors: see git history",
            "--license-file", rootProject.file("LICENSE").absolutePath,
            "--icon", project.file("packaging/archivetune.png").absolutePath,
            "--linux-app-category", "AudioVideo",
            "--linux-menu-group", "AudioVideo",
            "--linux-shortcut",
            "--resource-dir", resources.absolutePath,
            // RPM Requires lists are whitespace-separated; the comma form is the Debian Depends syntax used for the .deb.
            "--linux-package-deps", linuxBrowserRpmRequires.joinToString(" "),
        )
        val rebuilt = work.walkTopDown().filter { it.isFile && it.extension == "rpm" }.toList().singleOrNull()
            ?: error("jpackage wrote no single .rpm under $work")
        val requires = runHostTool("rpm", "-qp", "--requires", rebuilt.absolutePath)
            .lineSequence().map { it.trim().substringBefore(' ') }.toSet()
        linuxBrowserRpmRequires.forEach { name ->
            check(name in requires) { "${rebuilt.name} does not declare the $name requirement" }
        }
        val target = packages.singleOrNull { it.name == rebuilt.name }
            ?: error("packageRpm wrote no ${rebuilt.name} to replace; found ${packages.map { it.name }}")
        rebuilt.copyTo(target, overwrite = true)
    }
}
tasks.matching { it.name == "packageRpm" }.configureEach {
    finalizedBy(addLinuxBrowserRpmRequires)
}

// jpackage writes the --linux-shortcut entry (lib/<package>-<name>.desktop) only while it builds a .deb or .rpm, never into
// the app image, and without a MimeType or an Exec argument placeholder, so nothing would register archivetune:// for
// installed Linux packages. addLinuxBrowserDebDepends patches the entry in the .deb it already unpacks, and
// addLinuxBrowserRpmRequires hands jpackage an overriding template when it rebuilds the .rpm.
// Exec gets the %u placeholder a scheme handler needs to receive the clicked URI; MimeType declares the scheme once.
private fun registerUriSchemeHandler(file: File) {
    val lines = file.readLines().toMutableList()
    val exec = lines.indexOfFirst { it.startsWith("Exec=") }
    check(exec >= 0) { "no Exec= line in $file" }
    if (!lines[exec].contains("%u")) lines[exec] += " %u"
    val mime = lines.indexOfFirst { it.startsWith("MimeType=") }
    when {
        mime < 0 -> lines += "MimeType=x-scheme-handler/archivetune;"
        !lines[mime].contains("x-scheme-handler/archivetune") ->
            lines[mime] = lines[mime].trimEnd(';') + ";x-scheme-handler/archivetune;"
    }
    file.writeText(lines.joinToString("\n").trimEnd('\n') + "\n")
}

// A host packaging tool that fails stops the build with its output. Continuing would leave an unamended package behind
// while the task reports success.
private fun runHostTool(vararg command: String): String {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { "${command.first()} failed: $output" }
    return output
}

// Adds each required package that the Depends field does not already name, keeping the names jpackage wrote. The field
// is created before Description when jpackage wrote none, because Description must stay the last field of a paragraph.
private fun withDependsFields(control: String, packages: List<String>): String {
    val lines = control.lines().toMutableList()
    val index = lines.indexOfFirst { it.startsWith("Depends:") }
    val named = if (index >= 0) {
        lines[index].removePrefix("Depends:").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    } else {
        emptyList()
    }
    val depends = "Depends: " + (named + packages.filterNot { it in named }).joinToString(", ")
    if (index >= 0) {
        lines[index] = depends
    } else {
        val description = lines.indexOfFirst { it.startsWith("Description:") }
        lines.add(if (description >= 0) description else lines.size, depends)
    }
    return lines.joinToString("\n").trimEnd('\n') + "\n"
}

val buildNativeEngine = tasks.register<Exec>("buildNativeEngine") {
    group = "build"
    description = "Builds the desktop playback engine library from desktopApp/native."
    dependsOn(configureNativeEngine)
    onlyIf { nativePrefix.get().asFile.resolve("lib").isDirectory }
    inputs.dir(file("native"))
    outputs.file(nativeEngineLibrary)
    commandLine("cmake", "--build", nativeEngineBuildDir.get().asFile.absolutePath, "--config", "Release", "--parallel", "2")
}

// The playback tests load the engine from its build directory. A change to the engine rebuilds it,
// and the test task must rerun when the library changes, so the library is a declared input.
tasks.named<Test>("jvmTest") {
    dependsOn(buildNativeEngine)
    // Platform-only tests do not need FFmpeg. Engine tests still fail explicitly when it is absent.
    inputs.files(nativeEngineLibrary.map { library -> listOf(library.asFile).filter { it.isFile } })
    systemProperty("archivetune.engine.library", nativeEngineLibrary.get().asFile.absolutePath)
    systemProperty("archivetune.desktop.window.tests", providers.gradleProperty("desktopWindowTests").orElse("false").get())
    systemProperty("archivetune.desktop.vault.tests", providers.gradleProperty("desktopVaultTests").orElse("false").get())
    systemProperty("archivetune.desktop.browser.tests", providers.gradleProperty("desktopBrowserTests").orElse("false").get())
    // Three suites decode the committed audio fixtures through the native engine, and the engine only runs against
    // the pinned FFmpeg/OpenSSL/libxml2 closure that the opt-in desktop app image job builds. The unit-test workflow
    // runs this task without that closure, so it passes -PdesktopEngineTests=false and exactly those suites are
    // excluded instead of every run failing on the missing library. The app image job runs the task untrimmed, so
    // the engine suites still execute in CI; the default here is the untrimmed task.
    if (providers.gradleProperty("desktopEngineTests").orElse("true").get() != "true") {
        filter.excludeTestsMatching("moe.rukamori.archivetune.desktop.playback.DesktopPlaybackEngineTest")
        filter.excludeTestsMatching("moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPortTest")
        filter.excludeTestsMatching("moe.rukamori.archivetune.desktop.playback.DesktopPlaybackStreamTest")
    }
}

/*
 * The window, icon and renderer suites are pure JVM checks: no native engine, no fixtures, no display. They share the
 * untrimmed task's single JVM with the native playback suites, whose manual-engine ring is already sensitive to the work
 * the JVM did first (`DesktopPlaybackEngineTest.speedChangeScalesMediaTimeOnceTheQueuedAudioHasPlayed` assumes the ring
 * holds no more than the two seconds it drains, and it failed four times on this branch while the same suite passed on
 * the base). Running them in their own JVM keeps the playback suites the environment they have on the base and lets
 * these tests run without the engine closure.
 */
val desktopWindowTestClasses = listOf(
    "moe.rukamori.archivetune.desktop.DesktopWindowBoundsTest",
    "moe.rukamori.archivetune.desktop.DesktopWindowIconTest",
    "moe.rukamori.archivetune.desktop.DesktopRendererDiagnosticsTest",
    "moe.rukamori.archivetune.desktop.integration.windows.WindowsWindowChromeTest",
)
val jvmTestTask = tasks.named<Test>("jvmTest")
// `provider { }` reads the collections lazily but is not *derived from the task*, so it adds no dependency on jvmTest
// (a `jvmTestTask.map { }` here would make desktopWindowTests depend on jvmTest and close the dependsOn cycle).
val jvmTestClasses = provider { jvmTestTask.get().testClassesDirs }
val jvmTestClasspath = provider { jvmTestTask.get().classpath }
tasks.register<Test>("desktopWindowTests") {
    group = "verification"
    description = "Runs the desktop window, icon and renderer diagnostics suites in their own JVM."
    // The same compiled classes and runtime classpath as jvmTest; only the JVM instance differs.
    testClassesDirs = files(jvmTestClasses)
    classpath = files(jvmTestClasspath)
    desktopWindowTestClasses.forEach { filter.includeTestsMatching(it) }
}
jvmTestTask.configure {
    desktopWindowTestClasses.forEach { filter.excludeTestsMatching(it) }
    dependsOn("desktopWindowTests")
}
// KMP names the JVM test task `jvmTest`, but the validation commands run `:desktopApp:test --tests`.
// The alias is a Test task so Gradle accepts --tests on it. It never runs itself: once the graph is
// ready, its filter moves to jvmTest, which runs the tests.
tasks.register<Test>("test") {
    group = "verification"
    description = "Runs the desktop JVM tests through jvmTest, forwarding any --tests filter."
    dependsOn("jvmTest")
    enabled = false
}
val jvmTestForAlias = tasks.named<Test>("jvmTest")
gradle.taskGraph.whenReady {
    // Gradle keeps --tests values in a filter field that is not public API, so read the raw request
    // arguments instead: each pattern is the argument that follows a --tests flag.
    val requested = gradle.startParameter.taskRequests
        .flatMap { it.args }
        .zipWithNext()
        .filter { (flag, _) -> flag == "--tests" }
        .map { (_, pattern) -> pattern }
    jvmTestForAlias.configure {
        requested.forEach { filter.includeTestsMatching(it) }
    }
}
