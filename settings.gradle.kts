rootProject.name = "ridlc-gen-kotlin"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// Every module lives under `modules/`, and the JVM demo under `samples/`
// (docs/design.md §6). The Gradle project path is the directory name.
for (module in listOf(
    "ridlc-gen-kotlin",
    "ridl-rt-kt",
    "ridl-rt-kt-loopback",
    "ridl-rt-kt-coroutines",
    "ridl-rt-kt-conformance",
    "conformance",
)) {
    include(":$module")
    project(":$module").projectDir = file("modules/$module")
}

include(":samples:cabin")
