# Installing

## ridl

`ridl` is the toolchain that reads `.ridl` files and runs the plugin. Install
the release this repository pins with its install script; `RIDL_VERSION` names
the release:

```sh
curl -fsSL https://raw.githubusercontent.com/driftsys/ridl/editor-v0.7.0/install.sh \
  | RIDL_VERSION=editor-v0.7.0 bash
```

## The plugin

Each release of this repository carries `install.sh`, which installs that
release. It downloads the plugin distribution, checks it against the release's
`SHA256SUMS`, unpacks it under `~/.local/share/ridlc-gen-kotlin`, and links
`ridlc-gen-kotlin` into `~/.local/bin`. The plugin needs a JDK or JRE 17 or
later to run.

```sh
curl -fsSL https://github.com/driftsys/ridlc-gen-kotlin/releases/latest/download/install.sh | bash
```

| Setting                        | What it changes                                   |
| ------------------------------ | ------------------------------------------------- |
| `RIDLC_GEN_KOTLIN_VERSION`     | the release to install, such as `v0.1.0`          |
| `RIDLC_GEN_KOTLIN_HOME`        | where distributions are unpacked, one per version |
| `RIDLC_GEN_KOTLIN_INSTALL_DIR` | where the `ridlc-gen-kotlin` link goes            |

To build the plugin from a checkout of this repository instead, with a JDK 17 or
later:

```sh
just dist
# modules/ridlc-gen-kotlin/build/install/ridlc-gen-kotlin/bin/ridlc-gen-kotlin
```

## The runtime libraries

The generated code compiles against `ridl-rt-kt`, and a client or provider needs
a runtime that implements its ports. The four JVM libraries publish to this
repository's GitHub Packages registry as `io.github.driftsys.ridl`. Each push to
`main` publishes the `-SNAPSHOT` version, and each release publishes its own.
GitHub Packages asks for a GitHub token that can read packages, even for a
public package:

```kts
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/driftsys/ridlc-gen-kotlin")
        credentials {
            username = providers.gradleProperty("gpr.user").get()
            password = providers.gradleProperty("gpr.key").get()
        }
    }
}

dependencies {
    implementation("io.github.driftsys.ridl:ridl-rt-kt:0.1.0-SNAPSHOT")
    // Faces.kt's suspending client and serveAsync.
    implementation("io.github.driftsys.ridl:ridl-rt-kt-coroutines:0.1.0-SNAPSHOT")
    // An in-process runtime, for tests and demos.
    implementation("io.github.driftsys.ridl:ridl-rt-kt-loopback:0.1.0-SNAPSHOT")
}
```

`just publish-local` in a checkout puts the libraries in the local Maven
repository instead, for a build against a change not yet on `main`; that build
declares `mavenLocal()`.

A runtime written in another repository, such as the Binder runtime, also runs
the port contract suite of `ridl-rt-kt-conformance` from its own tests. The
[suite's README](https://github.com/driftsys/ridlc-gen-kotlin/blob/main/modules/ridl-rt-kt-conformance/README.md)
shows how.

## An Android tree

An Android tree that builds with Soong cannot read Maven. Each release attaches
`ridl-rt-kt-<version>-android.tar.gz` for it: the sources of `ridl-rt-kt`,
`ridl-rt-kt-coroutines` and `ridl-rt-kt-loopback`, with an `Android.bp`
declaring the three libraries, an AOSP `METADATA` file and the license files.
Unpack it at a path such as `external/ridl-rt-kt` and depend on the libraries by
name.

The tree must provide two things:

- a Kotlin compiler of 1.9 or later, since the runtime uses `data object` and
  `.entries`;
- kotlinx.coroutines, as the module `kotlinx_coroutines`, the name AOSP gives
  it. A tree with another name edits that line of `Android.bp`.

The port contract suite is not in the package: it is written for JUnit 5, which
AOSP's test tooling does not run, so a runtime runs it from a Gradle build.
