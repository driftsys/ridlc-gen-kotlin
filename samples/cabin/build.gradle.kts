plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    alias(libs.plugins.jmh)
}

// The JVM demo (docs/design.md §6, stage K4): `ridl/cabin.ridl`, the ridl
// repository's `examples/cabin`, generated into Kotlin by the pinned `ridl`
// running this repository's plugin — `ridl build --plugin kotlin=<script>` —
// and compiled with a consumer and a provider over the loopback. Nothing
// generated is tracked: it is written under build/ on every change.
val ridlOverride = providers.environmentVariable("RIDL_BIN")
val ridlBin = ridlOverride
    .orElse(project(":conformance").layout.buildDirectory.file("ridl/ridl").map { it.asFile.absolutePath })
// The installed distribution, jar included: the start script alone does not
// change when the plugin does, so it cannot stand for the plugin as an input.
val pluginDist = project(":ridlc-gen-kotlin").layout.buildDirectory.dir("install/ridlc-gen-kotlin")
val plugin = pluginDist.map { it.file("bin/ridlc-gen-kotlin") }
val source = layout.projectDirectory.dir("ridl")
val generated = layout.buildDirectory.dir("generated/ridl")

val generateCabin = tasks.register<Exec>("generateCabin") {
    description = "Generates the cabin package's Kotlin with the pinned ridl and this repository's plugin."
    dependsOn(":ridlc-gen-kotlin:installShadowDist")
    // The pinned release, installed by the conformance module, unless RIDL_BIN names one.
    if (!ridlOverride.isPresent) dependsOn(":conformance:installRidl")
    inputs.dir(source)
    inputs.dir(pluginDist)
    // The binary, not its path: a ridl rebuilt in place generates again.
    inputs.file(ridlBin).withPropertyName("ridl").withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(generated)
    val out = generated.get().asFile
    doFirst { out.deleteRecursively() }
    workingDir(source)
    commandLine(
        ridlBin.get(), "build",
        "--emit", "codegen-model",
        "--plugin", "kotlin=${plugin.get().asFile.absolutePath}",
        "--out-dir", out.absolutePath,
        ".",
    )
}

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(generateCabin.map { generated.get() })
    }
}

application {
    mainClass = "ridl.sample.cabin.MainKt"
}

dependencies {
    implementation(project(":ridl-rt-kt"))
    implementation(project(":ridl-rt-kt-loopback"))
    implementation(project(":ridl-rt-kt-coroutines"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// #47: GuideTest checks the excerpts of the book, docs/book, against this
// sample and its generated code, so the book is one of the test's inputs.
tasks.test {
    val book = rootProject.layout.projectDirectory.dir("docs/book")
    inputs.dir(book)
    inputs.file(rootProject.layout.projectDirectory.file("modules/conformance/ridl-release"))
    systemProperty("book.dir", book.asFile.absolutePath)
}

// #78: what `SignalState.value` costs, cached, read, or behind an age guard,
// over the loopback and over a seqlock'd stand-in for a shared-memory channel,
// both reached through the generated `CabinAsyncClient`. `just bench` runs it;
// nothing in `check` does. The results are in
// modules/ridl-rt-kt-coroutines/README.md.
jmh {
    jmhVersion = libs.versions.jmh
    includes.addAll(providers.gradleProperty("jmh.includes").map { listOf(it) }.orElse(emptyList()))
    threads = providers.gradleProperty("jmh.threads").map { it.toInt() }.orElse(1)
    fork = 1
    warmupIterations = 3
    warmup = "2s"
    iterations = 5
    timeOnIteration = "2s"
    benchmarkMode = listOf("avgt")
    timeUnit = "ns"
    resultFormat = "JSON"
}
