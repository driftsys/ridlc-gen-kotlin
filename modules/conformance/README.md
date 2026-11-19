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
- `src/test/descriptor/` holds the workspaces `DescriptorTest` builds into
  deployment descriptors: `identity` is
  `crates/ridl-rt-conformance/fixtures/identity` at the pinned tag, without its
  committed descriptor, which the pinned `ridl` rebuilds byte for byte;
  `attributes` is that fixture with attributes and an external component.
- `src/test/systems/` holds workspaces that declare an rsdl system, which the
  corpus tests do not run over: `instance-numbers` is
  `crates/ridlc/tests/corpus/instance-numbers` at the pinned tag, unchanged.
- `src/test/resources/probes/` holds hand-written probes, compiled with a
  package's generated code, for what the model-driven probe does not reach.
- `checkSchema`, run by `check`, fails when the schema vendored under
  `modules/ridlc-gen-kotlin/src/main/proto` differs from the pinned release's.

## Status

Pinned to `editor-v0.21.0`, the tag that carries ridl 0.21.0's binaries. The
tests of stage K2a run: a request the pinned `ridl` wrote parses, a request with
an unknown key parses, a request nested 1,000 levels parses in process and
through the installed script, a wrong schema is one error diagnostic and exit 0,
an unknown option is an error diagnostic, unreadable input is exit 3, and the
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

The test of stage K2c, `CodecTest`: for every corpus package that selects
FlatBuffers, the generated `Codec.kt` encodes sample values of every public
root, written from the model (`RoundTrip`), and a corpus of those buffers and
their mutants — 19,023 in all — goes through `verify`, `decode` and `encode`
again in Kotlin and, once, in the Rust codec of the pinned release, whose
verdicts are checked in as
`resources/flatbuffers/<package>-codec-rust-verdicts.txt`. Every sample
re-encodes to the same bytes in Rust, no buffer meets an exception other than
`VerifyError`, and every verdict is Rust's except where Kotlin alone refuses a
string off its pattern, which the Rust verifier checks only under its
`validate-pattern` feature; no buffer of the corpus reaches one. A wrong table
layout, a missing count check, a wrong union error or a float step check that
never fails each turns it red.

