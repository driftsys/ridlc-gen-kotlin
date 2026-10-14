import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.nmcp) apply false
    alias(libs.plugins.nmcp.aggregation)
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

// The four JVM libraries are consumed outside this repository: the Binder
// runtime's own repository depends on `ridl-rt-kt` (docs/design.md §6), and a
// runtime there runs `ridl-rt-kt-conformance`'s port contract suite from its
// own tests. Each publishes a Maven artifact with its sources and a javadoc
// jar, under the root MIT license, to GitHub Packages and to Maven Central.
// `java-library` selects exactly those four — the generator ships as the
// `application` distribution instead (§2), and the conformance tests and the
// sample ship not at all.
subprojects {
    plugins.withId("java-library") {
        apply(plugin = "maven-publish")
        apply(plugin = "signing")
        apply(plugin = "com.gradleup.nmcp")

        // Maven Central requires both jars. These modules are Kotlin only, so
        // the javadoc jar is the empty placeholder Central accepts.
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        val module = project
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") {
                from(module.components["java"])
                pom {
                    name.set(module.name)
                    description.set(module.provider { module.description })
                    url.set("https://github.com/driftsys/ridlc-gen-kotlin")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("driftsys")
                            name.set("driftsys")
                            url.set("https://github.com/driftsys")
                        }
                    }
                    scm {
                        url.set("https://github.com/driftsys/ridlc-gen-kotlin")
                        connection.set("scm:git:https://github.com/driftsys/ridlc-gen-kotlin.git")
                        developerConnection.set("scm:git:ssh://git@github.com/driftsys/ridlc-gen-kotlin.git")
                    }
                }
            }
            // The remote repository `just publish` writes to. Its credentials
            // are the Gradle properties `GitHubPackagesUsername` and
            // `GitHubPackagesPassword`, which the publish workflow sets from
            // the workflow's own token.
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/driftsys/ridlc-gen-kotlin")
                    credentials(PasswordCredentials::class)
                }
            }
        }

        // Maven Central needs a PGP signature on every file. The key comes in
        // as the Gradle properties `signingKey` (ASCII-armored) and
        // `signingPassword`; without a key nothing is signed, so a local build
        // and the GitHub Packages path need none.
        val signingKey = providers.gradleProperty("signingKey")
        extensions.configure<SigningExtension> {
            isRequired = signingKey.isPresent
            useInMemoryPgpKeys(signingKey.orNull, providers.gradleProperty("signingPassword").orNull)
            sign(module.extensions.getByType<PublishingExtension>().publications)
        }
    }
}

// One deployment to the Central Portal holding the four libraries, uploaded by
// `just publish-central`. The Portal validates it and waits for a manual
// release (USER_MANAGED); a release is immutable, so that click is the check.
nmcpAggregation {
    // The root project and the generator module share a name; neither is
    // aggregated, and the four libraries' names are unique.
    allowDuplicateProjectNames.set(true)
    centralPortal {
        username = providers.gradleProperty("mavenCentralUsername")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password = providers.gradleProperty("mavenCentralPassword")
            .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
        publishingType = "USER_MANAGED"
    }
}

dependencies {
    nmcpAggregation(project(":ridl-rt-kt"))
    nmcpAggregation(project(":ridl-rt-kt-loopback"))
    nmcpAggregation(project(":ridl-rt-kt-coroutines"))
    nmcpAggregation(project(":ridl-rt-kt-conformance"))
}
