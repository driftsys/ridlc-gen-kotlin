@file:JvmName("CabinClientsProbe")

package ridl.conformance.probe.clients.cabin

import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.Ordinal
import ridl.rt.error.ClientError
import ridl.rt.error.ProviderError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.loopback.Loopback
import ridl.rt.port.Handler
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.ServeError
import ridl.rt.port.Wakeable
import ridl.rt.sample.Duration
import ridl.rt.task.noopWaker
import veh.cabin.Average
import veh.cabin.Cabin
import veh.cabin.CabinAsyncClient
import veh.cabin.CabinAverageCall
import veh.cabin.CabinClient
import veh.cabin.CabinProvider
import veh.cabin.CabinPublisher
import veh.cabin.CabinSetLevelCall
import veh.cabin.Health
import veh.cabin.Horn
import veh.cabin.HornClient
import veh.cabin.HornPublisher
import veh.cabin.Level
import veh.cabin.Temperature
import veh.cabin.Warning
import veh.cabin.Window
import veh.cabin.commit
import veh.cabin.nextEvent
import veh.cabin.subscribeWarning
import veh.cabin.temperature
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        try { rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0), null) } catch (_: SendError.Busy) { return sent }
        sent += 1
    }
}

/** Fills the table with settled calls nobody forgot, and returns their correlations. */
private fun fill(rt: Loopback) = List(Loopback.SLOTS) {
    val c = rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0), null)
    rt.settle(rt.nextClaim(ByteBuffer.allocate(64))!!.id, Result.success(ByteBuffer.allocate(0)))
    c
}

private class Recorder : CabinProvider {
    val levels = mutableListOf<Level>()
    override fun setLevel(level: Level) { synchronized(this) { levels += level } }
    override fun average(window: Window): Average = Average.of(250)
}

private val waker = noopWaker()

fun probe(): List<String> {
    callObjects()
    blocking()
    async()
    readErrors()
    bounds()
    catalogs()
    return failures
}

/**
 * [block] throws the catalog mismatch: an `IllegalStateException` that names
 * the face, not the `CancellationException` (one too) of a timeout.
 */
private fun expectMismatch(label: String, block: () -> Unit) {
    val e = expectThrows<IllegalStateException>(label, block) ?: return
    expect("$label: threw $e", e.message.orEmpty().startsWith("the face of interface `"))
}

/**
 * ADR-0023 decision 8: every binding compares its port's catalog with the
 * face's, name and hash, and throws `IllegalStateException` on a mismatch,
 * before it uses the port.
 */
private fun catalogs() {
    val others = listOf(
        "hash" to CatalogRef(Cabin.catalog.name, CatalogHash(ByteArray(CatalogHash.SIZE) { 1 })),
        "name" to CatalogRef("veh.other", Cabin.catalog.hash),
    )
    for ((differs, catalog) in others) {
        val rt = Loopback(catalog)
        val of = "a port whose catalog's $differs differs"
        expectMismatch("CabinClient over $of") { CabinClient(rt) }
        expectMismatch("CabinAsyncClient over $of") { CabinAsyncClient(rt, kotlinx.coroutines.CoroutineScope(Dispatchers.Default)) }
        expectMismatch("CabinPublisher over $of") { CabinPublisher(rt) }
        expectMismatch("HornClient over $of") { HornClient(rt) }
        expectMismatch("HornPublisher over $of") { HornPublisher(rt) }
        expectMismatch("serve over $of") { Cabin.serve(rt, Recorder(), 100.milliseconds) }
        // Bounded, so a serveAsync that does not check fails here rather than serving forever.
        expectMismatch("serveAsync over $of") { runBlocking { withTimeout(1.seconds) { Cabin.serveAsync(rt, Recorder()) } } }
    }
    val rt = Loopback(others[0].second)
    expectEqual(
        "the message names the interface, the face's catalog, then the port's",
        "the face of interface `Cabin` was generated from catalog ${Cabin.catalog}, but the port is attached to catalog ${rt.catalog}",
        expectThrows<IllegalStateException>("a mismatch throws") { CabinClient(rt) }?.message,
    )
    expectEqual("the Horn descriptor names its own interface", true,
        expectThrows<IllegalStateException>("a Horn mismatch throws") { HornClient(rt) }?.message?.startsWith("the face of interface `Horn` "))
    expectEqual("Horn and Cabin share the package's catalog", Cabin.catalog, Horn.catalog)
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
        val window = ByteBuffer.allocate(veh.cabin.WindowCodec.maxSize)
        veh.cabin.WindowCodec.encode(Window.of(10), window)
        window.flip()
        rt.query(Cabin.number, Ordinal(4u), window, null)
        expectThrows<IllegalStateException>("a provider's exception leaves serve unchanged") { Cabin.serve(rt, broken, 1.seconds) }
    }
}

