import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.shadow) apply false
}

// One convention for every Kotlin module: JVM 17 bytecode, warnings as
// errors, explicit API in the libraries, JUnit Platform for the tests.
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(17)
            compilerOptions {
                jvmTarget = JvmTarget.JVM_17
                allWarningsAsErrors = true
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

// The three JVM libraries are consumed outside this repository: the Binder
// runtime's own repository depends on `ridl-rt-kt` (docs/design.md §6). Each
// publishes a Maven artifact with its sources, under the root MIT license.
// `java-library` selects exactly those three — the generator ships as the
// `application` distribution instead (§2), and the conformance tests and the
// sample ship not at all.
subprojects {
    plugins.withId("java-library") {
        apply(plugin = "maven-publish")

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }

        val module = project
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(module.components["java"])
                pom {
                    name.set(module.name)
                    description.set(module.provider { module.description })
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
    }
}
