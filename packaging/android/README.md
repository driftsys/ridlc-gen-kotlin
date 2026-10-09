# Android vendor package

## Responsibility

The files that turn the runtime sources into a directory an Android source tree
builds with Soong: `Android.bp`, `METADATA`, and this README. Each release
attaches `ridl-rt-kt-<version>-android.tar.gz`, which `just vendor-package`
builds from these files, the `src/main` sources of `ridl-rt-kt`,
`ridl-rt-kt-coroutines` and `ridl-rt-kt-loopback`, the root `LICENSE`, and an
empty `MODULE_LICENSE_MIT`. The version and the date in `Android.bp` and
`METADATA` are filled in from `gradle.properties` and the commit.

The package unpacks to one directory, `ridl-rt-kt-<version>/`. Put its contents
at a path of the tree such as `external/ridl-rt-kt`, and depend on the libraries
by name: `ridl-rt-kt`, `ridl-rt-kt-coroutines`, and `ridl-rt-kt-loopback` for
tests.

Two things are left to the tree:

- Its Kotlin compiler must be 1.9 or later: the runtime uses `data object` and
  `.entries`.
- The coroutine module depends on `kotlinx_coroutines`, the name AOSP gives
  kotlinx.coroutines. A tree with another name edits that line.

The port contract suite, `ridl-rt-kt-conformance`, is not in the package. It is
written for JUnit 5, which AOSP's test tooling does not run, so a runtime runs
it from a Gradle build, from the Maven artifact.

## Status

`Android.bp` has not been built in an AOSP tree yet. The package itself is built
and checked by `just vendor-package` in every run of the `jvm` CI job.
