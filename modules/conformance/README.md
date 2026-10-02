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

Pinned to `editor-v0.4.0`, the tag that carries ridl 0.4.0's binaries. The tests
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
exception of the JVM's own. How to regenerate both files is in
[`docs/k1b-flatbuffers-spike.md`](../../docs/k1b-flatbuffers-spike.md).

The test of stage K2c, `CodecTest`: for every corpus package, the generated
`Codec.kt` encodes sample values of every public root, written from the model
(`RoundTrip`), and a corpus of those buffers and their mutants — 10,266 in all —
goes through `verify`, `decode` and `encode` again in Kotlin and, once, in the
Rust codec of the pinned release, whose verdicts are checked in as
`resources/flatbuffers/<package>-codec-rust-verdicts.txt`. Every sample
re-encodes to the same bytes in Rust, no buffer meets an exception other than
`VerifyError`, and every verdict is Rust's except where Kotlin alone refuses a
step, a NaN or an inline constraint. A wrong table layout, a missing count check
or a wrong union error each turns it red.

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

The test of #16, `NamesTest`: a name the plugin chose never refuses a package.
Two packages, written inline and built with `ridl build --emit codegen-model`,
declare names that meet one the plugin writes. The first declares `Constants`,
`InteractionCall`, `TypesKt`, `CodecKt`, `FacesKt` and the Kotlin classes the
generated code names in expressions (`Long`, `Int`, `List`, `ByteArray`, `Math`,
…), beside an interface of every kind and the expressions that name them. The
second declares enum values named `name`, `ordinal`, `entries`, `value`,
`Companion`, `null` and `in`, enum set bits `EMPTY`, `EMPTY_` and
`DECLARED_MASK`, struct and tuple fields `other`, `result`, `class`, `fun`,
`this` and `in`, and a parameter and a member named like keywords. Both compile
with warnings as errors, and a probe checks that `equals` and `hashCode` read a
field named `other`, that the escaped entries and bits keep their values, and
that the struct round-trips through its codec. `Compiler` now compiles each file
under its own path and name, so a file's JVM class is the one a consumer's build
gives it: a JVM class clash with a file is seen.
