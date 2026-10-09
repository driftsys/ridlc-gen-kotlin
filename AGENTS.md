# Repository instructions

This repository holds the RIDL Kotlin plugin, `ridlc-gen-kotlin`, its runtime
library `ridl-rt-kt`, the modules beside them, and their conformance suite. The
design they implement is [`docs/design.md`](docs/design.md); its §8 stages are
the order work lands in.

Use the root `justfile` as the command surface for repository work. Run
formatting, checking, testing, building, and verification through Just; the
recipes call Prim, the repository check, and the Gradle wrapper.

The build is one Gradle build, Kotlin DSL, with one settings file at the root
and every module under `modules/` (docs/design.md §6). Dependency versions live
in `gradle/libs.versions.toml` only. Never track build outputs, generated
sources, `ridl` sources, or a binary other than the Gradle wrapper jar;
`scripts/check-repo.sh` refuses them.

The runtime modules and the generated code are also compiled against Android's
`android.jar`, where Kotlin types `ByteBuffer.position(Int)`, `limit(Int)`,
`flip()` and `clear()` as returning `Buffer`. Never use what one of those
methods returns, in a runtime module or in an emitted statement; call it as a
statement, then use the buffer. CI does not check this
(`modules/ridl-rt-kt/README.md`, "Compiling against Android").

`ridl` enters the repository only as the release tag in
`modules/conformance/ridl-release` (D-K9). The `ridl.codegen.v1` schema under
`modules/ridlc-gen-kotlin/src/main/proto` is that release's copy, and the
`checkSchema` task fails when they differ.

`docs/design.md` is this repository's only record of the design; driftsys/ridl
keeps no copy. Edit it here when the design itself changes, and record where the
code departs from it in the module README that owns the departure.
