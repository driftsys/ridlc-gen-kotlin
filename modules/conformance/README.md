# conformance

## Responsibility

This module owns the pinned `ridl` release, the corpus, and every test of
docs/design.md §7 that needs the plugin from outside. It is a JVM test module.
The repository is licensed under the root [MIT License](../../LICENSE).

- `ridl-release` is the pin: one `driftsys/ridl` release tag. The `installRidl`
  task installs that release's `ridl` binary into `build/ridl` with the
  release's own `install.sh`; `RIDL_BIN=<path>` uses an installed `ridl`
  instead, for a machine with no network. The pin never resolves to a local
  checkout.
- `src/test/corpus/` holds one directory per corpus package; its README says
  where each comes from and what it exercises.
- `src/test/resources/probes/` holds hand-written probes, compiled with a
  package's generated code, for what the model-driven probe does not reach.
- `checkSchema`, run by `check`, fails when the schema vendored under
  `modules/ridlc-gen-kotlin/src/main/proto` differs from the pinned release's.

## Status

Pinned to `editor-v0.7.0`, the tag that carries ridl 0.7.0's binaries. The tests
of stage K2a run: a request the pinned `ridl` wrote parses, a request with an
unknown key parses, a request nested 1,000 levels parses in process and through
the installed script, a wrong schema is one error diagnostic and exit 0, an
unknown option is an error diagnostic, unreadable input is exit 3, and the
parity test compares `ridl build` with `--plugin kotlin=<script>` to the script
invoked directly, for every package each build hands the plugin.

The tests of stage K2b: for every corpus package, the generated `Types.kt`
compiles with `kotlin-compile-testing` against `ridl-rt-kt` with warnings as
errors; and a probe written from the model, compiled with it, checks that every
constrained scalar accepts each bound and refuses one step outside it with the
right rule, that enums and enum sets read their declared members and no other,
and, for `kt-values`, that a struct checks its collections and inline fields.
Removing the float maximum check, counting UTF-16 units, or dropping an array
bound from the emitter each turns the probe red.

