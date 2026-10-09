# ridl-rt-kt

## Responsibility

This module is the Kotlin spelling of `ridl-rt`: the vocabulary types, the port
interfaces, the payload interface and the error types that generated code and a
runtime agree on, and nothing that runs (docs/design.md §3). It is a JVM library
with no dependency but the Kotlin standard library, in the packages
`ridl.rt.contract`, `ridl.rt.encoding`, `ridl.rt.error`, `ridl.rt.payload`,
`ridl.rt.port`, `ridl.rt.sample`, `ridl.rt.flatbuffers`, `ridl.rt.task` and
`ridl.rt.trace`, one per `ridl_rt` module. The repository is licensed under the
root [MIT License](../../LICENSE).

## Status

Stage K1a: the correspondence table of §3 is code, and `CorrespondenceTest` pins
one row per `ridl-rt` item of the pinned release: the Kotlin item exists, lives
in the package of its Rust module, has the Rust name, and has the Rust variants,
fields and methods under their Kotlin spellings.

Stage K1b adds `ridl.rt.flatbuffers`: `Reader`, the bounds-checked reads and
vtable walk a generated `verify` needs, and `Builder`, the back-to-front writer.
It is the spelling of `ridl_rt::flatbuffers`, whose free reading functions are
`Reader`'s methods over a `ByteBuffer`. The spike that decided it is
[`docs/k1b-flatbuffers-spike.md`](../../docs/k1b-flatbuffers-spike.md).

