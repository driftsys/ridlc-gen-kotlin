plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "The suspending adapter over a RIDL port: await, for polling a Wakeable runtime."

kotlin {
    explicitApi()
}

dependencies {
    api(project(":ridl-rt-kt"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(project(":ridl-rt-kt-loopback"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
