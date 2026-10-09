# K1b: the FlatBuffers verifier spike (O-K1)

**Status:** spike done, for Sebastien's disposition of O-K1. Written 2026-09-24.
`docs/design.md` §4 recommends option A — FlatBuffers with a verifier in
`ridl-rt-kt` — "decided by a bounded spike at the start of K1: write the
verifier for the cabin package's four payload types; if it comes in under the
size above and passes the malformed-buffer corpus (§7), FlatBuffers stays; if
not, C, with the frame amended." This is that spike. The design note is not
edited here (it is the same text as the driftsys/ridl copy); the frame
specification is untouched, because the answer is not C.

## What was built

- **`ridl-rt-kt`'s `ridl.rt.flatbuffers` package** — `Reader` and `Builder`, the
  Kotlin spelling of `ridl_rt::flatbuffers` at the pinned release: the
  bounds-checked little-endian reads, the vtable walk, the string and vector
  headers, and the back-to-front builder. Like the Rust module it decides no
  layout; a generated codec hands it slots, offsets and table sizes. Every read
  outside the buffer throws `VerifyError.Structure`, never a JVM exception.
- **The cabin package's payload codecs, written by hand** in the shape the K2c
  emitter would generate, over those two classes and the generated `Types.kt`:
  `Temperature`, `Level`, `Window`, `Average` and `Health` in their box tables
  (ADR-0019 decision 8), and `Warning` in its own table. They are in
  `modules/conformance/src/test/resources/flatbuffers/cabin-codec.kt`. Cabin has
  six payload types, not the four §4 counts: the command's and the query's
  arguments and the query's reply are three of them, and the `Horn` interface
  adds `Health`.
- **`SpikeTest`**, in the conformance module, run on every `check`.

## What was measured

| Criterion                                   | Result                                                                                                                                                                                   |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Size against `ridl-rt`'s FlatBuffers module | 363 lines, 210 of code, against the Rust module's 600 and 361                                                                                                                            |
| Encoding interoperates with Rust            | Kotlin encodes all 16 sample values of the six types to exactly the bytes the Rust codec encodes (`cabin-golden.txt`), and decodes those bytes back to the values                        |
| The malformed-buffer corpus                 | 1,385 mutants of the 16 buffers: every truncation, every byte set to 0x00, 0xFF and itself plus one, and each buffer grown past its size bound. None meets an exception of the JVM's own |
| The verifier agrees with Rust's             | On all 1,385, Kotlin's verdict — refused with which error, or decoded to which value — is the Rust verifier's, text for text: 978 refused, 407 decoded                                   |
| The comparison can fail                     | Removing the check that a field lies inside its table turns 24 verdicts into disagreements                                                                                               |

**Since ridl 0.5.0** (2026-10-01): the Rust codec reads an absent non-optional
scalar or enum field as its FlatBuffers default when 0 is a legal value of its
type (driftsys/ridl#472), and every cabin type admits 0. Regenerated over
`editor-v0.5.0`, 142 verdicts move from `MissingRequired` to a decoded default,
so the corpus is 960 refused and 425 decoded, and the hand-written codec reads
an absent field as 0 to match. ridl 0.4.0's PascalCase variants also changed the
`Debug` text of a `Health` (`Ok`, not `OK`). The golden bytes did not change.
The corpus no longer reaches a refused missing field; `kt-zero`'s, under
`CodecTest`, does.

**Since ridl 0.5.1** (2026-10-03): the Rust verifier checks float steps,
non-finite floats, inline constraints and map key uniqueness
(driftsys/ridl#654). Regenerated over `editor-v0.5.1`, neither the golden bytes
nor the 1,385 verdicts change: no cabin type has a float or a map.

**Since ridl 0.6.0** (2026-10-07): the Rust codec emitter and `ridl-rt`'s
FlatBuffers code differ from 0.5.1's in comments only, and no cabin type
changed, so the golden bytes and the verdicts stand as regenerated over
`editor-v0.5.1`.

**Since ridl 0.7.0** (2026-10-09): the Rust codec emitter and `ridl-rt`'s
FlatBuffers code are unchanged, so regenerating over `editor-v0.7.0` gives the
same golden bytes and verdicts.

The corpus covers §7's malformed cases for these types — truncated buffers,
offsets past the end, a vtable naming a field across its table's end, a missing
required field, an enum discriminant out of range, a value outside its range, a
buffer over its size bound. It does not reach a vector longer than its bound, a
union discriminant or a string, because the cabin package has none; K2c's corpus
over `fb-demo` and `veh-common` does.

## Recommendation

**Option A: FlatBuffers stays**, tag 1, as the frame specification §11.2 already
says. The verifier is smaller than its Rust original, and it agrees with it on
every buffer of the corpus.

One consequence to dispose of with it. D-K5 has the codec written against the
classes `flatc --kotlin` generates. The spike's codec uses none: like the Rust
codec, which does not use the `flatbuffers` crate, it reads and writes through
`ridl.rt.flatbuffers` alone. The recommendation is to keep it that way in K2c,
which removes §4's second objection to FlatBuffers — the large `flatc` output, a
second projection of every declaration — and removes `flatc` from the consumer's
build. The `.fbs` schema is still `ridl build`'s to emit (D-K5's first half
stands), for a consumer in another language.

## Regenerating the Rust verdicts

`just rust-verdicts` regenerates every file the conformance module compares with
the Rust codec of the pinned release — `cabin-golden.txt`,
`cabin-rust-verdicts.txt` and each `<package>-codec-rust-verdicts.txt` under
`modules/conformance/src/test/resources/flatbuffers/` — and fails when one
differs from the committed file; `just rust-verdicts --write` writes them
instead. The packages are those of the corpus, so a new corpus package with no
verdict file fails the check until `--write` creates its file (#49). The
`rust-verdicts` workflow runs it whenever the pin, the conformance tests and
their corpus, the plugin, the runtime, a Gradle build file or the version
catalog, or the recipe changes (#39, #53). It needs cargo, git and python3
beside the JVM build. A program that fails has its standard error printed, and
`--ridl-checkout` is refused unless it is at the pinned tag.

The recipe runs `CodecTest` and `SpikeTest`, which write the corpus to
`modules/conformance/build/spike/`, and then `scripts/rust-verdicts.py`, which:

1. clones driftsys/ridl at the tag in `modules/conformance/ridl-release`
   (`--ridl-checkout` names an existing checkout instead);
2. writes each corpus package's crate with `ridl build --emit rust`;
3. writes, under `modules/conformance/build/rust-verdicts/`, one round-trip
   program per package — each `pkg.Type label hex` corpus line is verified,
   decoded and re-encoded by the Rust codec — and the cabin spike's program,
   which encodes the golden values and verifies the spike's corpus. The
   repository tracks no Rust (D-K9), so the script writes them at each run. Each
   depends on `ridl-rt` at the release's minor version, `0.7` for
   `editor-v0.7.0`, patched to the tag's `crates/ridl-rt`;
4. builds them with cargo, runs them over the corpus, and compacts a round
   trip's output to the verdicts `CodecTest` compares: `ok:` and the first 16
   hex digits of the SHA-256 of the re-encoded hex, or `err:` and the error.
