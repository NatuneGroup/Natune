package moe.rukamori.archivetune.desktop.potoken

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BgutilSidecarReleaseTest {
    @Test
    fun selectsThePinnedAssetPerHost() {
        val linuxX64 = BgutilSidecarRelease.forOsArch("Linux", "amd64")
        assertEquals("bgutil-pot-linux-x86_64", linuxX64?.assetName)
        assertEquals("e7c264a574fa2705b6e5dc62283a8a4e80130f27b9d7e9df44e6b09aa6151a87", linuxX64?.sha256)
        assertEquals(50_990_856L, linuxX64?.assetBytes)

        val linuxArm = BgutilSidecarRelease.forOsArch("Linux", "aarch64")
        assertEquals("bgutil-pot-linux-aarch64", linuxArm?.assetName)
        assertEquals("4f4a1f681dba45e695e1c14d314517da180a1fd374afd09d634fd80ef6d0284b", linuxArm?.sha256)

        val macX64 = BgutilSidecarRelease.forOsArch("Mac OS X", "x86_64")
        assertEquals("bgutil-pot-macos-x86_64", macX64?.assetName)
        assertEquals("0391175fa938c7fabbb8b40a40bd43182ef75af97e1dd3fab56eb23b4ac3e113", macX64?.sha256)

        val macArm = BgutilSidecarRelease.forOsArch("Darwin", "arm64")
        assertEquals("bgutil-pot-macos-aarch64", macArm?.assetName)
        assertEquals("34b83baf0a557fecaa6d67a8177e53e169c2ccf987182883a4bae289a7176883", macArm?.sha256)

        val windows = BgutilSidecarRelease.forOsArch("Windows 11", "AMD64")
        assertEquals("bgutil-pot-windows-x86_64.exe", windows?.assetName)
        assertEquals("25d6b05c79176aa792454c3d1727922ca47e56cf11cb1e866615d751819b14a0", windows?.sha256)
        assertEquals("bgutil-ytdlp-pot-provider-rs.exe", windows?.binaryFileName)
    }

    @Test
    fun refusesHostsWithoutAPinnedBinary() {
        assertNull(BgutilSidecarRelease.forOsArch("Linux", "riscv64"))
        assertNull(BgutilSidecarRelease.forOsArch("FreeBSD", "x86_64"))
        assertNull(BgutilSidecarRelease.forOsArch(null, "x86_64"))
        assertNull(BgutilSidecarRelease.forOsArch("Linux", null))
        assertNull(BgutilSidecarRelease.forOsArch("Windows", "aarch64"))
    }

    @Test
    fun everyAssetCarriesAFullSha256AndTheOfficialReleaseUrl() {
        val specs =
            listOf(
                BgutilSidecarRelease.forOsArch("Linux", "x86_64"),
                BgutilSidecarRelease.forOsArch("Linux", "aarch64"),
                BgutilSidecarRelease.forOsArch("Mac OS X", "x86_64"),
                BgutilSidecarRelease.forOsArch("Mac OS X", "aarch64"),
                BgutilSidecarRelease.forOsArch("Windows", "x86_64"),
            )
        specs.forEach { spec ->
            val pinned = assertNotNull(spec)
            assertTrue(Regex("[0-9a-f]{64}").matches(pinned.sha256), "digest for ${pinned.assetName}")
            assertTrue(pinned.assetBytes in 1..BgutilSidecarRelease.MAX_ASSET_BYTES)
            assertEquals(
                "https://github.com/jim60105/bgutil-ytdlp-pot-provider-rs/releases/download/v0.8.1/${pinned.assetName}",
                pinned.downloadUrl,
            )
        }
        assertEquals("v0.8.1", BgutilSidecarRelease.VERSION)
        assertEquals("GPL-3.0", BgutilSidecarRelease.LICENSE)
    }
}
