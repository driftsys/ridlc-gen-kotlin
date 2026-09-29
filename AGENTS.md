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

`ridl` enters the repository only as the release tag in
`modules/conformance/ridl-release` (D-K9). The `ridl.codegen.v1` schema under
`modules/ridlc-gen-kotlin/src/main/proto` is that release's copy, and the
`checkSchema` task fails when they differ.

`docs/design.md` is the same text as
`docs/wip/2026-09-23-kotlin-plugin-design.md` in driftsys/ridl. Do not edit it
here alone; record where the code departs from it in the module README that owns
the departure.
