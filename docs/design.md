# The Kotlin plugin: `ridlc-gen-kotlin`, `ridl-rt-kt`, the value objects and the faces

**Status:** design note, for Sebastien's disposition. Written 2026-09-23, after
lane P merged (driftsys/ridl#496, commit 7fc417e). It is the design of the first
external codegen plugin, the product of this repository. This file is its only
record; the toolchain repository,
[driftsys/ridl](https://github.com/driftsys/ridl), keeps no copy, and every link
below points at that repository on GitHub.

The note answers four questions in order: how the plugin is launched (§2), what
the Kotlin runtime library is (§3), what the generated value objects are and how
a payload is serialized (§4), and what the generated face looks like (§5). §6 is
the repository layout, §7 the tests, §8 the stages, §9 the decisions with the
alternative each rejected, §10 the open items.

## Table of Contents

1. [What the plugin is bound by](#1-what-the-plugin-is-bound-by)
2. [The launcher and the JVM](#2-the-launcher-and-the-jvm)
3. [`ridl-rt-kt`, the runtime library](#3-ridl-rt-kt-the-runtime-library)
4. [The value objects and the payload codec](#4-the-value-objects-and-the-payload-codec)
5. [The face](#5-the-face)
6. [The repository](#6-the-repository)
7. [What is proved, and how](#7-what-is-proved-and-how)
8. [Stages](#8-stages)
9. [Decisions](#9-decisions)
10. [Open items](#10-open-items)

## 1. What the plugin is bound by

The plugin reads one request and writes one response, and everything it must
know is in five records of this repository, in this order:

- [`docs/specification/ir-specification.md`](https://github.com/driftsys/ridl/blob/main/docs/specification/ir-specification.md)
  §7 and §8: the `schema` and `toolchain` fields, the compatibility rule, and
  the four obligations of a reader in another language (parse leniently, read
  `schema` first, ignore unknown keys, use a file `ridlc` wrote as a fixture).
- [`docs/design/codegen-plugins.md`](https://github.com/driftsys/ridl/blob/main/docs/design/codegen-plugins.md)
  §2 and §4: the two messages, canonical protobuf JSON on standard input and
  standard output, the path rule, the lookup of `ridlc-gen-<language>` on
  `PATH`, the timeout, and the six failure cases the host reports.
- `crates/ridl-ir/proto/ridl/codegen/v1/plugin.proto` and `model.proto`: the
  schema of the request and of the lowered model it carries.
- [`docs/specification/frame-specification.md`](https://github.com/driftsys/ridl/blob/main/docs/specification/frame-specification.md)
  §11.2: the AIDL-over-Binder binding of the frame, which fixes the transaction
  code as the ordinal, the listener as the `subscribe`, a command as a `oneway`
  call plus its acknowledgment, a query as a method whose reply is the response,
  and the Binder identity of the caller as the caller identity.
- [ADR-0021](https://github.com/driftsys/ridl/blob/main/docs/decisions/ADR-0021-ridl-rt-0.1-api-and-release.md)
  and
  [`docs/design/ridl-rt.md`](https://github.com/driftsys/ridl/blob/main/docs/design/ridl-rt.md):
  the port model the Kotlin runtime mirrors, and
  [ADR-0023](https://github.com/driftsys/ridl/blob/main/docs/decisions/ADR-0023-interaction-face-generation.md)
  with
  [`docs/design/interaction-face.md`](https://github.com/driftsys/ridl/blob/main/docs/design/interaction-face.md):
  the face the Kotlin face mirrors.

Two facts from lane P that shape everything below. The request carries the
lowered model and never the raw IR (driver decision D-P2), so every name the
plugin emits is already spelled in the model's `Spellings` and `DottedName`
messages, and the plugin invents none. And the model is sufficient to generate
an IPC binding (D-P4): per package it carries the interface numbers and
ordinals, each interaction's kind, payload type and FlatBuffers `MAX_SIZE`, its
timing bounds, its contract clauses and the catalog hash.

The plugin is written in Kotlin on Gradle, not in Rust and not in Deno (D-K1).

## 2. The launcher and the JVM

**What `ridlc` runs.** `ridl build --plugin kotlin` finds an executable named
`ridlc-gen-kotlin` on `PATH`, spawns it with standard input and output piped and
standard error inherited, writes the request, reads the response, and kills the
process at `--plugin-timeout` (60 s by default). The executable is a shell
script on POSIX and a `.bat` on Windows, over one fat jar.

**The script ends in `exec`.** `codegen-plugins.md` §4 records why: the host
kills the plugin process it started, and a launcher that starts a JVM as a child
and waits for it leaves that child alive, holding both pipes, after the kill.
Gradle's `application` plugin writes a start script whose last line is
`exec "$JAVACMD" "$@"`, so the JVM replaces the shell and the kill reaches it.
The plugin repository uses that script as generated and adds nothing to it; the
`.bat` cannot `exec`, and on Windows a killed launcher may leave a JVM behind
until it finishes, which is recorded, not fixed.

**One jar, no daemon.** The `shadow` plugin builds `ridlc-gen-kotlin-all.jar`
with every dependency inside; the script runs `java -jar` on it. No Gradle
daemon, no wrapper download and no classpath resolution happen at plugin time,
so a build that runs the plugin costs one JVM start, about 400 ms on a
workstation, once per package. The `DEFAULT_JVM_OPTS` of the script carry
`-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xshare:auto`, which halves the start
of a short-lived JVM and costs nothing a generator notices.

**What the process does, in order.**

1. Reads all of standard input into memory and parses it as JSON with the
   `protobuf-java-util` `JsonFormat` parser over the classes `protobuf-kotlin`
   generates from `plugin.proto` and `model.proto`, configured
   `ignoringUnknownFields()` and `usingRecursionLimit(1000)`. The second is not
   optional: the parser's default limit is 100 and the IR specification §4
   requires 516 at least, 1,000 recommended.
2. Refuses a `schema` other than `ridl.codegen.v1` with one error diagnostic
   naming both values, and exits 0 with that response; a schema mismatch is a
   backend failure, not a host failure. `toolchain` is logged to standard error
   and never refused.
3. Reads the options. Two keys are known: `kotlin-package`, the Kotlin package
   the generated files are placed in (default: the ridl package's dotted name),
   and `wire-encoding`, the payload encoding the codec is generated for (§4,
   O-K1; default `flatbuffers`, the same key and value the Rust backend reads).
   An unknown key is an error diagnostic, as every in-tree backend answers it.
4. Generates the files (§4, §5) into a `CodegenResponse`, every path relative
   and `/`-separated per the path rule, every file a `text` arm, and writes the
   response to standard output as canonical protobuf JSON through the same
   `JsonFormat` printer with `omittingInsignificantWhitespace()` off, so a
   response is readable in a fixture.
5. Exits 0. A thrown exception is caught at the top, written to standard error
   with its stack, and turned into exit 3, which the host reports as
   ``plugin `ridlc-gen-kotlin` (<path>) failed: exit status: 3``.

**Installation.** The plugin repository publishes the distribution the
`application` plugin builds: a `.zip` and a `.tar` with `bin/ridlc-gen-kotlin`,
`bin/ridlc-gen-kotlin.bat` and `lib/ridlc-gen-kotlin-all.jar`. A user unpacks it
and puts `bin/` on `PATH`, or passes `--plugin kotlin=<path to the script>`. The
distribution is versioned with the plugin, and its `README` names the `ridl`
release it was tested against (§7).

## 3. `ridl-rt-kt`, the runtime library

**What it is.** The Kotlin spelling of `ridl-rt`: the vocabulary types, the port
interfaces, the payload interface and the error types, and nothing that runs. It
has no Android dependency and no transport. One module in this repository
implements the ports in process for tests (`ridl-rt-kt-loopback`, the mirror of
`crates/ridl-loopback`). The Binder runtime, which implements them over AIDL on
Android, is its own repository (D-K8), and a JNI implementation over a native
runtime would be another. The generated code depends on `ridl-rt-kt` alone, so
it compiles and its round trips run on a workstation with no Android SDK.

**The correspondence is by name.** Each Kotlin declaration names the `ridl-rt`
item it spells, so ADR-0021's decisions and the frame specification apply to it
without a second record. The table is the contract of the module:

| `ridl-rt`                                                                                           | `ridl-rt-kt`                                                                                                                                          | Note                                                 |
| --------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| `Ordinal(u32)`, `InterfaceNo(u32)`                                                                  | `@JvmInline value class Ordinal(val value: UInt)`, `InterfaceNo` likewise                                                                             | inline, so a port method takes them at no allocation |
| `CatalogHash([u8; 32])`, `CatalogRef`                                                               | `class CatalogHash(bytes: ByteArray)` with structural equality, `data class CatalogRef(name, hash)`                                                   |                                                      |
| `Kind` 1 to 5                                                                                       | `enum class Kind(val value: Int)` in the language order                                                                                               |                                                      |
| `Member`, `Timing`, `TimingMode`, `PayloadInfo`, `EncodedSizes`                                     | `data class` each                                                                                                                                     | the `MEMBERS` row of a generated descriptor          |
| `Timestamp(i64)`, `Duration(i64)`                                                                   | `value class` each over `Long`                                                                                                                        |                                                      |
| `Envelope`, `Provenance`, `Cause`, `Detection`, `Freshness`                                         | `data class Envelope`, `sealed interface Provenance`, `enum class` for the rest                                                                       | as `sample.rs`                                       |
| `Sample<T>`, `Occurrence<T>`                                                                        | `data class Sample<T>(value: T, envelope, provenance, freshness)`, `Occurrence<T>`                                                                    |                                                      |
| `Correlation`, `ClaimId`, `Claim`                                                                   | `value class Correlation(Long)`, `ClaimId`, `data class Claim`                                                                                        | untyped, as ADR-0023 decision 4 keeps them           |
| `Contract`, `Transport`, `CallError`                                                                | `sealed interface CallError` with `Contract` and `Transport` arms, each a `sealed interface` of the same variants                                     |                                                      |
| `ReadError`, `WriteError`, `SendError`, `SubscribeError`, `RaiseError`, `ServeError`, `SettleError` | one `sealed interface` each, thrown, not returned                                                                                                     | D-K3                                                 |
| `Attached`, `Clock`                                                                                 | `interface Attached { val catalog: CatalogRef }`, `interface Clock { fun now(): Timestamp }`                                                          |                                                      |
| `SignalReader`, `SignalWriter`, `EventSource`, `EventSink`, `Caller`, `Handler`                     | one `interface` each, same method names, same semantics                                                                                               | §3, "the ports"                                      |
| `FixedReader`, `ScannableSignals`, `CoherentSignals`                                                | the same three, optional for a runtime                                                                                                                |                                                      |
| `Payload<E: Encoding>`                                                                              | `interface Payload<T> { val maxSize: Int; fun encode(value: T, out: ByteBuffer): Int; fun verify(buf: ByteBuffer): View; fun decode(view: View): T }` | one object per generated type per encoding, §4       |
| `Encoding` with `FlatBuffers`, `Proto3`, `ReprC`                                                    | `enum class Encoding(val tag: Int)` 1, 2, 3                                                                                                           | the frame's tags                                     |

**The ports.** Each port interface keeps `ridl-rt`'s method names and semantics,
with two spellings changed for the JVM and recorded here so the Rust record
stays the reference:

- Where a Rust method takes `out: &mut [u8]` and returns a length, the Kotlin
  method takes a `java.nio.ByteBuffer` and advances its position. A `ByteBuffer`
  is what a Binder parcel and a FlatBuffers reader both accept without a copy,
  and a `ByteArray` with an offset would force one on every read.
- Where a Rust method returns `Result<T, E>`, the Kotlin method returns `T` and
  throws `E` (D-K3). `Caller.ack` and `Caller.reply`, whose `None` is a state
  and not a failure, return a nullable:
  `fun ack(c: Correlation): Result<Unit>?`, where `Result` is Kotlin's, carrying
  a `CallError` on failure, and `null` while unknown.

`Handler.settle` takes `Result<ByteBuffer>` for the same reason: a settled
outcome is a value, not a throw.

**Nothing in `ridl-rt-kt` waits.** The port interfaces are synchronous and
non-blocking, as the Rust ones are (RA-20). A runtime that can wake a caller
when an outcome arrives exposes that as one extra interface, `Wakeable`, with a
single `fun onChange(callback: () -> Unit): AutoCloseable`; it is what a
coroutine adapter builds on (§5, O-K3), and a runtime that lacks it is still a
complete runtime.

**Versioning.** `ridl-rt-kt` 0.x tracks `ridl-rt` 0.x, one minor per minor,
under ADR-0021 decision 10's 0.x rule: a breaking change bumps the minor and is
recorded. The correspondence table above is the thing a bump updates.

## 4. The value objects and the payload codec

**One Kotlin file per package, `Types.kt`, in the `kotlin-package`.** The plugin
emits it with KotlinPoet, so imports, qualification against a user type that
shadows a `kotlin.*` name, and keyword escaping are the library's, not a
template's (D-K2). Per declaration of the model:

| Model declaration                                       | Kotlin                                                                                                                       | Construction                                                                                                                                                                                                                                            |
| ------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| named scalar with a constraint                          | `@JvmInline value class Temperature private constructor(val value: Long)`                                                    | `Temperature.of(v)` throws `ConstraintViolation`; `Temperature.ofOrNull(v)` returns `null`; `internal fun unchecked(v)` for the decoder                                                                                                                 |
| named scalar with no constraint (vacuous, model `Init`) | the same `value class` with a public constructor                                                                             | as E10.4: a type that constrains nothing takes no fallible path                                                                                                                                                                                         |
| bounded string                                          | `value class` over `String`, checked on byte length and pattern                                                              | as above                                                                                                                                                                                                                                                |
| enum                                                    | `enum class Health(val value: Int)` with `companion object { fun fromValue(v: Int): Health? }`                               | an undefined discriminant is `null`, as E10.5                                                                                                                                                                                                           |
| enum set                                                | `value class HealthSet(val bits: Long)` over the members' bits, with `contains`, `plus`, `minus`                             |                                                                                                                                                                                                                                                         |
| struct                                                  | `class Warning(val code: Level, val health: Health)` with `equals`, `hashCode`, `toString`, no `copy`                        | the fields are already validated types, so a struct of valid fields is valid; a `data class` is not used because `copy` and `componentN` are surface a later field reorder breaks (E10.6's sound-derives argument, applied to Kotlin's derived members) |
| union                                                   | `sealed interface Shape` with one nested `value class` or `class` per arm, named by the model's spelling                     |                                                                                                                                                                                                                                                         |
| array, map                                              | `List<T>`, `Map<K, V>`, with the bound checked at the owning type's construction                                             |                                                                                                                                                                                                                                                         |
| induced tuple                                           | `class` named by the model's `InducedName.rust` spelling, since the Kotlin projection mirrors Rust's, not the wire backends' | D-K4                                                                                                                                                                                                                                                    |
| optional                                                | nullable type                                                                                                                |                                                                                                                                                                                                                                                         |
| constant                                                | `const val` in a package-level `object Constants`                                                                            |                                                                                                                                                                                                                                                         |

Every constraint the model states is checked in `of`: range, step, byte length,
pattern (through `java.util.regex`, as the Rust backend uses `regress`; the typl
match grammar is the same subset, and a pattern that `regress` accepts and
`java.util.regex` rejects is a defect to file, not to work around). The
exception type is one `class ConstraintViolation(type, rule, value)` in
`ridl-rt-kt`, mirroring `ridl_rt::payload::Violation` with its five rule
variants.

**The codec.** For every payload type of every interaction, and for every named
type a payload reaches, the plugin emits one one `<Type>Codec` object
implementing `Payload` in `Codec.kt`, for the encoding the `wire-encoding`
option names. The codec's obligation is the frame's §9: `verify` rejects a
buffer that is not structurally a value of the type, `decode` runs every typl
constraint on the way to a value object through `unchecked`, and `encode` writes
a legal value into a buffer no larger than `maxSize`, which is the model's
`Payload.flatbuffers_max_size` carried as a constant.

**The encoding is open, O-K1.** The frame specification §11.2 fixes FlatBuffers
(tag 1) for the AIDL binding, because the path is "passed within a node" and
ADR-0020 decision 2's matrix gives that cell to FlatBuffers. Three things make
this note keep it open rather than settled:

- The Java and Kotlin FlatBuffers runtimes ship no verifier. The C++, Rust,
  Swift and C# runtimes do; a Kotlin reader over `flatc --kotlin` output reads
  offsets from the buffer and trusts them, and a malformed buffer is at best an
  `IndexOutOfBoundsException` and at worst a wrong value read in bounds. The
  roadmap's Kotlin section already says a reader without a verifier is
  "restricted to inline or trusted reads". A Binder peer on the same device is
  trusted in the SELinux sense, but the frame's §9.5 requires the binding to
  detect a frame it cannot read, and the Rust side does that with a real
  verifier.
- The `flatc --kotlin` output is large per type, and its accessor classes are a
  second projection of every declaration beside the value classes, with the
  ADR-0019 wrapper tables and boxes visible to the reader of the generated code.
- proto3 is the other in-node candidate, encoding tag 2: `protobuf-kotlin` and
  `wire` both parse with full structural validation, the Rust side has E11.8
  scheduled for its codec, and the model's spellings and ordinals are what the
  proto3 projection (ADR-0017) is built from, so the plugin can emit a codec
  over `protoc`-generated classes with no second projection record.

The three ways to close it, with the cost of each:

| Option                                                | What it needs                                                                                                                                                                                             | What it costs                                                                                                                                                        |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A. FlatBuffers with a verifier in `ridl-rt-kt`        | a hand-written verifier for the subset ADR-0019 emits: every struct a table, a union in a wrapper table, a vector with a bound, a scalar in a box; roughly the size of `ridl-rt`'s own FlatBuffers module | one story in K1, and the verifier is the module's most security-relevant code                                                                                        |
| B. FlatBuffers without a verifier, trusted reads only | nothing                                                                                                                                                                                                   | a documented exception to the frame's §9.5, acceptable only while every peer is on the same device and under SELinux; closes the door to any untrusted binding later |
| C. proto3                                             | E11.8's Rust codec to exist, so a Rust peer can speak it; `protoc` in the consumer's build                                                                                                                | not zero-copy from the parcel, one allocation per read; a different cell of the matrix than the frame names, so the frame's §11.2 is amended                         |

The recommendation is A, decided by a bounded spike at the start of K1: write
the verifier for the cabin package's four payload types; if it comes in under
the size above and passes the malformed-buffer corpus (§7), FlatBuffers stays;
if not, C, with the frame amended. B is not recommended. The generator is
written so that the choice is one codec emitter behind the `wire-encoding`
option, the way the Rust backend's is, and the value objects and the faces are
the same under either.

**The `.fbs` and `.proto` schemas are not the plugin's to emit.** `ridl build`
with the `flatbuffers` and `proto` emits writes them in the same run, from the
same model, under ADR-0019 and ADR-0017; the consumer's Gradle build runs
`flatc --kotlin` or `protoc` over them beside the plugin's output. One emitter
per schema, and the plugin's codec is written against the classes that emitter's
tool produces (D-K5).

## 5. The face

**One Kotlin file per package, `Faces.kt`, mirroring ADR-0023 and
`interaction-face.md`.** Per interface with at least one member the face covers:

- **`object <Iface> : Interface`**, the descriptor: `catalog`, `number`,
  `provisional`, `name`, `members` (the `MEMBERS` rows), and one nested `object`
  per interaction with its `Member`, its `Payload` codec, and for a command or a
  query its `require` and, for a query, its `ensure`, translated from the
  model's clauses by the same narrow translator ADR-0023 decision 1 gives the
  Rust backend: every clause form it does not accept is a refusal at generation
  time, reported as an error diagnostic naming the clause.
- **`class <Iface>Client<P>(private val port: P)`** with upper bounds computed
  from the interface's own kinds and no others (RA-19): a `SignalReader` bound
  only if it declares a signal, `P : EventSource` only if an event, `P : Caller`
  only if a command or a query. One read method per signal returning
  `Sample<T>`; `subscribe<Event>()` per event and one a nullable `nextEvent()`
  returning a `sealed interface` with one arm per event; one send method per
  command or query returning that call's own correlation newtype
  (`SetLevelCorrelation`, `AverageCorrelation`, ADR-0023 decision 4 as amended)
  and throwing `SendError`; `setLevelAck(c): Result<Unit>?` per command and
  `averageReply(c): Result<Average>?` per query, `null` while unknown.
- **`class <Iface>Publisher<W>(private val port: W)`** over `SignalWriter` and
  `EventSink` on the same rule, with one `set`, one `invalidate` and one `touch`
  per signal, one `raise` per event, and `commit`.
- **`interface <Iface>Provider`** with one method per command (returns `Unit`)
  and per query (returns the reply type), generated only when the interface
  declares one.
- **`dispatch(handler, provider, buffer)` on the descriptor, returning an
  `Int`**, the same settlement table as the Rust `dispatch`: a claim whose
  interface or ordinal matches no arm settles `UnknownInteraction`; a structural
  verify failure settles `Transport.Corrupt`; a constraint failure
  `Contract.InvalidValue`; a failed `require` `PreconditionFailed`; a failed
  `ensure` `ContractBroken`. A command settles before the provider method runs;
  a query after. The return is the number of claims `settle` accepted.

A face holds its port by value with no lifetime, as ADR-0023 decision 5; on the
JVM that is the only option, and a runtime's aggregate handle (ADR-0021
decision 12) is what a face is normally built over.

**What is deliberately the same as Rust.** The shape, the bounds rule, the
correlation newtypes, the settlement table and the polling. The reason is the
one the lanes plan gives for a second implementation: a face that differs from
the Rust one in shape is a second design, reviewed once per language, and a face
that matches it is one design with two spellings, where a difference found in
one is a defect in both.

**What is Kotlin's own, O-K3.** A Kotlin consumer expects a suspending
`average(window)` returning the reply, not a correlation and a poll. That layer
is not generated: it is one hand-written module, `ridl-rt-kt-coroutines`, with a
generic `suspend fun <T> await(port: Wakeable, poll: () -> T?): T` over the
`Wakeable` port of §3, and two generated one-line extensions per call
(`suspend fun <Iface>Client<P>.averageAwait(window)`) that compose the send, the
await and the reply. The generated face stays the polling one, because it is
what the loopback tests, the frame describes and the Rust face has; the
suspension is an adapter over it, not a second face. Whether the extensions are
generated in M1 or written by hand in the sample is the open item.

**The AIDL, `aidl/<package path>/I<Iface>.aidl` and `I<Iface>Listener.aidl`.**
Emitted from a template, not KotlinPoet, and validated by the Android SDK's
`aidl` tool in the plugin's tests (§7). The generated interface is a binding of
the frame and not a rival protocol (frame §11.2), so it carries the frame, not a
typed method per payload:

- `I<Iface>`, the control plane on the four codes below the interface's lowest
  ordinal, then one method per command and per query on its ordinal:

  ```text
  void attach(in CatalogRef catalog) = 1;
  void subscribe(in int[] ordinals, I<Iface>Listener listener) = 2;
  void unsubscribe(in int[] ordinals, I<Iface>Listener listener) = 3;
  Frame read(int ordinal) = 4;
  oneway void <command>(in Frame args) = <ordinal>;
  Frame <query>(in Frame args) = <ordinal>;
  ```

  The plugin refuses at generation time an interface whose ordinals do not leave
  the four codes room, or whose ordinals or number exceed Binder's
  `LAST_CALL_TRANSACTION`.
- `I<Iface>Listener`:

  ```text
  oneway void onSignal(in Frame frame);
  oneway void onEvent(in Frame frame);
  oneway void onAck(long correlation, in Outcome outcome);
  ```

- `Frame`, `Outcome` and `CatalogRef` are three `parcelable` declarations the
  plugin emits once per package under `aidl/ridl/rt/`, identical for every
  package, so the generated AIDL is self-contained and passes the `aidl` tool on
  its own: the frame's header fields of §4 (interface number, ordinal, kind,
  sequence, timestamp, provenance, correlation) and the payload as `byte[]`.

The per-interface AIDL exists so that Binder's own permission model, SELinux
labels and service registration work per ridl interface, which a single generic
`IRidl` would not give; the frame parcelable exists so that the payload and
header cross exactly as §4 says. The Binder runtime, in its own repository
(D-K8), implements `Caller`, `Handler`, `SignalReader`, `EventSource`,
`SignalWriter` and `EventSink` over the `Stub` and `Proxy` classes `aidl`
generates from these files, with the Binder identity of the calling process as
the caller identity of §7, and the range check of §11.2 at `attach`.

## 6. The repository

Gradle, Kotlin DSL, one settings file, every module under `modules/`, all JVM:

| Module                          | Kind                      | Depends on                                            | Holds                                                                                                                                           |
| ------------------------------- | ------------------------- | ----------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `modules/ridlc-gen-kotlin`      | JVM application, `shadow` | `protobuf-kotlin`, `protobuf-java-util`, `kotlinpoet` | the reader (§2), the generators (§4, §5), the AIDL templates, the `main`; builds the `ridlc-gen-kotlin` executable                              |
| `modules/ridl-rt-kt`            | JVM library               | nothing                                               | §3                                                                                                                                              |
| `modules/ridl-rt-kt-loopback`   | JVM library               | `ridl-rt-kt`                                          | the in-process runtime: every port over a queue and a map, no IO; the mirror of `crates/ridl-loopback` and the runtime every test runs over     |
| `modules/ridl-rt-kt-coroutines` | JVM library               | `ridl-rt-kt`, `kotlinx-coroutines-core`               | §5's adapter, O-K3                                                                                                                              |
| `modules/conformance`           | JVM tests                 | the four above, `kotlin-compile-testing`              | the pinned `ridl` release, the corpus fixtures, and every test of §7                                                                            |
| `samples/cabin`                 | JVM application           | `ridl-rt-kt`, `ridl-rt-kt-loopback`                   | `examples/cabin`'s package generated by the plugin, with a Kotlin consumer and a Kotlin provider round-tripping over the loopback: the JVM demo |

No Android module is in this repository. The Binder runtime and the on-device
demo, a Kotlin consumer against a Kotlin provider over Binder, live in the
Binder runtime's own repository, which depends on `ridl-rt-kt` and consumes the
AIDL this plugin emits (D-K8). The one thing here that needs the Android SDK is
the `aidl` tool check of §7, which runs in a CI job that installs the SDK's
build-tools and nothing else; the Gradle `check` task on a workstation without
the SDK builds and tests everything.

The `ridl` toolchain enters the repository in one place: `conformance` pins a
release tag of `driftsys/ridl` and downloads its `ridl` binary through
`install.sh` at test time. No `ridl` source is vendored.

## 7. What is proved, and how

Each test is named with the claim it pins; the three review passes the lane P
driver prescribes apply to every pull request of this repository too, and the
mutation pass is what keeps the snapshot tests honest.

- **The request is read as the specification says.** A request written by the
  pinned `ridl` release for each corpus package (the `codegen-model` emit,
  wrapped as a `CodegenRequest`) parses; a request with an unknown key parses; a
  request nested to 1,000 levels parses; a request with a wrong `schema` gets
  exactly one error diagnostic and exit 0.
- **The output compiles.** For every corpus package, the generated `Types.kt`,
  `Codec.kt` and `Faces.kt` compile with `kotlin-compile-testing` against
  `ridl-rt-kt` and the `flatc --kotlin` (or `protoc`) output for that package,
  in one JUnit test per package, with warnings as errors.
- **The value objects refuse invalid values.** For every constrained type of the
  corpus, one value inside the range constructs and one value one step outside
  each bound throws `ConstraintViolation` with the right rule. The mutation:
  remove one check from the emitter and the test for that bound goes red.
- **The codec round-trips and the verifier refuses.** For every payload type,
  encode then verify then decode gives an equal value; a corpus of malformed
  buffers (truncated, an offset past the end, a vector longer than its bound, a
  union discriminant out of range) is refused by `verify`, never by an exception
  out of `decode`. This is the test that decides O-K1's spike.
- **The face round-trips on the loopback.** The cabin package's four interaction
  kinds: a signal set and read, an event raised and received, a command sent,
  dispatched to a provider and acknowledged, a query sent, dispatched, replied
  and read, over `ridl-rt-kt-loopback`, with the settlement table exercised by
  one failing `require`, one failing `ensure`, one corrupt argument buffer and
  one unknown ordinal.
- **The AIDL is valid.** Every generated `.aidl`, the three parcelables
  included, passes the SDK's `aidl` tool, in the CI job that carries the
  build-tools.
- **The plugin is the same plugin through the host.** `ridl build` with the
  plugin given by path over the corpus writes the same files as invoking the jar
  directly on the same request. This is the parity test of `codegen-plugins.md`
  §6, run from outside this repository, which is the reason the plugin exists.
- **Snapshots, last.** The generated files for the corpus are checked in and
  compared, so a change in output is seen in review; a snapshot test alone
  proves nothing, and the tests above are the ones that must fail under
  mutation.

Every check runs on the Gradle `check` task; the build-tools job adds the `aidl`
check.

## 8. Stages

Each stage is one or more pull requests in the plugin repository, reviewed by
the lane P driver's three passes, merged before the next branches. The model
routing follows the step-1 lanes plan's rule: Fable for the design-sensitive
half, Opus for the code, Sonnet for the mechanical rows.

| Stage | Work                                                                                                                                                                                | Model                                                             | After                    |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- | ------------------------ |
| K0    | The repository: Gradle settings, the modules empty, `conformance` pinning a `ridl` release and downloading it, CI with the JVM job and the `aidl` job, `.gitlab-ci.yml`             | Sonnet                                                            | this note disposed       |
| K1a   | `ridl-rt-kt`: the correspondence table of §3 as code, with a test per row that the Kotlin item exists and spells the Rust one                                                       | Opus, high                                                        | K0                       |
| K1b   | The O-K1 spike: the FlatBuffers verifier for the cabin package's payloads, or the proto3 codec if it fails; the disposition recorded here and in the frame specification if it is C | Fable                                                             | K1a                      |
| K1c   | `ridl-rt-kt-loopback`                                                                                                                                                               | Opus, high                                                        | K1a                      |
| K2a   | The plugin's reader, the launcher, the schema refusal, the option parsing, and `ridlc-gen-kotlin` answering the request with an empty response; the parity test through the host    | Opus, high                                                        | K0                       |
| K2b   | `Types.kt`: the value objects of §4, with the constraint tests                                                                                                                      | Opus, high                                                        | K2a, K1a                 |
| K2c   | `Codec.kt`: the codec of §4 for the encoding K1b chose, with the round-trip and malformed-buffer tests                                                                              | Opus, high                                                        | K2b, K1b                 |
| K3a   | `Faces.kt`: the descriptors, the clients, the publishers, the providers and `dispatch`, with the loopback round trips                                                               | Fable for the clause translator and `dispatch`; Opus for the rest | K2c, K1c                 |
| K3b   | The AIDL templates and their `aidl` check                                                                                                                                           | Opus, high                                                        | K3a                      |
| K4    | `samples/cabin`: the plugin's output for the cabin package, a Kotlin consumer and a Kotlin provider round-tripping over the loopback; the JVM demo                                  | Opus, high                                                        | K3b                      |
| K5    | `ridl-rt-kt-coroutines` and the `*Await` extensions, O-K3                                                                                                                           | Opus                                                              | K3a; in parallel with K4 |

K1 and K2a run in parallel: the runtime library and the plugin's reader touch no
common file. The on-device demo, Kotlin on both sides by the disposition of
O-P3, is the Binder runtime repository's first stage, after K3b; a Rust provider
over Binder is a later repository again.

## 9. Decisions

Each decision names the alternative it rejected.

- **D-K1. The plugin is Kotlin on Gradle.** Rejected: Rust in this workspace,
  because then Kotlin is not the first external plugin and the contract is not
  tested from outside; Deno, because the generated Kotlin could not be compiled
  or run in the same tests, which leaves snapshot tests, the class of test the
  P2b review showed passing with every field misnamed.
- **D-K2. Kotlin sources are emitted with KotlinPoet; AIDL with a template.**
  Rejected: string templates for Kotlin, because import management, shadowing
  and keyword escaping are exactly what this repository's Rust emitter got wrong
  (driftsys/ridl#423, #452, #453); KotlinPoet for AIDL, because there is no AIDL
  builder and the grammar is small.
- **D-K3. A port method throws its error; a state that is not a failure is a
  nullable.** Rejected: a `Result<T>` return on every port method, because
  Kotlin's `Result` is not a checked type and every call site would unwrap it,
  which is the shape Kotlin code does not write; an `Either` library, because
  the runtime library takes no dependency.
- **D-K4. The Kotlin projection mirrors the Rust one: induced names by the
  model's `rust` spelling, the face by ADR-0023.** Rejected: mirroring the wire
  backends' spelling, because the face and the value objects are a language
  surface, and the model carries both spellings for exactly this choice.
- **D-K5. The plugin emits no `.fbs` and no `.proto`; the consumer's build runs
  `ridl build` with the schema emit and the plugin in one invocation.**
  Rejected: the plugin emitting a copy of the schema from the model's
  projection, because two emitters of one schema drift, and ADR-0019 and
  ADR-0017 name the in-tree backends as the owners.
- **D-K6. The generated face is the polling face; suspension is an adapter
  module.** Rejected: a `suspend` face as the only face, because it could not be
  tested on the loopback without a coroutine runtime in every test, and it would
  differ in shape from the Rust face, making every face defect a per-language
  finding.
- **D-K7. The per-interface AIDL carries the frame, in a parcelable, not one
  typed method per payload.** Rejected: typed AIDL methods with AIDL parcelables
  per ridl type, because that is a second projection of every declaration onto
  AIDL's type system, a rival protocol to the frame, and the thing a Rust peer
  could never speak.
- **D-K8. The plugin repository is JVM only; the Binder runtime and the
  on-device demo are a separate repository.** The plugin, the runtime contract,
  the loopback and the conformance tests build and run on any workstation with a
  JDK, and the Android toolchain enters only for the `aidl` check. Rejected: the
  Binder runtime as an Android module here behind a Gradle property, because it
  doubles the build configuration and the CI of a repository whose product is a
  code generator, and because the Binder runtime has its own release cadence,
  tied to the device, not to the plugin. The cost accepted: a change to the AIDL
  templates is tested here by the `aidl` tool alone, and end to end only when
  the Binder repository bumps its plugin pin.
- **D-K9. `ridl` enters the plugin repository as a pinned release binary, never
  as source.** Rejected: a git submodule of `driftsys/ridl`, because the plugin
  must prove it works against what a user installs, and a submodule drifts to a
  commit no release carries.

## 10. Open items

- **O-K1. The payload encoding: FlatBuffers with a verifier, or proto3.** §4;
  decided by K1b's spike. Sebastien's disposition after the spike.
- **O-K2. The `kotlin-package` default.** The ridl package's dotted name
  (`veh.cabin`) is a legal Kotlin package and the natural default, but a
  consumer who already owns `veh.*` on the JVM may want a prefix. The option
  exists from K2a; whether the default gains a fixed prefix such as
  `ridl.<dotted>` is open until a second consumer exists.
- **O-K3. Whether the coroutine extensions are generated or hand-written in
  M1.** §5; K5 decides by writing the sample both ways and keeping the shorter.
- **O-K4. The bounds a `List` and a `Map` check.** A bound on a collection is
  checked at the owning type's construction in this note; whether a bounded
  collection gets its own `value class` over `List<T>`, as the Rust backend
  gives it a newtype, is decided with K2b's first bounded collection in the
  corpus.
- **O-K5. Streams.** ridl §12 streams have no port in `ridl-rt` yet
  (driftsys/ridl#336), so the Kotlin face covers the four kinds with a payload
  and `fixed`, and refuses a package with a stream at generation time with a
  diagnostic naming the story. Reopened by that issue.

## Trace

- Lane P: driftsys/ridl#494 (the driver), driftsys/ridl#496 (the code), commit
  7fc417e; the dispositions of O-P1, O-P2 and O-P3 on the roadmap.
- The plugin contract:
  [`../design/codegen-plugins.md`](https://github.com/driftsys/ridl/blob/main/docs/design/codegen-plugins.md).
- The frame and its AIDL binding:
  [`../specification/frame-specification.md`](https://github.com/driftsys/ridl/blob/main/docs/specification/frame-specification.md)
  §11.2.
- The port model: ADR-0021 and
  [`../design/ridl-rt.md`](https://github.com/driftsys/ridl/blob/main/docs/design/ridl-rt.md).
- The face: ADR-0023 and
  [`../design/interaction-face.md`](https://github.com/driftsys/ridl/blob/main/docs/design/interaction-face.md).
- The value objects:
  [`typl-value-objects-plan.md`](https://github.com/driftsys/ridl/blob/main/docs/wip/typl-value-objects-plan.md)
  Tasks 3 to 6.
- Model routing:
  [`2026-09-13-step1-lanes-plan.md`](https://github.com/driftsys/ridl/blob/main/docs/wip/2026-09-13-step1-lanes-plan.md)
  §4.
