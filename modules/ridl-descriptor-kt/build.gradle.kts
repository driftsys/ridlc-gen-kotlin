plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "The RIDL catalog and deployment descriptors for Kotlin: verifiers and read-only values."

// The mirror of `crates/ridl-descriptor`'s reading half. It depends on
// nothing but the Kotlin standard library (docs/design.md §6).
kotlin {
    explicitApi()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
