import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
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
            // The one HTTP client of the desktop graph. The CIO engine is pure JVM, so it adds no native library.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.quickjs.kt)
            // The YouTube fallback tiers. InnerTubeX expects JSON content negotiation on its client. :core and the
            // NewPipe extractor already reach the package through shared-data; they are declared here so the NewPipe
            // tier can drive the extractor directly, which adds nothing to the package.
            implementation(libs.innertubex.desktop)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(project(":core"))
            implementation(libs.metrolist.extractor)
            implementation(compose.desktop.currentOs)
            // Pinned here for the isolated native lane; centralize in the catalog at integration.
            implementation("com.github.hypfvieh:dbus-java-core:5.1.1")
            implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.1")
            implementation("net.java.dev.jna:jna:5.17.0")
            // The TDLight binding behind the Telegram file adapter. Integration registers this project in the root
            // settings; its native library and notices are staged by stageDesktopNativeResources.
            implementation(project(":desktopApp:tdlight-jvm"))
            // The Spotify catalog client (GraphQL with REST fallback, web-player token). It brings its own OkHttp engine.
            implementation(project(":spotifycore"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

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
    "ARCHIVETUNE_LISTENER_PRESENCE_URL",
    "ARCHIVETUNE_TELEGRAM_API_ID",
    "ARCHIVETUNE_TELEGRAM_API_HASH",
    "ARCHIVETUNE_SOURCE_PROVIDER_URL",
    "ARCHIVETUNE_SOURCE_PROVIDER_KEY",
    "ARCHIVETUNE_POOL_CLIENT_KEY",
    "ARCHIVETUNE_FEEDBACK_API_URL",
    "ARCHIVETUNE_FEEDBACK_INGEST_KEY",
    "ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_ID",
    "ARCHIVETUNE_GOOGLE_DESKTOP_CLIENT_SECRET",
)
val desktopLegacyCredentialNames = mapOf(
    "ARCHIVETUNE_LASTFM_API_KEY" to arrayOf("LASTFM_API_KEY"),
    "ARCHIVETUNE_LASTFM_API_SECRET" to arrayOf("LASTFM_SECRET"),
    "ARCHIVETUNE_TELEGRAM_API_ID" to arrayOf("TELEGRAM_API_ID"),
    "ARCHIVETUNE_TELEGRAM_API_HASH" to arrayOf("TELEGRAM_API_HASH"),
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
val desktopBuildIdentityDirectory = layout.buildDirectory.dir("generated/buildIdentity")
val generateDesktopBuildIdentity = tasks.register("generateBuildIdentity") {
    val revision = desktopForkRevision
    val branch = desktopForkBranch
    val dirty = desktopForkDirty
    val version = desktopDisplayVersion
    val output = desktopBuildIdentityDirectory
    inputs.property("revision", revision)
    inputs.property("branch", branch)
    inputs.property("dirty", dirty)
    inputs.property("version", version)
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
            |    const val FORK_REVISION = "$revision"
            |    const val FORK_BRANCH = "$branch"
            |    const val FORK_DIRTY = $dirty
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
        // Lets DesktopRuntime reach the X11 toolkit's window-class field on Linux.
        jvmArgs += listOf("--add-opens", "java.desktop/sun.awt.X11=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion
            description = "Natune music player"
            vendor = "Natune contributors"
            copyright = "GPL-3.0; contributors: see git history"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql", "java.net.http", "jdk.unsupported", "jdk.crypto.ec")
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
                        <key>CFBundleURLSchemes</key><array><string>archivetune</string></array>
                        </dict></array>
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

val configureNativeEngine = tasks.register<Exec>("configureNativeEngine") {
    group = "build"
    description = "Configures the desktop playback engine against the native dependency prefix."
    onlyIf { nativePrefix.get().asFile.resolve("lib").isDirectory }
    outputs.file(nativeEngineBuildDir.map { it.file("CMakeCache.txt") })
    inputs.file(file("native/CMakeLists.txt"))
    commandLine(
        "cmake",
        "-S", file("native").absolutePath,
        "-B", nativeEngineBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DAE_PREFIX=${nativePrefix.get().asFile.absolutePath}",
        "-DAE_MINIAUDIO_DIR=${nativeMiniaudioDir.get().asFile.absolutePath}",
    )
}

val hostIsLinux = System.getProperty("os.name").startsWith("Linux")
val nativeSystemBuildDir = nativeRoot.map { it.dir("system-build") }
val configureNativeSystem = tasks.register<Exec>("configureNativeSystem") {
    onlyIf { !hostIsLinux }
    inputs.dir(file("native/system"))
    outputs.file(nativeSystemBuildDir.map { it.file("CMakeCache.txt") })
    commandLine("cmake", "-S", file("native/system").absolutePath, "-B", nativeSystemBuildDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release")
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
val hostArch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
val hostNativeName = when {
    hostIsLinux -> "linux-$hostArch"
    System.getProperty("os.name").startsWith("Windows") -> "windows-$hostArch"
    else -> "macos-$hostArch"
}
val hostNativeDirectory = layout.buildDirectory.dir("distribution-resources/$hostNativeName/native")
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
    ) + webView2Arguments
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
    mustRunAfter("stageLinuxNativeResources")
    inputs.dir(nativeBrowserBuildDir)
    outputs.dir(hostNativeDirectory)
    commandLine("cmake", "--install", nativeBrowserBuildDir.get().asFile.absolutePath, "--config", "Release",
        "--prefix", hostNativeDirectory.get().asFile.absolutePath)
}
val stageDesktopNativeResources = tasks.register("stageDesktopNativeResources") {
    group = "distribution"
    description = "Stages actual host-native libraries and required notices; refuses incomplete packages."
    dependsOn(buildNativeSystem, installNativeBrowser)
    if (hostIsLinux) dependsOn("stageLinuxNativeResources")
    // Recheck the closure on every package; a stale resource directory is not a native build.
    doLast {
        if (!hostIsLinux) {
            val input = providers.gradleProperty("desktopNativeDirectory").orNull?.let { file(it) }
                ?: error("Provide -PdesktopNativeDirectory=/host-built-media-closure; Linux output is not portable.")
            check(input.resolve(System.mapLibraryName("archivetune_engine")).isFile) { "Host playback engine missing" }
            val systemName = System.mapLibraryName("archivetune_system")
            val system = nativeSystemBuildDir.get().asFile.walkTopDown().firstOrNull { it.name == systemName && it.isFile }
                ?: error("Host native OS adapter missing")
            val host = if (System.getProperty("os.name").startsWith("Windows")) "windows" else "macos"
            val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
            copy { from(input); from(system); into(layout.buildDirectory.dir("distribution-resources/$host-$arch/native")) }
            copy { from(rootProject.file("LICENSE")); into(layout.buildDirectory.dir("distribution-resources/common/notices")) }
        }
        copy { from("packaging/notices"); into(layout.buildDirectory.dir("distribution-resources/common/notices")) }
        // TDLight is desktop-only. Its native library comes from the pinned source through the host's recipe, and its notice
        // must carry the verbatim Boost license from that tree. A package missing either is refused, not shipped with a
        // placeholder, because the Telegram path would fail at run time with no packaging-time signal.
        val tdlightStage = layout.buildDirectory.dir("tdlight-jvm/stage").get().asFile
        val tdlightLibrary = tdlightStage.resolve("$hostNativeName/native/${System.mapLibraryName("tdjni")}")
        check(tdlightLibrary.isFile) {
            // Each host has one recipe: build-tdjni.sh for Linux and macOS, build-tdjni.ps1 for Windows x64. No arm64
            // Windows recipe exists, so that host is named as uncovered rather than sent to a recipe that refuses it.
            when (hostNativeName) {
                "windows-x64" -> "Missing $tdlightLibrary; run powershell -ExecutionPolicy Bypass -File desktopApp/tdlight-jvm/native/build-tdjni.ps1 first."
                "linux-x64", "linux-arm64", "macos-arm64", "macos-x64" -> "Missing $tdlightLibrary; run bash desktopApp/tdlight-jvm/native/build-tdjni.sh first."
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
            runHostTool("dpkg-deb", "--raw-extract", deb.absolutePath, tree.absolutePath)
            val control = tree.resolve("DEBIAN/control")
            control.writeText(withDependsFields(control.readText(), linuxBrowserDebDepends))
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
    commandLine("cmake", "--build", nativeEngineBuildDir.get().asFile.absolutePath, "--parallel", "2")
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
