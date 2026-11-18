plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "The shared RIDL proto3 reader and writer for Kotlin."

kotlin {
    explicitApi()
}

dependencies {
    api(project(":ridl-rt-kt"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
