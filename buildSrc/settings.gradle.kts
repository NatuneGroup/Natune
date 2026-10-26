pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        // GCS mirror of Maven Central, declared BEFORE gradlePluginPortal() for
        // the same reason as in the root settings.gradle.kts: plugin marker POMs
        // resolve from plugins.gradle.org, but their parent BOMs (for example
        // kotlin-gradle-plugins-bom) are fetched from Maven Central, which
        // rate-limits with HTTP 429. Gradle does not fall through to the next
        // repository on 429, so the mirror must precede mavenCentral().
        maven {
            name = "GcsCentral"
            setUrl("https://maven-central.storage-download.googleapis.com/maven2/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