driftsys/ridlc-gen-kotlin#5 adds three items of ridl `main` ahead of the release
that carries them, and `CorrespondenceTest` rows them from `main`: the keyed
`port::Wakeable` and `port::Interest` and `error::Transport::Busy` (story
E11.16, c0fa57c and c2543c2), and `ridl.rt.task`, the spelling of
`ridl_rt::task` `block_on` and `noop_waker` (story E11.17, 3f2cfb3), with
`Waker` standing for `core::task::Waker`. `TaskTest` is
`crates/ridl-rt/tests/task.rs`, case for case. ridl 0.4.0 adds `flag_waker` and
`WakeFlag` (driftsys/ridl#568), spelled `flagWaker`, which returns the waker and
its `WakeFlag` as a `Pair`, and a `ShortClaim` read error (driftsys/ridl#569).

driftsys/ridlc-gen-kotlin#6 adds the runtime helpers of story E11.19 (ridl
`main` at 87de8c6): `Freshness.of`, `EventSeqTracker` with `Continuity` and
`TrackerFull`, `Member.callDeadline`, `Member.reservation`, `tableBudget` with
`Unsized`, and `Encoding.maxSize`; and those of story E11.18 (eb41a7a):
`ridl.rt.correlate`, the call `Table` with `Settled` and `Forgotten` and the
`Waiters` registry, and the composed `ClientError` and `ProviderError`.
`FreshnessTest`, `EventSeqTrackerTest`, `BudgetTest`, `CallDeadlineTest` and
`CorrelateTest` are `freshness.rs`, `event_seq.rs`, `budget.rs`,
`call_deadline.rs` and `correlate.rs`, case for case.

ridl 0.5.1 adds `Rule.Unique`, a map holding two entries with one key
(driftsys/ridl#654), and the float `step` check generated code calls, `Steps`,
below.

ridl 0.6.0 adds `ridl.rt.trace` (driftsys/ridl#752, #754, ADR-0021 decision 21):
`TraceContext`, the W3C `traceparent` layout without its version byte, carried
unvalidated; and the `Propagation` hook, registered once per process with
`setPropagation` and read with `propagation()`, which no generated code calls
yet. `Caller.command`, `Caller.query` and `EventSink.raise` take a last argument
`trace: TraceContext?`, and `Claim`, `RawOccurrence` and `ReadError.ShortClaim`
carry it, under the delivery contract the port interfaces state. `TraceTest` is
`trace.rs`, where the JVM can spell it, and `PropagationTest` is
`propagation.rs` and `propagation_unset.rs`. The hook cannot be cleared, so
`PropagationTest` runs in a JVM of its own, the `processHookTest` task that
`test` runs first, as each Rust file is a test binary of its own (#52).
`ProcessHookTagTest` fails on a test file that calls `setPropagation` without
the `process-hook` tag (#65).

## Compiling against Android

The runtime and the loopback are also built from source against Android's
`android.jar`, with no JDK on the classpath, as an AOSP `java_library` is.
There, Kotlin resolves `ByteBuffer.position(Int)`, `limit(Int)`, `flip()` and
`clear()` to the `java.nio.Buffer` methods, which return a `Buffer`, although
`android.jar` declares the `ByteBuffer` overrides as the JDK does. A chain such
as `buf.duplicate().position(n).put(bytes)`, or a `flip()` passed where a
`ByteBuffer` is expected, compiles on the JVM and fails there. So the code never
uses what one of those methods returns: it calls the method as a statement, then
uses the buffer. CI has no `android.jar`, so nothing checks this rule; the last
check compiled this module, `ridl-rt-kt-loopback`, `ridl-rt-kt-coroutines`,
`ridl-rt-kt-conformance` and the generated code of every corpus package with
`kotlinc -no-jdk` against `android-34/android.jar`.

## Where the code departs from docs/design.md

- **Errors are exceptions.** §3 spells the error types as `sealed interface`s
  that are thrown, and `Caller.ack` as a Kotlin `Result` carrying a `CallError`.
  Kotlin throws and carries in a `Result` only a `Throwable`, so every error
  type is a sealed class over `RidlError`, a `RuntimeException` that captures no
  stack trace; its data-less variants are `data object`s. `Contract` and
  `Transport` extend `CallError`, so a `when` over a call error names its two
  arms or every variant of both.
- **`Cause`, `Detection` and `Freshness` are sealed**, not `enum class`, because
  `Detected`, `InvalidValue` and `Stale` carry data as the Rust variants do.
  `Detection` is an error, so `Occurrence.payload` is a Kotlin `Result`.
- **`Payload<T, V>`** has a second type parameter, the view `verify` returns and
  `decode` takes, and an `encoding` property. Taking the view is how a value is
  decoded only from checked bytes, the guarantee Rust's `Ref` proof gives; `Ref`
  and `Encoded` have no Kotlin spelling.
- **`Command.require`, `Query.require` and `Query.ensure` return `Boolean`**
  where Rust returns `Result<(), ()>`.
- **`ConstraintViolation`** (§4) is Kotlin's own, with no Rust item.
- **`Steps`** is Kotlin's own, with no Rust item: the floating-point half of the
  float `step` check (driftsys/ridl#654), which the Rust backend inlines into
  every generated check and the plugin's generated code calls here, with the
  constants it computed exactly at generation time. `StepsTest` covers it over a
  decimal lattice; the conformance module's `ConstraintsTest` covers it over
  ridl's extreme lattices, through generated code.
- **`Wakeable` is keyed** (`wakeOn(what: Interest, waker: Waker)`), where §3
  gives it one unkeyed `onChange(callback): AutoCloseable` as Kotlin's own
  extension: `ridl-rt` now has the extension itself (ADR-0021 decision 13), and
  this is its spelling. A registration is not closed; a stored waker is woken at
  most once and cleared, and a later registration displaces it.
- **`Waker`** is Kotlin's own, the `fun interface` spelling of
  `core::task::Waker`: two wakers are the same task when they are the same
  object, which is `will_wake`. A Rust future is spelled `(Waker) -> T?`, a poll
  that answers `null` for `Pending`.
- **`blockOn(deadline, poll)`** takes the future last, so it can be a trailing
  lambda, and the deadline as a `TimeSource.Monotonic.ValueTimeMark`, the
  monotonic clock `std::time::Instant` is. The JVM always has what the Rust
  `std` feature adds, so `ridl.rt.task` is not optional. Its waker goes inert
  when the wait returns: Rust drops a waker with its task, while a port may keep
  a finished wait's waker and wake it later, which would otherwise leave a
  permit that ends an unrelated park of the thread at once.
- **The E11.19 helpers take the encoding as an argument**:
  `member.reservation(Encoding.FlatBuffers)` and
  `tableBudget(members, encoding)` where Rust has
  `reservation::<FlatBuffers>()`, and `Unsized` and `TrackerFull` are thrown, as
  every error type is. `EventSeqTracker(capacity)` takes at run time the `N`
  Rust takes as a const generic, so `event_seq.rs`'s const-context case has no
  spelling here.
- **`correlate.Table(capacity, budget)`** takes at run time the `N` Rust takes
  as a const generic, and refuses more than 65536 slots with an
  `IllegalArgumentException` where Rust refuses to compile. An outcome is a
  Kotlin `Result<Unit>` carrying a `CallError`, as `Caller.ack` is, and
  `Waiters.takeAll` returns a list. `correlate.rs`'s reference-count checks and
  const-context case have no JVM spelling.
- **`ClientError` and `ProviderError` have no `From` conversions**: Kotlin has
  no `?`, so a variant is built from its inner error directly.
- **`TraceContext` is a class over copied arrays**, as `CatalogHash` is: its ids
  are copied in and out, equality and the hash are over the bytes, and `flags`
  is a `UByte`. A wrong id length is an `IllegalArgumentException`, where the
  Rust arrays cannot have one; `trace.rs`'s 25- and 26-byte size test has no JVM
  spelling.
- **The `Propagation` hook is not optional**: Rust puts it behind the `std`
  feature, which the JVM always has. `setPropagation` throws `AlreadySet`, where
  Rust returns it, and the hook is an interface instance, not a
  `&'static dyn Propagation`.
- **`Transport.Busy` breaks an exhaustive `when`.** `Transport` is
  `#[non_exhaustive]` in Rust, so adding `Busy` breaks nothing there; a Kotlin
  sealed class has no such marker, and a `when` over `Transport` or `CallError`
  with no `else` must name it.
