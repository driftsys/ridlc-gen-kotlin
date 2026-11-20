# Running the plugin

`ridl build` runs a plugin once per package. Name the plugin by its language,
and `ridl` runs `ridlc-gen-kotlin` from your `PATH`, or give its path:

```sh
ridl build --plugin kotlin .
ridl build --plugin kotlin=path/to/bin/ridlc-gen-kotlin --out-dir out .
```

For each package, the plugin writes its files into the package's directory under
the output directory, `veh/cabin/` for package `veh.cabin`:

| File             | What it holds                                                                                   |
| ---------------- | ----------------------------------------------------------------------------------------------- |
| `Types.kt`       | one value object per declaration: scalars, enums, enum sets, structs, tuples, unions, constants |
| `Codec.kt`       | the FlatBuffers codec of each payload: `encode`, `verify` and `decode`                          |
| `Proto3Codec.kt` | the proto3 codec of each payload, the same three functions                                      |
| `PodbufCodec.kt` | the podbuf codec of each payload, the same three functions                                      |
| `Transcode.kt`   | functions that transcode payloads between selected encodings                                    |
| `Instances.kt`   | instance numbers from the deployment section                                                    |
| `Faces.kt`       | per interface: its descriptor, the clients, the provider interface, the publisher, `serve`      |

`Instances.kt` is written when the request's deployment section gives at least
one instance a nonzero number. Setting `transcode = true` under
`[backend.kotlin]` enables `Transcode.kt`; it needs two or more encodings and a
payload type with codecs in at least two of them. The option accepts `true` or
`false`, defaults to `false`, and the last value wins. Each object has one
function per ordered pair of its codecs.

The manifest chooses the codecs: `encodings` under `[backend.kotlin]` in
`ridl.toml` names `flatbuffers`, `proto3` and `podbuf`, in any combination, and
the plugin writes a codec for each encoding named and no other. The sample names
`flatbuffers`. A package that names none gets `Types.kt` alone. `Faces.kt` is
written only for a package that names an encoding and declares an interface. Its
face serves a port of any encoding the package names. The Kotlin package is the
ridl package's name. The plugin has a `kotlin-package` option, but no
`ridl build` flag sets a plugin option yet, so the name is always the default.

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
