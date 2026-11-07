package moe.rukamori.archivetune.desktop.potoken

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The operating systems the pinned PO token helper publishes a binary for. */
internal enum class BgutilHostPlatform { LINUX, MACOS, WINDOWS }

/** The architectures the pinned PO token helper publishes a binary for; other hosts stay unsupported. */
internal enum class BgutilHostArch { X64, ARM64 }

/**
 * One pinned release asset. [sha256] is the digest of the exact bytes the official release publishes and is
 * verified again after every download and on every reuse, so a tampered cache entry never reaches execution.
 */
internal data class BgutilSidecarSpec(
    val platform: BgutilHostPlatform,
    val arch: BgutilHostArch,
    val assetName: String,
    val sha256: String,
    val binaryFileName: String,
    val assetBytes: Long,
) {
    val downloadUrl: String get() = "${BgutilSidecarRelease.DOWNLOAD_BASE}/$assetName"
}

/**
 * The pinned `jim60105/bgutil-ytdlp-pot-provider-rs` standalone server Metrolist-style desktop installations use
 * when a build carries no BotGuard credentials. The binary is never bundled: it is downloaded from this release on
 * first need, checked against the hard-coded digest above, and run only on `127.0.0.1`.
 *
 * Digests were taken from the official v0.8.1 release assets (GitHub release API `digest` field) and independently
 * recomputed from the downloaded Linux x64 asset. The project is GPL-3.0; see `docs/CREDITS.md`.
 */
internal object BgutilSidecarRelease {
    const val REPOSITORY = "jim60105/bgutil-ytdlp-pot-provider-rs"
    const val VERSION = "v0.8.1"
    const val LICENSE = "GPL-3.0"
    const val RELEASE_URL = "https://github.com/$REPOSITORY/releases/tag/$VERSION"
    const val DOWNLOAD_BASE = "https://github.com/$REPOSITORY/releases/download/$VERSION"

    /** The helper answers only on loopback; a request to any other host is never made. */
    const val HOST = "127.0.0.1"

    /** The upstream default port, preferred when it is free; otherwise an ephemeral loopback port is used. */
    const val PREFERRED_PORT = 4416

    const val MAX_ASSET_BYTES = 64L * 1024L * 1024L

    private val specs =
        listOf(
            BgutilSidecarSpec(
                platform = BgutilHostPlatform.LINUX,
                arch = BgutilHostArch.X64,
                assetName = "bgutil-pot-linux-x86_64",
                sha256 = "e7c264a574fa2705b6e5dc62283a8a4e80130f27b9d7e9df44e6b09aa6151a87",
                binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                assetBytes = 50_990_856L,
            ),
            BgutilSidecarSpec(
                platform = BgutilHostPlatform.LINUX,
                arch = BgutilHostArch.ARM64,
                assetName = "bgutil-pot-linux-aarch64",
                sha256 = "4f4a1f681dba45e695e1c14d314517da180a1fd374afd09d634fd80ef6d0284b",
                binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                assetBytes = 54_147_304L,
            ),
            BgutilSidecarSpec(
                platform = BgutilHostPlatform.MACOS,
                arch = BgutilHostArch.X64,
                assetName = "bgutil-pot-macos-x86_64",
                sha256 = "0391175fa938c7fabbb8b40a40bd43182ef75af97e1dd3fab56eb23b4ac3e113",
                binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                assetBytes = 46_407_960L,
            ),
            BgutilSidecarSpec(
                platform = BgutilHostPlatform.MACOS,
                arch = BgutilHostArch.ARM64,
                assetName = "bgutil-pot-macos-aarch64",
                sha256 = "34b83baf0a557fecaa6d67a8177e53e169c2ccf987182883a4bae289a7176883",
                binaryFileName = "bgutil-ytdlp-pot-provider-rs",
                assetBytes = 43_353_024L,
            ),
            BgutilSidecarSpec(
                platform = BgutilHostPlatform.WINDOWS,
                arch = BgutilHostArch.X64,
                assetName = "bgutil-pot-windows-x86_64.exe",
                sha256 = "25d6b05c79176aa792454c3d1727922ca47e56cf11cb1e866615d751819b14a0",
                binaryFileName = "bgutil-ytdlp-pot-provider-rs.exe",
                assetBytes = 45_795_328L,
            ),
        )

    /** The spec for this process's host, or null when the release publishes no matching binary. */
    fun forHost(): BgutilSidecarSpec? = forOsArch(System.getProperty("os.name"), System.getProperty("os.arch"))

    /**
     * Pure host mapping so asset selection is testable without depending on the machine running the test. Names are
     * matched case-insensitively and accept the common JDK spellings (`Mac OS X`, `amd64`, `aarch64`).
     */
    fun forOsArch(osName: String?, osArch: String?): BgutilSidecarSpec? {
        val platform = platformFor(osName) ?: return null
        val arch = archFor(osArch) ?: return null
        return specs.firstOrNull { it.platform == platform && it.arch == arch }
    }

    internal fun platformFor(osName: String?): BgutilHostPlatform? {
        val normalized = osName?.trim()?.lowercase() ?: return null
        return when {
            normalized.startsWith("linux") -> BgutilHostPlatform.LINUX
            normalized.startsWith("mac") || normalized.startsWith("darwin") -> BgutilHostPlatform.MACOS
            normalized.startsWith("windows") -> BgutilHostPlatform.WINDOWS
            else -> null
        }
    }

    internal fun archFor(osArch: String?): BgutilHostArch? =
        when (osArch?.trim()?.lowercase()) {
            "amd64", "x86_64", "x64" -> BgutilHostArch.X64
            "aarch64", "arm64" -> BgutilHostArch.ARM64
            else -> null
        }
}

/** Hex SHA-256 of [path], or null when the file cannot be read. */
internal fun sha256Hex(path: Path): String? =
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString(separator = "") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }
    } catch (_: Exception) {
        null
    }

/**
 * A downloaded asset must be a real native executable before it is allowed to run: ELF, Mach-O (both endiannesses)
 * or a PE image. This rejects an HTML error page or a proxy interstitial that happened to match a cache path.
 */
internal fun isNativeExecutable(path: Path): Boolean =
    runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) < 4L) {
            false
        } else {
            Files.newInputStream(path).use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) {
                    false
                } else {
                    val b0 = header[0].toInt() and 0xFF
                    val b1 = header[1].toInt() and 0xFF
                    val b2 = header[2].toInt() and 0xFF
                    val b3 = header[3].toInt() and 0xFF
                    val elf = b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46
                    val machO64 = b0 == 0xCF && b1 == 0xFA && b2 == 0xED && b3 == 0xFE
                    val machO64LittleEndian = b0 == 0xFE && b1 == 0xED && b2 == 0xFA && b3 == 0xCF
                    val pe = b0 == 0x4D && b1 == 0x5A
                    elf || machO64 || machO64LittleEndian || pe
                }
            }
        }
    }.getOrDefault(false)
