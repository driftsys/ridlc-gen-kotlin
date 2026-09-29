# Interaction Clients Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate, per interface, a blocking `<Iface>Client`, a suspending
`<Iface>AsyncClient`, and `serve`/`serveAsync`, over one generated call object
per command and query, and make the poll face `internal`.

**Architecture:** `FacesEmitter` gains a generated internal base class,
`InteractionCall<P, T>`, that carries every call rule of the spec's §4 (send at
once, wait on `Slot` when `Busy`, the deadline on the port's clock, `forget`
when done or cancelled), and one small subclass per command and query
(`CabinSetLevelCall`, `CabinAverageCall`) that only reads its outcome. The
blocking client runs a call with `ridl.rt.task.blockOn`, the async client with a
new `ridl.rt.coroutines.awaitPoll`, and `serve`/`serveAsync` drive the existing
one-pass settlement step (`dispatch`, now `internal`) the same two ways. Today's
public client becomes the internal `<Iface>PollClient`.

**Tech Stack:** Kotlin 2.4 (explicit API in libraries), KotlinPoet (the
emitter), kotlinx-coroutines-core (the async client), JUnit 6 with
kotlin-compile-testing (generated code compiled in the conformance tests),
Gradle through `just`.

**Spec:** `docs/superpowers/specs/2026-09-28-interaction-clients-design.md`

## Global Constraints

- A call throws `ridl.rt.error.ClientError`; `serve` and `serveAsync` throw
  `ridl.rt.error.ProviderError` (spec §2).
- `<Iface>AsyncClient` is always generated for an interface with an event, a
  command or a query; generated packages depend on `ridl-rt-kt-coroutines` (spec
  §1, §3).
- The async client runs its calls one at a time under a coroutine `Mutex` (spec
  §3).
- Blocking names are unmarked, async names are suffixed: `CabinClient` /
  `CabinAsyncClient`, `Cabin.serve` / `Cabin.serveAsync` (spec §3).
- The deadline is `port.now() + member.callDeadline()`; `now > deadline` expires
  a call, so a call at exactly `max` is within it (spec §4).
- A signal-only interface keeps one public `<Iface>Client` and gets no async
  client and no `serve`; an event-only interface gets both clients and no
  `serve` (spec §3).
- No change to `ridl-rt-kt`'s public items or to the port contract (spec §9).
  `ridl-rt-kt-coroutines` gains `awaitPoll`.
- `docs/design.md` is never edited; departures go in the module README that owns
  them (AGENTS.md).
