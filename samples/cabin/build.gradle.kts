plugins {
    alias(libs.plugins.kotlin.jvm)
    application
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
    inputs.property("ridl", ridlBin)
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

// #47: GuideTest checks the excerpts of docs/guide.md against this sample and
// its generated code, so the guide is one of the test's inputs.
tasks.test {
    val guide = rootProject.layout.projectDirectory.file("docs/guide.md")
    inputs.file(guide)
    inputs.file(rootProject.layout.projectDirectory.file("modules/conformance/ridl-release"))
    systemProperty("guide.file", guide.asFile.absolutePath)
}
