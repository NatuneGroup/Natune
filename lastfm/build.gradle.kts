plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// Last.fm application credentials are injected at build time and never committed. The generated
// ProviderBuildCredentials object is the only source of these values for LastFmAppCredentials.
val providerCredentials = mapOf(
    "ARCHIVETUNE_LASTFM_API_KEY" to ProviderCredentials.resolve(project, "ARCHIVETUNE_LASTFM_API_KEY", "LASTFM_API_KEY"),
    "ARCHIVETUNE_LASTFM_API_SECRET" to ProviderCredentials.resolve(project, "ARCHIVETUNE_LASTFM_API_SECRET", "LASTFM_SECRET"),
)
val providerCredentialsDirectory = layout.buildDirectory.dir("generated/providerCredentials")
val generateProviderCredentials = tasks.register("generateProviderCredentials") {
    // Fingerprints, not values, are task inputs: a changed credential regenerates the source without
    // writing the credential itself into Gradle's task history.
    inputs.property(
        "providerCredentialFingerprints",
        providerCredentials.mapValues { ProviderCredentials.fingerprint(it.value) }.toString(),
    )
    outputs.dir(providerCredentialsDirectory)
    // The generated source embeds the credential, so it must never be written to a shared build cache.
    outputs.doNotCacheIf("generated source embeds provider credentials") { true }
    doLast {
        ProviderCredentials.writeKotlinObject(
            providerCredentialsDirectory.get().asFile,
            "moe.rukamori.archivetune.lastfm",
            "ProviderBuildCredentials",
            providerCredentials,
        )
    }
}
kotlin.sourceSets.named("main") { kotlin.srcDir(providerCredentialsDirectory) }
tasks.named("compileKotlin") { dependsOn(generateProviderCredentials) }

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    testImplementation(libs.junit)
}
