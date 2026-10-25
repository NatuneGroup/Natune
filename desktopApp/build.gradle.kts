import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}
kotlin {
    jvm()
    jvmToolchain(21)
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.compose.components:components-resources:1.12.2")
        }
    }
}
compose.desktop {
    application {
        mainClass = "moe.rukamori.archivetune.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "ArchiveTune-KMP"
            packageVersion = "0.1.0"
        }
    }
}
