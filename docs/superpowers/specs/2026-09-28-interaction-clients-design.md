# Interaction clients and `serve` — design

Status: design for driftsys/ridlc-gen-kotlin#7, agreed 2026-09-28. The Kotlin
twin of ridl story E11.21 (driftsys/ridl#566, #577, ridl `main` at 1eb0fba) and
of ADR-0023 decision 6. The Rust surface this mirrors is the handoff comment on
#7.

## 1. What changes

Today the generated face is a poll face: a send returns a correlation, and the
caller polls `…Ack`, `…Reply` and `nextEvent` in a loop of its own, or awaits
them through `ridl-rt-kt-coroutines`' generic `await`; a provider calls
`Cabin.dispatch` repeatedly. This design replaces that public surface with two
clients per interface and `serve` in two forms, and makes the poll face
`internal`.

The async client depends on `ridl-rt-kt-coroutines`, which reverses O-K3 of
driftsys/ridlc-gen-kotlin#3 for generated code: every generated package that has
a command, a query or an event depends on `kotlinx-coroutines-core`. An
application that never calls the async client loses its code when its release
build shrinks unused classes.

## 2. Errors

A call returns its declared reply, business errors included, since a fallible
query declares its error in its return type. Everything else throws
`ridl.rt.error.ClientError`, a sealed `RidlError`:

| Group                  | Variants                                                                       | Meaning                                                                                          |
| ---------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------ |
| Transport (ridl §10.3) | `Call(Timeout)`, `Call(Undelivered)`, `Send(Busy)`, `Call(Busy)`, `Call(Down)` | Expected in a distributed system; handled where a retry or back-off policy lives, not per call   |
| Contract (ridl §10.2)  | `Send(Contract(PreconditionFailed))`, `Call(Contract(…))`, `Call(Corrupt)`     | A bug or version skew; the one a caller may check for is its own `require` failing on user input |
| Local                  | `Send(Detached)`, `Read(Detached)`                                             | The local runtime is gone                                                                        |

`serve` throws `ridl.rt.error.ProviderError`: `Serve(e)` when the handler
refuses the members, `Claim(e)` when a claim read fails.

## 3. What is generated

For each interface, `Faces.kt` gains:

- **One internal call class per command and query**, such as
  `internal class AverageCall`, the counterpart of the Rust named future. It is
  created by a client method and sends at once. `fun poll(waker: Waker): R?`
  returns `null` while waiting, returns the reply, or throws `ClientError`;
  `fun sent(): Boolean` says whether the call was sent; `fun cancel()` forgets a
  sent call still waiting, once. Every call rule of §4 lives in this class.
- **`<Iface>AsyncClient<P>`**: one `suspend` method per call, which creates the
  call and awaits `poll` through `ridl-rt-kt-coroutines`, with coroutine
  cancellation calling `cancel()`; `suspend fun nextEvent(): Event`; the signal
  reads and `subscribe…`/`unsubscribe…` as plain functions. It runs its calls
  one at a time, under a coroutine `Mutex`: a second concurrent call waits for
  the first, and its KDoc says to use a second client over a second caller
  handle for concurrency, as Rust requires.
- **`<Iface>Client<P>`**, the blocking client, constructed as
  `CabinClient(port, timeout: Duration? = null)` with a mutable `var timeout`.
  One plain method per call runs `blockOn(deadline) { call.poll(it) }` with the
  deadline `timeout` after the call starts. When `blockOn` gives up it throws
  `Send(Busy)` for a call never sent and `Call(Undelivered)` for a command or
  `Call(Timeout)` for a query that was sent, then cancels the call.
  `nextEvent(): Event?` returns `null` at the timeout. The signal reads and
  subscriptions are plain port reads. It is used from one thread at a time, like
  the port handles, and holds no lock.
- **`<Iface>.serve(handler, provider, timeout: Duration? = null)`** and
  **`suspend fun <Iface>.serveAsync(handler, provider): Nothing`** on the
  interface's descriptor object, where `dispatch` is today (§5).
- **The poll face becomes `internal`**: the correlation types, the `…Ack` and
  `…Reply` methods, the polling `nextEvent`, and `dispatch`. Generated code
  compiles into its consumer's module, so the conformance probes and the sample,
  compiled with it, still reach it where they need to.

The port bounds follow Rust (RA-19): `SignalReader` with a signal, `EventSource`
with an event, `Caller` and `Clock` with a command or a query, `Wakeable` with
an event, a command or a query. A signal-only interface keeps one
`<Iface>Client` and has no async client and no `serve`, because nothing in it
waits. An event-only interface has both clients and no `serve`.

`ridl-rt-kt-coroutines` gains the small helper the async client needs, over its
existing `await`, with cancellation; no new module.

## 4. A call

**Start** (in the client method):

1. Evaluate `require`. If it fails, throw
   `ClientError.Send(SendError.Contract(Contract.PreconditionFailed))`; nothing
   is sent.
2. Encode the argument.
3. The deadline is `port.now() + member.callDeadline()`, or none when the member
   has no `max`.
4. Send through `port.command` or `port.query`. `SendError.Busy` leaves the call
   unsent, to retry on a later poll; any other `SendError` throws `Send(e)`.

**Each `poll(waker)`** registers first, reads the port once, and returns:

- **Unsent**: register `Interest.Slot`, then retry the send.
- **Sent**: register `Interest.Outcome(c)`, then read `ack` for a command or
  `reply` for a query. An outcome that has arrived: `forget(c)`, then return the
  reply or throw `Call(error)`. A reply that fails its check throws
  `Call(Transport.Corrupt)` for bad structure and
  `Call(Contract.InvalidValue(v))` for a constraint violation, as the poll face
  reports today. A failed port read throws `Read(Detached)`.
- **The deadline** is read from the port's clock at each poll, and
  `now > deadline` expires the call, so a call at exactly `max` is within it.
  Past it, unsent: throw `Send(Busy)`. Past it, sent: `forget(c)`, then throw
  `Call(Undelivered)` for a command or `Call(Timeout)` for a query.
- **Polling a finished call** throws `IllegalStateException`, where Rust panics.

**`cancel()`** forgets a sent call that has no outcome yet, once. The async
client calls it when its coroutine is cancelled, and the blocking client after
`blockOn` gives up.

The port's clock matters only when something wakes the call. A runtime that
measures deadlines wakes the call at `max`; one that does not, the loopback
among them, leaves an unanswered call waiting until the blocking client's
`timeout` or the caller's `withTimeout`, as in Rust.

**`nextEvent`** registers `Interest.Event(iface)`, reads the next occurrence and
decodes it. It has no deadline, and cancelling it does nothing.

## 5. `serve`

Both forms first call `handler.serve(NUMBER, <the command and query
ordinals>)`;
a refusal throws `ProviderError.Serve(e)`. Then they loop: each pass registers
`Interest.Claim(NUMBER)` and drains the handler through the existing one-pass
settlement step, now `internal`. The settlement table is unchanged: an unknown
interface or ordinal settles `UnknownInteraction`, malformed bytes
`Transport.Corrupt`, a typl violation `InvalidValue`, a failed `require`
`PreconditionFailed`, a failed `ensure` `ContractBroken`; a command is settled
before its provider method runs, a query after. A failed claim read throws
`ProviderError.Claim(e)`, and every claim settled before it stays settled.

- **`serveAsync`** suspends between passes and never returns normally: it ends
  by throwing, or by the cancellation of its coroutine.
- **`serve`** parks the thread between passes with `blockOn`, returns when its
  timeout passes, and with no timeout returns only by throwing.

## 6. Name collisions

The generated names can collide with a member's: `nextEvent` with a member named
`next_event`, and the blocking client's `timeout` with one named `timeout`
(driftsys/ridl#570, open in Rust). When they do, the face skips the interface
with a warning, as it already skips an interface it cannot carry. Nothing that
works today is refused, and the rule can be relaxed when ridl#570 is decided.

## 7. What moves

- **`samples/cabin`**: `Main.kt` runs the round trips through `CabinClient` and
  `Cabin.serve`, and `CoroutineDemo` through `CabinAsyncClient` and
  `Cabin.serveAsync`. `DemoTest` keeps pinning the printed lines.
- **The conformance face probes**
  (`modules/conformance/src/test/resources/faces/*.kt`) exercise the clients,
  and keep reaching the settlement table through the internal `dispatch`.
- **`ridl-rt-kt-coroutines`**: the O-K3 section of its README records the
  reversal for generated code and why.
- **`docs/design.md` §5** is not edited (AGENTS.md); the `ridlc-gen-kotlin`
  README records where the code departs from it.

## 8. Testing

- **The call objects**, directly over the loopback: `Busy`, then a slot freed,
  then the call sent; the deadline, unsent and sent; `forget` when the outcome
  is taken; `cancel` forgetting a call once; a finished call polled again
  throwing.
- **The clients**: both round-trip every interaction kind of the cabin package.
  A cancelled coroutine calls `forget` (#7's "Done when"): a command in flight,
  cancelled, gives its slot back. The blocking client's timeout throws the
  documented error for a sent and an unsent call.
- **`serve`**: `serve` ends with `ProviderError.Claim` when the port fails, and
  `serveAsync` ends when its coroutine is cancelled.
- **Mutations** that must turn the tests red: a call that does not forget its
  outcome, a cancellation that does not forget, and a deadline compared with
  `>=` rather than `>`.

## 9. Out of scope

A generated `*Await` extension beyond the async client; any change to the port
contract or to `ridl-rt-kt`'s public items; a Kotlin twin of the enum-variant
spelling change of driftsys/ridl#560, which Kotlin does not need.
