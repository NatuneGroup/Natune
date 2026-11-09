import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

@DisableCachingByDefault(because = "Validation-only task has no outputs.")
abstract class ValidateStartIoReleaseConfigurationTask : DefaultTask() {
    @get:Input
    abstract val appId: Property<String>

    @TaskAction
    fun validate() {
        // Official GMS release builds inject START_IO_APP_ID from a secret. Forks and CI that lack
        // that proprietary secret must still be able to assemble release APKs, so a blank value is a
        // warning rather than a hard failure. The Start.io identifier is not consumed by the app at
        // runtime, so building without it is safe (ads are simply not configured).
        if (appId.get().isBlank()) {
            logger.warn(
                "START_IO_APP_ID is not set; building the GMS release without a Start.io identifier.",
            )
        }
    }
}

@CacheableTask
abstract class GenerateDiscordSdkManifestTask : DefaultTask() {
    @get:Input abstract val redirectScheme: Property<String>
    @get:OutputFile abstract val manifestOutputFile: RegularFileProperty
    @TaskAction fun generate() {
        val output = manifestOutputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText("""
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools">
                <application>
                    <!-- The SDK's own callback receiver; official SDK docs use a scheme-only filter. -->
                    <activity
                        android:name="com.discord.socialsdk.AuthenticationActivity"
                        android:exported="true"
                        tools:replace="android:exported">
                        <intent-filter>
                            <action android:name="android.intent.action.VIEW" />
                            <category android:name="android.intent.category.DEFAULT" />
                            <category android:name="android.intent.category.BROWSABLE" />
                            <data android:scheme="${redirectScheme.get()}" />
                        </intent-filter>
                    </activity>
                    <!-- SDK builds authorize through AuthenticationActivity; the browser callback is inert. -->
                    <!-- Voice/call service; Natune uses auth and rich presence only. -->
                    <service android:name="com.discord.socialsdk.ForegroundService" tools:node="remove" />
                </application>
            </manifest>
        """.trimIndent())
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries.android)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// A provider credential as a Java string literal for buildConfigField; see buildSrc/ProviderCredentials.kt.
fun providerCredentialLiteral(name: String, vararg legacyNames: String): String =
    ProviderCredentials.javaStringLiteral(ProviderCredentials.resolve(project, name, *legacyNames))

// Stable workflow fallback when no vX.Y.Z release tag exists. Keep this assignment on one line
// because CI extracts it directly; both channels inject VERSION_NAME_OVERRIDE and
// VERSION_CODE_OVERRIDE without committing a version bump.
//
// The versionCode is not derived from this repository's commit count: the private KMP root has a
// fresh history below the published builds. CI allocates Unix epoch seconds plus
// trustedUpgradeVersionCodeFloor (see below), preserving the existing install/upgrade line.
//
// Nightly names use an unused UTC minute, "N<yyyyMMddHHmm>", for the release tag, title and app versionName.
val baseVersionName = "15.0.0"
val baseVersionCode = 1500
// Last trusted published build (Nightly N202610151104, build 6833). Signed builds must install over it in place.
val trustedUpgradeVersionCodeFloor = 6833

val forkRevision = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "rev-parse", "--short=12", "HEAD")
    }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")
val forkBranch = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "branch", "--show-current")
    }.standardOutput.asText.get().trim().ifEmpty { "detached" }
}.getOrDefault("unknown").filter { it.isLetterOrDigit() || it in "/._-" }
val forkDirty = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "status", "--porcelain", "--untracked-files=no")
    }.standardOutput.asText.get().isNotBlank()
}.getOrDefault(false)

// Settings › Additional downloads fetches optional files from addons/ instead of packing them into
// the APK. The URL pins the last commit that touched addons/ and the digest is this build's copy,
// so an installed app always fetches the exact bytes it was built with, however later releases
// change them. Empty outside a git checkout, which hides the downloads rather than guessing.
val addonsRevision = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "log", "-1", "--format=%H", "--", "addons")
    }.standardOutput.asText.get().trim()
}.getOrDefault("")
val addonsBaseUrl =
    if (addonsRevision.length == 40) {
        "https://raw.githubusercontent.com/NatuneGroup/Natune/$addonsRevision/addons"
    } else {
        ""
    }
fun addonSha256(relativePath: String): String =
    rootDir.resolve("addons/$relativePath").takeIf { it.isFile }?.let { file ->
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
    } ?: ""

val discordApplicationIdLong = sequenceOf(
    localProperties.getProperty("DISCORD_APPLICATION_ID"),
    System.getenv("DISCORD_APPLICATION_ID"),
).mapNotNull { value -> value?.trim()?.toLongOrNull()?.takeIf { it > 0L } }
    .firstOrNull() ?: 1569030685010821170L
val discordApplicationId = discordApplicationIdLong.toString()
val discordRedirectScheme = "discord-$discordApplicationId"
val discordSocialSdkAar = rootProject.file("third_party/discord-social-sdk/android/discord_partner_sdk.aar")
// Strictly opt-in: the property must be set AND the licensed AAR must exist. Default builds,
// release/nightly CI and pull-request builds never pass the property and never fetch the SDK.
val discordSocialSdkEnabled = providers.gradleProperty("natune.discordSdk").orNull
    ?.toBooleanStrictOrNull() == true && discordSocialSdkAar.isFile
// SDK builds receive the OAuth callback in the SDK's AuthenticationActivity; the app's browser
// callback keeps only an inert scheme so two activities never compete for the real redirect.
val discordOAuthCallbackScheme =
    if (discordSocialSdkEnabled) "discord-oauth-disabled-$discordApplicationId" else discordRedirectScheme
