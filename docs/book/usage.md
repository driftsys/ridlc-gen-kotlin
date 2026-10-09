# Running the plugin

`ridl build` runs a plugin once per package. Name the plugin by its language,
and `ridl` runs `ridlc-gen-kotlin` from your `PATH`, or give its path:

```sh
ridl build --plugin kotlin .
ridl build --plugin kotlin=path/to/bin/ridlc-gen-kotlin --out-dir out .
```

For each package, the plugin writes three files into the package's directory
under the output directory, `veh/cabin/` for package `veh.cabin`:

| File       | What it holds                                                                                   |
| ---------- | ----------------------------------------------------------------------------------------------- |
| `Types.kt` | one value object per declaration: scalars, enums, enum sets, structs, tuples, unions, constants |
| `Codec.kt` | the FlatBuffers codec of each payload: `encode`, `verify` and `decode`                          |
| `Faces.kt` | per interface: its descriptor, the clients, the provider interface, the publisher, `serve`      |

`Faces.kt` is written only for a package that declares an interface. The Kotlin
package is the ridl package's name. The plugin has a `kotlin-package` option,
but no `ridl build` flag sets a plugin option yet, so the name is always the
default.

## The Gradle build

The generated code compiles against the runtime libraries
([Installing](install.md#the-runtime-libraries) shows how to declare them).

The sample generates its code in the build rather than checking it in: its
`generateCabin` task runs `ridl build --plugin kotlin=<plugin>` into
`build/generated/ridl`, and the main source set includes that directory. See
`samples/cabin/build.gradle.kts`.

## The runtime

A generated client or provider talks to a **port**, which a runtime provides.
This repository ships one runtime, `ridl-rt-kt-loopback`, which connects a
client and a provider in one process. It is what the sample and the tests use. A
runtime that crosses processes, such as the Binder runtime, lives in its own
repository and implements the same port interfaces of `ridl-rt-kt`.