- Every commit passes `just build` and `just lint-commits`; commits carry no AI
  attribution line (the user's global instructions).

## Review Focus

- **A call polled again after it finished or was cancelled.** It must throw
  `IllegalStateException`, not send or forget a second time. Pinned in Task 3.
- **Cancelling an async call that is still unsent (every slot taken).** It must
  end quietly, forget nothing, and leave the table's count unchanged. Pinned in
  Task 5.
- **A blocking client whose timeout is too large to represent**
  (`Duration.INFINITE`). It must wait with no bound and return the reply, not
  overflow. Pinned in Task 4.
- **A provider method that throws** while `serve` runs. The application's
  exception must propagate out of `serve` unchanged, not be wrapped in
  `ProviderError`. Pinned in Task 4.
- **Two coroutines calling one async client at once.** The second call must not
  be sent until the first finished. Pinned in Task 5.

---

### Task 1: `awaitPoll` in `ridl-rt-kt-coroutines`

**Files:**

- Modify:
  `modules/ridl-rt-kt-coroutines/src/main/kotlin/ridl/rt/coroutines/Await.kt`
- Test:
  `modules/ridl-rt-kt-coroutines/src/test/kotlin/ridl/rt/coroutines/AwaitPollTest.kt`
- Modify: `modules/ridl-rt-kt-coroutines/README.md`

**Interfaces:**

- Produces:
  `public suspend fun <T : Any> awaitPoll(cancel: () -> Unit, poll: (Waker) -> T?): T`
  in package `ridl.rt.coroutines`. It calls `poll` with one waker for the whole
  wait until `poll` returns non-null or throws, suspends between polls until the
  waker is woken, and calls `cancel` exactly once when the coroutine is
  cancelled while waiting, then rethrows the `CancellationException`.
- Consumes: `ridl.rt.task.Waker` (ridl-rt-kt).

- [ ] **Step 1: Write the failing test**

```kotlin
package ridl.rt.coroutines

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.task.Waker
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AwaitPollTest {
    @Test
    fun `a value the first poll answers is returned at once`() = runBlocking {
        assertEquals(7, awaitPoll({ error("not cancelled") }) { 7 })
    }

    @Test
    fun `it polls again when the waker is woken, with the same waker`() = runBlocking {
        val wakers = mutableListOf<Waker>()
        val value = AtomicReference<Int?>(null)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            awaitPoll({}) { waker -> wakers += waker; value.get() }
        }
        value.set(3)
        wakers.last().wake()
        assertEquals(3, waiting.await())
        assertEquals(2, wakers.size)
        assertTrue(wakers[0] === wakers[1], "one waker for the whole wait")
    }

    @Test
    fun `a cancelled wait calls cancel once and ends`() = runBlocking {
        val cancels = AtomicInteger()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { awaitPoll<Int>({ cancels.incrementAndGet() }) { null } }
        yield()
        waiting.cancelAndJoin()
        assertEquals(1, cancels.get())
        assertTrue(waiting.isCancelled)
    }

    @Test
    fun `an exception the poll throws is thrown and cancel is not called`() = runBlocking {
        val cancels = AtomicInteger()
        assertThrows<IllegalStateException> { runBlocking { awaitPoll<Int>({ cancels.incrementAndGet() }) { error("boom") } } }
        assertEquals(0, cancels.get())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
`./gradlew :ridl-rt-kt-coroutines:test --tests ridl.rt.coroutines.AwaitPollTest`
Expected: FAIL to compile, `Unresolved reference 'awaitPoll'`.

- [ ] **Step 3: Write minimal implementation**

In `Await.kt`, add `awaitPoll` and rewrite `await` over it:

```kotlin
/**
 * Suspends until [poll] answers a value, and returns it: the suspending twin
 * of `ridl.rt.task.blockOn`. [poll] is called once at once, then again each
 * time the waker it was given is woken; one waker serves the whole wait, so a
 * port sees one task registering again. [poll] registers its own interest
 * before it reads, as the `Wakeable` contract requires. An exception [poll]
 * throws ends the wait. When the coroutine is cancelled while waiting,
 * [cancel] runs once and the cancellation is rethrown.
 */
public suspend fun <T : Any> awaitPoll(cancel: () -> Unit, poll: (Waker) -> T?): T {
    val woken = Channel<Unit>(Channel.CONFLATED)
    val waker = Waker { woken.trySend(Unit) }
    while (true) {
        poll(waker)?.let { return it }
        try {
            woken.receive()
        } catch (e: CancellationException) {
            cancel()
            throw e
        }
    }
}

public suspend fun <T : Any> await(port: Wakeable, interest: Interest, poll: () -> T?): T =
    awaitPoll({}) { waker ->
        port.wakeOn(interest, waker)
        poll()
    }
```

Add `import kotlinx.coroutines.CancellationException` (or
`kotlin.coroutines.cancellation.CancellationException`). Keep the existing KDoc
of `await`, adding one sentence: "It is `awaitPoll` with a poll that registers
[interest] first."

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :ridl-rt-kt-coroutines:test` Expected: PASS, the existing
`AwaitTest` included.

- [ ] **Step 5: README and commit**

In `modules/ridl-rt-kt-coroutines/README.md` "Responsibility", add a paragraph:
"`awaitPoll(cancel, poll)` is the general form: `poll` receives the waker and
registers its own interest, and `cancel` runs once when the coroutine is
cancelled while waiting. A generated `<Iface>AsyncClient` runs each call through
it."

```bash
just fmt
git add modules/ridl-rt-kt-coroutines
git commit -m "feat(ridl-rt-kt-coroutines): add awaitPoll, the suspending twin of blockOn" -m "Refs #7"
```

---

### Task 2: Make the poll face internal

**Files:**

- Modify:
  `modules/ridlc-gen-kotlin/src/main/kotlin/ridl/codegen/kotlin/types/FacesEmitter.kt`
- Modify: `modules/conformance/src/test/resources/faces/cabin.kt`
- Modify: `modules/conformance/src/test/resources/faces/kt-values.kt`
- Modify: `samples/cabin/src/main/kotlin/ridl/sample/cabin/Main.kt`
- Modify: `samples/cabin/src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt`

**Interfaces:**

- Produces, per interface with an event, a command or a query:
  `internal class <Iface>PollClient<P>(port: P)` with today's methods unchanged
  (`temperature()`, `subscribeWarning()`, `unsubscribeWarning()`,
  `nextEvent(): Cabin.Event?`, `setLevel(level): Cabin.SetLevelCorrelation`,
  `average(window): Cabin.AverageCorrelation`, `setLevelAck(c)`,
  `averageReply(c)`); `internal` correlation value classes; and
  `internal fun Cabin.dispatch(handler: Handler, provider: CabinProvider, buffer: ByteBuffer): Int`,
  which now lets a `ReadError` from `handler.nextClaim` propagate.
- A signal-only interface keeps `public class <Iface>Client<P : SignalReader>`.

- [ ] **Step 1: Write the failing test**

In `modules/conformance/src/test/resources/faces/cabin.kt`, replace every
`CabinClient(` with `CabinPollClient(` and the import `veh.cabin.CabinClient`
with `veh.cabin.CabinPollClient`. Append, before `return failures`:

```kotlin
// dispatch lets the handler's read failure through: serve reports it.
val failing = object : ridl.rt.port.Handler by rt {
    override fun nextClaim(out: ByteBuffer): ridl.rt.port.Claim? = throw ReadError.Detached
}
try {
    Cabin.dispatch(failing, provider, buffer)
    failures += "a read failure of the handler reaches dispatch's caller"
} catch (_: ReadError.Detached) {
}
```

Add `import ridl.rt.port.ReadError`. In `kt-values.kt` replace `ProbeClient`
with `ProbePollClient` (import and use). In `samples/cabin` `Main.kt` and
`CoroutineDemo.kt` replace `CabinClient` with `CabinPollClient`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :conformance:test --tests ridl.conformance.FacesTest` Expected:
FAIL to compile the probe, `Unresolved reference 'CabinPollClient'`.

- [ ] **Step 3: Write minimal implementation**

In `FacesEmitter.kt`:

1. Give `client()` a name and a visibility:
   ```kotlin
   private fun client(className: ClassName, pollFace: Boolean): TypeSpec {
       // ... the existing body, with ClassName(pkg, "${name}Client") replaced by className
       val builder = TypeSpec.classBuilder(className)
           .apply { if (pollFace) addModifiers(KModifier.INTERNAL) else visibility() }
           .addKdoc(
               if (pollFace) "The poll face of interface `%L`: a send returns a correlation, and an outcome is read without waiting. Internal: the clients and `serve` are built over it."
               else "The consumer face of interface `%L`, over exactly the ports its interactions need.",
               iface.declared.declared,
           )
   ```
2. In `emit()`:
   ```kotlin
   val waits = events.isNotEmpty() || commands.isNotEmpty() || queries.isNotEmpty()
   if (waits) types += client(ClassName(pkg, "${name}PollClient"), pollFace = true)
   else if (signals.isNotEmpty()) types += client(ClassName(pkg, "${name}Client"), pollFace = false)
   ```
3. In `descriptor()`, add `.addModifiers(KModifier.INTERNAL)` to each
   correlation value class builder.
4. In `dispatch()`: add `.addModifiers(KModifier.INTERNAL)` to the `FunSpec`,
   and replace the claim read line with
   `.addStatement("val claim = handler.nextClaim(buffer) ?: return settled")`.
   Add to its KDoc: "A read failure of the handler is thrown: `serve` reports it
   as `ProviderError.Claim`."
5. In `provider()`, change the KDoc's `[%T.dispatch]` to "driven by `serve` or
   `serveAsync`" (drop the `%T` argument).

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ridlc-gen-kotlin:test :conformance:test :samples:cabin:test`
Expected: PASS. `just demo` still prints the seven lines.

- [ ] **Step 5: Commit**

```bash
just fmt
git add modules/ridlc-gen-kotlin modules/conformance samples/cabin
git commit -m "feat(ridlc-gen-kotlin)!: make the poll face internal" -m "The public client of an interface that waits becomes the internal <Iface>PollClient, the correlation types and dispatch become internal, and dispatch lets a read failure of the handler through for serve to report. A signal-only interface keeps its public <Iface>Client. Refs #7"
```

---

### Task 3: The generated call object

**Files:**

- Modify:
  `modules/ridlc-gen-kotlin/src/main/kotlin/ridl/codegen/kotlin/types/FacesEmitter.kt`
- Create: `modules/conformance/src/test/kotlin/ridl/conformance/ClientsTest.kt`
- Create: `modules/conformance/src/test/resources/clients/cabin.kt`

**Interfaces:**

- Produces, in each generated `Faces.kt` with a command or a query:
  ```kotlin
  internal abstract class InteractionCall<P, T : Any>(
      protected val port: P, iface: InterfaceNo, ord: Ordinal, private val query: Boolean,
      args: ByteBuffer, max: ridl.rt.sample.Duration?,
  ) where P : Caller, P : Clock, P : Wakeable {
      protected abstract fun read(c: Correlation): T?
      fun sent(): Boolean
      fun poll(waker: Waker): T?
      fun cancel()
      fun block(timeout: kotlin.time.Duration?): T      // used by Task 4
      suspend fun await(): T                           // used by Task 5
  }
  internal class CabinSetLevelCall<P>(port: P, level: Level) : InteractionCall<P, Unit>(...)
  internal class CabinAverageCall<P>(port: P, window: Window) : InteractionCall<P, Average>(...)
  ```
  and the file-private helpers `request(allowed, codec, value): ByteBuffer`,
  `deadlineAfter(now: Timestamp, max: ridl.rt.sample.Duration): Timestamp`,
  `deadline(timeout: kotlin.time.Duration?): TimeSource.Monotonic.ValueTimeMark?`.
- Consumes: `awaitPoll` (Task 1), `Member.callDeadline()`, `ClientError`,
  `blockOn`, `Waker`, `Interest` (ridl-rt-kt).

- [ ] **Step 1: Write the failing test**

`ClientsTest.kt`, the harness, mirrors `FacesTest`:

```kotlin
package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path

/**
 * The spec's §8: the clients and `serve` generated for the cabin package,
 * driven over ridl-rt-kt-loopback by the probe of `resources/clients/cabin.kt`.
 */
class ClientsTest {
    @TempDir
    lateinit var work: Path

    private fun sources(): Map<String, String> =
        Harness.capturedRequests("cabin", work.resolve("capture")).values.map(Wire::readRequest).flatMap { r ->
            Generator.generate(r).filesList.map { it.path to it.text }
        }.toMap()

    @Test
    fun `the clients round-trip on the loopback`() {
        val probe = checkNotNull(javaClass.getResource("/clients/cabin.kt")).readText()
        val compiled = Compiler.compile(sources() + ("clients/cabin.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("ridl.conformance.probe.clients.cabin.CabinClientsProbe")
            .getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
```

`resources/clients/cabin.kt`, the probe, with the call-object checks:

```kotlin
@file:JvmName("CabinClientsProbe")

package ridl.conformance.probe.clients.cabin

import ridl.rt.contract.Ordinal
import ridl.rt.error.ClientError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.loopback.Loopback
import ridl.rt.port.SendError
import ridl.rt.sample.Duration
import ridl.rt.task.noopWaker
import veh.cabin.Average
import veh.cabin.Cabin
import veh.cabin.CabinAverageCall
import veh.cabin.CabinProvider
import veh.cabin.CabinSetLevelCall
import veh.cabin.Level
import veh.cabin.Window
import java.nio.ByteBuffer

private val failures = mutableListOf<String>()

private fun expect(label: String, condition: Boolean) {
    if (!condition) failures += label
}

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private inline fun <reified E : Throwable> expectThrows(label: String, block: () -> Unit): E? = try {
    block()
    failures += "$label: nothing was thrown"
    null
} catch (e: Throwable) {
    if (e is E) e else { failures += "$label: threw $e"; null }
}

/** Commands [rt] accepts before `SendError.Busy`, sent raw; the table holds `Loopback.SLOTS`. */
private fun sendsUntilBusy(rt: Loopback): Int {
    var sent = 0
    while (true) {
        try { rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0)) } catch (_: SendError.Busy) { return sent }
        sent += 1
    }
}

/** Fills the table with settled calls nobody forgot, and returns their correlations. */
private fun fill(rt: Loopback) = List(Loopback.SLOTS) {
    val c = rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0))
    rt.settle(rt.nextClaim(ByteBuffer.allocate(64))!!.id, Result.success(ByteBuffer.allocate(0)))
    c
}

private class Recorder : CabinProvider {
    val levels = mutableListOf<Level>()
    override fun setLevel(level: Level) { levels += level }
    override fun average(window: Window): Average = Average.of(250)
}

private val waker = noopWaker()

fun probe(): List<String> {
    callObjects()
    return failures
}

private fun callObjects() {
    // A require that fails sends nothing.
    Loopback(Cabin.catalog).let { rt ->
        val e = expectThrows<ClientError.Send>("a failing require throws Send") { CabinSetLevelCall(rt, Level.of(100)) }
        expectEqual("with PreconditionFailed", SendError.Contract(Contract.PreconditionFailed), e?.error)
        expectEqual("and nothing is sent", Loopback.SLOTS, sendsUntilBusy(rt))
    }
    // Busy, then a slot freed, then the call sent.
    Loopback(Cabin.catalog).let { rt ->
        val calls = fill(rt)
        val call = CabinSetLevelCall(rt, Level.of(1))
        expect("a full table leaves the call unsent", !call.sent())
        expectEqual("an unsent call waits", null, call.poll(waker))
        rt.forget(calls[0])
        expectEqual("the freed slot lets the poll send it", null, call.poll(waker))
        expect("and it is sent", call.sent())
    }
    // The deadline, unsent: setLevel's max is 50 ms.
    Loopback(Cabin.catalog).let { rt ->
        fill(rt)
        val call = CabinSetLevelCall(rt, Level.of(1))
        rt.advance(Duration(50_000))
        expectEqual("a call at exactly max is within it", null, call.poll(waker))
        rt.advance(Duration(1))
        val e = expectThrows<ClientError.Send>("an unsent call past its deadline throws Send") { call.poll(waker) }
        expectEqual("with Busy", SendError.Busy, e?.error)
    }
    // The deadline, sent: a command is Undelivered, a query Timeout, and the slot comes back.
    Loopback(Cabin.catalog).let { rt ->
        val command = CabinSetLevelCall(rt, Level.of(1))
        val query = CabinAverageCall(rt, Window.of(10))
        rt.advance(Duration(200_001))
        expectEqual("a sent command past its deadline", Transport.Undelivered,
            expectThrows<ClientError.Call>("a sent command past its deadline throws Call") { command.poll(waker) }?.error)
        expectEqual("a sent query past its deadline", Transport.Timeout,
            expectThrows<ClientError.Call>("a sent query past its deadline throws Call") { query.poll(waker) }?.error)
        expectEqual("both calls were forgotten", Loopback.SLOTS, sendsUntilBusy(rt))
    }
    // An outcome taken forgets the call, and a finished call polled again throws.
    Loopback(Cabin.catalog).let { rt ->
        val call = CabinAverageCall(rt, Window.of(10))
        val recorder = Recorder()
        Cabin.dispatch(rt, recorder, ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE))
        expectEqual("the reply is returned", Average.of(250), call.poll(waker))
        expectEqual("the call was forgotten", Loopback.SLOTS, sendsUntilBusy(rt))
        expectThrows<IllegalStateException>("a finished call polled again throws") { call.poll(waker) }
    }
    // cancel forgets a sent call once, and a cancelled call polled again throws.
    Loopback(Cabin.catalog).let { rt ->
        val call = CabinSetLevelCall(rt, Level.of(1))
        call.cancel()
        call.cancel()
        expectEqual("cancel forgot the call", Loopback.SLOTS, sendsUntilBusy(rt))
        expectThrows<IllegalStateException>("a cancelled call polled again throws") { call.poll(waker) }
    }
}
```

`ClientError` variants carry `error` (Task 1 of #6:
`ClientError.Send(val error: SendError)`, `Call(val error: CallError)`,
`Read(val error: ReadError)`).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :conformance:test --tests ridl.conformance.ClientsTest`
Expected: FAIL, the probe does not compile:
`Unresolved reference 'CabinSetLevelCall'`.

- [ ] **Step 3: Write minimal implementation**

In `FacesEmitter.kt`, add the class names:

```kotlin
private val CLOCK = ClassName("$RT.port", "Clock")
private val WAKEABLE = ClassName("$RT.port", "Wakeable")
private val INTEREST = ClassName("$RT.port", "Interest")
private val CALL_ERROR = ClassName("$RT.error", "CallError")
private val CLIENT_ERROR = ClassName("$RT.error", "ClientError")
private val TIMESTAMP = ClassName("$RT.sample", "Timestamp")
private val WAKER = ClassName("$RT.task", "Waker")
private val BLOCK_ON = MemberName("$RT.task", "blockOn")
private val AWAIT_POLL = MemberName("$RT.coroutines", "awaitPoll")
private val TIME_DURATION = ClassName("kotlin.time", "Duration")
private val TIME_SOURCE = ClassName("kotlin.time", "TimeSource")
private val VALUE_TIME_MARK = TIME_SOURCE.nestedClass("Monotonic").nestedClass("ValueTimeMark")
```

(import `com.squareup.kotlinpoet.MemberName`). Track what the file needs: a
`private var waits = false` and a `private var calls = false` on the emitter,
set in `Face.emit()` from
`events.isNotEmpty() || commands.isNotEmpty() ||
queries.isNotEmpty()` and
`commands.isNotEmpty() || queries.isNotEmpty()`. In `emit()`, after
`addHelpers()`: when `waits`, call `addWaitHelpers()`; when `calls`, call
`addCallHelpers()`.

`addWaitHelpers()` emits the wall-clock deadline both clients and `serve` use,
including an event-only interface's blocking client:

```kotlin
private fun addWaitHelpers() {
    file.addFunction(
        FunSpec.builder("deadline").addModifiers(KModifier.PRIVATE)
            .addKdoc("The wall-clock point [timeout] after now, or `null` for no bound.")
            .addParameter("timeout", TIME_DURATION.copy(nullable = true)).returns(VALUE_TIME_MARK.copy(nullable = true))
            .addStatement("return timeout?.let { %T.Monotonic.markNow() + it }", TIME_SOURCE)
            .build(),
    )
}
```

`addCallHelpers()` emits the two call helpers and the base class:

```kotlin
private fun addCallHelpers() {
    val t = TypeVariableName("T")
    val v = TypeVariableName("V")
    file.addFunction(
        FunSpec.builder("request").addModifiers(KModifier.PRIVATE).addTypeVariable(t).addTypeVariable(v)
            .addKdoc("[value] encoded, or `ClientError.Send(SendError.Contract(Contract.PreconditionFailed))` when its `require` failed: nothing is sent.")
            .addParameter("allowed", BOOLEAN).addParameter("codec", PAYLOAD.parameterizedBy(t, v)).addParameter("value", t)
            .returns(BYTE_BUFFER)
            .beginControlFlow("if (!allowed)")
            .addStatement("throw %T.Send(%T.Contract(%T.PreconditionFailed))", CLIENT_ERROR, SEND_ERROR, CONTRACT)
            .endControlFlow()
            .addStatement("return encoded(codec, value)")
            .build(),
    )
    file.addFunction(
        FunSpec.builder("deadlineAfter").addModifiers(KModifier.PRIVATE)
            .addKdoc("[now] plus [max], saturating at the largest timestamp.")
            .addParameter("now", TIMESTAMP).addParameter("max", DURATION).returns(TIMESTAMP)
            .addStatement("return %T(if (Long.MAX_VALUE - max.micros < now.micros) Long.MAX_VALUE else now.micros + max.micros)", TIMESTAMP)
            .build(),
    )
    file.addType(interactionCall())
}
```

`interactionCall()` builds the base class; its bodies are written with `addCode`
over the class names above:

```kotlin
private fun interactionCall(): TypeSpec {
    val p = TypeVariableName("P", listOf(CALLER, CLOCK, WAKEABLE))
    val t = TypeVariableName("T", ANY)
    val correlationOrNull = CORRELATION.copy(nullable = true)
    return TypeSpec.classBuilder("InteractionCall").addModifiers(KModifier.INTERNAL, KModifier.ABSTRACT)
        .addKdoc(
            "One command or query in flight: the counterpart of the Rust named future. It is sent when it is " +
                "created; `SendError.Busy` leaves it unsent, to retry on a later poll. Each [poll] registers " +
                "its interest, reads the port once and returns: `null` while waiting, the outcome, or a thrown " +
                "`ClientError`. Past its deadline, the port's `now()` plus the member's `max`, an unsent call " +
                "throws `Send(Busy)` and a sent one is forgotten and throws `Call(Undelivered)` for a command or " +
                "`Call(Timeout)` for a query. A call that finished forgets itself, and polling it again throws " +
                "`IllegalStateException`.",
        )
        .addTypeVariable(p).addTypeVariable(t)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("port", p).addParameter("iface", INTERFACE_NO).addParameter("ord", ORDINAL)
                .addParameter("query", BOOLEAN).addParameter("args", BYTE_BUFFER)
                .addParameter("max", DURATION.copy(nullable = true)).build(),
        )
        .addProperty(PropertySpec.builder("port", p, KModifier.PROTECTED).initializer("port").build())
        .addProperty(PropertySpec.builder("query", BOOLEAN, KModifier.PRIVATE).initializer("query").build())
        .addProperty(
            PropertySpec.builder("send", LambdaTypeName.get(returnType = CORRELATION), KModifier.PRIVATE)
                .initializer("if (query) { { port.query(iface, ord, args) } } else { { port.command(iface, ord, args) } }").build(),
        )
        .addProperty(
            PropertySpec.builder("deadline", TIMESTAMP.copy(nullable = true), KModifier.PRIVATE)
                .initializer("max?.let { deadlineAfter(port.now(), it) }").build(),
        )
        .addProperty(PropertySpec.builder("correlation", correlationOrNull, KModifier.PRIVATE).mutable().initializer("null").build())
        .addProperty(PropertySpec.builder("done", BOOLEAN, KModifier.PRIVATE).mutable().initializer("false").build())
        .addInitializerBlock(CodeBlock.of("trySend()\n"))
        .addFunction(
            FunSpec.builder("read").addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                .addKdoc("The outcome of the call sent under [c], or `null` while it is not known; throws `ClientError` for a failed one.")
                .addParameter("c", CORRELATION).returns(t.copy(nullable = true)).build(),
        )
        .addFunction(FunSpec.builder("sent").returns(BOOLEAN).addStatement("return correlation != null").build())
        .addFunction(
            FunSpec.builder("poll").addParameter("waker", WAKER).returns(t.copy(nullable = true))
                .addStatement("check(!done) { %S }", "a call that finished is polled again")
                .beginControlFlow("if (correlation == null)")
                .addStatement("port.wakeOn(%T.Slot, waker)", INTEREST)
                .beginControlFlow("if (!trySend())")
                .beginControlFlow("if (expired())")
                .addStatement("done = true")
                .addStatement("throw %T.Send(%T.Busy)", CLIENT_ERROR, SEND_ERROR)
                .endControlFlow()
                .addStatement("return null")
                .endControlFlow()
                .endControlFlow()
                .addStatement("val c = correlation!!")
                .addStatement("port.wakeOn(%T.Outcome(c), waker)", INTEREST)
                .addStatement("val result = try { read(c) } catch (e: %T) { finish(c); throw e }", CLIENT_ERROR)
                .beginControlFlow("if (result != null)")
                .addStatement("finish(c)")
                .addStatement("return result")
                .endControlFlow()
                .beginControlFlow("if (expired())")
                .addStatement("finish(c)")
                .addStatement("throw %T.Call(if (query) %T.Timeout else %T.Undelivered)", CLIENT_ERROR, TRANSPORT, TRANSPORT)
                .endControlFlow()
                .addStatement("return null")
                .build(),
        )
        .addFunction(
            FunSpec.builder("cancel")
                .addKdoc("Forgets a sent call that has no outcome yet, once; a call that finished is left alone.")
                .beginControlFlow("if (!done)")
                .addStatement("done = true")
                .addStatement("correlation?.let { port.forget(it) }")
                .endControlFlow()
                .build(),
        )
        .addFunction(
            FunSpec.builder("block").addParameter("timeout", TIME_DURATION.copy(nullable = true)).returns(t)
                .addKdoc("Waits for the outcome on this thread, at most [timeout]; when it passes, cancels the call and throws what the call's state says.")
                .addStatement("val result = %M(deadline(timeout)) { poll(it) }", BLOCK_ON)
                .beginControlFlow("if (result != null)")
                .addStatement("return result")
                .endControlFlow()
                .addStatement("val wasSent = sent()")
                .addStatement("cancel()")
                .addStatement(
                    "throw if (wasSent) %T.Call(if (query) %T.Timeout else %T.Undelivered) else %T.Send(%T.Busy)",
                    CLIENT_ERROR, TRANSPORT, TRANSPORT, CLIENT_ERROR, SEND_ERROR,
                )
                .build(),
        )
        .addFunction(
            FunSpec.builder("await").addModifiers(KModifier.SUSPEND).returns(t)
                .addKdoc("Suspends until the outcome; a cancelled coroutine cancels the call.")
                .addStatement("return %M(::cancel, ::poll)", AWAIT_POLL)
                .build(),
        )
        .addFunction(
            FunSpec.builder("trySend").addModifiers(KModifier.PRIVATE).returns(BOOLEAN)
                .beginControlFlow("return try")
                .addStatement("correlation = send()")
                .addStatement("true")
                .nextControlFlow("catch (e: %T)", SEND_ERROR)
                .beginControlFlow("if (e != %T.Busy)", SEND_ERROR)
                .addStatement("done = true")
                .addStatement("throw %T.Send(e)", CLIENT_ERROR)
                .endControlFlow()
                .addStatement("false")
                .endControlFlow()
                .build(),
        )
        .addFunction(
            FunSpec.builder("expired").addModifiers(KModifier.PRIVATE).returns(BOOLEAN)
                .addStatement("val at = deadline ?: return false")
                .addStatement("return port.now() > at")
                .build(),
        )
        .addFunction(
            FunSpec.builder("finish").addModifiers(KModifier.PRIVATE).addParameter("c", CORRELATION)
                .addStatement("done = true")
                .addStatement("port.forget(c)")
                .build(),
        )
        .build()
}
```

(import `com.squareup.kotlinpoet.ANY` and
`com.squareup.kotlinpoet.LambdaTypeName`. The property initializers are declared
before the `init` block, so `send`, `deadline`, `correlation` and `done` are set
when `trySend()` runs.)

Per call, in `Face.emit()` after the interaction descriptors, when the interface
has a call, add `types += call(m)` for each of `commands + queries`:

```kotlin
private fun callClass(m: Member): ClassName = ClassName(pkg, "$name${m.camel}Call")

private fun call(m: Member): TypeSpec {
    val (param, arg) = argument(m)
    val value = param.name.camel.replaceFirstChar(Char::lowercaseChar)
    val query = m.interaction.hasQuery()
    val result = if (query) reply(m).type else UNIT
    val p = TypeVariableName("P", listOf(CALLER, CLOCK, WAKEABLE))
    val read = FunSpec.builder("read").addModifiers(KModifier.OVERRIDE).addParameter("c", CORRELATION).returns(result.copy(nullable = true))
    if (query) {
        val reply = reply(m)
        read.addStatement("val buf = %T.allocate(%T.maxSize)", BYTE_BUFFER, reply.codec)
            .addStatement("val outcome = try { port.reply(c, buf) } catch (e: %T) { throw %T.Read(e) } ?: return null", READ_ERROR, CLIENT_ERROR)
            .addStatement("outcome.onFailure { throw %T.Call(it as %T) }", CLIENT_ERROR, CALL_ERROR)
            .addStatement("return callOutcome(%T, buf.flip()).getOrElse { throw %T.Call(it as %T) }", reply.codec, CLIENT_ERROR, CALL_ERROR)
    } else {
        read.addStatement("return port.ack(c)?.getOrElse { throw %T.Call(it as %T) }", CLIENT_ERROR, CALL_ERROR)
    }
    return TypeSpec.classBuilder(callClass(m)).addModifiers(KModifier.INTERNAL)
        .addKdoc("One %L `%L` in flight.", if (query) "query" else "command", m.declared)
        .addTypeVariable(p)
        .primaryConstructor(FunSpec.constructorBuilder().addParameter("port", p).addParameter(value, arg.type).build())
        .superclass(ClassName(pkg, "InteractionCall").parameterizedBy(p, result))
        .addSuperclassConstructorParameter("port")
        .addSuperclassConstructorParameter("%L", number())
        .addSuperclassConstructorParameter("%L", ordinal(m))
        .addSuperclassConstructorParameter("%L", query)
        .addSuperclassConstructorParameter("request(%T.require(%L), %T, %L)", m.descriptor, value, arg.codec, value)
        .addSuperclassConstructorParameter("%T.member.callDeadline()", m.descriptor)
        .addFunction(read.build())
        .build()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ridlc-gen-kotlin:test :conformance:test` Expected: PASS,
`ClientsTest` included.

- [ ] **Step 5: Mutations, then commit**

Each must turn `ClientsTest` red; revert after each:

1. In `interactionCall()`'s `expired`, `port.now() > at` → `port.now() >= at`
   ("a call at exactly max is within it").
2. In `finish`, drop `port.forget(c)` ("the call was forgotten").
3. In `cancel`, drop `correlation?.let { port.forget(it) }` ("cancel forgot the
   call").

```bash
just fmt
git add modules/ridlc-gen-kotlin modules/conformance
git commit -m "feat(ridlc-gen-kotlin): generate a call object per command and query" -m "InteractionCall carries the call rules of the design's §4; <Iface><Member>Call reads its outcome. Refs #7"
```

---

### Task 4: The blocking client and `serve`

**Files:**

- Modify:
  `modules/ridlc-gen-kotlin/src/main/kotlin/ridl/codegen/kotlin/types/FacesEmitter.kt`
- Modify: `modules/conformance/src/test/resources/clients/cabin.kt`

**Interfaces:**

- Consumes: `InteractionCall.block(timeout)`, `deadline(timeout)` (Task 3),
  `<Iface>PollClient` (Task 2), `blockOn`.
- Produces:
  - `public class CabinClient<P>(port: P, public var timeout: kotlin.time.Duration? = null) where P : SignalReader, P : EventSource, P : Caller, P : Clock, P : Wakeable`
    with `temperature(): Sample<Temperature>`, `subscribeWarning()`,
    `unsubscribeWarning()`, `nextEvent(): Cabin.Event?`,
    `setLevel(level: Level)`, `average(window: Window): Average`.
  - On the descriptor object:
    `public fun <H> serve(handler: H, provider: CabinProvider, timeout: kotlin.time.Duration? = null) where H : Handler, H : Wakeable`,
    and the private `claimServed(handler: Handler)` and
    `servePass(handler: H, provider, buffer, waker): Nothing?` that Task 5
    reuses.

- [ ] **Step 1: Write the failing test**

In `resources/clients/cabin.kt`, add `blocking()` to `probe()` after
`callObjects()`, with these imports: `kotlin.concurrent.thread`,
`kotlin.time.Duration.Companion.milliseconds`,
`kotlin.time.Duration.Companion.seconds`, `ridl.rt.error.ProviderError`,
`ridl.rt.port.Handler`, `ridl.rt.port.ReadError`, `ridl.rt.port.ServeError`,
`ridl.rt.port.Wakeable`, `veh.cabin.CabinClient`, `veh.cabin.CabinPublisher`,
`veh.cabin.Health`, `veh.cabin.Temperature`, `veh.cabin.Warning`.

```kotlin
private fun blocking() {
    // The round trips, with the provider on a thread of its own.
    Loopback(Cabin.catalog).let { rt ->
        val recorder = Recorder()
        val handler = rt.handler()
        // serve returns at each short timeout, so the flag stops it within one.
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val provider = thread { while (running.get()) Cabin.serve(handler, recorder, 100.milliseconds) }
        val client = CabinClient(rt, timeout = 5.seconds)
        CabinPublisher(rt).apply { temperature(Temperature.of(21)); commit() }
        expectEqual("a signal reads", Temperature.of(21), client.temperature().value)
        client.setLevel(Level.of(42))
        expectEqual("a query replies", Average.of(250), client.average(Window.of(10)))
        expectEqual("the command ran before the query was served", listOf(Level.of(42)), synchronized(recorder) { recorder.levels.toList() })
        client.subscribeWarning()
        CabinPublisher(rt).warning(Warning(Level.of(5), Health.WARN))
        expect("an event is received", client.nextEvent() is Cabin.Event.Warning)
        running.set(false)
        provider.join()
    }
    // A timeout too large to represent waits with no bound and returns.
    Loopback(Cabin.catalog).let { rt ->
        val handler = rt.handler()
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val provider = thread { while (running.get()) Cabin.serve(handler, Recorder(), 100.milliseconds) }
        expectEqual("an infinite timeout still returns the reply", Average.of(250),
            CabinClient(rt, timeout = kotlin.time.Duration.INFINITE).average(Window.of(10)))
        running.set(false)
        provider.join()
    }
    // The timeout: sent, unsent, and an event.
    Loopback(Cabin.catalog).let { rt ->
        val client = CabinClient(rt, timeout = 50.milliseconds)
        expectEqual("a sent command past the timeout", Transport.Undelivered,
            expectThrows<ClientError.Call>("a sent command past the timeout throws Call") { client.setLevel(Level.of(1)) }?.error)
        expectEqual("a sent query past the timeout", Transport.Timeout,
            expectThrows<ClientError.Call>("a sent query past the timeout throws Call") { client.average(Window.of(10)) }?.error)
        expectEqual("both were forgotten", Loopback.SLOTS, sendsUntilBusy(rt))
    }
    Loopback(Cabin.catalog).let { rt ->
        fill(rt)
        val client = CabinClient(rt, timeout = 50.milliseconds)
        expectEqual("an unsent call past the timeout", SendError.Busy,
            expectThrows<ClientError.Send>("an unsent call past the timeout throws Send") { client.setLevel(Level.of(1)) }?.error)
        client.subscribeWarning()
        expectEqual("no event before the timeout", null, client.nextEvent())
    }
    // serve's two failures, and a provider's own exception.
    Loopback(Cabin.catalog).let { rt ->
        val refusing = object : Handler by rt, Wakeable by rt {
            override fun serve(iface: ridl.rt.contract.InterfaceNo, ords: List<Ordinal>) = throw ServeError.NotOwner
        }
        expectEqual("a refused serve", ServeError.NotOwner,
            expectThrows<ProviderError.Serve>("a refused serve throws Serve") { Cabin.serve(refusing, Recorder()) }?.error)
        val failing = object : Handler by rt, Wakeable by rt {
            override fun nextClaim(out: ByteBuffer): ridl.rt.port.Claim? = throw ReadError.Detached
        }
        expectEqual("a failed claim read", ReadError.Detached,
            expectThrows<ProviderError.Claim>("a failed claim read throws Claim") { Cabin.serve(failing, Recorder()) }?.error)
        val broken = object : CabinProvider {
            override fun setLevel(level: Level) = Unit
            override fun average(window: Window): Average = throw IllegalStateException("the provider's own failure")
        }
        rt.query(Cabin.number, Ordinal(4u), ByteBuffer.allocate(veh.cabin.WindowCodec.maxSize).also { veh.cabin.WindowCodec.encode(Window.of(10), it) }.flip())
        expectThrows<IllegalStateException>("a provider's exception leaves serve unchanged") { Cabin.serve(rt, broken, 1.seconds) }
    }
}
```

Mark `Recorder.setLevel` as `synchronized(this) { levels += level }` now that it
runs on the provider thread.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :conformance:test --tests ridl.conformance.ClientsTest`
Expected: FAIL to compile: `Unresolved reference 'CabinClient'` (the class is
`CabinPollClient` since Task 2) and `Unresolved reference 'serve'`.

- [ ] **Step 3: Write minimal implementation**

In `FacesEmitter.kt` add
`private val SERVE_ERROR = ClassName("$RT.port", "ServeError")` and
`private val PROVIDER_ERROR = ClassName("$RT.error", "ProviderError")`.

In `emit()`, after the poll client, when `waits`: `types += blockingClient()`.

```kotlin
/** The ports a waiting client needs (RA-19): a signal reads, an event waits, a call sends and waits on the clock. */
private fun waitingBounds(): List<TypeName> = buildList {
    if (signals.isNotEmpty()) add(SIGNAL_READER)
    if (events.isNotEmpty()) add(EVENT_SOURCE)
    if (commands.isNotEmpty() || queries.isNotEmpty()) { add(CALLER); add(CLOCK) }
    add(WAKEABLE)
}

/** The signal reads and subscriptions, delegated to the poll face, which both clients share. */
private fun TypeSpec.Builder.delegatedReads(): TypeSpec.Builder = apply {
    for (m in signals) {
        addFunction(FunSpec.builder(m.method).returns(SAMPLE.parameterizedBy(signalPayload(m).type))
            .addKdoc("Reads signal `%L`, as the runtime resolved it. It does not wait.", m.declared)
            .addStatement("return poll.%L()", m.method).build())
    }
    for (m in events) {
        addFunction(FunSpec.builder("subscribe${m.camel}").addKdoc("Starts delivery of event `%L`.", m.declared)
            .addStatement("poll.subscribe%L()", m.camel).build())
        addFunction(FunSpec.builder("unsubscribe${m.camel}").addKdoc("Stops delivery of event `%L`.", m.declared)
            .addStatement("poll.unsubscribe%L()", m.camel).build())
    }
}

private fun blockingClient(): TypeSpec {
    val p = TypeVariableName("P", waitingBounds())
    val poll = ClassName(pkg, "${name}PollClient")
    val builder = TypeSpec.classBuilder(ClassName(pkg, "${name}Client")).visibility()
        .addKdoc(
            "The blocking client of interface `%L`: each call waits on this thread for its outcome, at most " +
                "[timeout] when it is set, and throws `ClientError` for a failed call. Use it from one thread at a time.",
            iface.declared.declared,
        )
        .addTypeVariable(p)
        .primaryConstructor(
            FunSpec.constructorBuilder().addParameter("port", p)
                .addParameter(ParameterSpec.builder("timeout", TIME_DURATION.copy(nullable = true)).defaultValue("null").build())
                .build(),
        )
        .addProperty(PropertySpec.builder("port", p, KModifier.PRIVATE).initializer("port").build())
        .addProperty(
            PropertySpec.builder("timeout", TIME_DURATION.copy(nullable = true)).mutable().initializer("timeout")
                .addKdoc("The longest a call or [nextEvent] waits, or `null` for no bound.").build(),
        )
        .addProperty(PropertySpec.builder("poll", poll.parameterizedBy(p), KModifier.PRIVATE).initializer("%T(port)", poll).build())
        .delegatedReads()
    if (events.isNotEmpty()) {
        builder.addFunction(
            FunSpec.builder("nextEvent").returns(self.nestedClass("Event").copy(nullable = true))
                .addKdoc("Waits for the next occurrence of any subscribed event of `%L`, or returns `null` at [timeout].", iface.declared.declared)
                .addStatement("return %M(deadline(timeout)) { waker ->", BLOCK_ON)
                .addStatement("  port.wakeOn(%T.Event(%L), waker)", INTEREST, number())
                .addStatement("  poll.nextEvent()")
                .addStatement("}")
                .build(),
        )
    }
    for (m in commands + queries) {
        val (param, arg) = argument(m)
        val value = param.name.camel.replaceFirstChar(Char::lowercaseChar)
        val f = FunSpec.builder(m.method).addParameter(value, arg.type)
            .addKdoc("Calls %L `%L` and waits for its outcome.", if (m.interaction.hasQuery()) "query" else "command", m.declared)
        if (m.interaction.hasQuery()) f.returns(reply(m).type).addStatement("return %T(port, %L).block(timeout)", callClass(m), value)
        else f.addStatement("%T(port, %L).block(timeout)", callClass(m), value)
        builder.addFunction(f.build())
    }
    return builder.build()
}
```

(import `com.squareup.kotlinpoet.ParameterSpec`.)

In `descriptor()`, when the interface has a call, add after `dispatch()`:

```kotlin
builder.addFunction(claimServed()).addFunction(servePass()).addFunction(serve())

private fun claimServed(): FunSpec = FunSpec.builder("claimServed").addModifiers(KModifier.PRIVATE)
    .addParameter("handler", HANDLER)
    .addStatement(
        "try { handler.serve(number, listOf(%L)) } catch (e: %T) { throw %T.Serve(e) }",
        (commands + queries).map { ordinal(it) }.joinToCode(", "), SERVE_ERROR, PROVIDER_ERROR,
    )
    .build()

private fun servePass(): FunSpec {
    val h = TypeVariableName("H", listOf(HANDLER, WAKEABLE))
    return FunSpec.builder("servePass").addModifiers(KModifier.PRIVATE).addTypeVariable(h)
        .addParameter("handler", h).addParameter("provider", ClassName(pkg, "${name}Provider"))
        .addParameter("buffer", BYTE_BUFFER).addParameter("waker", WAKER)
        .returns(NOTHING.copy(nullable = true))
        .addStatement("handler.wakeOn(%T.Claim(number), waker)", INTEREST)
        .addStatement("try { dispatch(handler, provider, buffer) } catch (e: %T) { throw %T.Claim(e) }", READ_ERROR, PROVIDER_ERROR)
        .addStatement("return null")
        .build()
}

private fun serve(): FunSpec {
    val h = TypeVariableName("H", listOf(HANDLER, WAKEABLE))
    return FunSpec.builder("serve").addTypeVariable(h)
        .addKdoc(
            "Serves interface `%L`'s calls on this thread: registers its members with [handler], then settles each " +
                "claim as it arrives. Returns when [timeout] passes; with no timeout it returns only by throwing " +
                "`ProviderError`: `Serve` when [handler] refuses the members, `Claim` when a claim read fails, every " +
                "claim settled before it staying settled. An exception [provider] throws is thrown unchanged.",
            iface.declared.declared,
        )
        .addParameter("handler", h).addParameter("provider", ClassName(pkg, "${name}Provider"))
        .addParameter(ParameterSpec.builder("timeout", TIME_DURATION.copy(nullable = true)).defaultValue("null").build())
        .addStatement("claimServed(handler)")
        .addStatement("val buffer = %T.allocate(MAX_BUFFER_SIZE)", BYTE_BUFFER)
        .addStatement("%M<Unit>(deadline(timeout)) { waker -> servePass(handler, provider, buffer, waker) }", BLOCK_ON)
        .build()
}
```

(import `com.squareup.kotlinpoet.NOTHING`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ridlc-gen-kotlin:test :conformance:test` Expected: PASS.

- [ ] **Step 5: Mutation, then commit**

In `InteractionCall.block`, swap the two thrown errors (sent → `Send(Busy)`);
`ClientsTest` must fail on "a sent command past the timeout". Revert.

```bash
just fmt
git add modules/ridlc-gen-kotlin modules/conformance
git commit -m "feat(ridlc-gen-kotlin): generate the blocking client and serve" -m "Refs #7"
```

---

### Task 5: The async client and `serveAsync`

**Files:**

- Modify:
  `modules/ridlc-gen-kotlin/src/main/kotlin/ridl/codegen/kotlin/types/FacesEmitter.kt`
- Modify: `modules/conformance/src/test/resources/clients/cabin.kt`

**Interfaces:**

- Consumes: `InteractionCall.await()`, `claimServed`, `servePass` (Tasks 3, 4),
  `awaitPoll` (Task 1).
- Produces:
  - `public class CabinAsyncClient<P>(port: P)` with the same bounds as the
    blocking client and `temperature()`, `subscribeWarning()`,
    `unsubscribeWarning()`, `suspend fun nextEvent(): Cabin.Event`,
    `suspend fun setLevel(level: Level)`,
    `suspend fun average(window: Window): Average`.
  - On the descriptor object:
    `public suspend fun <H> serveAsync(handler: H, provider: CabinProvider): Nothing where H : Handler, H : Wakeable`.

- [ ] **Step 1: Write the failing test**

Add `async()` to `probe()` after `blocking()`, with imports
`kotlinx.coroutines.CoroutineStart`, `kotlinx.coroutines.Dispatchers`,
`kotlinx.coroutines.async`, `kotlinx.coroutines.cancelAndJoin`,
`kotlinx.coroutines.launch`, `kotlinx.coroutines.runBlocking`,
`kotlinx.coroutines.withTimeout`, `veh.cabin.CabinAsyncClient`:

```kotlin
private fun async() = runBlocking {
    withTimeout(10_000) {
        // The round trips, with serveAsync on another thread.
        Loopback(Cabin.catalog).let { rt ->
            val recorder = Recorder()
            val handler = rt.handler()
            val provider = launch(Dispatchers.Default) { Cabin.serveAsync(handler, recorder) }
            val client = CabinAsyncClient(rt)
            client.setLevel(Level.of(42))
            expectEqual("a query replies", Average.of(250), client.average(Window.of(10)))
            expectEqual("the command ran", listOf(Level.of(42)), synchronized(recorder) { recorder.levels.toList() })
            client.subscribeWarning()
            CabinPublisher(rt).warning(Warning(Level.of(5), Health.WARN))
            expect("an event is received", client.nextEvent() is Cabin.Event.Warning)
            provider.cancelAndJoin()
            expect("a cancelled serveAsync ends", provider.isCancelled)
        }
        // A cancelled coroutine forgets its call (#7's Done when).
        Loopback(Cabin.catalog).let { rt ->
            val call = launch(start = CoroutineStart.UNDISPATCHED) { CabinAsyncClient(rt).setLevel(Level.of(1)) }
            // Count the free slots without keeping them: send until Busy, then forget each.
            val counted = mutableListOf<ridl.rt.port.Correlation>()
            try { while (true) counted += rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0)) } catch (_: SendError.Busy) {}
            expectEqual("the waiting call holds a slot", Loopback.SLOTS - 1, counted.size)
            counted.forEach(rt::forget)
            call.cancelAndJoin()
            expectEqual("the cancelled call gave its slot back", Loopback.SLOTS, sendsUntilBusy(rt))
        }
        // A cancelled call still unsent forgets nothing and ends quietly.
        Loopback(Cabin.catalog).let { rt ->
            fill(rt)
            val call = launch(start = CoroutineStart.UNDISPATCHED) { CabinAsyncClient(rt).setLevel(Level.of(1)) }
            call.cancelAndJoin()
            expect("an unsent call cancelled ends", call.isCancelled)
            expectEqual("and the table is as full as before", 0, sendsUntilBusy(rt))
        }
        // One call at a time per client.
        Loopback(Cabin.catalog).let { rt ->
            val client = CabinAsyncClient(rt)
            val first = async(start = CoroutineStart.UNDISPATCHED) { client.setLevel(Level.of(1)) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { client.average(Window.of(10)) }
            val handler = rt.handler()
            val claim = handler.nextClaim(ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE))
            expect("the first call is sent", claim != null)
            expect("the second waits for the first", handler.nextClaim(ByteBuffer.allocate(Cabin.MAX_BUFFER_SIZE)) == null)
            handler.settle(claim!!.id, Result.success(ByteBuffer.allocate(0)))
            first.await()
            val recorder = Recorder()
            val provider = launch(Dispatchers.Default) { Cabin.serveAsync(handler, recorder) }
            expectEqual("then the second is sent and replied", Average.of(250), second.await())
            provider.cancelAndJoin()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :conformance:test --tests ridl.conformance.ClientsTest`
Expected: FAIL to compile: `Unresolved reference 'CabinAsyncClient'`.

- [ ] **Step 3: Write minimal implementation**

Add `private val MUTEX = ClassName("kotlinx.coroutines.sync", "Mutex")` and
`private val WITH_LOCK = MemberName("kotlinx.coroutines.sync", "withLock")`. In
`emit()`, when `waits`, `types += asyncClient()`; when the interface has a call,
add `serveAsync()` to the descriptor beside `serve()`.

```kotlin
private fun asyncClient(): TypeSpec {
    val p = TypeVariableName("P", waitingBounds())
    val poll = ClassName(pkg, "${name}PollClient")
    val builder = TypeSpec.classBuilder(ClassName(pkg, "${name}AsyncClient")).visibility()
        .addKdoc(
            "The suspending client of interface `%L`: each call suspends until its outcome and throws `ClientError` " +
                "for a failed one; a cancelled call is forgotten. Calls run one at a time: a second concurrent call " +
                "waits for the first, so use a second client over a second caller handle for concurrency.",
            iface.declared.declared,
        )
        .addTypeVariable(p)
        .primaryConstructor(FunSpec.constructorBuilder().addParameter("port", p).build())
        .addProperty(PropertySpec.builder("port", p, KModifier.PRIVATE).initializer("port").build())
        .addProperty(PropertySpec.builder("poll", poll.parameterizedBy(p), KModifier.PRIVATE).initializer("%T(port)", poll).build())
        .delegatedReads()
    if (commands.isNotEmpty() || queries.isNotEmpty()) {
        builder.addProperty(PropertySpec.builder("calls", MUTEX, KModifier.PRIVATE).initializer("%T()", MUTEX).build())
    }
    if (events.isNotEmpty()) {
        builder.addFunction(
            FunSpec.builder("nextEvent").addModifiers(KModifier.SUSPEND).returns(self.nestedClass("Event"))
                .addKdoc("Suspends until the next occurrence of any subscribed event of `%L`.", iface.declared.declared)
                .addStatement("return %M({}) { waker ->", AWAIT_POLL)
                .addStatement("  port.wakeOn(%T.Event(%L), waker)", INTEREST, number())
                .addStatement("  poll.nextEvent()")
                .addStatement("}")
                .build(),
        )
    }
    for (m in commands + queries) {
        val (param, arg) = argument(m)
        val value = param.name.camel.replaceFirstChar(Char::lowercaseChar)
        val f = FunSpec.builder(m.method).addModifiers(KModifier.SUSPEND).addParameter(value, arg.type)
            .addKdoc("Calls %L `%L` and suspends until its outcome.", if (m.interaction.hasQuery()) "query" else "command", m.declared)
        if (m.interaction.hasQuery()) {
            f.returns(reply(m).type).addStatement("return calls.%M { %T(port, %L).await() }", WITH_LOCK, callClass(m), value)
        } else {
            f.addStatement("calls.%M { %T(port, %L).await() }", WITH_LOCK, callClass(m), value)
        }
        builder.addFunction(f.build())
    }
    return builder.build()
}

private fun serveAsync(): FunSpec {
    val h = TypeVariableName("H", listOf(HANDLER, WAKEABLE))
    return FunSpec.builder("serveAsync").addModifiers(KModifier.SUSPEND).addTypeVariable(h)
        .addKdoc(
            "Serves interface `%L`'s calls, suspending between claims. It never returns normally: it ends by " +
                "throwing `ProviderError`, as [serve] does, or by the cancellation of its coroutine.",
            iface.declared.declared,
        )
        .addParameter("handler", h).addParameter("provider", ClassName(pkg, "${name}Provider"))
        .returns(NOTHING)
        .addStatement("claimServed(handler)")
        .addStatement("val buffer = %T.allocate(MAX_BUFFER_SIZE)", BYTE_BUFFER)
        .addStatement("return %M<Nothing>({}) { waker -> servePass(handler, provider, buffer, waker) }", AWAIT_POLL)
        .build()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ridlc-gen-kotlin:test :conformance:test` Expected: PASS.

- [ ] **Step 5: Mutations, then commit**

1. In `asyncClient()`, emit the call without `calls.withLock { }` → "the second
   waits for the first" fails.
2. In `interactionCall()`'s `await`, pass `{}` in place of `::cancel` → "the
   cancelled call gave its slot back" fails.

```bash
just fmt
git add modules/ridlc-gen-kotlin modules/conformance
git commit -m "feat(ridlc-gen-kotlin): generate the async client and serveAsync" -m "The async client depends on ridl-rt-kt-coroutines, which reverses O-K3 of #3 for generated code. Refs #7"
```

---

### Task 6: Name collisions and the signal-only shape

**Files:**

- Modify:
  `modules/ridlc-gen-kotlin/src/main/kotlin/ridl/codegen/kotlin/types/FacesEmitter.kt`
- Test:
  `modules/ridlc-gen-kotlin/src/test/kotlin/ridl/codegen/kotlin/FacesEmitterTest.kt`
- Modify: `modules/conformance/src/test/kotlin/ridl/conformance/ClientsTest.kt`

**Interfaces:**

- Produces: a skipped interface, with the warning
  `interface`<pkg>.<Iface>`: member`<m>`collides with the generated`<name>`(driftsys/ridl#570)`,
  when a member's method name is `nextEvent` or `timeout` and the interface
  waits.

- [ ] **Step 1: Write the failing tests**

In `FacesEmitterTest.kt`, reusing its `model(...)` helper pattern:

```kotlin
@Test
fun `a member named like a generated client method skips its interface`() {
    val event = Interaction.newBuilder().setName(Spellings.newBuilder().setDeclared("next_event").setCamel("NextEvent"))
        .setEvent(ridl.codegen.v1.ModelOuterClass.EventShape.newBuilder()
            .setPayload(Payload.newBuilder().setType(TypeRef.newBuilder().setReference("Level")).setFlatbuffersMaxSize(8)))
    val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
        .addSlots(InteractionSlot.newBuilder().setOrdinal(1).setInteraction(event))
    val emitted = FacesEmitter(Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).addInterfaces(iface).build(), options).emit()
    assertNull(emitted.text)
    assertTrue("collides with the generated `nextEvent`" in emitted.warnings.single(), emitted.warnings.single())
}
```

(If the model needs a `Level` declaration for the payload to resolve, add it as
the existing tests in this file do for their payload type.)

In `ClientsTest.kt`:

```kotlin
@Test
fun `a signal-only interface has one client and no async client or serve`() {
    val faces = sources().getValue("veh/cabin/Faces.kt")
    assertTrue("public class HornClient<" in faces, "Horn keeps its public client")
    assertFalse("HornAsyncClient" in faces, "Horn has no async client")
    assertFalse("HornPollClient" in faces, "Horn has no poll face")
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
`./gradlew :ridlc-gen-kotlin:test --tests ridl.codegen.kotlin.FacesEmitterTest :conformance:test --tests ridl.conformance.ClientsTest`
Expected: the collision test FAILS (no warning is produced); the Horn test
PASSES already (Task 2's shape), which pins it.

- [ ] **Step 3: Write minimal implementation**

At the start of `Face.emit()`:

```kotlin
val waits = events.isNotEmpty() || commands.isNotEmpty() || queries.isNotEmpty()
if (waits) {
    for (m in members) {
        if (m.method == "nextEvent" || m.method == "timeout") {
            refuse("member `${m.declared}` collides with the generated `${m.method}` (driftsys/ridl#570)")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :ridlc-gen-kotlin:test :conformance:test` Expected: PASS.

- [ ] **Step 5: Commit**

```bash
just fmt
git add modules/ridlc-gen-kotlin modules/conformance
git commit -m "feat(ridlc-gen-kotlin): skip an interface whose member names collide with the clients" -m "Refs #7 and driftsys/ridl#570"
```

---

### Task 7: The cabin sample on the clients

**Files:**

- Modify: `samples/cabin/src/main/kotlin/ridl/sample/cabin/Main.kt`
- Modify: `samples/cabin/src/main/kotlin/ridl/sample/cabin/CoroutineDemo.kt`
- Modify: `samples/cabin/src/test/kotlin/ridl/sample/cabin/DemoTest.kt`
- Modify: `samples/cabin/README.md`

**Interfaces:**

- Consumes: `CabinClient`, `CabinAsyncClient`, `Cabin.serve`,
  `Cabin.serveAsync`.

- [ ] **Step 1: Write the failing test**

`DemoTest` keeps its two tests and expected lines; add a third that pins the
sample no longer reaches the internal face (it would still compile, since the
generated sources are in this module):

```kotlin
@Test
fun `the sample uses only the public clients`() {
    val root = java.nio.file.Path.of("src/main/kotlin/ridl/sample/cabin")
    for (file in listOf("Main.kt", "CoroutineDemo.kt")) {
        val text = root.resolve(file).toFile().readText()
        for (internal in listOf("PollClient", "dispatch(", "Ack(", "Reply(", "Correlation")) {
            assertEquals(false, internal in text, "$file uses $internal")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :samples:cabin:test` Expected: FAIL: `Main.kt uses PollClient`.

- [ ] **Step 3: Rewrite the demos**

`Main.kt`'s `demo()`: one loopback, the provider on a thread running
`Cabin.serve(handler, service, 100.milliseconds)` in a loop until a flag stops
it, and the four round trips through `CabinClient(port, timeout = 5.seconds)`,
printing the same four lines in the same order. The command's line reads
`service.levels.single()` after the query has returned: the provider runs claims
in order on its one thread, so the command's method has run by then. Clear the
flag and join the thread at the end. Imports:
`java.util.concurrent.atomic.AtomicBoolean`, `kotlin.concurrent.thread`,
`kotlin.time.Duration.Companion.milliseconds`,
`kotlin.time.Duration.Companion.seconds`.

```kotlin
fun demo(): List<String> {
    val lines = mutableListOf<String>()
    val port = Loopback(Cabin.catalog)
    val handler = port.handler()
    val service = CabinService(average = 7)
    // serve returns at each short timeout, so the flag stops the provider within one.
    val running = AtomicBoolean(true)
    val provider = thread(name = "cabin-provider") { while (running.get()) Cabin.serve(handler, service, 100.milliseconds) }
    val client = CabinClient(port, timeout = 5.seconds)

    CabinPublisher(port).apply { temperature(Temperature.of(21)); commit() }
    val sample = client.temperature()
    check(sample.value == Temperature.of(21) && sample.provenance == Provenance.Live) { "signal: $sample" }
    lines += "signal ok ${sample.value.value}"

    client.subscribeWarning()
    CabinPublisher(port).warning(Warning(Level.of(5), Health.WARN))
    val event = client.nextEvent() as? Cabin.Event.Warning ?: error("event: no occurrence")
    val warning = event.occurrence.payload.getOrThrow()
    check(warning.health == Health.WARN) { "event: $warning" }
    lines += "event ok ${warning.code.value}"

    client.setLevel(Level.of(42))
    val average = client.average(Window.of(10))
    lines += "command ok ${service.levels.single()}"
    lines += "query ok ${average.value}"

    running.set(false)
    provider.join()
    return lines
}
```

`CoroutineDemo.kt`: the provider is
`launch(Dispatchers.Default) { Cabin.serveAsync(handler, service) }`; the
consumer uses `CabinAsyncClient(port)`: `client.average(Window.of(10))`,
`client.setLevel(Level.of(42))`, then after `subscribeWarning()` and a raise,
`client.nextEvent() as Cabin.Event.Warning`. Cancel the provider before reading
`levels`, as today. Drop the `await` import and the `Interest` import. Update
the file comment: "Every wait is a call of `CabinAsyncClient`; the provider is
`Cabin.serveAsync`."

`samples/cabin/README.md`: replace the description of the poll face and
`dispatch` with the two clients and `serve`/`serveAsync`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :samples:cabin:test && just demo` Expected: PASS; `just demo`
prints the seven lines.

- [ ] **Step 5: Commit**

```bash
just fmt
git add samples/cabin
git commit -m "feat(samples): run the cabin demo through the generated clients and serve" -m "Refs #7"
```

---

### Task 8: Records and the gate

**Files:**

- Modify: `modules/ridlc-gen-kotlin/README.md`
- Modify: `modules/ridl-rt-kt-coroutines/README.md`
- Modify: `modules/conformance/README.md`

- [ ] **Step 1: The READMEs**

- `ridlc-gen-kotlin` README, "Where the code departs from docs/design.md",
  `Faces.kt`: design.md §5 describes the poll face as public and an
  `averageAwait` extension per call; the face now generates `<Iface>Client`
  (blocking), `<Iface>AsyncClient` and `serve`/`serveAsync` over
  `InteractionCall`, the poll face is internal, a call throws `ClientError`, and
  an interface whose member names collide with the clients' (`nextEvent`,
  `timeout`) is skipped until driftsys/ridl#570 is decided. Status: "#7: the
  clients and `serve` of ADR-0023 decision 6 (ridl `main` at 1eb0fba)."
- `ridl-rt-kt-coroutines` README, the O-K3 section: "Reversed for generated code
  by #7: every `<Iface>AsyncClient` is generated and runs its calls through
  `awaitPoll`, so a generated package with an event, a command or a query
  depends on `kotlinx-coroutines-core`. An application that never calls the
  async client loses its code when its release build shrinks unused classes."
- `conformance` README: `ClientsTest` and its probe, with what they pin (Tasks 3
  to 6) and the mutations that turn them red.

- [ ] **Step 2: The gate**

Run: `just build && just demo && just lint-commits` Expected: every check
passes; the seven demo lines print.

- [ ] **Step 3: Commit**

```bash
just fmt
git add modules/ridlc-gen-kotlin/README.md modules/ridl-rt-kt-coroutines/README.md modules/conformance/README.md
git commit -m "docs(ridlc-gen-kotlin): record the clients and serve" -m "Closes #7"
```