`DescriptorTest` (#142) checks `ridl-descriptor-kt` against the Rust
`ridl-descriptor` of the pinned release: both fixtures of `src/test/descriptor`
read as built, and 55,824 truncations and one-byte changes of their deployment
descriptors and of a catalog descriptor are refused or read as the Rust `verify`
refuses or reads them, down to planus's error kind. `just rust-verdicts`
regenerates `resources/descriptor/descriptor-rust-verdicts.txt`.

`InstancesTest` (#137) builds the `instance-numbers` system with `--emit rust`:
the `Instances.kt` of each of its two packages compiles, and its constants are
those of the Rust `__ridl_instances.rs` of the same build, under the system's
deployment and under a second one selected with `--deployment`. With two
deployments and none selected, ridl sends no deployment section and the plugin
writes no file, and a corpus package with no system gets none.

The test of #134, `PodbufCodecTest`, holds the podbuf codec to the Rust podbuf
codec of the pinned release in the same way, for every corpus package that
selects podbuf: `kt-podbuf`, which selects it alone and reaches every form of
the layout law, and `cabin` and `kt-values`, which select all three encodings.
The generated `PodbufCodec.kt` encodes the samples `RoundTrip` writes from the
model, in its podbuf mode; the corpus is `CodecTest`'s, plus 200 random mutants
of every sample from seed 134, and for each map of a full sample its first two
entries swapped, its second key made the first, and for a float key a NaN key
and the keys -0.0 then 0.0, 24,756 buffers in all, and each goes through
`verify`, `decode` and `encode` in Kotlin at position 0 and at position 8 of a
larger buffer, which must agree, and once in Rust, whose verdicts are checked in
as `resources/podbuf/<package>-codec-rust-verdicts.txt`. The Rust program copies
each input into an 8-aligned `Aligned` buffer before `verify`. Every verdict is
Rust's, with no exception. A test walks the podbuf projection of each of those
models and fails on a form, an integer or float width, a descriptor kind or an
interaction kind the corpus does not reach. A field read one byte off its
offset, a string length read before its bound is checked, or map entries written
unsorted each turns it red. `EncodingsFaceTest` drives `cabin`'s face over a
FlatBuffers, a proto3 and a podbuf loopback, and a package of its own over all
three with a reply stream served by `serveAsync` and read by the async client,
whose element codec is chosen from the port's encoding on each side; the same
package selecting FlatBuffers and podbuf, proto3 alone, and podbuf alone serves
each port it carries, and its client, publisher and `dispatch` refuse a port of
any other encoding. `PodbufEmitterRunTest` compiles packages of its own with
warnings as errors and runs what the corpus cannot pin by verdict: a u64 at
2^63, every byte of a buffer replaced by six hostile values and every
truncation, the tail's placement order, map key order over UTF-8 bytes and over
-0.0 and 0.0, a string holding U+0000, a stride, a length checked before its
data, a deprecated field and an empty union arm.

The test of #133, `Proto3CodecTest`, holds the proto3 codec to the Rust proto3
codec of the pinned release in the same way, for every corpus package that
selects proto3: `kt-proto3`, which selects it alone, reaches every wire kind of
the projection, and whose interface sends the structs the lowering induces for a
call of two parameters, a call of none and a tuple reply, each a proto3 root of
its own, and `cabin` and `kt-values`, which select all three encodings. The
generated `Proto3Codec.kt` encodes the samples `RoundTrip` writes from the
model, in its proto3 mode; the corpus is `CodecTest`'s, plus 200 random mutants
of every sample from seed 133, plus, for each full sample and union arm, the
reader cases of the Rust codec's header: its fields in reverse number order,
each packed field unpacked and mixed, and an unknown field, each read as the
sample is; a singular field twice (`Duplicate`), one under another wire type
(`WireType`), a 32-bit field's varint past u32 (`Varint`), a fixed-width packed
run one byte long (`OutOfBounds`), a map's second key made its first's
(`Unique`), a map with too many entries whose last repeats a key (`Length`),
optional scalars present at their zero or empty defaults, and a double set to
`-0.0`, which is written again. Each buffer is run through `verify`, `decode`
and `encode` in Kotlin at position 0 and at position 8, which must agree, and
once in Rust, whose verdicts are checked in as
`resources/proto3/<package>-codec-rust-verdicts.txt`. Every verdict is Rust's,
with no exception: the Rust crate checks a pattern too, under its
`validate-pattern` feature, a default one the verdict program keeps, as for
FlatBuffers and podbuf. A test checks each reader case's own verdict, and one
walks the projection and fails on a wire kind the corpus does not reach; the
proto3 `INT64` scalar has no source, since an enum is projected as an enum. A
package of its own selects proto3 alone and declares an interface: it gets its
face with no warning, and the codec of the struct its call induces compiles and
writes `0803120261 62` for `(3, "ab")`. Writing `-0.0` as the default, reading
no `Duplicate`, or writing a packed array one record per element each turns it
red.

`Proto3EmitterRunTest` compiles regressions for nested messages with no valid
empty default, present optional defaults, map count ordering, and encode
refusals for range, UTF-8 and length. It also checks enum and enum-set map keys
in hand-built models, since source syntax refuses those keys, and packed numeric
input when the projection selects an unpacked writer.

`SelectionTest` holds the selection rule (docs/design.md §4): the output of
every corpus package, and of every package its build reaches, generated with
`encodings` set to `flatbuffers` alone, has the digests in
`resources/flatbuffers/output-digests.txt`, one line per file, 48 in all. The
lines of every package older than #134 were taken from `main`'s output before
podbuf, so podbuf changed no byte of a FlatBuffers-only package, and the proto3
codec added the lines of `kt-proto3` alone. The proto3 face (#133) changed only
`kt-proto3`'s lines, through the interface the corpus gave it. That output also
names no podbuf item, and neither `proto3` nor `ridl.rt.proto3`.

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
pass with any hash. `CatalogTest` checks the hash itself (#48): for every corpus
package, the `CatalogRef` of each generated descriptor is the `Catalog` of the
model the pinned `ridl` hands the plugin, name and hash, none of 32 zero bytes;
the Rust backend reads the same model, so no ridl formatting is read (#65). A
zero hash or one wrong byte turns it red. Untimed commands and queries take a
default response bound of 1 s and 3 s, which the model states as their timing.
The Rust codec and `ridl-rt`'s FlatBuffers code differ from 0.5.1's in comments
only, so no golden file or Rust verdict changed. The corpus copies of `cabin`
(doc comments and a `service` declaration), `fb-demo` and `veh-cruise`
(comments) were taken again from the tag.

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

The test of stage K3a, `FacesTest`: the generated faces of `cabin`, `kt-values`
and `face-induced`, over `ridl-rt-kt-loopback`, driven by the probes of
`resources/faces/`. Cabin's four interaction kinds round-trip — a signal set and
read, an event raised and received, a command sent, dispatched and acknowledged,
a query sent, dispatched and replied — and `dispatch`'s settlement table is
reached past the client: a failing `require`, a corrupt argument buffer, an
argument outside its constraints, an unknown ordinal, another interface's
number, a settlement the handler refuses, a buffer too short, and, since ridl
0.4.0, a claim larger than the size table's `maxBufferSize`: settled `Corrupt`
with the claim behind it served, or ending the pass when that settlement is
refused, while a `ReadError.Short` from `nextClaim` stays `ProviderError.Claim`.
`kt-values`' `Probe`, driven through its blocking and async clients with `serve`
and `serveAsync`, adds a failing `ensure` returned as
`ClientError.Call(ContractBroken)`, a float clause and a signal's own init, and
settles a query by hand with every outcome a provider or a runtime can send: a
corrupt reply is `Call(Corrupt)`, a reply outside its constraint
`Call(InvalidValue)`, a provider's failed `require` `Call(PreconditionFailed)`,
an unknown interaction `Call(UnknownInteraction)`. A wrong comparison operator,
a wrong settlement, a missing interface check or a reply check always reported
as `Corrupt` each turns it red. `face-induced` (ridl 0.10, driftsys/ridl#713)
sends a call of one named parameter as it is, and the struct the lowering
induced for a call of two parameters, of none, and for a tuple reply; each is
dispatched and settled, the provider is handed each argument as it was sent, and
the size table reserves each induced struct's codec's `maxSize`.

The test of #156, `UploadRequireTest`: a `require` on the ordinary parameter of
a call that also takes a parameter stream, which the lowering translates since
ridl 0.18.0 (driftsys/ridl#1289) with the parameter's declared index, 1 when the
stream comes first. Its package, written by the test after driftsys/ridl's
`crates/ridl-backend-rust/tests/query_uploads.rs`, is built with the stream last
and with it first: an upload query, an upload command and a query of both
streams, beside a query with a reply stream, each `require limit > 0`. Over the
loopback with `serveAsync`, a call with `limit` 0 is refused by the async client
with `Send(Contract(PreconditionFailed))`, without collecting its stream or
reaching the provider, and settled `PreconditionFailed` by the provider when
sent past the client; a call with `limit` 1 goes through.

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

The tests of #186, `PatternsTest`: a typl pattern is an I-Regexp that matches
the whole value (driftsys/ridl#597). Each literal of ridl's agreement table,
`resources/patterns/pattern-agreement.json`, which is the pinned release's
`crates/ridl-ir/tests/pattern_agreement.json`, becomes a named string type, and
each of the table's 125 pairs gets the table's verdict from the generated
`ofOrNull`. A local `Day` type uses the same pattern text as `ridl.std.Date` and
refuses a date with a character before or after it, or a line break after it,
and a date in other digits. A string with two clauses refuses a value that
matches only one of them, in a named type and in an inline struct field. That a
pattern `java.util.regex` cannot compile refuses its declaration (#12) is
checked with hand-built constraints in `TypesEmitterTest` and `PatternsTest`,
including a repetition bound Java cannot compile. Codec tests also check that
both encodings refuse an inline string that breaks only its second match clause.

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
X-8, X-8c, X-9, X-11, X-12, X-13 and X-14b, and three cases only Kotlin meets:
two declarations `Level` and `level`, a struct named `LevelCodec`, and a member
named `provider` beside a call. X-6a, a tuple's `minSpeed` and `min_speed`, is
refused by ridl since 0.15 (RIDL-149, driftsys/ridl#1210), so a tuple's `XY` and
`x_y` test the plugin's refusal instead. X-8b, whose face is skipped, claims
nothing and compiles, as X-14a and X-17 do.

The test of #105, `StreamsTest`: a member with a stream has no method, no call
and no typed descriptor, keeps its descriptor row and its share of the table
budget, and leaves the rest of its interface generated and compiling.

The test of #17, in `NamesTest`: X-18, a package `veh` declaring `type common`
beside its child package `veh.common`, which uses it, compiles, and a consumer
builds a `veh.common.Uses` from a `veh.Common`.

The test of #157, `WellFormedTest`: a package of its own carrying both
encodings, compiled with warnings as errors. A named string scalar's `of`
refuses `"x\uD800y"`, a lone low surrogate and a high surrogate at the end as
`Utf8`, including a scalar without constraints and when the value breaks its
length and its pattern. Each encoder refuses the same values in a bare field, a
named field, an array element, a map key and a map value, an optional, a union
arm, a nested table and a vector of tables, with `EncodeError.Contract` naming
the named type or the owner, and round-trips `"😀"` in each. A Rust `String`
cannot hold such a value, so there are no Rust bytes to compare with. The output
digests of `SelectionTest` were written again for it.
