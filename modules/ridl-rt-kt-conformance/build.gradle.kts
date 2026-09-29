plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "The port contract tests of ridl-rt-kt, generic over a runtime factory, so a runtime runs them from its own tests."

// Not published, as `crates/ridl-rt-conformance` is not: the suite is run by
// the runtimes of this repository, and its surface follows the port contract
// as the contract changes (story E11.20). It applies `kotlin-jvm` without
// `java-library`, which is what the root build publishes.
kotlin {
    explicitApi()
}

dependencies {
    api(project(":ridl-rt-kt"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter.api)

    testImplementation(project(":ridl-rt-kt-loopback"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
}
