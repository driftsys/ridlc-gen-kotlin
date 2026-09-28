# ridlc-gen-kotlin

## Responsibility

This module owns the `ridlc-gen-kotlin` executable: it reads one
`ridl.codegen.v1` `CodegenRequest` on standard input and writes one
`CodegenResponse` on standard output, both canonical protobuf JSON
(docs/design.md §2). It is a JVM application built with Gradle, Kotlin 2 and
KotlinPoet, and its distribution is `bin/ridlc-gen-kotlin`,
`bin/ridlc-gen-kotlin.bat` and `lib/ridlc-gen-kotlin-all.jar`. The repository is
licensed under the root [MIT License](../../LICENSE).

## Status

Stages K2a to K3a: the reader, the launcher, the schema refusal, the option
parsing, and three files per package in its `kotlin-package`, emitted with
KotlinPoet from the request's model: `Types.kt`, the value objects of §4;
`Codec.kt`, their FlatBuffers codec; and `Faces.kt`, the interaction face of §5
for every declared interface, with its clients and `serve`. No AIDL is
generated; the last section below says why. `just dist` builds the distribution,
and `ridl build --plugin kotlin=<path to bin/ridlc-gen-kotlin>` runs it.

A declaration the plugin cannot spell in Kotlin is refused with one error
diagnostic naming it, and every refused declaration of the package is reported
in the one response: a stream (O-K5), a reference that resolves to nothing, two
tuples spelling one name, an enum with no value, a union with no arm, and every
position the Rust codec emitter refuses — a type with no finite FlatBuffers
bound, a bare `string` or `bytes`, an optional array element or map part.

#7: the clients and `serve` of ADR-0023 decision 6 (ridl `main` at 1eb0fba).

Tested against ridl `editor-v0.2.2` (`modules/conformance/ridl-release`).

## Where the code departs from docs/design.md

- **The recursion limit.** §2 step 1 configures the parser with
  `usingRecursionLimit(1000)`, but protobuf-java-util declares that method
  package-private. `com.google.protobuf.util.withRecursionLimit`, one function
  in the parser's own package, is the access to it.
- **The parser's stack.** A request nested 1,000 levels overflows the JVM's
  default 1 MiB thread stack inside the recursive parser, so `Wire.readRequest`
  parses on a thread with a 256 MiB stack. The stack is reserved, not committed.
- **`-classpath`, not `-jar`.** The Shadow start script runs the main class with
  the fat jar as the class path rather than `java -jar`; the effect is the same,
  and the script still ends in `exec "$JAVACMD" "$@"`.
- **`wire-encoding`** accepts `flatbuffers` alone until O-K1 is disposed.
- **Foreignness is read from `TypeRef.package`, not `TypeRef.foreign`**
  (`Refs.kt`), a workaround for driftsys/ridl#586: the pinned lowering leaves
  `foreign` false on a reference inside a foreign declaration, such as an
  imported union's arm, while indexing it into `Model.foreign`. Every reference
  goes through `Model.declarationOf`, which also refuses an index past its table
  with a diagnostic naming the reference, where it used to exit 3. Remove the
  workaround when the pin carries the fix; the `kt-foreign` corpus entry keeps
  it honest.

### `Types.kt`

- **A string's length is its count of Unicode scalar values**, not its byte
  length as §4 writes: typl §4.4 says a `string [N]` bound counts scalar values
  "not bytes, which `bytes` counts", and the Rust backend counts `chars()`.
