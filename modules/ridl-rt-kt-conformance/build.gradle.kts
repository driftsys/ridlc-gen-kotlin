plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "The port contract tests of ridl-rt-kt, generic over a runtime factory, so a runtime runs them from its own tests."

// Published, unlike `crates/ridl-rt-conformance`: a runtime in another
// repository runs the suite from its own tests, and a JVM consumer has no way
// to depend on it by path or git, as a Rust one does. `java-library` is what
// the root build selects publications by. The suite's surface follows the port
// contract as the contract changes (story E11.20), so a runtime takes the
// version that matches the `ridl-rt-kt` it implements.
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
