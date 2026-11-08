import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

val librespotLibraries = layout.buildDirectory.dir("generated/librespot/jniLibs")
val requireLibrespotAudio = providers.gradleProperty("requireLibrespotAudio").map(String::toBoolean).orElse(false)
extensions.configure<ApplicationExtension> {
    sourceSets.getByName("main").jniLibs.srcDir(librespotLibraries)
}

extensions.configure<ApplicationAndroidComponentsExtension> {
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
