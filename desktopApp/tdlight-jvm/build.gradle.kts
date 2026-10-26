// Desktop-only TDLight SDK component. It is plain Java with no Android or Kotlin plugin, so the desktop build never
// reaches the Android app. The Android app keeps its own vendored copy under app/src/main/java/org/drinkless/tdlib,
// which this component neither reads nor changes.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    // The generated sources carry non-ASCII comment text, so the encoding is pinned rather than inherited from the host.
    options.encoding = "UTF-8"
}

dependencies {
    // TdApi.java imports AndroidX @IntDef and @Nullable for compile-time checks only. The JVM artifact of the AndroidX
    // annotation release that the Android catalog pins (gradle/libs.versions.toml, annotation 1.10.0) supplies them.
    // compileOnly keeps them out of this component's jar, so desktop packages do not carry Android annotation classes.
    compileOnly("androidx.annotation:annotation-jvm:1.10.0")
}
