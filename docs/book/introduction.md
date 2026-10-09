# The RIDL Kotlin plugin

`ridlc-gen-kotlin` is the Kotlin code generator of the
[RIDL](https://driftsys.github.io/ridl/) toolchain. `ridl build --plugin kotlin`
runs it on each package and it writes Kotlin value objects, their FlatBuffers
codecs, and the faces of each interface: the clients, the provider interface and
the publisher. The generated code runs over `ridl-rt-kt`, the Kotlin runtime
contract, and any runtime that implements it.

This book takes a `.ridl` package to a running Kotlin client and provider. It
uses the cabin sample (`samples/cabin`) throughout:

- [Installing](install.md) sets up `ridl`, the plugin and the runtime libraries,
  for Gradle and for an Android tree.
- [Running the plugin](usage.md) shows what `ridl build` writes and how a Gradle
  build compiles it.
- [The generated code](codegen.md) puts each `.ridl` declaration of the sample
  beside the Kotlin it becomes, then runs it, blocking and with coroutines.

Every Kotlin and ridl block in these chapters is an excerpt of the sample, of
`cabin.ridl`, or of the code the plugin generates for it, and the sample's
`GuideTest` fails when one stops matching its source. The excerpts come from
ridl `editor-v0.7.0`, the release this repository pins in
`modules/conformance/ridl-release`, and from the plugin built from this
repository at the same commit as the book.

A `// ...` line in an excerpt stands for lines left out.
