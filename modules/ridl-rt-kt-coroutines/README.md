# ridl-rt-kt-coroutines

## Responsibility

This module is the `suspend` adapter over the polling face (docs/design.md §5,
D-K6): one function, `await(port, interest, poll)`, which suspends until a
polling read of a face answers, woken by the runtime's `Wakeable` extension of
[`ridl-rt-kt`](../ridl-rt-kt/README.md) under the key the read's answer changes
under. It is a JVM library over `kotlinx-coroutines-core`. The repository is
licensed under the root [MIT License](../../LICENSE).

```kotlin
val correlation = client.average(Window.of(10))
val reply = await(port, Interest.Outcome(correlation.correlation)) {
    client.averageReply(correlation)
}
```

Each round registers under `interest`, then calls `poll`, as the `Wakeable`
contract requires, until `poll` answers something other than `null`; an outcome
that lands between the read and the suspension still wakes it. One waker serves
the whole wait, so the port sees one task registering again rather than a new
one each round. `port` is the handle the read goes through: a provider waiting
on a handler of its own passes that handler, not the aggregate. A wait is
bounded with `withTimeout`, as nothing in a port times out.

`awaitPoll(cancel, poll)` is the general form: `poll` receives the waker and
registers its own interest, and `cancel` runs once when the coroutine is
cancelled while waiting. A generated `<Iface>AsyncClient` runs each call through
it. A wake that lands during a poll makes the next round yield once before it
polls again, whoever woke it: a poll that wakes itself, as the generated
`serveAsync` does after a pass of 32 claims (driftsys/ridl#568), then never
holds its dispatcher, and the yield is where a cancellation is seen. The rule
also covers a client: when its outcome or event lands while its own poll runs,
it reads the value on the next poll, one dispatch later. `AwaitPollTest` pins
both cases on one thread.

`SignalState<T>` is a signal as a shared `StateFlow` of its samples (#76), what
a generated `<signal>` property of an async client returns; a client keeps its
states in one `SignalStates`, one per signal and period. The runtime has no
wake-up for a signal change, so a state polls the port, on these rules:

- **The period** is `effectivePeriod(timing, desired, quantum)`: the signal's
  rate floor rounded up to the 10 ms quantum and capped at its staleness bound
  rounded down; with no floor, half the staleness bound; with neither, ridl's
  default floor of 100 ms. `every(desired)` asks for another period, never
  faster than that default one, and returns the state shared for that period; an
  infinite one saturates at the longest period.
- **The grid.** Every state polls at the multiples of its period from the epoch
  of one `PollGrid`, by default the process's `PollGrid.Default`, so states of
  compatible periods read in the same slot whenever each started, a poll is
  scheduled against its slot rather than after a `delay(period)`, and an overrun
  skips to the next slot.
- **The lifecycle.** A state polls while it has a collector, and stops `linger`
  (3 s) after the last one left, whatever its period. It counts its collectors
  itself, and one coroutine is the whole lifecycle: it sleeps until the next
  slot or the end of the linger, whichever comes first, polls on a slot and
  stops at the end; the last collector's leaving wakes it, so a state of a long
  period does not sleep on to its next slot. So no coroutine runs while a state
  has no collector and no stop is pending, and a client made with a scope that
  must end, such as `runBlocking`'s, does not hold it unless a state is
  collected. When the scope ends, every collector's `collect` throws an
  `IllegalStateException`, as does a later one's: no collector waits on a state
  nothing polls.
- **The client's states.** `SignalStates` keeps a signal's default-period state
  for the client's life, so a generated `<signal>` property is one stable
  instance, and a state of another period while it polls: the state leaves the
  client when its polling stops (#79), so a caller that asks for one period
  after another, a slider say, does not fill the client with idle states. A
  state of another period that is not polling, created or dropped, is only
  remembered weakly: a caller that holds it finds it again, so two requests for
  one period share one state, and one nobody holds is collected, so a state
  created and never collected leaks nothing. A state never read holds no buffer.
- **`value`** is the last sample polled while the state polls, with that poll's
  freshness, as long as it is at most one period and one quantum old, and a
  fresh read otherwise; `read()` always reads the port. The age guard is one
  clock read on the grid's time source, so a poller its dispatcher starves never
  leaves `value` handing out an old sample. Why a guard, and not a read every
  time: [`value`: cache, read or guard](#value-cache-read-or-guard-78). A read
  and its publication hold one lock, so two reads publish in the order they
  read.
- **Failure.** A read or a decode that throws while polling stops the polling,
  and every collector's `collect` throws it, as a cold flow would; the failure
  never reaches the scope the state polls in. A later subscriber, `value` or
  `read()` reads the port again.
- **Emission.** A sample is emitted when its value, provenance, envelope or
  staleness differs from the last one emitted; the age of a stale sample is not
  compared. `values` is the values alone, emitted when the value changes.
- **Cost.** A read copies the channel into one buffer the state keeps, and a
  publication already decoded, the same envelope, provenance and bytes, is not
  decoded again. The bytes are compared because an envelope alone does not name
  a publication: two writers each count their own sequence numbers.

`SignalStateTest` pins these on virtual time, with a grid over the test
scheduler's time source.

The lifecycle is hand-written rather than a `shareIn` or `stateIn` over a
polling flow with `SharingStarted.WhileSubscribed(linger)`, which #80 proposed
and three of the rules above rule out. The sharing coroutine `shareIn` launches
waits on the subscription count for the scope's life, so a `runBlocking` scope
never ends; a `MutableStateFlow` hands a new collector its current value before
any start command runs, so the restart read could not come first; and
`WhileSubscribed` issues a start only after a stop, so a collector back within
the linger after a failure would wait for the linger to run out instead of
restarting the polling. What was taken from the proposal is the shape: one
coroutine owns the start, the polls and the stop, in place of the poller, the
stopper and the polling flag it replaced.

Implementing `StateFlow` needs the opt-in
`ExperimentalForInheritanceCoroutinesApi` of kotlinx-coroutines: a later release
of the library may add members to the interface, which `SignalState` would then
implement.

## `value`: cache, read or guard (#78)

Until #78, `value` returned the cached sample while the state polled, with no
check of its age, and #78 weighed three ways for it: **A**, that cache; **B**, a
read every time (`read()`); **C**, the cache behind an age guard, falling back
to a read. The question was whether C's clock read costs as much as the read it
avoids on a seqlock'd shared-memory runtime, where a port read is two atomic
loads and a copy.

Measured by `samples/cabin/src/jmh` (`just bench [threads] [filter]`), through
the generated `CabinAsyncClient.temperature` with its collector polling at 10
ms, over the loopback and over `SeqlockPort`, a seqlock'd off-heap stand-in that
reads a clock for the freshness as a runtime does. JMH 1.37, OpenJDK 17, x86-64
Linux, 20 hardware threads, average time in ns per call:

| Call                                 | Loopback, 1 thread | Seqlock, 1 thread | Loopback, 4 threads | Seqlock, 4 threads |
| ------------------------------------ | -----------------: | ----------------: | ------------------: | -----------------: |
| `System.nanoTime()`                  |               12.9 |              12.9 |                14.5 |               15.0 |
| port read alone                      |                 18 |                24 |                 393 |                 39 |
| A, `value` before #78                |               0.47 |              0.47 |                0.57 |               0.57 |
| C, `value` now                       |               17.3 |              17.1 |                19.7 |               19.8 |
| B, `read()`, publication unchanged   |                 98 |                98 |                 635 |           1350[^2] |
| B, `read()`, publication changed[^1] |                359 |               270 |                   — |                  — |

[^1]: `readChanged − publishChanged`, before `read()` stamped its read: a read
    that decodes, publishes the new sample and wakes the collector.

[^2]: ±1,900 ns across its iterations; an earlier run measured 1,030 ns.

- **The port read is as cheap as the issue expected, `read()` is not.** A
  seqlock'd port read costs about two clock reads, but `read()` around it takes
  the state's lock, compares the bytes, builds a `Sample` and publishes it, and
  costs four to six times C before any decode. Its lock makes it worse with
  threads: four threads calling `read()` together wait on each other, where
  `value` scales.
- **C costs one clock read over A,** about 17 ns, as `kotlin.time`'s mark
  arithmetic adds about 4 ns to `System.nanoTime()`. Every read now also stamps
  itself, which took `read()` from 78 to 98 ns: the poller pays it once per
  period.
- **An IPC-backed port** (Binder, a socket) was not measured: its read costs
  microseconds to milliseconds, so C's 17 ns is noise there, and the guard
  matters more, as a stalled poller's sample is the costlier one to replace.

So `value` is C on every runtime class, with no knob: it is the only variant
that never returns a sample older than one period and one quantum, and it costs
a fifth of a read or less on both runtimes measured.

## Status

Stage K5, keyed by driftsys/ridlc-gen-kotlin#5. `AwaitTest` pins the properties
above over a hand-driven `Wakeable` with one waker per key: a known value after
one registration and one read, registration before every read, no wake from
another key, one waker for the whole wait, and a wake from another thread. The
cabin sample's `CoroutineDemo.kt` runs a consumer and a provider on two threads
over the loopback with it.

## O-K3: no `*Await` extension, generated or hand-written

docs/design.md §5 has two generated one-line extensions per call,
`suspend fun <Iface>Client<P>.averageAwait(window)`, and O-K3 leaves open
whether they are generated or written by hand in the sample, to be decided by
writing the sample both ways and keeping the shorter. Measured on the cabin
sample's two calls:

| Form                                                                     | Lines at the call sites | Lines written beside them                                                                                                |
| ------------------------------------------------------------------------ | ----------------------: | ------------------------------------------------------------------------------------------------------------------------ |
| `await(port, Interest.Outcome(c.correlation)) { client.<call>Reply(c) }` |                       4 | none                                                                                                                     |
| hand-written `<call>Await` extensions                                    |                       2 | about 4 per call, each taking the port the client keeps private                                                          |
| generated `<call>Await` extensions                                       |                       2 | none, but every generated package then depends on `kotlinx-coroutines`, or a third plugin option decides whether it does |

The recommendation, for disposition, is the first: the generic `await` is the
whole adapter, the generated face stays free of any coroutine dependency, and a
call's suspending form is its send and one `await`.

Reversed for generated code by #7: every `<Iface>AsyncClient` is generated and
runs its calls through `awaitPoll`, so a generated package with an event, a
command or a query depends on `kotlinx-coroutines-core`. An application that
never calls the async client loses its code when its release build shrinks
unused classes.
