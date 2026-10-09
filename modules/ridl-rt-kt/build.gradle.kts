plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

description = "The RIDL runtime contract for Kotlin: the vocabulary types, the ports and the codec surface."

// §3: the runtime contract depends on nothing but the Kotlin standard library.
kotlin {
    explicitApi()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// #52: the trace propagation hook is set once per process and never cleared,
// as `ridl_rt`'s is, so the tests tagged `process-hook` run in a JVM of their
// own, as Rust runs `propagation.rs` as a test binary of its own. `test` runs
// them first, so `just test` and `check` still cover them.
val processHookTest = tasks.register<Test>("processHookTest") {
    description = "Runs the tests that set the process-wide trace propagation hook, in a JVM of their own."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("process-hook") }
}

tasks.test {
    useJUnitPlatform { excludeTags("process-hook") }
    dependsOn(processHookTest)
}
