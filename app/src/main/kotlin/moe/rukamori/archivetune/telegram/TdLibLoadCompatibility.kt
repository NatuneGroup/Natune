package moe.rukamori.archivetune.telegram

internal class TdLibLoadCompatibility(
    private val sdkInt: Int,
) {
    @Volatile
    var androidVersionUnsupported = false
        private set

    val unavailableStateName: String
        get() = if (androidVersionUnsupported) "AndroidVersion" else "NativeLibraryMissing"

    fun recordFailure(failure: Throwable, libraryPresent: Boolean) {
        // The pinned recipe targets API 16/21, not 26. Do not reject all Nougat devices,
        // or mistake the expected "not found" on a slim build for platform incompatibility.
        if (libraryPresent && sdkInt < ANDROID_O_SDK && failure is UnsatisfiedLinkError) {
            androidVersionUnsupported = true
        }
    }

    private companion object {
        const val ANDROID_O_SDK = 26
    }
}
