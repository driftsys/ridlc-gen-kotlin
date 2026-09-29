# Contributing

The work lands in the stages of [`docs/design.md`](docs/design.md) §8, one or
more pull requests per stage. Keep each module's code, tests and README
together, and record in the module README where the code departs from the
design.

## Commits

Use Git-std Conventional Commits with one of the explicit scopes configured in
`.git-std.toml`. For example:

```text
feat(ridl-rt-kt): spell the port interfaces
```

Run commit checks through the root Just interface.

## Build

One Gradle build, Kotlin DSL, run through the wrapper. Declare every dependency
version in `gradle/libs.versions.toml`, and every module under `modules/` in
`settings.gradle.kts`. A module's tests run on `./gradlew check`, which
`just check` calls.

The `ridl` release the tests run against is `modules/conformance/ridl-release`;
bumping it means copying the release's `ridl.codegen.v1` schema into
`modules/ridlc-gen-kotlin/src/main/proto` in the same change, which the
`checkSchema` task enforces.

## Formatting

Use Prim for connective-tissue formatting. Run `just fmt` when formatting files
and use `just fmt-check` to verify formatting without writing changes. Kotlin
follows the official code style (`kotlin.code.style=official`) with a 120 column
limit.

## Verification

Before submitting a change, run `just verify`. This runs commit linting and the
build gate: formatting, Prim linting, the repository check, every Gradle check
and the assembly. CI runs the same recipes.