private fun async() = runBlocking {
    withTimeout(10_000) {
        // The round trips, with serveAsync on another thread.
        Loopback(Cabin.catalog).let { rt ->
            val recorder = Recorder()
            val handler = rt.handler()
            val provider = launch(Dispatchers.Default) { Cabin.serveAsync(handler, recorder) }
            val client = CabinAsyncClient(rt, this)
            CabinPublisher(rt).apply { temperature(Temperature.of(19)); commit() }
            expectEqual("an async signal reads", Temperature.of(19), client.temperature.value.value)
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
            val call = launch(start = CoroutineStart.UNDISPATCHED) { CabinAsyncClient(rt, this).setLevel(Level.of(1)) }
            // Count the free slots without keeping them: send until Busy, then forget each.
            val counted = mutableListOf<ridl.rt.port.Correlation>()
            try { while (true) counted += rt.command(Cabin.number, Ordinal(3u), ByteBuffer.allocate(0), null) } catch (_: SendError.Busy) {}
            expectEqual("the waiting call holds a slot", Loopback.SLOTS - 1, counted.size)
            counted.forEach(rt::forget)
            call.cancelAndJoin()
            expectEqual("the cancelled call gave its slot back", Loopback.SLOTS, sendsUntilBusy(rt))
        }
        // A cancelled call still unsent forgets nothing and ends quietly.
        Loopback(Cabin.catalog).let { rt ->
            fill(rt)
            val call = launch(start = CoroutineStart.UNDISPATCHED) { CabinAsyncClient(rt, this).setLevel(Level.of(1)) }
            call.cancelAndJoin()
            expect("an unsent call cancelled ends", call.isCancelled)
            expectEqual("and the table is as full as before", 0, sendsUntilBusy(rt))
        }
        // One call at a time per client.
        Loopback(Cabin.catalog).let { rt ->
            val client = CabinAsyncClient(rt, this)
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

/** A loopback whose event source is detached: every read of an occurrence fails. */
private class Detached(rt: Loopback) :
    ridl.rt.port.SignalReader by rt,
    ridl.rt.port.EventSource by rt,
    ridl.rt.port.Caller by rt,
    ridl.rt.port.Clock by rt,
    Wakeable by rt {
    private val rt = rt
    override val catalog: ridl.rt.contract.CatalogRef get() = rt.catalog
    override fun next(out: ByteBuffer): ridl.rt.port.RawOccurrence? = throw ReadError.Detached
}

/** A read failure reaches a client's caller as `ClientError.Read`, and a provider's own `ReadError` leaves serve unchanged. */
private fun readErrors() {
    Loopback(Cabin.catalog).let { rt ->
        expectEqual("a blocking nextEvent over a detached source", ReadError.Detached,
            expectThrows<ClientError.Read>("a blocking nextEvent throws Read") { CabinClient(Detached(rt)).nextEvent() }?.error)
        expectEqual("an async nextEvent over a detached source", ReadError.Detached,
            expectThrows<ClientError.Read>("an async nextEvent throws Read") { runBlocking { CabinAsyncClient(Detached(rt), this).nextEvent() } }?.error)
    }
    Loopback(Cabin.catalog).let { rt ->
        val reading = object : CabinProvider {
            override fun setLevel(level: Level) = throw ReadError.Detached
            override fun average(window: Window): Average = Average.of(0)
        }
        CabinSetLevelCall(rt, Level.of(1))
        expectThrows<ReadError.Detached>("a provider's ReadError leaves serve unchanged") { Cabin.serve(rt, reading, 1.seconds) }
    }
}

/** A loopback that counts the `Event` registrations made through it. */
private class Counting(val rt: Loopback) :
    ridl.rt.port.SignalReader by rt,
    ridl.rt.port.EventSource by rt,
    ridl.rt.port.Caller by rt,
    ridl.rt.port.Clock by rt,
    Wakeable by rt {
    val eventWaits = java.util.concurrent.atomic.AtomicInteger()
    override val catalog: ridl.rt.contract.CatalogRef get() = rt.catalog
    override fun wakeOn(what: ridl.rt.port.Interest, waker: ridl.rt.task.Waker) {
        if (what is ridl.rt.port.Interest.Event) eventWaits.incrementAndGet()
        rt.wakeOn(what, waker)
    }
}

/** serve keeps to its timeout under a claim stream that never ends, and two nextEvent calls wait without waking each other. */
private fun bounds() {
    Loopback(Cabin.catalog).let { rt ->
        val until = kotlin.time.TimeSource.Monotonic.markNow() + 3.seconds
        var id = 0L
        val endless = object : Handler by rt, Wakeable by rt {
            override fun serve(iface: ridl.rt.contract.InterfaceNo, ords: List<Ordinal>) = Unit
            override fun nextClaim(out: ByteBuffer): ridl.rt.port.Claim? = if (until.hasPassedNow()) null else ridl.rt.port.Claim(
                ridl.rt.port.ClaimId(++id), ridl.rt.contract.InterfaceNo(99u), Ordinal(1u),
                ridl.rt.sample.Envelope(ridl.rt.sample.Timestamp(0), 0u), null, null, 0,
            )
            override fun settle(claim: ridl.rt.port.ClaimId, outcome: Result<ByteBuffer>) = Unit
        }
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        Cabin.serve(endless, Recorder(), 100.milliseconds)
        expect("serve returns at its timeout under a claim stream: ${start.elapsedNow()}", start.elapsedNow() < 1.seconds)
    }
    // driftsys/ridl#568: under a claim stream that never ends, serveAsync
    // settles a bounded number of claims per pass and yields between passes,
    // so a coroutine beside it on one thread runs, and a cancellation stops it.
    // Run on a thread of its own, so a serve that never yields fails the join
    // rather than hanging the probe.
    Loopback(Cabin.catalog).let { rt ->
        val settled = java.util.concurrent.atomic.AtomicLong()
        var id = 0L
        val stream = object : Handler by rt, Wakeable by rt {
            override fun serve(iface: ridl.rt.contract.InterfaceNo, ords: List<Ordinal>) = Unit
            override fun nextClaim(out: ByteBuffer): ridl.rt.port.Claim = ridl.rt.port.Claim(
                ridl.rt.port.ClaimId(++id), ridl.rt.contract.InterfaceNo(99u), Ordinal(1u),
                ridl.rt.sample.Envelope(ridl.rt.sample.Timestamp(0), 0u), null, null, 0,
            )
            override fun settle(claim: ridl.rt.port.ClaimId, outcome: Result<ByteBuffer>) {
                settled.incrementAndGet()
            }
        }
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)
        val runner = thread(isDaemon = true) {
            runBlocking {
                val serving = launch { Cabin.serveAsync(stream, Recorder()) }
                launch { ran.set(true) }.join()
                // A pass takes 32 claims; more means serveAsync woke itself for another pass.
                while (settled.get() <= 32) kotlinx.coroutines.yield()
                serving.cancelAndJoin()
            }
        }
        runner.join(5_000)
        expect("serveAsync under an endless claim stream yields its thread and stops when cancelled", !runner.isAlive)
        expect("a coroutine beside it runs", ran.get())
        expect("it woke itself for pass after pass: ${settled.get()} claims settled", settled.get() > 32)
    }
    runBlocking {
        withTimeout(10_000) {
            val port = Counting(Loopback(Cabin.catalog))
            val client = CabinAsyncClient(port, this)
            client.subscribeWarning()
            val first = async(Dispatchers.Default) { client.nextEvent() }
            val second = async(Dispatchers.Default) { client.nextEvent() }
            kotlinx.coroutines.delay(200)
            expect("two waiting nextEvent calls do not wake each other: ${port.eventWaits.get()} registrations", port.eventWaits.get() <= 4)
            CabinPublisher(port.rt).let {
                it.warning(Warning(Level.of(1), Health.WARN))
                it.warning(Warning(Level.of(2), Health.WARN))
            }
            expect("both receive an occurrence", first.await() is Cabin.Event.Warning && second.await() is Cabin.Event.Warning)
        }
    }
}
