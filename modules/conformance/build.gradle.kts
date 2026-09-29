import java.net.URI

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    testImplementation(project(":ridlc-gen-kotlin"))
    testImplementation(project(":ridl-rt-kt"))
    testImplementation(project(":ridl-rt-kt-loopback"))
    testImplementation(project(":ridl-rt-kt-coroutines"))
    testImplementation(libs.protobuf.java.util)
    testImplementation(libs.kotlin.compile.testing)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// D-K9: `ridl` enters this repository as one pinned release binary, installed
// by that release's own `install.sh`. `RIDL_BIN` names an installed `ridl`
// instead, for a machine with no network; the pin is still what CI runs.
val ridlRelease = providers.fileContents(layout.projectDirectory.file("ridl-release"))
    .asText.map { it.trim() }
val ridlDir = layout.buildDirectory.dir("ridl")
val ridlOverride = providers.environmentVariable("RIDL_BIN")

val installRidl = tasks.register<Exec>("installRidl") {
    description = "Installs the pinned ridl release into build/ridl."
    val release = ridlRelease.get()
    val dir = ridlDir.get().asFile.absolutePath
    inputs.property("release", release)
    outputs.dir(dir)
    val override = ridlOverride
    onlyIf { !override.isPresent }
    environment("RIDL_VERSION", release)
    environment("RIDL_INSTALL_DIR", dir)
    commandLine(
        "bash", "-euo", "pipefail", "-c",
        "curl -fsSL https://raw.githubusercontent.com/driftsys/ridl/\$RIDL_VERSION/install.sh | bash",
    )
}

// The vendored schema is the pinned release's, byte for byte.
val checkSchema = tasks.register("checkSchema") {
    description = "Checks that the vendored ridl.codegen.v1 schema is the pinned release's."
    val release = ridlRelease
    val protoDir = rootProject.layout.projectDirectory
        .dir("modules/ridlc-gen-kotlin/src/main/proto/ridl/codegen/v1")
    inputs.property("release", release)
    inputs.dir(protoDir)
    doLast {
        for (name in listOf("plugin.proto", "model.proto")) {
            val url = "https://raw.githubusercontent.com/driftsys/ridl/${release.get()}" +
                "/crates/ridl-ir/proto/ridl/codegen/v1/$name"
            val upstream = URI(url).toURL().readBytes()
            val vendored = protoDir.file(name).asFile.readBytes()
            check(upstream.contentEquals(vendored)) {
                "modules/ridlc-gen-kotlin/src/main/proto/ridl/codegen/v1/$name differs from $url"
            }
        }
    }
}

tasks.test {
    dependsOn(installRidl, ":ridlc-gen-kotlin:installShadowDist")
    val ridl = ridlOverride.orElse(ridlDir.map { it.file("ridl").asFile.absolutePath })
    val plugin = project(":ridlc-gen-kotlin").layout.buildDirectory
        .file("install/ridlc-gen-kotlin/bin/ridlc-gen-kotlin")
    val corpus = layout.projectDirectory.dir("src/test/corpus")
    val spike = layout.buildDirectory.dir("spike")
    inputs.dir(corpus)
    val release = ridlRelease
    inputs.property("release", release)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dridl.bin=${ridl.get()}",
            "-Dridl.release=${release.get()}",
            "-Dplugin.bin=${plugin.get().asFile.absolutePath}",
            "-Dcorpus.dir=${corpus.asFile.absolutePath}",
            "-Dspike.dir=${spike.get().asFile.absolutePath}",
        )
    })
}

tasks.check {
    dependsOn(checkSchema)
}
