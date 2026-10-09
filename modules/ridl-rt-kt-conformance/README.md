# ridl-rt-kt-conformance

## Responsibility

This module is the port contract suite of
[`ridl-rt-kt`](../ridl-rt-kt/README.md): the tests of what `ridl.rt.port` states
a runtime does behind each port, generic over a runtime, so any Kotlin runtime
runs them from its own tests. It is the Kotlin spelling of
`crates/ridl-rt-conformance` (story E11.20), a JVM library in the package
`ridl.rt.conformance` over `ridl-rt-kt` and the JUnit Jupiter API, published as
`io.github.driftsys.ridl:ridl-rt-kt-conformance` so that a runtime in another
repository runs it. The repository is licensed under the root
[MIT License](../../LICENSE).

A runtime writes one `Factory<R>` — how to build a runtime `R` implementing
every port the suite calls, how to get a second source, caller or handler on it,
and two hooks the ports do not offer, `advance` for the clock and
`failNextSettle` for one injected settlement failure — and runs the suites from
a `@TestFactory`:

```kotlin
@TestFactory fun ports() = suite(MyFactory)
@TestFactory fun scannable() = scannableSuite(MyFactory)
@TestFactory fun coherent() = coherentSuite(MyFactory)
@TestFactory fun trace() = traceSuite(MyFactory)
```

A runtime in another repository takes the suite from this repository's GitHub
Packages registry, which the book's
[Installing](../../docs/book/install.md#the-runtime-libraries) chapter shows how
to declare. `just publish-local` here puts it in the local Maven repository
instead, for a change not yet on `main`. The runtime's build declares the suite
in its test source set, with the JUnit engine that runs it:

```kotlin
dependencies {
    implementation("io.github.driftsys.ridl:ridl-rt-kt:0.1.0-SNAPSHOT")
    testImplementation("io.github.driftsys.ridl:ridl-rt-kt-conformance:0.1.0-SNAPSHOT")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
```

The suite's version follows `ridl-rt-kt`'s: a runtime takes the suite of the
`ridl-rt-kt` it implements, because the suite's surface changes with the port
contract. Run only the suites of the extensions the runtime implements, and
`traceSuite` only when it carries the trace context.

## Status

driftsys/ridlc-gen-kotlin#8, over story E11.20 (ridl `main` at 44e59fa): the 51
tests of the Rust suite, case for case and under the same names, in one contract
class per Rust module — `AttachedContract`, `ClockContract`, `SignalsContract`,
`EventsContract`, `CallsContract`, `ScannableContract`, `CoherentContract` and
`WakeableContract` — and `Factory.slots`, the size of the runtime's call table,
which the call-table cases fill. `ridl-rt-kt-loopback` runs all 51 from its
`ConformanceTest`. The mutations ridl#531 names turn it red as they turn the
Rust suite: M1, `settle` without its owner check, fails
`a handler
cannot settle another handlers claim`, and M5, a `touch` that
overwrites a staged value, fails the two touch tests; the mutation note F-14
names, a settlement that wakes no outcome waker, fails
`an outcome waker is kept with its
call and woken once by the settlement`.
`SuiteTest` is the Rust crate's own test that every test function is run: a
public test method its contract's `tests` list does not name turns it red.

driftsys/ridlc-gen-kotlin#10, over ridl 0.4.0 (driftsys/ridl#569):
`ReadError.ShortClaim` replaces
`a short buffer leaves the claim for the next call` with the four cases of
`calls.rs` — `an oversized claim is reported with its id and is not
consumed`,
`an unread claim is settled by its id`,
`the calls behind an
oversized claim are presented once it is settled` and
`forget between the offer
and the settlement leaves the settlement valid` —
which makes 54 tests.

ridl 0.6.0 (driftsys/ridl#752, ADR-0021 decision 21): the trace context. The
base arm gains `an event raised without a context arrives without one` and
`a call sent without a context arrives without one`, rule 4, which every runtime
passes, and `an oversized claim is reported with its id and is not consumed`
checks that its `ShortClaim` carries no context. `TraceContract`, run by
`traceSuite`, holds the 18 cases of the `trace` arm, rules 1 and 2, for a
runtime that carries the context: 42 base cases and 74 in all. Over the
loopback, a runtime that delivers `null` fails all 18; one that delivers the
latest call's context on a claim fails the two in-flight cases, and on a
`ShortClaim` fails
`an oversized claims context is the offered calls not the latest`.

The case of a forget before any claim accepts either a call still presented and
settled or a withdrawn one, and checks that the runtime then accepts exactly
`slots` further sends, so the forgotten call gave its slot back.

What the suite leaves out is the Rust crate's list, unchanged: what the port
contract leaves to a runtime, a handler that has served nothing, two sinks on
one event channel, `FixedReader`, anything reported from a catalog descriptor,
the threading model, a runtime's own API beyond the factory, a wake the contract
allows but does not require, which serving handlers a call wakes, what a forget
does to a call no handler has claimed beyond giving its slot back, and the close
of a handle.

## Where the code departs from the Rust crate and docs/design.md

- **Published.** `crates/ridl-rt-conformance` is not published: a Rust runtime
  depends on it by path or git. A JVM build cannot, so this module publishes a
  Maven artifact, as the other three libraries do, for a runtime outside this
  repository.

- **Dynamic tests, not a macro.** `suite!(F; scannable, coherent)` writes one
  `#[test]` per function; here `suite`, `scannableSuite` and `coherentSuite`
  return one JUnit `DynamicTest` per test method, named after it. A runtime that
  omits a signal extension does not call that extension's suite, where Rust
  leaves its arm out of the macro.
- **The trace arm is one contract.** Rust keeps the `trace` arm's cases in
  `calls.rs` and `events.rs`; here they are `TraceContract`, in the order of the
  arm, because each Kotlin contract class is one arm's.
- **Tests are methods of a contract class** over the factory, where Rust has
  free functions generic over `F: Factory`, so the port bounds on `R` are stated
  once per class rather than once per test.
- **The factory is a value**, an `object` a runtime's tests write, where the
  Rust trait has only associated functions; `source`, `caller` and `handler`
  return the port interfaces rather than associated types.
- **`wakeableSuite` checks `Wakeable` at run time.** Rust bounds the factory's
  handles to `Wakeable` at compile time; a Kotlin factory returns the port
  interfaces, so a handle that is not `Wakeable` fails the test that needs it,
  naming the handle.
- **The lock test calls back from another thread.** Rust's
  `every_wake_runs_with_the_runtime_lock_released` has the woken waker call back
  into the port on the waking thread, where a runtime holding its mutex
  deadlocks. A JVM monitor is reentrant, so that call-back would succeed; the
  Kotlin waker calls back from another thread, which a runtime holding its
  monitor blocks, and gives up after a second. It counts only a wake on the
  thread that made the change, which Rust's thread-local probe checks.
- docs/design.md §6 lists no conformance suite for the runtime: this module is
  added by lane F (driftsys/ridl's `docs/wip/2026-09-25-lane-f-driver.md`, item
  K5).