- **`step` is checked.** Integers have no step (TYPL-105). A float is valid at
  `min + n·step`, from 0 without a minimum, within a millionth of the step plus
  one ulp at the wire width, so a binary32 that crossed the wire still passes.
  The Rust backend checks no step (driftsys/ridl#469).
- **A range check refuses NaN** (`!(value >= min)`); the Rust one admits it.
- **A pattern is searched for**, as Rust's `Regex::is_match` does, not matched
  whole: the pattern's own `^` and `$` decide.
- **Every integer is a `Long` and every float a `Double`**, as every Rust
  backing is `i64` or `f64`; the declared widths are the codec's.
- **An enum's discriminant is a `Long`**, not the `Int` of §4, because the
  model's is an `int64`.
- **An enum set has a private constructor** and `of`, which refuses a bit no
  member declares with the rule `Variant`, as the Rust `TryFrom` does.
- **A bytes scalar is a final class**, not a value class: a value class cannot
  compare a `ByteArray` by content. It holds a private copy.
- **O-K4 is decided as §4 has it**: an array's and a map's bounds are checked
  when the owning struct or tuple is built, with no value class per bounded
  collection. The same `init` checks an inline scalar's constraints, which the
  Rust backend does not check at all. Collections and bytes are copied in, and
  bytes copied out, so a constructed value stays valid.
- **Constants** are properties of `object Constants`: a `const val` for a
  primitive or a regex, a `val` holding the value object for a named scalar. A
  bytes constant has no spelling and is left out, as in the Rust backend.
- **A reference into another package** is spelled in that package's dotted name,
  the `kotlin-package` default (O-K2): the model does not say what option the
  other package was generated with.

### `Codec.kt`

`Codec.kt` is the Rust codec emitter of the pinned release spelled in Kotlin,
over `ridl-rt-kt`'s `ridl.rt.flatbuffers`: one `<Type>Codec` object implementing
`Payload` per root of the package's FlatBuffers projection, and `internal`
encode, verify and decode helpers per table-shaped type. It lays a table out as
the Rust codec does — declaration order, each field at its own alignment — and
pushes children in the same order, so it writes the same bytes, and verifies in
the same order, so it reaches the same verdict. The conformance module holds it
to that over 10,266 buffers (`CodecTest`).

- **O-K1 is taken as option A**, pending its disposition
  ([`docs/k1b-flatbuffers-spike.md`](../../docs/k1b-flatbuffers-spike.md)), and
  **D-K5's second half is not followed**: the codec is written over
  `ridl.rt.flatbuffers`, as the Rust codec is over `ridl_rt::flatbuffers`, not
  over the classes `flatc --kotlin` generates, so a consumer's build needs no
  `flatc`.
- **`verify` refuses three things the Rust verifier accepts**: a float off its
  `step`, a NaN, and an inline scalar outside its constraints. `decode` builds
  value objects, whose constructors refuse all three, and must never throw.
- **A map decodes to a `Map`**, so two entries with one key keep the last, where
  the Rust codec keeps a `Vec` of pairs.
- **The helpers are `internal`**, and a codec reaches another package's helpers
  by name: the packages of one `ridl build` are compiled into one module, as the
  Rust backend writes them into one crate.
- **An exempt root** — one the projection cannot bound because it reaches a type
  it cannot judge — gets no codec, and the file's header names it, as the Rust
  codec writes a `__RIDL_FB_NO_CODEC_*` note.

### `Faces.kt`

Per declared interface, the Rust face of the pinned release spelled in Kotlin
(ADR-0023): the descriptor `object <Iface> : Interface`, with its `MEMBERS`
rows, `MAX_BUFFER_SIZE`, `EVENT_SOURCE_BUFFER_SIZE`, one correlation value class
per call, the `Event` sealed interface, `dispatch`, and `serve` and
`serveAsync`; one descriptor object per interaction, `<Iface><Member>`, with its
codec and its `require`, `ensure` or `init`; the clients, bound to exactly the
ports their kinds need (RA-19); `<Iface>Publisher<W>`; and `<Iface>Provider`.
`dispatch` settles as the Rust one does, a command before its provider method
runs and a query after.

- **The clients replace the public poll face.** docs/design.md §5 describes the
  poll face as public, with an `averageAwait` extension per call. The face now
  generates, per interface that waits, the blocking
  `<Iface>Client<P>(port,
  timeout)`, the suspending `<Iface>AsyncClient<P>`,
  and `serve` and `serveAsync` on the descriptor, all over one internal
  `<Iface><Member>Call` per command and query, whose `InteractionCall` base
  carries every call rule. The poll face, `<Iface>PollClient`, its correlations,
  its `…Ack` and `…Reply` methods and `dispatch` are internal. A call returns
  its reply and throws `ClientError` for anything else; `serve` throws
  `ProviderError`. A signal-only interface has one plain `<Iface>Client` beside
  its descriptors and publisher, and no async client, poll face or `serve`.
  `serve(timeout)` takes no claim past its timeout, and the async client's
  `nextEvent` takes occurrences one at a time.
- **An interface whose member names collide with the clients'** — a member
  spelled `nextEvent` or `timeout` in an interface that waits — is skipped with
  a warning until driftsys/ridl#570 is decided. So is an interface whose
  generated types would take a name already taken: two of its own (members `set`
  and `set_call` both give `<Iface>SetCall`), a declaration's or its codec's, or
  another interface's. A parameter's name never shadows the generated code's
  own.

- **An interface the face cannot carry is skipped with a warning**, not refused
  with the error §5 names: a clause outside the narrow translator's one form, a
  call with other than one named parameter, a reply that is not a named type.
  The rest of the package is generated, as the Rust pipeline skips such an
  interface with a `__RIDL_NO_FACE_*` note (E11.14 decision 2).
- **A channel's init is the signal's own `= value`** when it declares one over a
  named scalar, else the payload type's typl init, built from the model's `Init`
  facts. The Rust face always calls the payload's `Default`, and calls the
  override a follow-up.
- **The descriptors are top-level**, `CabinTemperature` beside `Cabin`, as in
  Rust: nested in `Cabin`, a descriptor named after its signal would shadow the
  payload type of the same name.
- **A `PayloadInfo` states its FlatBuffers size**, from the model, where the
  Rust descriptor writes `None` until E16.2.
- **The publisher has `touch<Signal>`**, as §5 lists, and the client
  `unsubscribe<Event>`; the Rust face has neither.
- **A port error is thrown**, and `dispatch` counts a settlement the handler
  refused with a `SettleError` as not accepted, as the Rust one counts an `Err`.

### No AIDL

The plugin emits no AIDL: §5's per-interface `I<Iface>.aidl` and
`I<Iface>Listener.aidl`, the three shared parcelables, and §7's `aidl` tool
check are not generated or run, and CI installs no Android SDK
(driftsys/ridlc-gen-kotlin#4). The frame specification §11.2 says so since
driftsys/ridl#516 (ridl `main` at ddd56fd, after the pinned `editor-v0.2.2`),
which reverses the lane P decision D-P5: on Android a runtime binds the ports
over its own binder contract, which may be one generic, versioned AIDL serving
every catalog; ridl specifies no Binder layout and no transaction code; and the
Kotlin backend generates no binding. Generated code binds only to the
`ridl-rt-kt` ports. Stage K3b of §8 is withdrawn, and with it the choice of
where the control plane's transaction codes go, which both of §5's placements
left colliding with cabin's calls.
