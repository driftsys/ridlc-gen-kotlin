# ridlc-gen-kotlin

`ridlc-gen-kotlin` is the language-neutral repository home for five planned
modules and the `samples/cabin` demonstration:

- [`ridlc-gen-kotlin`](modules/ridlc-gen-kotlin/README.md) — the future RIDL
  generator plugin executable.
- [`ridl-rt-kt`](modules/ridl-rt-kt/README.md) — the future Kotlin runtime
  contract.
- [`ridl-rt-kt-loopback`](modules/ridl-rt-kt-loopback/README.md) — the future
  in-process runtime used by tests.
- [`ridl-rt-kt-coroutines`](modules/ridl-rt-kt-coroutines/README.md) — the
  future coroutine adapter over the runtime contract.
- [`conformance`](modules/conformance/README.md) — the future pinned RIDL
  release and cross-module conformance suite.
- [`samples/cabin`](samples/cabin/README.md) — the future JVM demonstration.

## Design

The design of the plugin, the runtime contract, the value objects, the codec and
the face is [`docs/design.md`](docs/design.md). It is the only record of that
design; it rests on the records of
[driftsys/ridl](https://github.com/driftsys/ridl), the toolchain repository,
which keeps no copy of it.

## Scaffold status

This scaffold contains no implementation. It has no Kotlin, Rust, runtime,
generator, or generated source, and does not define a language-specific build
toolchain. The module READMEs describe the planned boundaries and status.

The root command surface is provided by Just. Repository checks use Git-std,
Prim, and Just only; future module toolchains remain inside their modules.

The repository is licensed under the root [MIT License](LICENSE). Future module
READMEs must refer to this license rather than adding another license file.
