plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "The in-process RIDL runtime: every port over a queue and a map, with no IO."

kotlin {
    explicitApi()
}

dependencies {
    api(project(":ridl-rt-kt"))
    // `Node` reads its machine from a deployment descriptor, and `NodeError` carries the refusal.
    api(project(":ridl-descriptor-kt"))

    testImplementation(project(":ridl-rt-kt-conformance"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
