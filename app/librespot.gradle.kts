import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

val librespotLibraries = layout.buildDirectory.dir("generated/librespot/jniLibs")
extensions.configure<ApplicationExtension> {
    sourceSets.getByName("main").jniLibs.srcDir(librespotLibraries)
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants(selector().all()) { variant ->
        val abiFlavor = variant.productFlavors.first { it.first == "abi" }.second
        val abis = when (abiFlavor) {
            "universal" -> listOf("arm64-v8a", "x86_64")
            "arm64" -> listOf("arm64-v8a")
            "armeabi" -> listOf("armeabi-v7a")
            "x86" -> listOf("x86")
            "x86_64" -> listOf("x86_64")
            else -> error("Unsupported Librespot ABI flavor: $abiFlavor")
        }
        val capitalized = variant.name.replaceFirstChar { it.uppercaseChar() }
        val verify = tasks.register("verify${capitalized}LibrespotAudio") {
            doLast {
                abis.forEach { abi ->
                    val library = librespotLibraries.get().file("$abi/libnatune_librespot.so").asFile
                    check(library.isFile && library.length() > 0L) {
                        "Missing Librespot audio for $abi; CI must run native/librespot/build-android.sh $abiFlavor before APK assembly."
                    }
                }
            }
        }
        // Compilation/unit tests do not require JNI. Every packaged APK does.
        tasks.matching { it.name == "merge${capitalized}NativeLibs" }.configureEach { dependsOn(verify) }
    }
}
