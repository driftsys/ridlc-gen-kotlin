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

## Releasing

`git std bump` writes the changelog from the commit messages. Two things it
cannot do here: it detects no version file, so `version` in `gradle.properties`
is set by hand, and it lists as breaking only a commit marked `!` or carrying a
`BREAKING CHANGE:` footer. A break that reached `main` unmarked is added to the
changelog by hand, from the list below.

1. `git std bump --no-commit` prepends the release's section to `CHANGELOG.md`.
2. Set `version` in `gradle.properties` to the release's version.
3. Add each pending note below to the new section's `### BREAKING CHANGES`, and
   remove it from this list.
4. Commit as `chore(release): <version>` and tag `v<version>`.
5. Push the commit through a pull request, then push the tag. The `release`
   workflow checks that the tag matches `gradle.properties`, runs `just build`
   and `just rust-verdicts`, publishes the libraries to GitHub Packages, and
   creates the GitHub release with the plugin distribution, `install.sh`, the
   Android vendor package, the conformance results and their `SHA256SUMS`. It
   runs that `install.sh` on the assets before it publishes anything.
6. Set `version` in `gradle.properties` to the next `-SNAPSHOT`, so the
   `snapshot` job of the `ci` workflow publishes `main` again.

A hand-written line survives every later `git std bump`, which only prepends;
`git std changelog --full` regenerates the file from the commits and drops it.

### Breaking changes the commits do not mark

- **ridl-rt-kt:** `ReadError.ShortClaim` (b748a3d,
  driftsys/ridlc-gen-kotlin#14). `ReadError` is a sealed class, so an exhaustive
  `when` over it no longer compiles, and a `Handler` whose `nextClaim` reports
  an oversized claim with `Short` no longer meets the port contract: it reports
  `ShortClaim` with the claim's id. Rust's `ReadError` is `#[non_exhaustive]`,
  so the same change was not breaking there.