The test of stage K1b, `SpikeTest`: the cabin package's payload codecs, written
by hand over `ridl.rt.flatbuffers`, encode the bytes the Rust codec of the
pinned release encodes (`resources/flatbuffers/cabin-golden.txt`), and a corpus
of 1,385 mutants of those bytes is refused or decoded exactly as the Rust
verifier refuses or decodes it (`cabin-rust-verdicts.txt`), never by an
exception of the JVM's own. `just rust-verdicts` regenerates both files and
every codec verdict file below from the pinned release and fails when one
differs from the committed file, and the `rust-verdicts` workflow runs it (#39);
[`docs/k1b-flatbuffers-spike.md`](../../docs/k1b-flatbuffers-spike.md) says how.

The test of stage K2c, `CodecTest`: for every corpus package, the generated
`Codec.kt` encodes sample values of every public root, written from the model
(`RoundTrip`), and a corpus of those buffers and their mutants — 15,780 in all —
goes through `verify`, `decode` and `encode` again in Kotlin and, once, in the
Rust codec of the pinned release, whose verdicts are checked in as
`resources/flatbuffers/<package>-codec-rust-verdicts.txt`. Every sample
re-encodes to the same bytes in Rust, no buffer meets an exception other than
`VerifyError`, and every verdict is Rust's except where Kotlin alone refuses a
string off its pattern, which the Rust verifier checks only under its
`validate-pattern` feature; no buffer of the corpus reaches one. A wrong table
layout, a missing count check, a wrong union error or a float step check that
never fails each turns it red.

Since ridl 0.7.0 (#55), a catalog is one unit, the source packages under one
`ridl.toml`, and every catalog hash changed. `UnitTest` builds the corpus
package `kt-unit`, a root package and a subpackage under one manifest with the
unit's `interfaces.lock`. It checks that both models name catalog `kt.unit` with
one nonzero hash, and that the two generated descriptors carry that `CatalogRef`
and the lock's numbers, 1 and 2. As a corpus package, it also goes through every
test over the corpus, with its own Rust codec verdicts (#65). No golden holds a
catalog hash, so no other test changed. The Rust codec and `ridl-rt`'s
FlatBuffers code did not change, and the corpus copy of `cabin` (two anchor
comments) was taken again from the tag.

Since ridl 0.6.0, the model carries the real catalog hash (driftsys/ridl#676),
so every generated descriptor of the corpus carries it; the faces tests attach
their loopbacks to the generated `<Iface>.catalog`, so they did not change, and
the cabin hash the plugin writes is the one the Rust backend writes. Untimed
commands and queries take a default response bound of 1 s and 3 s, which the
model states as their timing. The Rust codec and `ridl-rt`'s FlatBuffers code
differ from 0.5.1's in comments only, so no golden file or Rust verdict changed.
The corpus copies of `cabin` (doc comments and a `service` declaration),
`fb-demo` and `veh-cruise` (comments) were taken again from the tag.

Since ridl 0.5.1 (driftsys/ridl#654), the Rust verifier checks every float step,
every non-finite float under a range and every inline constraint, which Kotlin
alone checked before, and refuses two map entries with one key. The codec
verdicts were regenerated over that release: the 91 refusals that were Kotlin's
alone (`kt-values` 5, `kt-zero` 13, `veh-common` 37, `veh-cruise` 36) are now
Rust's too, and `CodecTest` no longer excuses them; and 2 `kt-zero` buffers
whose `Loose` field is absent move from `MissingRequired` to a decoded 0, since
a step with no minimum now counts from 0. The spike's verdicts and golden bytes
did not change. The corpus package `payload-constraints`, ridl's own fixture for
#654, adds 2,731 buffers.

The test of driftsys/ridl#654, `ConstraintsTest`: ridl's
`crates/ridl-backend-rust/tests/payload_constraints.rs`, case for case where
Kotlin can spell it, over the `payload-constraints` package, its model rewritten
as the Rust test rewrites the IR. The value objects accept the lattice points of
a decimal, a large-origin, a subnormal, an underflowing and an overflowing step
and refuse their neighbours; a value and the binary32 it crosses the wire as get
one verdict; an absent field of an extreme lattice reads as 0; and `verify`
refuses a step, a pattern and a duplicate textual, integer, float (0.0 and -0.0)
or bytes key with the right rule, while two NaN keys pass. Removing the float
step check or the key comparison each turns it red.

Since ridl 0.5.0 (driftsys/ridl#472), an absent non-optional scalar or enum
field reads as its FlatBuffers default when 0 is a legal value of its type; a
mutant that zeroes a byte of a vtable entry makes a field absent. Both the codec
verdicts and the spike's were regenerated over that release, where 219 verdicts
changed from `MissingRequired` to a decoded default, and the spike's
hand-written codec follows. The corpus package `kt-zero` holds what the other
packages cannot: an absent field whose type excludes 0, an inline range, a named
range, a step whose grid misses 0 and an enum with no zero member, refused, and
a step with no minimum, which admits 0 since ridl 0.5.1, each beside one that
admits 0, in a struct, a tuple, a map entry, a union arm and a box root. Reading
every absent scalar as 0, reading an absent enum as its first member rather than
its zero member, or refusing an absent named scalar each turns it red.

The test of stage K3a, `FacesTest`: the generated faces of `cabin` and
`kt-values`, over `ridl-rt-kt-loopback`, driven by the probes of
`resources/faces/`. Cabin's four interaction kinds round-trip — a signal set and
read, an event raised and received, a command sent, dispatched and acknowledged,
a query sent, dispatched and replied — and `dispatch`'s settlement table is
reached past the client: a failing `require`, a corrupt argument buffer, an
argument outside its constraints, an unknown ordinal, another interface's
number, a settlement the handler refuses, a buffer too short, and, since ridl
0.4.0, a claim larger than `MAX_BUFFER_SIZE`: settled `Corrupt` with the claim
behind it served, or ending the pass when that settlement is refused, while a
`ReadError.Short` from `nextClaim` stays `ProviderError.Claim`. `kt-values`'
`Probe`, driven through its blocking and async clients with `serve` and
`serveAsync`, adds a failing `ensure` returned as
`ClientError.Call(ContractBroken)`, a float clause and a signal's own init, and
settles a query by hand with every outcome a provider or a runtime can send: a
corrupt reply is `Call(Corrupt)`, a reply outside its constraint
`Call(InvalidValue)`, a provider's failed `require` `Call(PreconditionFailed)`,
an unknown interaction `Call(UnknownInteraction)`. A wrong comparison operator,
a wrong settlement, a missing interface check or a reply check always reported
as `Corrupt` each turns it red.

The test of #7, `ClientsTest`: the clients and `serve` generated for `cabin`,
over `ridl-rt-kt-loopback`, driven by the probe of `resources/clients/`. The
call objects directly: a failing `require` sends nothing, `Busy` leaves a call
unsent until a slot frees, a call at exactly its `max` is within it and one past
it throws `Send(Busy)` unsent or `Call(Undelivered)` and `Call(Timeout)` sent,
an outcome taken or a `cancel` forgets the call once, and a finished call polled
again throws. The blocking client and `serve` round-trip every kind with the
provider on its own thread, throw the documented error at the timeout for a sent
and an unsent call, and `serve` throws `ProviderError.Serve` and
`ProviderError.Claim`. The async client and `serveAsync` round-trip, a signal
read included, a cancelled coroutine gives its call's slot back, and a client
runs one call at a time. A detached event source makes either client's
`nextEvent` throw `ClientError.Read`, and a provider's own `ReadError` leaves
`serve` unchanged. `serve` keeps to its timeout under a claim stream that never
ends, two async `nextEvent` calls wait without waking each other, and Horn,
signal-only, keeps its one client, and `kt-values`' `Names` interface, whose
parameters are named after the generated code's own members and locals
(`timeout`, `calls`, `port`, `provider`, `handler`, `buffer`, `claim`, `reply`),
compiles. A deadline compared with `>=`, a call that does not forget its
outcome, a `cancel` that does not forget, `block`'s two errors swapped, an async
call outside its lock, or a coroutine cancellation that does not cancel the call
each turns it red.

Since ridl 0.4.0's serve bound (driftsys/ridl#568), `dispatch` takes at most its
`budget` of claims and says when it stopped there, and `ClientsTest` runs
`serveAsync` under a claim stream that never ends on one thread: it settles pass
after pass, a coroutine beside it runs, and a cancellation stops it. Removing
the self-wake at the bound, or the yield in `awaitPoll`, turns it red.

The test of #9, in `FacesTest`'s `kt-values` probe: `Clash`, whose members are
named like the face's fixed and derived operations — signals `next_event`,
`timeout`, `get_timeout`, `commit`, `invalidate_level` and `touch_level` beside
a signal `level`, `subscribe_ping` and `unsubscribe_ping` beside an event
`ping`, a command `set_timeout` and a query `new` — the cases of the Rust
`face_compile.rs`, compiles with warnings as errors, and runs: each member keeps
its plain call on both clients and the publisher, and each operation stays
reachable, the shadowed `nextEvent`, `subscribePing` and `unsubscribePing`
through an aliased import. The probe imports the extensions of `Probe` and
`Clash` under one name, the Kotlin counterpart of a consumer of two preludes.

The test of #12, `PatternsTest`: a package the pinned `ridl` accepts, whose
patterns ECMA-262 and the Rust `regex` crate both compile, declares four that
`java.util.regex` does not — `\p{Greek}` through a regex constant, `\p{Letter}`,
`\u{41}` and, inline in a struct, `\p{Emoji}` — and each refuses its declaration
with the pattern and Java's reason. Eighteen patterns Java compiles, where the
engines' syntaxes differ most, generate, compile, and their classes load.

The tests of #16 and #18, `NamesTest`: a name the plugin chose never refuses a
package. Two packages, written inline and built with
`ridl build --emit codegen-model`, declare names that meet one the plugin
writes. The first declares `Constants`, `InteractionCall`, `TypesKt`, `CodecKt`,
`FacesKt` and the Kotlin classes the generated code names in expressions
(`Long`, `Int`, `List`, `ByteArray`, `Math`, …), beside an interface of every
kind and the expressions that name them. The second declares enum values named
`name`, `ordinal`, `entries`, `value`, `Companion`, `null` and `in`, enum set
bits `EMPTY`, `EMPTY_` and `DECLARED_MASK`, struct and tuple fields `other`,
`result`, `class`, `fun`, `this` and `in`, and a parameter and a member named
like keywords. Both compile with warnings as errors, and a probe checks that
`equals` and `hashCode` read a field named `other`, that the escaped entries and
bits keep their values, and that the struct round-trips through its codec.
`Compiler` now compiles each file under its own path and name, so a file's JVM
class is the one a consumer's build gives it: a JVM class clash with a file is
seen.

Two generated names spelled from ridl names that one Kotlin namespace cannot
hold refuse the package with one message naming both sources: the design note's
X-6a, X-8, X-8c, X-9, X-11, X-12, X-13 and X-14b, and three cases only Kotlin
meets: two declarations `Level` and `level`, a struct named `LevelCodec`, and a
member named `provider` beside a call. X-8b, whose face is skipped, claims
nothing and compiles, as X-14a and X-17 do.

The test of #17, in `NamesTest`: X-18, a package `veh` declaring `type common`
beside its child package `veh.common`, which uses it, compiles, and a consumer
builds a `veh.common.Uses` from a `veh.Common`.
