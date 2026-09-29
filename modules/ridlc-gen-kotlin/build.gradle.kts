plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.shadow)
    application
}

dependencies {
    implementation(libs.protobuf.kotlin)
    implementation(libs.protobuf.java.util)
    implementation(libs.kotlinpoet)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// `plugin.proto` and `model.proto` of the pinned ridl release (see
// src/main/proto/README.md), compiled to Java with the Kotlin builders beside.
protobuf {
    protoc {
        artifact = libs.protoc.get().toString()
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("kotlin")
            }
        }
    }
}

// §2: `bin/ridlc-gen-kotlin`, a start script ending in `exec`, over one fat
// jar, with the JVM options that halve a short-lived JVM's start.
application {
    applicationName = "ridlc-gen-kotlin"
    mainClass = "ridl.codegen.kotlin.MainKt"
    applicationDefaultJvmArgs = listOf(
        "-XX:TieredStopAtLevel=1",
        "-XX:+UseSerialGC",
        "-Xshare:auto",
    )
}

tasks.shadowJar {
    // Shadow merges the Kotlin module metadata itself; the default EXCLUDE
    // would drop the duplicates before its transformer sees them.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    archiveBaseName = "ridlc-gen-kotlin"
    archiveClassifier = "all"
    archiveVersion = ""
}

// The only distribution is the fat jar's: the plain one would ship the thin
// jar and every dependency beside it under the same names.
distributions {
    named("shadow") {
        distributionBaseName = "ridlc-gen-kotlin"
    }
}
listOf("distZip", "distTar", "installDist", "startScripts").forEach { name ->
    tasks.named(name) { enabled = false }
}