val releaseKeystoreFile = file("keystore/release.keystore")
val releaseStorePassword =
    System.getenv("STORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigningConfig =
    releaseKeystoreFile.isFile &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
// Signed builds must never be produced with a code that installs as older than the published line.
// The check runs at configuration time, before any signing or packaging task.
val versionCodeOverride = System.getenv("VERSION_CODE_OVERRIDE")?.trim().orEmpty()
val requestedVersionCode = versionCodeOverride.takeIf { it.isNotEmpty() }?.toIntOrNull()
require(versionCodeOverride.isEmpty() || requestedVersionCode != null) {
    "VERSION_CODE_OVERRIDE must be a positive Android versionCode integer."
}
require(requestedVersionCode == null || requestedVersionCode > 0) {
    "VERSION_CODE_OVERRIDE must be a positive Android versionCode integer."
}
require(
    !hasReleaseSigningConfig ||
        (requestedVersionCode != null && requestedVersionCode > trustedUpgradeVersionCodeFloor),
) {
    "Signed builds need VERSION_CODE_OVERRIDE above $trustedUpgradeVersionCodeFloor (an allocated code, not a commit count)."
}
// Start.io (StartApp) app identifier used to initialize the support-ads SDK in GMS builds.
// The fork ships a default committed ID so ads work out of the box; an override can still be
// supplied via local.properties or the START_IO_APP_ID environment variable (e.g. in CI).
val startIoAppId =
    (
        localProperties.getProperty("START_IO_APP_ID")
            ?: System.getenv("START_IO_APP_ID")
            ?: "206779743"
        ).trim()
tasks.register<ValidateStartIoReleaseConfigurationTask>("validateStartIoReleaseConfiguration") {
    group = "verification"
    description = "Validates the production Start.io identifier for GMS release artifacts."
    appId.set(startIoAppId)
}

tasks.configureEach {
    val isGmsReleaseArtifactTask =
        (name.startsWith("assemble") || name.startsWith("bundle")) &&
            name.contains("Gms") &&
            name.endsWith("Release")
    if (isGmsReleaseArtifactTask) {
        dependsOn("validateStartIoReleaseConfiguration")
    }
}

// Debug builds use the standard AGP debug keystore (auto-generated locally, never committed).
// Release builds require a private keystore at app/keystore/release.keystore plus the
// KEYSTORE / KEY_ALIAS / KEYSTORE_PASSWORD / KEY_PASSWORD secrets. The previously
// committed debug keystore was removed from the tree (same key now lives only in
// GitHub Secrets) and must not be restored.

// Automix's local beat/vocal analysis runs on ONNX models through the onnxruntime AAR:
// ~27 MiB of native runtime per ABI (double in a universal APK) plus ~13 MiB of models.
// Off by default — the slim APK ships without either and the analyzers fall back to the
// plain crossfade path, which they already treat as a missing model. CI builds the full
// variant as a separate artifact with -Pautomix=true.
val automix = (project.findProperty("automix") as String?)?.toBoolean() ?: false

android {
    namespace = "moe.rukamori.archivetune"
    compileSdk = 37

    // Pinned so the NDK used by every builder is identical (AGP otherwise picks whatever
    // the local SDK holds); reproducible-build hash comparisons depend on it (#1323).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        buildConfigField("boolean", "DISCORD_SOCIAL_SDK_AVAILABLE", discordSocialSdkEnabled.toString())
        applicationId = "moe.rukamori.archivetune"
        // API 24 floor. Platform calls newer than 24 are guarded at their call sites, and the
        // AAudio entry points are loaded at runtime (native/dsp) so the library loads on 24-25.
        minSdk = 24
        targetSdk = 37
        // Version. Unsigned local builds use the committed base values. Signed CI builds pass an
        // allocated VERSION_CODE_OVERRIDE above trustedUpgradeVersionCodeFloor, enforced above.
        versionCode = requestedVersionCode ?: baseVersionCode
        versionName =
            System.getenv("VERSION_NAME_OVERRIDE")?.trim()?.takeIf { it.isNotEmpty() } ?: baseVersionName
        buildConfigField("String", "FORK_REVISION", "\"$forkRevision\"")
        buildConfigField("String", "FORK_BRANCH", "\"$forkBranch\"")
        buildConfigField("boolean", "FORK_DIRTY", forkDirty.toString())
        buildConfigField("String", "ADDONS_BASE_URL", "\"$addonsBaseUrl\"")
        buildConfigField(
            "String",
            "SPATIALFLOW_FONT_SHA256",
            "\"${addonSha256("fonts/spatialflow_google_sans_flex.ttf")}\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Provider credentials are never committed. Each value comes from the environment (CI
        // secrets), then local.properties, then a Gradle property. A blank value leaves the dependent
        // feature reporting "not configured"; the variable list is in docs/build-credentials.md.
        listOf(
            "ARCHIVETUNE_AMAZON_TURNSTILE_SITEKEY",
            "ARCHIVETUNE_AMAZON_TURNSTILE_ORIGIN",
            "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_ID",
            "ARCHIVETUNE_YOUTUBE_OAUTH_CLIENT_SECRET",
            "ARCHIVETUNE_POTOKEN_GOOGLE_API_KEY",
            "ARCHIVETUNE_POTOKEN_REQUEST_KEY",
            "ARCHIVETUNE_TIDAL_CLIENT_ID",
            "ARCHIVETUNE_TIDAL_CLIENT_SECRET",
            "ARCHIVETUNE_TIDAL_PKCE_CLIENT_ID",
            "ARCHIVETUNE_TIDAL_PKCE_CLIENT_SECRET",
        ).forEach { key -> buildConfigField("String", key, providerCredentialLiteral(key)) }
        // Last.fm keeps its historical BuildConfig names; the legacy environment names still resolve.
        buildConfigField(
            "String",
            "LASTFM_API_KEY",
            providerCredentialLiteral("ARCHIVETUNE_LASTFM_API_KEY", "LASTFM_API_KEY"),
        )
        buildConfigField(
            "String",
            "LASTFM_SECRET",
            providerCredentialLiteral("ARCHIVETUNE_LASTFM_API_SECRET", "LASTFM_SECRET"),
        )
        // The built-in Lyprio lyrics server pair follows the same pattern: canonical ARCHIVETUNE_*
        // names first, unprefixed names as the fallback. Both values must be present for the bundled
        // endpoint to exist; it is the only endpoint Lyprio can have (see LyprioEndpoint).
        buildConfigField(
            "String",
            "LYPRIO_BASE_URL",
            providerCredentialLiteral("ARCHIVETUNE_LYPRIO_BASE_URL", "LYPRIO_BASE_URL"),
        )
        buildConfigField(
            "String",
            "LYPRIO_HMAC_KEY",
            providerCredentialLiteral("ARCHIVETUNE_LYPRIO_HMAC_KEY", "LYPRIO_HMAC_KEY"),
        )
        // The Librespot sign-in is a public OAuth client: PKCE proves the caller, so only the
        // application id is baked in — never a secret. A blank build leaves the sign-in disabled
        // and reporting "not configured".
        buildConfigField(
            "String",
            "ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID",
            providerCredentialLiteral("ARCHIVETUNE_SPOTIFY_OAUTH_CLIENT_ID", "SPOTIFY_CLIENT_ID"),
        )

        // Telegram (TDLib) app credentials. Baked in at build time so users sign in with just
        // their phone number + login code — no my.telegram.org api_id/api_hash entry. Override via
        // local.properties or the TELEGRAM_API_ID / TELEGRAM_API_HASH env vars (e.g. in CI) to ship
        // the fork's own registered app. The fallback is the public Telegram Desktop api_id/hash,
        // which every TDLib client can use out of the box.
        val telegramApiId =
            (
                localProperties.getProperty("TELEGRAM_API_ID")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("TELEGRAM_API_ID")?.takeIf { it.isNotBlank() }
                    ?: "2040"
            ).trim()
        val telegramApiHash =
            (
                localProperties.getProperty("TELEGRAM_API_HASH")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("TELEGRAM_API_HASH")?.takeIf { it.isNotBlank() }
                    ?: "b18441a1ff607e10a989891a5462e627"
            ).trim()
        buildConfigField("int", "TELEGRAM_API_ID", telegramApiId)
        buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")

        // libtdjni.so is never packaged: the app downloads the gzip-compressed, digest-pinned
        // library for its own ABI the first time someone opens Telegram (see TdLibNativeLibrary),
        // so the ~46 MB of natives an optional integration needs is not paid for by every user.
        //
        // `-PslimTdlib=false` bundles it instead, which requires placing libtdjni.so under
        // app/src/main/jniLibs/<abi>/ by hand — there is no longer a Maven artifact to take it
        // from, since the org.drinkless.tdlib binding is vendored.
        val slimTdlib = (project.findProperty("slimTdlib") as String?)?.toBoolean() ?: true
        buildConfigField("boolean", "TDLIB_BUNDLED", "${!slimTdlib}")
        buildConfigField(
            "String",
            "TDLIB_NATIVE_BASE_URL",
            "\"${project.findProperty("tdlibNativeBaseUrl") as String?
                ?: "https://github.com/NatuneGroup/ArchiveTune/releases/download/tdlight-2b51b33"}\"",
        )

        // In-app announcements feed ("News"): a JSON index plus Markdown content served
        // first-party from the Natune site. Precedence: local.properties, then the
        // NATUNE_NEWS_URL env/CI variable, then the baked-in default. An explicitly blank
        // value disables the feature and hides the News entry point.
        val natuneNewsUrl =
            (
                localProperties.getProperty("NATUNE_NEWS_URL")
                    ?: System.getenv("NATUNE_NEWS_URL")
                    ?: "https://natune.pages.dev/api/news"
                ).trim().trimEnd('/')
        buildConfigField("String", "NATUNE_NEWS_URL", "\"$natuneNewsUrl\"")

        // Base URL of the community Source Pool website (Next.js). When set, the app auto-discovers
        // health-checked Tidal/Qobuz instances from it. Precedence: local.properties override, then
        // the SOURCE_PROVIDER_URL env/CI variable, then the baked-in default below. Set to "" in
        // local.properties to disable remote discovery for a build.
        // Use .takeIf { it.isNotBlank() } on each source so an explicitly-empty env var or
        // local.properties entry doesn't shadow the hardcoded fallback (a plain `?: fallback`
        // chain would leave the URL blank when the env var is set to "" rather than unset).
        val sourceProviderUrl =
            (
                localProperties.getProperty("SOURCE_PROVIDER_URL")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("SOURCE_PROVIDER_URL")?.takeIf { it.isNotBlank() }
                    ?: "https://archivepool.vercel.app"
                ).trim().trimEnd('/')
        buildConfigField("String", "SOURCE_PROVIDER_URL", "\"$sourceProviderUrl\"")

        // The dedicated ArchivePool deployment override. Same canonical/legacy env pair as the
        // other build credentials: ARCHIVETUNE_ARCHIVEPOOL_BASE_URL first, unprefixed
        // ARCHIVEPOOL_BASE_URL as the fallback. When set and https it wins over
        // SOURCE_PROVIDER_URL as the primary; the public default remains the ordered fallback.
        buildConfigField(
            "String",
            "ARCHIVEPOOL_BASE_URL",
            providerCredentialLiteral("ARCHIVETUNE_ARCHIVEPOOL_BASE_URL", "ARCHIVEPOOL_BASE_URL"),
        )

        // Per-app read key for the Source Pool. Sent as a Bearer token on discovery requests so the
        // pool can gate access. Optional: blank works fine while the pool runs unenforced.
        val sourceProviderKey =
            (
                localProperties.getProperty("SOURCE_PROVIDER_KEY")
                    ?: System.getenv("SOURCE_PROVIDER_KEY")
                    ?: ""
                ).trim()
        buildConfigField("String", "SOURCE_PROVIDER_KEY", "\"$sourceProviderKey\"")

        // LEGACY end-to-end decryption key for sensitive Source Pool credentials (base64 32-byte
        // AES-256 key, matching the site's POOL_CLIENT_KEY). Current builds request the v2 feed
        // protocol (X-Pool-Client: v2), where the encryption key is derived from the read key
        // above and this value is only a fallback for older pool deployments. Optional: blank is
        // fine when every configured pool speaks v2.
        val poolClientKey =
            (
                localProperties.getProperty("POOL_CLIENT_KEY")
                    ?: System.getenv("POOL_CLIENT_KEY")
                    ?: ""
                ).trim()
        buildConfigField("String", "POOL_CLIENT_KEY", "\"$poolClientKey\"")

        // Opt-in hosted feedback service (Natune site + Vercel API). Empty by default, which
        // hides the whole feature: no playback-error feedback action, no crash capture, no
        // crash prompt. When set, the app offers per-report review + explicit send by the user
        // only. The ingest key travels with the APK and is public by nature — the server must
        // treat it as write-only container access, never as report-read or admin authority.
        val feedbackApiUrl =
            (
                localProperties.getProperty("FEEDBACK_API_URL")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("FEEDBACK_API_URL")?.takeIf { it.isNotBlank() }
                    ?: ""
                ).trim().trimEnd('/')
        val feedbackIngestKey =
            (
                localProperties.getProperty("FEEDBACK_INGEST_KEY")
                    ?: System.getenv("FEEDBACK_INGEST_KEY")
                    ?: ""
                ).trim()
        buildConfigField("String", "FEEDBACK_API_URL", "\"$feedbackApiUrl\"")
        buildConfigField("String", "FEEDBACK_INGEST_KEY", "\"$feedbackIngestKey\"")

        // Opt-in listener presence (the anonymous live listener map). Blank by default, which keeps the
        // feature silent: no heartbeat and no request. Must be the https:// origin of services/listener-presence.
        val listenerPresenceUrl =
            ProviderCredentials.resolve(project, "ARCHIVETUNE_LISTENER_PRESENCE_URL").trimEnd('/')
        buildConfigField("String", "ARCHIVETUNE_LISTENER_PRESENCE_URL", "\"$listenerPresenceUrl\"")

        // Amazon Music Web API. Access is approval-gated — Amazon issues a Login with Amazon
        // security profile (client id) to approved partners only — so the client id defaults to
        // blank and the source stays inert until it is set: a blank client id makes
        // AmazonMusicProvider refuse every resolve, and playback falls through to the next source
        // exactly as it did before this existed. Never commit a real value; local.properties or CI
        // secrets only.
        val amazonApiBase =
            (
                localProperties.getProperty("AMAZON_API_BASE")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("AMAZON_API_BASE")?.takeIf { it.isNotBlank() }
                    ?: "https://api.music.amazon.dev"
                ).trim().trimEnd('/')
        buildConfigField("String", "AMAZON_API_BASE", "\"$amazonApiBase\"")

        val amazonLwaClientId =
            (
                localProperties.getProperty("AMAZON_LWA_CLIENT_ID")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("AMAZON_LWA_CLIENT_ID")?.takeIf { it.isNotBlank() }
                    ?: ""
                ).trim()
        buildConfigField("String", "AMAZON_LWA_CLIENT_ID", "\"$amazonLwaClientId\"")

        val nightlyBuildHash =
            (
                localProperties.getProperty("NIGHTLY_BUILD_HASH")
                    ?: System.getenv("NIGHTLY_BUILD_HASH")
                    ?: ""
                ).trim()
        buildConfigField("String", "NIGHTLY_BUILD_HASH", "\"$nightlyBuildHash\"")
        // True only for builds produced by the nightly workflow (it sets IS_NIGHTLY_BUILD). Used to
        // default the in-app updater to the NIGHTLY channel and to compare pre-release builds by
        // their monotonic versionCode rather than the fixed display versionName.
        val isNightlyBuild =
            (System.getenv("IS_NIGHTLY_BUILD") ?: localProperties.getProperty("IS_NIGHTLY_BUILD") ?: "")
                .trim()
                .equals("true", ignoreCase = true)
        buildConfigField("boolean", "IS_NIGHTLY", "$isNightlyBuild")
        buildConfigField("String", "DISTRIBUTION", "\"gms\"")
        buildConfigField("boolean", "UPDATER_AVAILABLE", "true")
        // The two in-tree JNI libraries own their C++ objects and catch exceptions internally.
        // Optional prebuilt SDK/runtime variants retain the shared STL conservatively.
        externalNativeBuild {
            cmake {
                val androidStl = if (discordSocialSdkEnabled || automix) "c++_shared" else "c++_static"
                arguments += "-DANDROID_STL=$androidStl"
                arguments += "-DNATUNE_DISCORD_SOCIAL_SDK=${if (discordSocialSdkEnabled) "ON" else "OFF"}"
            }
        }
    }

    flavorDimensions += listOf("distribution", "device", "abi")
    productFlavors {
        create("gms") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("String", "DISTRIBUTION", "\"gms\"")
            buildConfigField("boolean", "UPDATER_AVAILABLE", "true")
            buildConfigField("String", "DISCORD_APPLICATION_ID", "\"$discordApplicationId\"")
            buildConfigField("long", "DISCORD_APPLICATION_ID_LONG", "${discordApplicationIdLong}L")
            buildConfigField("String", "DISCORD_REDIRECT_SCHEME", "\"$discordRedirectScheme\"")
            manifestPlaceholders["discordRedirectScheme"] = discordRedirectScheme
            manifestPlaceholders["discordOAuthCallbackScheme"] = discordOAuthCallbackScheme
        }
        create("foss") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"foss\"")
            buildConfigField("boolean", "UPDATER_AVAILABLE", "true")
            buildConfigField("String", "DISCORD_APPLICATION_ID", "\"$discordApplicationId\"")
            buildConfigField("long", "DISCORD_APPLICATION_ID_LONG", "${discordApplicationIdLong}L")
            buildConfigField("String", "DISCORD_REDIRECT_SCHEME", "\"$discordRedirectScheme\"")
            manifestPlaceholders["discordRedirectScheme"] = discordRedirectScheme
            manifestPlaceholders["discordOAuthCallbackScheme"] = discordOAuthCallbackScheme
        }
        create("mobile") {
            dimension = "device"
            buildConfigField("String", "DEVICE", "\"mobile\"")
        }
        create("tv") {
            dimension = "device"
            buildConfigField("String", "DEVICE", "\"tv\"")
            ndk { abiFilters += listOf("armeabi-v7a", "x86") }
        }
        create("automotive") {
            dimension = "device"
            minSdk = 28
            buildConfigField("String", "DEVICE", "\"automotive\"")
        }
        create("universal") {
            dimension = "abi"
            // Only the two 64-bit ABIs, so the universal APK stays close to a per-ABI one.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
            buildConfigField("String", "ARCHITECTURE", "\"universal\"")
        }
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
            buildConfigField("String", "ARCHITECTURE", "\"arm64\"")
        }
        create("armeabi") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
            buildConfigField("String", "ARCHITECTURE", "\"armeabi\"")
        }
        create("x86") {
            dimension = "abi"
            ndk { abiFilters += "x86" }
            buildConfigField("String", "ARCHITECTURE", "\"x86\"")
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
            buildConfigField("String", "ARCHITECTURE", "\"x86_64\"")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigningConfig) {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
        // Debug uses AGP's default debug keystore; no committed keystore.
    }

    buildTypes {
        release {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Slim builds do not package the runtime, so keeping the whole package there would
            // only pin dead classes into the dex.
            if (automix) proguardFiles("proguard-onnx.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            val qaOptimized = providers.gradleProperty("qaOptimized").orNull?.toBoolean() ?: false
            isMinifyEnabled = qaOptimized
            isShrinkResources = qaOptimized
            if (qaOptimized) {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
        }
    }

    compileOptions {
        // java.time, java.util.Base64 and other Java 8+ APIs are used below API 26 (minSdk 24).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        lintConfig = file("lint.xml")
        warningsAsErrors = false
        abortOnError = false
        checkDependencies = false
        // lintVital re-analyses the whole app on every assembleRelease and, with abortOnError off,
        // can never fail the build. On a 4-vCPU runner it ran beside R8 for minutes and slowed it.
        // PR builds still run the full :app:lint task.
        checkReleaseBuilds = false
    }

    androidResources {
        generateLocaleConfig = true
        // Restrict resource locales to the languages the app itself ships translations for
        // (app/src/main/res/values-*) plus the base languages of every entry in the in-app
        // language picker (LanguageCodeToName), so androidx/Play/Media3 translations for
        // other locales are dropped from resources.arsc. Measured against the canary APK:
        // ~1.16 MB of the stored (uncompressible) resources.arsc. Entries use aapt2 -c
        // qualifier syntax ("de-rDE", "zh-rTW"); "in"/"iw" are the legacy Java codes aapt2
        // normalizes to id/he, "nb" covers the picker's "no". The default (English) config
        // is always kept and must not be listed.
        localeFilters += listOf(
            "en",
            "en-rGB",
            "ar",
            "arz",
            "bg",
            "cs",
            "da",
            "de",
            "de-rDE",
            "el",
            "es",
            "et",
            "fi",
            "fr",
            "hi",
            "hr",
            "hu",
            "in",
            "in-rID",
            "it",
            "iw",
            "ja",
            "jv",
            "ko",
            "lt",
            "lv",
            "ml",
            "ms",
            "nb",
            "nl",
            "pl",
            "pt",
            "pt-rBR",
            "ro",
            "ru",
            "sa",
            "sk",
            "sr",
            "sv",
            "ta",
            "th",
            "tr",
            "uk",
            "ur",
            "vi",
            "zh-rCN",
            "zh-rTW",
        )
    }

    // Automix's native analysis front end (tempo/key/energy/structure DSP plus the mel and vocal
    // STFT front ends the ONNX models read), ported from 4nx3b.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // Compress native libs inside the APK and extract only the device's ABI at install.
            useLegacyPackaging = true
            // Belt and braces for the default slim build: nothing should be supplying a
            // libtdjni.so, but a stale jniLibs/ drop would silently re-inflate the APK.
            if ((project.findProperty("slimTdlib") as String?)?.toBoolean() ?: true) {
                excludes += "**/libtdjni.so"
            }
            if (!automix) {
                excludes += listOf(
                    "**/libonnxruntime.so",
                    "**/libonnxruntime4j_jni.so",
                )
            } else {
                // Analysis targets arm64 phones; the x86_64 copy exists for emulators and
                // Chromebooks and doubles the runtime's cost in a universal APK.
                excludes += listOf(
                    "lib/x86_64/libonnxruntime.so",
                    "lib/x86_64/libonnxruntime4j_jni.so",
                )
            }
            keepDebugSymbols += listOf(
                "**/libandroidx.graphics.path.so",
                "**/libdatastore_shared_counter.so",
                // Cargo already strips this library; preserve the bytes checked by the CI manifest.
                "**/libnatune_librespot.so"
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/CONTRIBUTORS.md"
            excludes += "META-INF/LICENSE.md"
            // Installed on demand from Lyrics settings; saves roughly 13 MiB per APK.
            excludes += "com/atilika/kuromoji/ipadic/*.bin"
            // BouncyCastle PQ legacy parameters and localized error strings ship inside bcpkix/bcprov
            // jars but are only reachable through the desktop PQ APIs the app never calls; upstream
            // excludes them. Dropped by mistake during the packaging-block rewrite — ~1.17 MiB.
            excludes += "org/bouncycastle/pqc/legacy/picnic/**"
            excludes += "org/bouncycastle/x509/CertPathReviewerMessages*.properties"
            // org/mozilla/javascript/resources and org/commonmark/internal/util hold no classes,
            // only properties files read at runtime (Rhino's message bundle, commonmark's HTML
            // entity table, loaded by a static initializer); excluding them crashes on first use.
            // Additional safe META-INF / metadata excludes — none of these are read at runtime by
            // the app or any of its libraries (verified by checking for ServiceLoader / reflection
            // usage on each). They are pure build-time / IDE metadata and just bloat every APK.
            // - META-INF/DEPENDENCIES: Maven dependency manifest, only used by build tooling.
            // - META-INF/INDEX.LIST: JAR index used by desktop ClassLoaders, never by Android.
            // - META-INF/io.netty.versions.properties: Netty version manifest, runtime-irrelevant.
            // - META-INF/*.version: per-library version files (e.g. kotlin-stdlib.version).
            // - DebugProbesKt.bin: kotlinx.coroutines debug binary, only consulted by debugger.
            // - kotlin-tooling-metadata.json: Kotlin tooling manifest, build-time only.
            // - META-INF/buildinfo.properties / build.archives: Gradle/AGP build metadata.
            // - META-INF/com.android.tools/**: AGP build-metadata, not consumed at runtime.
            // - META-INF/proguard/**: bundled proguard configs, only needed at minify time.
            // DO NOT add: META-INF/services/** (ServiceLoader), META-INF/MANIFEST.MF (signing +
            // attributes), META-INF/*.kotlin_module (Kotlin reflection) — those are load-bearing.
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/*.version"
            excludes += "DebugProbesKt.bin"
            excludes += "kotlin-tooling-metadata.json"
            excludes += "META-INF/buildinfo.properties"
            excludes += "META-INF/build.archives"
            excludes += "META-INF/com.android.tools/**"
            excludes += "META-INF/proguard/**"
            // Room's schema JSONs are committed KSP output under shared-data's commonMain resources so
            // PortableStateMigrationTest can load them from the JVM test classpath. Nothing at runtime,
            // backup or import reads them, so only the members that shipped are dropped here. They are
            // listed one by one, never by namespace, so a future production schema under room-schemas/
            // still reaches the APK and cannot disappear silently.
            excludes += listOf(
                "room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/1.json",
                "room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/7.json",
                "room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/8.json",
                "room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/9.json",
                "room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/10.json",
                "room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/11.json",
                "room-schemas/moe.rukamori.archivetune.shared.data.persistence.PortableStateDatabase/12.json",
            )
        }
    }

    if (automix) {
        // The analysis models ride in their own asset dir so the default APK never
        // packages them; the trackers already treat a missing asset as no analysis.
        sourceSets.getByName("main") {
            assets.srcDir("src/automix/assets")
        }
    }

}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Listen Together wire protocol note: the protocol is encoded by hand in
// listentogether/ProtoWire.kt + MessageCodec.kt against the field numbers in
// app/src/main/proto/listentogether.proto. No protobuf runtime is declared because two other
// routes are blocked in this build: protobuf-gradle-plugin cannot apply to AGP 9 (it casts the
// app extension to the removed BaseExtension), and a pre-generated copy collides with the
// protobuf-java runtime another dependency already ships (same `com.google.protobuf` package).

dependencies {
    if (discordSocialSdkEnabled) {
        implementation(files(discordSocialSdkAar))
        implementation("androidx.browser:browser:1.8.0")
    }
    // Automix: the Beat This! beat/downbeat and open-unmix vocal models run through ONNX Runtime.
    // The full android artifact, not -mobile: mobile only loads .ort sessions.
    implementation(libs.onnxruntime.android)
    implementation(libs.guava)
    implementation(libs.coroutines.guava)
    implementation(libs.concurrent.futures)

    implementation(libs.activity)
    implementation(libs.core.splashscreen)
    implementation(libs.navigation)
    implementation(libs.hilt.navigation)
    implementation(libs.datastore)
    implementation(libs.work.runtime)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    compileOnly("androidx.compose.ui:ui-tooling-preview:${libs.versions.compose.get()}")
    debugImplementation("androidx.compose.ui:ui-tooling-preview:${libs.versions.compose.get()}")
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.reorderable)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    implementation(libs.viewmodel)
    implementation(libs.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.material3)
    implementation(libs.androidx.graphics.shapes)
    
    implementation(libs.palette)
    implementation(libs.androidsvg)
    implementation(libs.aboutlibraries.core)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.html)
    implementation(libs.markwon.image)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.simple.ext)

    implementation(libs.coil)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)

    implementation(libs.shimmer)

    // Glance Widget support
    implementation("androidx.glance:glance:1.1.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation(libs.androidx.webkit)

    implementation(libs.media3)
    implementation("androidx.media3:media3-exoplayer-hls:${libs.versions.media3.get()}")
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)
    implementation("androidx.media3:media3-ui:${libs.versions.media3.get()}")
    implementation("androidx.media3:media3-ui-compose:${libs.versions.media3.get()}")
    add("gmsImplementation", libs.media3.cast)
    add("gmsImplementation", libs.mediarouter)
    // Drive backup authorization (gms source set only): Identity/AuthorizationClient
    // for com.google.android.gms.auth.api.identity.* in the gms Drive stack.
    add("gmsImplementation", "com.google.android.gms:play-services-auth:22.0.0")
    implementation(libs.squigglyslider)

    // The Telegram engine has no Maven artifact: org.drinkless.tdlib Client/TdApi is vendored
    // under app/src/main/java, generated by the same TDLight commit that builds the
    // runtime-downloaded libtdjni.so, so the TL schema always matches the native library.


    implementation(libs.room.runtime)
    implementation(libs.kuromoji.ipadic)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.apache.lang3)

    // Liquid glass / backdrop blur effect for the SimpMusic-style floating
    // header pills on album / artist / playlist screens.
    implementation(libs.liquid.glass)

    implementation(libs.hilt)
    implementation(libs.re2j)
    annotationProcessor(libs.kotlin.metadata.jvm)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata.jvm)

    implementation(project(":shared-domain"))
    implementation(project(":shared-data"))
    implementation(project(":shared-ui"))
    implementation(project(":core"))
    implementation(project(":lyrics:kugou"))
    implementation(project(":lyrics:lrclib"))
    implementation(project(":lyrics:simpmusic"))
    implementation(project(":lyrics:paxsenix"))
    implementation(project(":lyrics:betterlyrics"))
    implementation(project(":lyrics:unison"))
    implementation(project(":lyrics:youlyplus"))
    implementation(project(":musixmatch"))
    implementation(project(":lastfm"))
    implementation(project(":canvas"))
    implementation(project(":shazamkit"))
    implementation(project(":spotifycore"))
    implementation(project(":deobfuscator"))
    implementation(project(":jiosaavn"))
    implementation(project(":modules"))
    implementation("com.materialkolor:material-kolor:5.0.0-alpha07")

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    // InnerTubeX — first extractor tier behind the shipped YouTube resolver. Its own
    // InnerTube client and cipher stack sit beside :core's; they share no package namespace, so
    // the NewPipe extractor classes are unaffected.
    implementation(libs.innertubex)
    // The same MetrolistExtractor artifact :core already ships (core declares it `implementation`,
    // so it is not on :app's compile classpath). Declared here only so the NewPipe tier can drive
    // NewPipe's stream extractor directly; Gradle resolves one copy, so the APK does not grow.
    implementation(libs.metrolist.extractor)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)

    coreLibraryDesugaring(libs.desugaring)

    implementation(libs.timber)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    implementation(libs.translator)
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    // androidx.car.app — CarConnection, which tells the Android Auto settings screen whether
    // the app is projected through Android Auto or running natively on Android Automotive.
    implementation(libs.car.app)
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0-rc01")
    implementation(libs.accompanist.lyrics.ui)
    implementation(libs.accompanist.lyrics.core)

    implementation(libs.json)

    // PRDownloader — lightweight (~45 KB) file download library with
    // pause/resume, retry, and progress callbacks. Used as the HTTP
    // fetcher inside PRDownloaderDataSource (a Media3 DataSource wrapper).
    // Replaces Ketch — Ketch's WorkManager + Flow observation added
    // overhead without improving throughput, and its temp-file lifecycle
    // occasionally left partial files that corrupted subsequent exports.
    // PRDownloader is simpler (single OkHttp call per download, callback
    // API instead of Flow), which makes the temp-file lifecycle easier
    // to reason about.
    implementation(libs.prdownloader)

    // jaudiotagger — pure-Java audio metadata tagger (ID3v2 / Vorbis Comments
    // / MP4 / FLAC). Used by AudioTagger to write title / artist / album /
    // year / track-number / artwork tags onto exported downloaded songs so
    // they show up correctly in external music players. Pinned to 1.4.x
    // (Java 21 bytecode) — the 2.x line targets Java 25 which Android cannot
    // consume.
    implementation("com.github.RouHim:jaudiotagger:1.4.31")
    // SLF4J binding required by jaudiotagger at runtime — jaudiotagger
    // depends on slf4j-api but does not bundle a binding. slf4j-jdk14
    // routes log calls through java.util.logging (which Android forwards
    // to logcat). Without this, jaudiotagger logs a single "no SLF4J
    // providers found" warning at startup and silently no-ops logging.
    implementation("org.slf4j:slf4j-jdk14:2.0.17")

    // QuickJS + BouncyCastle for yt-dlp stream resolution (signature
    // verification of yt-dlp releases + JS challenge evaluation).
    implementation(libs.quickjs.kt)
    implementation(libs.bcpg)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        // The automotive manifest removes MainActivity, so the generated icon-pack manifest —
        // whose startup gate opens MainActivity — would lead to a component not in the APK.
        // Match the flavour, not the name.
        if (variant.productFlavors.any { (_, flavor) -> flavor == "automotive" }) return@onVariants
        val capitalizedVariantName =
            variant.name.replaceFirstChar { character ->
                if (character.isLowerCase()) character.titlecase() else character.toString()
            }
        val generateIconPack =
            tasks.register<GenerateIconPackTask>("generate${capitalizedVariantName}IconPack") {
                metadataFile.set(rootProject.layout.projectDirectory.file("IconPack/metadata.json"))
                svgDirectory.set(rootProject.layout.projectDirectory.dir("IconPack/svg"))
                applicationId.set(variant.applicationId)
                targetActivityClassName.set("moe.rukamori.archivetune.MainActivity")
                resourceOutputDirectory.set(
                    layout.buildDirectory.dir("generated/iconPack/${variant.name}/res"),
                )
                assetOutputDirectory.set(
                    layout.buildDirectory.dir("generated/iconPack/${variant.name}/assets"),
                )
                manifestOutputFile.set(
                    layout.buildDirectory.file(
                        "generated/iconPack/${variant.name}/AndroidManifest.xml",
                    ),
                )
            }

        variant.sources.res?.addGeneratedSourceDirectory(
            generateIconPack,
            GenerateIconPackTask::resourceOutputDirectory,
        )
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateIconPack,
            GenerateIconPackTask::assetOutputDirectory,
        )
        variant.sources.manifests.addGeneratedManifestFile(
            generateIconPack,
            GenerateIconPackTask::manifestOutputFile,
        )
        if (discordSocialSdkEnabled) {
            val sdkManifest = tasks.register<GenerateDiscordSdkManifestTask>("generate${capitalizedVariantName}DiscordSdkManifest") {
                redirectScheme.set(discordRedirectScheme)
                manifestOutputFile.set(layout.buildDirectory.file("generated/discordSdk/${variant.name}/AndroidManifest.xml"))
            }
            variant.sources.manifests.addGeneratedManifestFile(sdkManifest, GenerateDiscordSdkManifestTask::manifestOutputFile)
        }

    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:featureFlag=OptimizeNonSkippingGroups",
        )
        suppressWarnings.set(true)
    }
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.compose.runtime:runtime:${libs.versions.compose.get()}",
        "androidx.compose.foundation:foundation:${libs.versions.compose.get()}",
        "androidx.compose.ui:ui:${libs.versions.compose.get()}",
        "androidx.compose.ui:ui-util:${libs.versions.compose.get()}",
        "androidx.compose.ui:ui-tooling:${libs.versions.compose.get()}",
        "androidx.compose.animation:animation-graphics:${libs.versions.compose.get()}",
        "org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlinMetadata.get()}",
    )
}

// The app's module host contract tests run with its existing CI unit-test gate.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(":modules:testDebugUnitTest")
}

// Librespot JNI libraries are prepared by CI (native/librespot/build-android.sh) before assembly.
val librespotLibraries = layout.buildDirectory.dir("generated/librespot/jniLibs")
val requireLibrespotAudio = providers.gradleProperty("requireLibrespotAudio").map(String::toBoolean).orElse(false)
android {
    // The SourceSet API refuses Provider values; the directory is fixed under build/, so resolve it eagerly.
    sourceSets.getByName("main").jniLibs.srcDir(librespotLibraries.get().asFile)
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val abiFlavor = variant.productFlavors.first { it.first == "abi" }.second
        val flavorAbis = when (abiFlavor) {
            "universal" -> listOf("arm64-v8a", "x86_64")
            "arm64" -> listOf("arm64-v8a")
            "armeabi" -> listOf("armeabi-v7a")
            "x86" -> listOf("x86")
            "x86_64" -> listOf("x86_64")
            else -> error("Unsupported Librespot ABI flavor: $abiFlavor")
        }
        val isTv = variant.productFlavors.any { it.first == "device" && it.second == "tv" }
        val abis = (flavorAbis + if (isTv) listOf("armeabi-v7a", "x86") else emptyList()).distinct()
        val capitalized = variant.name.replaceFirstChar { it.uppercaseChar() }
        val verify = tasks.register("verify${capitalized}LibrespotAudio") {
            doLast {
                val anyLibrary = abis.any { librespotLibraries.get().file("$it/libnatune_librespot.so").asFile.exists() }
                if (!requireLibrespotAudio.get() && !anyLibrary) {
                    logger.warn("Librespot audio unavailable for ${variant.name}: no prepared JNI; playback uses the configured source fallback.")
                    return@doLast
                }
                abis.forEach { abi ->
                    val library = librespotLibraries.get().file("$abi/libnatune_librespot.so").asFile
                    check(library.isFile && library.length() > 0L) {
                        "Missing Librespot audio for $abi; CI must run native/librespot/build-android.sh $abiFlavor before APK assembly."
                    }
                }
            }
        }
        // CI requires the complete closure after native preparation succeeds. Toolchain failure
        // can explicitly ship without this optional source; partial JNI closures are never valid.
        tasks.matching { it.name == "merge${capitalized}NativeLibs" }.configureEach { dependsOn(verify) }
    }
}
