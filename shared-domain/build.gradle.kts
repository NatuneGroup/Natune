import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.plugins.ExtensionAware

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Device Connect wire payloads are serialized in common code, like the provider models in :shared-data.
    alias(libs.plugins.kotlin.serialization)
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
    iosArm64()
    iosSimulatorArm64()

    if (hasAndroidSdk) {
        val androidTarget = (this as ExtensionAware).extensions.getByName("androidLibrary") as KotlinMultiplatformAndroidLibraryExtension
        androidTarget.namespace = "moe.rukamori.archivetune.shared.domain"
        androidTarget.compileSdk = 37
        androidTarget.minSdk = 24
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            // Optional Japanese morphological tokenizer. Only the JVM host ships Kuromoji; the
            // common code sees it through JapaneseTokenizer.
            implementation(libs.kuromoji.ipadic)
            // JVM counterpart of android.icu for the generic transliteration pass; see LyricsTransliterator.
            implementation(libs.icu4j)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}
