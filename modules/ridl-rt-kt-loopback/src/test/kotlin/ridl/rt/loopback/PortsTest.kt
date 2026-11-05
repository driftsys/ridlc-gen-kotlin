package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.contract.StreamNo
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.port.Caller
import ridl.rt.port.ClaimId
import ridl.rt.port.Handler
import ridl.rt.port.Interest
import ridl.rt.port.Pull
import ridl.rt.port.PushError
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.RaiseError
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.SettleError
import ridl.rt.port.WriteError
import ridl.rt.sample.Duration
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The tests of what only this runtime can express:
 * `crates/ridl-loopback/tests/ports.rs` on ridl `main`, outside its "Waking"
 * section (which is `WakeableTest`), under the same names. The port contract
 * tests any runtime can run are the suite of `ridl-rt-kt-conformance`, which
 * `ConformanceTest` runs over this runtime; each test here stays for the
 * reason the Rust file gives:
 *
 * - where the clock starts, and what `advance` does with a negative duration,
 *   are left to a runtime;
 * - `Freshness.Unbounded` follows from this runtime holding no member table;
 * - two sinks on one event channel is a misuse this runtime does not police;
 * - `HandlerHandle.served`, `Loopback.split` and `Loopback.attach` are this
 *   runtime's own API;
 * - a handler that has served nothing is presented every call, this runtime's
 *   deliberate deviation from `Handler.serve`;
 * - provisioning a `fixed` has no port;
 * - a limit other than the default ceiling is told through `Loopback.setLimit`,
 *   this runtime's own API;
 * - which error the injected fault reports is left to a runtime;
 * - the threading model is ADR-0021 decision 12's, which the suite leaves out;
 * - the stream tests stay for the reasons their section's heading names.
 *
 * The last section is Kotlin's own: what the JVM changes.
 */
class PortsTest {
    private val iface = InterfaceNo(1u)
    private val ord = Ordinal(1u)
    private val other = Ordinal(2u)

    private fun catalog() = CatalogRef("face.demo", CatalogHash(ByteArray(32)))

    private fun runtime() = Loopback(catalog())

    // ---------------------------------------------------------------------
    // The clock.
    // ---------------------------------------------------------------------

    @Test
    fun `two runtimes start at the same logical time`() {
        // Two runtimes built apart in real time start at the same logical
        // time: a clock that read wall-clock time could not guarantee that.
        val first = runtime()
        Thread.sleep(5)
        val second = runtime()
        assertEquals(Timestamp(0), first.now(), "the clock starts at 0")
        assertEquals(first.now(), second.now(), "the clock must not read wall-clock time")
    }

    @Test
    fun `the clock refuses to run backwards`() {
        val e = assertThrows<IllegalArgumentException> { runtime().advance(Duration(-1)) }
        assertTrue(e.message!!.startsWith("the clock advances forward"))
    }

    @Test
    fun `every value is unbounded because the runtime has no member table`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(1))
        rt.commit()
        assertEquals(Freshness.Unbounded, rt.read(iface, ord, out(8)).freshness)
    }

    // ---------------------------------------------------------------------
    // Events.
    // ---------------------------------------------------------------------

    @Test
    fun `a sink counts its own channel and not another sinks`() {
        val rt = runtime()
        val source = rt.source()
        val first = rt.sink()
        val second = rt.sink()
        source.subscribe(iface, listOf(ord))

        first.raise(iface, ord, bytes(1), null)
        second.raise(iface, ord, bytes(2), null)
        first.raise(iface, ord, bytes(3), null)
        assertEquals(listOf(1uL, 1uL, 2uL), drain(source))
    }

    // ---------------------------------------------------------------------
    // Calls.
    // ---------------------------------------------------------------------

    @Test
    fun `serve records what it was asked to present`() {
        val handler = runtime().handler()
        handler.serve(iface, listOf(ord, other))
        handler.serve(iface, listOf(ord))
        assertEquals(listOf(iface to ord, iface to other), handler.served)
    }

    @Test
    fun `a handler that served nothing is presented every call`() {
        val rt = runtime()
        rt.caller().command(InterfaceNo(2u), other, bytes(1), null)
        assertEquals(InterfaceNo(2u), rt.handler().nextClaim(out(8))!!.iface)
    }

    @Test
    fun `the injected settle failure is too large with no capacity`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!
        rt.failNextSettle()
        assertEquals(
            SettleError.TooLarge(0),
            assertThrows<SettleError> { rt.settle(claim.id, Result.success(bytes())) },
            "the one fault this runtime injects",
        )
    }

    /** A size refusal is made before the armed fault is consumed, so the fault still fails the next settlement within the limit. */
    @Test
    fun `a size refusal does not use up the armed settle failure`() {
        val rt = runtime()
        rt.serve(iface, listOf(ord))
        rt.setLimit(iface, ord, 2)
        rt.query(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!

        rt.failNextSettle()
        assertEquals(
            SettleError.TooLarge(2),
            assertThrows<SettleError> { rt.settle(claim.id, Result.success(bytes(1, 2, 3))) },
            "the size refusal names the member's limit",
        )
        assertEquals(
            SettleError.TooLarge(0),
            assertThrows<SettleError> { rt.settle(claim.id, Result.success(bytes(1, 2))) },
            "the armed fault is still there for the next settlement",
        )
        rt.settle(claim.id, Result.success(bytes(1, 2)))
    }

    // ---------------------------------------------------------------------
    // `fixed`.
    // ---------------------------------------------------------------------

    @Test
    fun `a provisioned fixed reads back and an unprovisioned one reports it`() {
        val rt = runtime()
        assertEquals(
            ReadError.Contract(Contract.UnknownInteraction),
            assertThrows<ReadError> { rt.readFixed(iface, ord, out(8)) },
            "nothing was provisioned at that ordinal",
        )

        rt.provisionFixed(iface, ord, bytes(1, 2))
        val buf = out(8)
        assertEquals(2, rt.readFixed(iface, ord, buf))
        assertArrayEquals(array(1, 2), buf.written())
        assertEquals(ReadError.Short(2), assertThrows<ReadError> { rt.readFixed(iface, ord, out(1)) })
    }

    @Test
    fun `a fixed longer than its limit is refused and reads as unprovisioned`() {
        val rt = runtime()
        assertEquals(
            WriteError.TooLarge(65_280),
            assertThrows<WriteError> { rt.provisionFixed(iface, ord, out(65_281)) },
            "one byte above the default ceiling of a fixed is refused",
        )
        assertEquals(
            ReadError.Contract(Contract.UnknownInteraction),
            assertThrows<ReadError> { rt.readFixed(iface, ord, out(8)) },
            "a fixed whose only provision was refused reads as an unprovisioned fixed",
        )

        rt.provisionFixed(iface, ord, out(65_280))
        assertEquals(65_280, rt.readFixed(iface, ord, out(65_280)))

        rt.setLimit(iface, ord, 2)
        assertEquals(
            WriteError.TooLarge(2),
            assertThrows<WriteError> { rt.provisionFixed(iface, ord, bytes(1, 2, 3)) },
            "a limit given to the loopback lowers the cap of a fixed",
        )
        assertEquals(65_280, rt.readFixed(iface, ord, out(65_280)), "a refused provision leaves the stored value as it was")
        rt.provisionFixed(iface, ord, bytes(1, 2))
        assertEquals(2, rt.readFixed(iface, ord, out(8)))
    }

    // ---------------------------------------------------------------------
    // Member limits: `Loopback.setLimit` in place of the kind's default
    // ceiling, applied by every sending port and by `provisionFixed`.
    // ---------------------------------------------------------------------

    @Test
    fun `a limit given to the loopback lowers and raises a signals cap`() {
        val rt = runtime()
        rt.setLimit(iface, ord, 2)
        assertEquals(WriteError.TooLarge(2), assertThrows<WriteError> { rt.set(iface, ord, bytes(1, 2, 3)) })
        rt.set(iface, ord, bytes(1, 2))
        assertEquals(
            WriteError.TooLarge(3_840),
            assertThrows<WriteError> { rt.set(iface, other, out(3_841)) },
            "another member keeps the default ceiling",
        )

        rt.setLimit(iface, ord, 16_128)
        rt.set(iface, ord, out(16_128))
        assertEquals(WriteError.TooLarge(16_128), assertThrows<WriteError> { rt.set(iface, ord, out(16_129)) })
    }

    @Test
    fun `a limit given to the loopback lowers and raises an events cap`() {
        val rt = runtime()
        rt.subscribe(iface, listOf(ord))
        rt.setLimit(iface, ord, 2)
        assertEquals(RaiseError.TooLarge(2), assertThrows<RaiseError> { rt.raise(iface, ord, bytes(1, 2, 3), null) })
        rt.raise(iface, ord, bytes(1, 2), null)

        rt.setLimit(iface, ord, 16_128)
        rt.raise(iface, ord, out(16_128), null)
        assertEquals(RaiseError.TooLarge(16_128), assertThrows<RaiseError> { rt.raise(iface, ord, out(16_129), null) })

        val buf = out(16_384)
        val first = rt.next(buf)!!
        assertEquals(2 to 1uL, first.len to first.envelope.seq)
        buf.clear()
        val second = rt.next(buf)!!
        assertEquals(16_128 to 2uL, second.len to second.envelope.seq, "a refused occurrence draws no sequence number")
        assertNull(rt.next(buf))
    }

    @Test
    fun `a limit given to the loopback lowers and raises a calls cap`() {
        val rt = runtime()
        rt.serve(iface, listOf(ord))
        rt.setLimit(iface, ord, 2)
        assertEquals(SendError.TooLarge(2), assertThrows<SendError> { rt.command(iface, ord, bytes(1, 2, 3), null) })
        assertEquals(SendError.TooLarge(2), assertThrows<SendError> { rt.query(iface, ord, bytes(1, 2, 3), null) })
        val c = rt.query(iface, ord, bytes(1, 2), null)
        val claim = rt.nextClaim(out(8))!!
        assertEquals(1uL, claim.envelope.seq, "a refused call draws no sequence number")
        assertEquals(
            SettleError.TooLarge(2),
            assertThrows<SettleError> { rt.settle(claim.id, Result.success(bytes(1, 2, 3))) },
            "the reply is held to the same limit",
        )
        rt.settle(claim.id, Result.success(bytes(1, 2)))
        assertEquals(2, rt.reply(c, out(8))?.getOrThrow())
        rt.forget(c)

        rt.setLimit(iface, ord, 65_280)
        val large = rt.query(iface, ord, out(65_280), null)
        assertEquals(SendError.TooLarge(65_280), assertThrows<SendError> { rt.query(iface, ord, out(65_281), null) })
        val second = rt.nextClaim(out(65_280))!!
        rt.settle(second.id, Result.success(out(65_280)))
        assertEquals(65_280, rt.reply(large, out(65_280))?.getOrThrow())
    }

    @Test
    fun `a limit is the stores so an attached aggregate applies it`() {
        val rt = runtime()
        rt.setLimit(iface, ord, 2)
        assertEquals(WriteError.TooLarge(2), assertThrows<WriteError> { rt.attach().set(iface, ord, bytes(1, 2, 3)) })
        assertEquals(WriteError.TooLarge(2), assertThrows<WriteError> { rt.writer().set(iface, ord, bytes(1, 2, 3)) })
    }

    // ---------------------------------------------------------------------
    // The handle model.
    // ---------------------------------------------------------------------

    @Test
    fun `every split handle carries the catalog`() {
        val handles = runtime().split()
        for (catalog in listOf(
            handles.reader.catalog, handles.writer.catalog, handles.source.catalog,
            handles.sink.catalog, handles.caller.catalog, handles.handler.catalog,
        )) {
            assertEquals(catalog(), catalog)
        }
    }

    // `Loopback.attach` (driftsys/ridl#488). The Kotlin aggregate has no
    // `close`, so where the Rust tests drop an attached aggregate, these close
    // the handles it splits into, which is what that drop closes.

    @Test
    fun `an attached aggregate carries the catalog`() {
        val attached = runtime().attach()
        assertEquals(catalog(), attached.caller().catalog)
        val handles = attached.split()
        for (catalog in listOf(
            handles.reader.catalog, handles.writer.catalog, handles.source.catalog,
            handles.sink.catalog, handles.caller.catalog, handles.handler.catalog,
        )) {
            assertEquals(catalog(), catalog)
        }
    }

    @Test
    fun `a value committed through one aggregate is read through an attached one`() {
        // The published signals are in the store, and staging is on the writer
        // handle: one aggregate's commit publishes only what that aggregate
        // staged, and a value staged before the attach is not carried over.
        val rt = runtime()
        rt.set(iface, ord, bytes(1))
        val attached = rt.attach()
        attached.set(iface, other, bytes(2))
        attached.commit()

        assertEquals(Provenance.Init, rt.read(iface, ord, out(8)).provenance)
        val published = out(8)
        rt.read(iface, other, published)
        assertArrayEquals(array(2), published.written())

        rt.commit()
        val sample = out(8)
        attached.read(iface, ord, sample)
        assertArrayEquals(array(1), sample.written())
    }

    @Test
    fun `an attached aggregate advances the one clock`() {
        val rt = runtime()
        rt.attach().advance(Duration(5))
        assertEquals(Timestamp(5), rt.now())
    }

    @Test
    fun `an attached aggregate starts with none of the originals handle state`() {
        // Subscriptions, the served set and the sequence counters are on a
        // handle, so an aggregate attached after the original used all of them
        // starts with none of them.
        val rt = runtime()
        rt.subscribe(iface, listOf(ord))
        rt.serve(iface, listOf(ord))
        rt.set(iface, ord, bytes(1))
        rt.commit()
        rt.raise(iface, ord, bytes(1), null)
        rt.command(iface, ord, bytes(1), null)
        val attached = rt.attach()

        attached.set(iface, ord, bytes(2))
        attached.commit()
        assertEquals(1uL, attached.read(iface, ord, out(8)).envelope.seq, "the attached writer's first publication")

        attached.raise(iface, ord, bytes(2), null)
        assertNull(attached.next(out(8)), "the attached source is subscribed to nothing")
        assertEquals(listOf(1uL, 1uL), drain(rt), "each sink's first raise is its own seq 1")

        attached.command(iface, ord, bytes(2), null)
        val sent = generateSequence { rt.nextClaim(out(8)) }.map { it.envelope.seq }.toList()
        assertEquals(listOf(1uL, 1uL), sent, "each caller's first call is its own seq 1")

        assertTrue(attached.split().handler.served.isEmpty())
    }

    @Test
    fun `an attached aggregate has its own subscriptions and queue`() {
        val rt = runtime()
        val attached = rt.attach()
        rt.subscribe(iface, listOf(ord))

        attached.raise(iface, ord, bytes(1), null)
        assertNull(attached.next(out(8)), "the attached aggregate's source is subscribed to nothing")

        attached.subscribe(iface, listOf(ord))
        attached.raise(iface, ord, bytes(2), null)
        assertEquals(listOf(listOf<Byte>(1), listOf<Byte>(2)), payloads(rt))
        assertEquals(listOf(listOf<Byte>(2)), payloads(attached))
    }

    @Test
    fun `a call sent through one aggregate is served through an attached one`() {
        val rt = runtime()
        val attached = rt.attach()
        val sent = rt.command(iface, ord, bytes(1), null)

        val claim = attached.nextClaim(out(8))!!
        attached.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(sent))
    }

    @Test
    fun `closing an attached aggregate leaves the originals calls and subscriptions`() {
        // Each handle's close ends that handle's own identity in the store,
        // and the handles of an attached aggregate have identities of their
        // own: the original's claim stays the original's. The attached
        // aggregate's own unclaimed call is withdrawn with it.
        val rt = runtime()
        rt.subscribe(iface, listOf(ord))
        val sent = rt.command(iface, ord, bytes(1), null)
        val attached = rt.attach()
        attached.subscribe(iface, listOf(ord))
        attached.command(iface, ord, bytes(2), null)
        val buf = out(8)
        val claim = rt.nextClaim(buf)!!
        assertArrayEquals(array(1), buf.written(), "the original's call, sent first")
        attached.split().run {
            source.close()
            caller.close()
            handler.close()
        }

        rt.raise(iface, ord, bytes(3), null)
        assertEquals(listOf(listOf<Byte>(3)), payloads(rt))

        rt.settle(claim.id, ok())
        assertEquals(Result.success(Unit), rt.ack(sent), "the claim is still the original's")
        assertNull(rt.nextClaim(out(8)), "the closed aggregate's call was withdrawn")
    }

    @Test
    fun `an attached aggregate keeps the store after the original is closed`() {
        // The store outlives the original, and so do the attached aggregate's
        // own subscription and call.
        val rt = runtime()
        val attached = rt.attach()
        attached.subscribe(iface, listOf(ord))
        val sent = attached.command(iface, ord, bytes(5), null)
        rt.set(iface, ord, bytes(4))
        rt.commit()
        rt.split().run {
            source.close()
            caller.close()
            handler.close()
        }

        val sample = out(8)
        attached.read(iface, ord, sample)
        assertArrayEquals(array(4), sample.written())

        attached.raise(iface, ord, bytes(6), null)
        assertEquals(listOf(listOf<Byte>(6)), payloads(attached))

        val buf = out(8)
        val claim = attached.nextClaim(buf)!!
        assertArrayEquals(array(5), buf.written(), "the attached aggregate's call")
        attached.settle(claim.id, ok())
        assertEquals(Result.success(Unit), attached.ack(sent))
    }

    @Test
    fun `a writer handle publishes on one thread while a reader reads on another`() {
        val handles = runtime().split()
        val reader = handles.reader
        val writer = handles.writer

        val publisher = Thread {
            for (value in 1..50) {
                // Four bytes that must agree: a read that saw part of one
                // publication and part of the next would not.
                writer.set(iface, ord, ByteBuffer.wrap(ByteArray(4) { value.toByte() }))
                writer.commit()
            }
        }
        publisher.start()

        var seen = 0
        while (seen < 200) {
            val buf = out(8)
            val raw = reader.read(iface, ord, buf)
            if (raw.len == 0) continue
            val bytes = buf.written()
            assertEquals(4, raw.len)
            val value = bytes[0].toInt()
            assertTrue(value in 1..50)
            assertArrayEquals(ByteArray(4) { value.toByte() }, bytes, "a publication is read whole or not at all")
            assertEquals(value.toULong(), raw.envelope.seq, "and its envelope belongs to the value read")
            seen += 1
        }

        publisher.join()
        val buf = out(8)
        assertEquals(50uL, reader.read(iface, ord, buf).envelope.seq)
        assertArrayEquals(ByteArray(4) { 50 }, buf.written())
    }

    @Test
    fun `a reader handle is shared between threads`() {
        val handles = runtime().split()
        handles.writer.set(iface, ord, bytes(3))
        handles.writer.commit()

        val results = arrayOfNulls<ByteArray>(4)
        val threads = (0 until 4).map { index ->
            Thread {
                val buf = out(8)
                handles.reader.read(iface, ord, buf)
                results[index] = buf.written()
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        for (result in results) assertArrayEquals(array(3), result)
    }

    // ---------------------------------------------------------------------
    // Streams. The tests of what the suite leaves out: the close of a handle,
    // when a forgotten call's slot comes back, a registration whose key already
    // holds, and this runtime's own numbers and choices. Where a Rust test
    // drops a handle, these close it.
    // ---------------------------------------------------------------------

    /** A waker that counts its wakes. */
    private class Count : Waker {
        private val count = AtomicInteger()

        val wakes: Int get() = count.get()

        override fun wake() {
            count.incrementAndGet()
        }
    }

    /** A waker that holds [handle], as a task does. Waking reaches the handle and leaves it open. */
    private fun owning(handle: AutoCloseable): Waker =
        Waker { assertNotNull(handle, "the handle is owned until the waker is dropped") }

    /**
     * Closes [handle] on another thread and fails, rather than hangs, when the
     * close does not finish. The thread is not joined, on purpose: a deadlocked
     * close never finishes, and a join would wait for it forever.
     */
    private fun assertCloseFinishes(handle: AutoCloseable, what: String) {
        val finished = CountDownLatch(1)
        thread(isDaemon = true) {
            handle.close()
            finished.countDown()
        }
        assertTrue(
            finished.await(5, TimeUnit.SECONDS),
            "$what: the drop finished, so no waker was dropped under the store's lock",
        )
    }

    @Test
    fun `a dropped handler interrupts a taken call whose streams moved`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        val c = caller.query(iface, ord, bytes(1), null)
        val claim = first.nextClaim(out(8))!!.id
        val param = StreamNo(1u)
        assertEquals(1, caller.push(c, param, bytes(1)))
        assertEquals(1, caller.push(c, param, bytes(2)))
        assertEquals(Pull.Chunk(1), first.pullClaim(claim, param, out(8)))
        val outcome = Count()
        val reply = Count()
        caller.wakeOn(Interest.Outcome(c), outcome)
        caller.wakeOn(Interest.Stream(c, REPLY_STREAM), reply)
        first.close()

        assertNull(second.nextClaim(out(8)), "a call whose streams moved is not presented to another handler")
        val interrupted = Transport.Interrupted
        assertEquals(1, outcome.wakes, "the settlement wakes the outcome waiter")
        assertEquals(1, reply.wakes, "the settlement wakes the stream waiter")
        assertEquals(Result.failure<Int>(interrupted), caller.reply(c, out(8)))
        assertEquals(
            PushError.Failed(interrupted),
            assertThrows<PushError> { caller.push(c, param, bytes(3)) },
            "the parameter stream stopped with the handler",
        )
        assertEquals(Pull.Failed(interrupted), caller.pull(c, REPLY_STREAM, out(8)))
    }

    @Test
    fun `a claim stream registration refreshes the same task and displaces another`() {
        val rt = runtime()
        rt.query(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!.id
        val key = Interest.ClaimStream(claim, StreamNo(1u))
        val first = Count()
        rt.wakeOn(key, first)
        rt.wakeOn(key, first)
        assertEquals(0, first.wakes, "a registration of the same task is a refresh")
        val second = Count()
        rt.wakeOn(key, second)
        assertEquals(1, first.wakes, "another task's waker displaces the stored one")
        assertEquals(0, second.wakes, "the new waker is stored, not woken")
    }

    @Test
    fun `a parameter stream the provider refused does not hold the slot after the forget`() {
        val rt = runtime()
        val param = StreamNo(1u)
        val refusal = Contract.PreconditionFailed
        for (round in 0 until Loopback.SLOTS * 2) {
            val c = try {
                rt.command(iface, ord, bytes(1), null)
            } catch (e: SendError) {
                fail("round $round: the send answered $e")
            }
            val claim = rt.nextClaim(out(8))!!.id
            rt.settle(claim, ok())
            assertEquals(1, rt.push(c, param, bytes(1)))
            assertEquals(Pull.Chunk(1), rt.pullClaim(claim, param, out(8)))
            rt.abortClaim(claim, param, refusal)
            assertEquals(PushError.Failed(refusal), assertThrows<PushError> { rt.push(c, param, bytes(2)) })
            rt.forget(c)
        }
    }

    @Test
    fun `a query settled with an unread parameter stream frees its slot at the forget`() {
        val rt = runtime()
        val param = StreamNo(1u)
        val old = rt.query(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!.id
        assertEquals(1, rt.push(old, param, bytes(1)))
        val error = Contract.PreconditionFailed
        rt.settle(claim, Result.failure(error))
        assertEquals(
            PushError.Failed(Transport.Interrupted),
            assertThrows<PushError> { rt.push(old, param, bytes(2)) },
            "the settlement of a query releases its parameter streams",
        )
        repeat(Loopback.SLOTS - 1) { rt.command(iface, ord, bytes(1), null) }
        assertEquals(
            SendError.Busy,
            assertThrows<SendError> { rt.command(iface, ord, bytes(1), null) },
            "every other slot is taken",
        )
        rt.forget(old)
        val new = rt.query(iface, ord, bytes(2), null)
        assertNotEquals(old, new)
    }

    @Test
    fun `end claim after the callers refusal settles the claim with the refusal`() {
        val rt = runtime()
        val c = rt.query(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!.id
        val refusal = Contract.PreconditionFailed
        rt.abort(c, REPLY_STREAM, refusal)
        assertEquals(PushError.Failed(refusal), assertThrows<PushError> { rt.endClaim(claim, REPLY_STREAM) })
        assertEquals(
            Result.failure<Int>(refusal),
            rt.reply(c, out(8)),
            "the claim is settled with the refusal, not Ok",
        )
        assertEquals(SettleError.UnknownClaim, assertThrows<SettleError> { rt.settle(claim, ok()) })
    }

    @Test
    fun `a dropped handler stops the parameter streams of a command it acknowledged`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val param = StreamNo(1u)
        val c = caller.command(iface, ord, bytes(1), null)
        val claim = handler.nextClaim(out(8))!!.id
        handler.settle(claim, ok())
        assertEquals(1, caller.push(c, param, bytes(1)))
        val waiter = Count()
        caller.wakeOn(Interest.Stream(c, param), waiter)
        handler.close()
        assertEquals(1, waiter.wakes, "the drop wakes the producer's waiter")
        assertEquals(
            PushError.Failed(Transport.Interrupted),
            assertThrows<PushError> { caller.push(c, param, bytes(2)) },
        )
        assertEquals(Result.success(Unit), caller.ack(c), "the acknowledgment stands")
    }

    @Test
    fun `a stream registration on a forgotten call is woken at once`() {
        val rt = runtime()
        val param = StreamNo(1u)
        val c = rt.command(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!.id
        rt.settle(claim, ok())
        assertEquals(1, rt.push(c, param, bytes(1)))
        rt.forget(c)
        val waiter = Count()
        rt.wakeOn(Interest.Stream(c, param), waiter)
        assertEquals(1, waiter.wakes, "no stream of a forgotten call changes")
    }

    @Test
    fun `a querys settlement wakes the providers waiter on a parameter stream`() {
        val rt = runtime()
        val param = StreamNo(1u)
        rt.query(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!.id
        val waiter = Count()
        rt.wakeOn(Interest.ClaimStream(claim, param), waiter)
        assertEquals(Pull.Pending, rt.pullClaim(claim, param, out(8)))
        assertEquals(0, waiter.wakes, "nothing changed since the registration")
        rt.settle(claim, Result.failure(Contract.PreconditionFailed))
        assertEquals(1, waiter.wakes, "the settlement releases the stream")
        assertEquals(Pull.Failed(Transport.Interrupted), rt.pullClaim(claim, param, out(8)))
    }

    @Test
    fun `a claim stream registration on a finished stream is woken at once`() {
        val rt = runtime()
        val param = StreamNo(1u)
        val c = rt.query(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!.id
        rt.end(c, param)
        val waiter = Count()
        rt.wakeOn(Interest.ClaimStream(claim, param), waiter)
        assertEquals(1, waiter.wakes, "an ended stream does not change again")
    }

    /**
     * The JVM has no destructor, so no waker is dropped by the close: what this
     * keeps of the Rust test is that a close with such a waker stored finishes.
     */
    @Test
    fun `a stream waker whose task holds a handle drops without deadlock`() {
        val rt = runtime()
        val caller = rt.caller()
        val c = caller.query(iface, ord, bytes(1), null)
        caller.wakeOn(Interest.Stream(c, REPLY_STREAM), owning(rt.caller()))
        // The close forgets the call, which takes the stream waker, the last
        // reference to the handle it owns.
        assertCloseFinishes(caller, "a caller with a stream waker")
    }

    /** The next claim [handler] presents, with its arguments read. */
    private fun takeClaim(handler: Handler): ClaimId = handler.nextClaim(out(8))!!.id

    /**
     * Sends `Loopback.SLOTS` commands through [caller] and checks that each one
     * takes a slot: every slot of the table is free.
     */
    private fun assertEverySlotIsFree(caller: Caller, what: String) {
        for (round in 0 until Loopback.SLOTS) {
            try {
                caller.command(iface, ord, bytes(1), null)
            } catch (e: SendError) {
                fail("$what: send $round answered $e")
            }
        }
    }

    @Test
    fun `a stream parameter first named after a querys settlement starts released`() {
        val rt = runtime()
        val interrupted = Transport.Interrupted
        val c = rt.query(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        rt.settle(claim, ok())
        val param = StreamNo(1u)
        assertEquals(
            PushError.Failed(interrupted),
            assertThrows<PushError> { rt.push(c, param, bytes(2)) },
            "the settlement released the stream before anything named it",
        )
        assertEquals(PushError.Failed(interrupted), assertThrows<PushError> { rt.end(c, param) })
        rt.forget(c)
        assertEverySlotIsFree(rt, "after the forget")
    }

    @Test
    fun `a dropped handler completes a forget deferred on an acknowledged command`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val c = caller.command(iface, ord, bytes(1), null)
        val claim = takeClaim(handler)
        handler.settle(claim, ok())
        assertEquals(1, caller.push(c, StreamNo(1u), bytes(1)))
        caller.forget(c)
        handler.close()
        rt.handler()
        assertEverySlotIsFree(caller, "after the handler's drop")
    }

    @Test
    fun `a commands refusal releases its parameter streams and the forget frees its slot`() {
        val interrupted = Transport.Interrupted
        val param = StreamNo(1u)
        // A claim taken and settled with an error, and a claim offered through
        // `ShortClaim` and settled by its id with an error, as the generated
        // dispatch does when the arguments do not fit.
        for (offered in listOf(false, true)) {
            val rt = runtime()
            val c = rt.command(iface, ord, bytes(1, 2, 3), null)
            assertEquals(1, rt.push(c, param, bytes(1)))
            val claim = if (offered) {
                assertThrows<ReadError.ShortClaim>("an offer") { rt.nextClaim(out(1)) }.claim
            } else {
                takeClaim(rt)
            }
            val error: CallError = if (offered) Transport.Corrupt else Contract.UnknownInteraction
            rt.settle(claim, Result.failure(error))
            assertEquals(Result.failure<Unit>(error), rt.ack(c))
            assertEquals(
                PushError.Failed(interrupted),
                assertThrows<PushError> { rt.push(c, param, bytes(2)) },
                "offered $offered: the refusal released the stream",
            )
            rt.forget(c)
            assertEverySlotIsFree(rt, "after the forget of a refused command")
        }
    }

    @Test
    fun `a forget before the claim withdraws the call with its ended stream`() {
        val rt = runtime()
        rt.serve(iface, listOf(ord))
        val c = rt.command(iface, ord, bytes(1), null)
        val param = StreamNo(1u)
        assertEquals(1, rt.push(c, param, bytes(9)))
        rt.end(c, param)
        rt.forget(c)
        assertNull(rt.nextClaim(out(8)), "no provider is presented a call withdrawn before its claim")
        assertEverySlotIsFree(rt, "after the withdrawal")
    }

    @Test
    fun `a claim only offered has no streams and another handler reads them whole`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        val param = StreamNo(1u)
        val c = caller.query(iface, ord, bytes(1, 2, 3), null)
        assertEquals(1, caller.push(c, param, bytes(1)))
        val claim = assertThrows<ReadError.ShortClaim>("an offer") { first.nextClaim(out(1)) }.claim
        val unknown = Contract.UnknownInteraction
        assertEquals(
            ReadError.Contract(unknown),
            assertThrows<ReadError> { first.pullClaim(claim, param, out(8)) },
            "an offered claim is taken before its streams are read",
        )
        assertEquals(
            PushError.Contract(unknown),
            assertThrows<PushError> { first.pushClaim(claim, REPLY_STREAM, bytes(7)) },
        )
        assertEquals(PushError.Contract(unknown), assertThrows<PushError> { first.endClaim(claim, REPLY_STREAM) })
        first.close()
        val taken = takeClaim(second)
        assertEquals(claim, taken, "the call is presented under the same id")
        val buf = out(8)
        assertEquals(
            Pull.Chunk(1),
            second.pullClaim(taken, param, buf),
            "the next holder reads the stream from its first chunk",
        )
        assertArrayEquals(array(1), buf.written())
    }

    @Test
    fun `a dropped handler that moved no stream returns the call`() {
        val rt = runtime()
        val caller = rt.caller()
        val first = rt.handler()
        val second = rt.handler()
        val param = StreamNo(1u)
        val c = caller.query(iface, ord, bytes(1), null)
        val claim = takeClaim(first)
        assertEquals(Pull.Pending, first.pullClaim(claim, param, out(8)))
        first.wakeOn(Interest.ClaimStream(claim, param), Count())
        first.close()
        assertEquals(
            claim,
            takeClaim(second),
            "a Pending pull and a registration move nothing, so the call is presented again",
        )
        assertNull(caller.reply(c, out(8)), "the call is not settled")
    }

    @Test
    fun `the window counts only the non empty chunks`() {
        val rt = runtime()
        val c = rt.query(iface, ord, bytes(1), null)
        takeClaim(rt)
        val param = StreamNo(1u)
        repeat(Loopback.STREAM_WINDOW) {
            assertEquals(0, rt.push(c, param, bytes()), "an empty chunk is taken")
        }
        for (n in 0 until Loopback.STREAM_WINDOW) {
            assertEquals(1, rt.push(c, param, bytes(1)), "non-empty chunk $n fits the window beside the empty chunks")
        }
        assertEquals(0, rt.push(c, param, bytes(1)), "the window is full")
    }

    @Test
    fun `abort claim after the callers refusal is ended and settles with the providers error`() {
        val rt = runtime()
        val c = rt.query(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        val refusal = Contract.PreconditionFailed
        val error = Transport.Corrupt
        rt.abort(c, REPLY_STREAM, refusal)
        assertEquals(
            PushError.Ended,
            assertThrows<PushError> { rt.abortClaim(claim, REPLY_STREAM, error) },
            "the reply stream had already stopped",
        )
        assertEquals(Result.failure<Int>(error), rt.reply(c, out(8)), "the provider's abort is its verdict")
    }

    @Test
    fun `a settle ok on a streamed reply ends the stream and reports the bytes`() {
        val rt = runtime()
        val c = rt.query(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        assertEquals(1, rt.pushClaim(claim, REPLY_STREAM, bytes(1)))
        rt.settle(claim, ok(1, 2, 3))
        assertEquals(Result.success(3), rt.reply(c, out(8)))
        assertEquals(Pull.Chunk(1), rt.pull(c, REPLY_STREAM, out(8)))
        assertEquals(Pull.End, rt.pull(c, REPLY_STREAM, out(8)))
    }

    @Test
    fun `the providers push after its own abort of the reply stream is ended`() {
        val rt = runtime()
        rt.query(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        rt.abortClaim(claim, REPLY_STREAM, Transport.Interrupted)
        assertEquals(
            PushError.Ended,
            assertThrows<PushError> { rt.pushClaim(claim, REPLY_STREAM, bytes(1)) },
            "a stream its producer stopped is Ended for that producer",
        )
    }

    @Test
    fun `a deferred forget answers none from ack and reply while the slot is held`() {
        val rt = runtime()
        val c = rt.command(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        rt.settle(claim, ok())
        assertEquals(1, rt.push(c, StreamNo(1u), bytes(1)))
        rt.forget(c)
        assertNull(rt.ack(c), "a forgotten correlation has no outcome")
        assertNull(rt.reply(c, out(8)))
        repeat(Loopback.SLOTS - 1) { rt.command(iface, ord, bytes(1), null) }
        assertEquals(
            SendError.Busy,
            assertThrows<SendError> { rt.command(iface, ord, bytes(1), null) },
            "the forgotten call still holds its slot",
        )
    }

    @Test
    fun `a dropped handlers interruption releases a stream parameter not yet named`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val c = caller.query(iface, ord, bytes(1), null)
        val claim = takeClaim(handler)
        assertEquals(1, caller.push(c, StreamNo(1u), bytes(1)))
        assertEquals(Pull.Chunk(1), handler.pullClaim(claim, StreamNo(1u), out(8)))
        val later = StreamNo(2u)
        val waiter = Count()
        caller.wakeOn(Interest.Stream(c, later), waiter)
        handler.close()
        assertEquals(1, waiter.wakes, "the interruption wakes the waiter on a stream nothing named yet")
        val interrupted = Transport.Interrupted
        assertEquals(
            PushError.Failed(interrupted),
            assertThrows<PushError> { caller.push(c, later, bytes(2)) },
            "a stream parameter first named after the interruption starts stopped",
        )
        assertEquals(PushError.Failed(interrupted), assertThrows<PushError> { caller.end(c, later) })
        assertEquals(Result.failure<Int>(interrupted), caller.reply(c, out(8)))
    }

    @Test
    fun `a dropped handler releases a stream parameter of an acknowledged command not yet named`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        val param = StreamNo(1u)
        val c = caller.command(iface, ord, bytes(1), null)
        val claim = takeClaim(handler)
        handler.settle(claim, ok())
        val waiter = Count()
        caller.wakeOn(Interest.Stream(c, param), waiter)
        handler.close()
        assertEquals(1, waiter.wakes, "the drop wakes the waiter on a stream nothing named yet")
        val interrupted = Transport.Interrupted
        assertEquals(
            PushError.Failed(interrupted),
            assertThrows<PushError> { caller.push(c, param, bytes(1)) },
            "no provider is left to read a stream parameter first named after the drop",
        )
        assertEquals(PushError.Failed(interrupted), assertThrows<PushError> { caller.end(c, param) })
        assertEquals(Result.success(Unit), caller.ack(c), "the acknowledgment stands")
    }

    /**
     * Each way the provider is done with a stream parameter completes a forget
     * deferred on it, and the slot comes back: pulling `End`, pulling `Failed`,
     * aborting the interrupted stream, aborting the ended stream, and the
     * settlement of a query. A forget stops every stream that was not ended, so
     * after it the provider's abort always finds a finished stream and answers
     * `Ended`. Every other slot is taken by a call in flight, so the slot is
     * shown held before the provider is done and free after.
     */
    @Test
    fun `a deferred forget frees the slot once the provider is done with the parameter stream`() {
        val param = StreamNo(1u)
        val error = Contract.PreconditionFailed
        val interrupted = Transport.Interrupted
        for (done in 0 until 5) {
            val rt = runtime()
            val (c, claim) = if (done == 4) {
                val query = rt.query(iface, ord, bytes(1), null)
                query to takeClaim(rt)
            } else {
                val command = rt.command(iface, ord, bytes(1), null)
                val taken = takeClaim(rt)
                rt.settle(taken, ok())
                command to taken
            }
            assertEquals(1, rt.push(c, param, bytes(1)))
            if (done == 0 || done == 3) {
                rt.end(c, param)
            }
            rt.forget(c)
            repeat(Loopback.SLOTS - 1) { rt.command(iface, ord, bytes(1), null) }
            assertEquals(
                SendError.Busy,
                assertThrows<SendError> { rt.command(iface, ord, bytes(1), null) },
                "case $done: the forgotten call holds its slot while the provider has its stream to read",
            )
            when (done) {
                0 -> {
                    assertEquals(Pull.Chunk(1), rt.pullClaim(claim, param, out(8)))
                    assertEquals(Pull.End, rt.pullClaim(claim, param, out(8)))
                }
                1 -> {
                    assertEquals(Pull.Chunk(1), rt.pullClaim(claim, param, out(8)))
                    assertEquals(Pull.Failed(interrupted), rt.pullClaim(claim, param, out(8)))
                }
                2 -> assertEquals(
                    PushError.Ended,
                    assertThrows<PushError> { rt.abortClaim(claim, param, error) },
                    "the abort of a stream the forget interrupted",
                )
                3 -> assertEquals(
                    PushError.Ended,
                    assertThrows<PushError> { rt.abortClaim(claim, param, error) },
                    "the abort of an ended stream",
                )
                else -> rt.settle(claim, ok())
            }
            try {
                rt.command(iface, ord, bytes(1), null)
            } catch (e: SendError) {
                fail("case $done: the slot did not come back: $e")
            }
        }
    }

    @Test
    fun `a producers end and abort wake the receiver and a receivers refusal wakes the producer`() {
        val rt = runtime()
        val c = rt.query(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        val error = Contract.PreconditionFailed

        val ended = Count()
        rt.wakeOn(Interest.ClaimStream(claim, StreamNo(1u)), ended)
        assertEquals(0, ended.wakes, "nothing changed since the registration")
        rt.end(c, StreamNo(1u))
        assertEquals(1, ended.wakes, "the caller's end wakes the provider's waiter")

        val aborted = Count()
        rt.wakeOn(Interest.ClaimStream(claim, StreamNo(2u)), aborted)
        assertEquals(0, aborted.wakes, "nothing changed since the registration")
        rt.abort(c, StreamNo(2u), error)
        assertEquals(1, aborted.wakes, "the caller's abort wakes the provider's waiter")

        val refused = Count()
        rt.wakeOn(Interest.Stream(c, StreamNo(3u)), refused)
        assertEquals(0, refused.wakes, "nothing changed since the registration")
        rt.abortClaim(claim, StreamNo(3u), error)
        assertEquals(1, refused.wakes, "the provider's refusal wakes the caller's waiter")
    }

    /**
     * Each operation that moves a stream marks the call, so that the handler's
     * close interrupts it instead of presenting it again: a push, a pull that
     * answers `End`, a pull that answers `Failed`, and an abort. The pull of a
     * chunk is pinned by
     * `a dropped handler interrupts a taken call whose streams moved`.
     */
    @Test
    fun `a dropped handler interrupts a call on which it pushed pulled an end or aborted`() {
        val param = StreamNo(1u)
        val error = Contract.PreconditionFailed
        val interrupted = Transport.Interrupted
        for (moved in 0 until 4) {
            val rt = runtime()
            val caller = rt.caller()
            val first = rt.handler()
            val second = rt.handler()
            val c = caller.query(iface, ord, bytes(1), null)
            val claim = takeClaim(first)
            when (moved) {
                0 -> assertEquals(1, first.pushClaim(claim, REPLY_STREAM, bytes(1)))
                1 -> {
                    caller.end(c, param)
                    assertEquals(Pull.End, first.pullClaim(claim, param, out(8)))
                }
                2 -> {
                    caller.abort(c, param, error)
                    assertEquals(Pull.Failed(error), first.pullClaim(claim, param, out(8)))
                }
                else -> first.abortClaim(claim, param, error)
            }
            first.close()
            assertNull(second.nextClaim(out(8)), "case $moved: a call whose streams moved is not presented again")
            assertEquals(
                Result.failure<Int>(interrupted),
                caller.reply(c, out(8)),
                "case $moved: the call settles with the interruption",
            )
        }
    }

    @Test
    fun `a forget wakes the stream waiters of a call with no stream parameter`() {
        val rt = runtime()
        val query = rt.query(iface, ord, bytes(1), null)
        takeClaim(rt)
        val reply = Count()
        rt.wakeOn(Interest.Stream(query, REPLY_STREAM), reply)
        assertEquals(0, reply.wakes, "nothing changed since the registration")
        rt.forget(query)
        assertEquals(1, reply.wakes, "the forget of a call in flight wakes its reply stream waiter")

        val command = rt.command(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        rt.settle(claim, ok())
        val param = Count()
        rt.wakeOn(Interest.Stream(command, StreamNo(1u)), param)
        assertEquals(0, param.wakes, "a settled command's stream parameter goes on, so the waker is stored")
        rt.forget(command)
        assertEquals(1, param.wakes, "the forget that reclaims the slot wakes its stream waiter")
    }

    @Test
    fun `a forgotten unacknowledged commands ended stream is read after the acknowledgment`() {
        val rt = runtime()
        val param = StreamNo(1u)
        val c = rt.command(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        assertEquals(1, rt.push(c, param, bytes(1)))
        rt.end(c, param)
        rt.forget(c)
        rt.settle(claim, ok())
        val buf = out(8)
        assertEquals(
            Pull.Chunk(1),
            rt.pullClaim(claim, param, buf),
            "the ended stream is kept for the provider across the acknowledgment",
        )
        assertArrayEquals(array(1), buf.written())
        assertEquals(Pull.End, rt.pullClaim(claim, param, out(8)))
        assertEverySlotIsFree(rt, "after the provider read the stream to its end")
    }

    @Test
    fun `a second forget of a deferred call leaves its slot held`() {
        val rt = runtime()
        val param = StreamNo(1u)
        val c = rt.command(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        rt.settle(claim, ok())
        assertEquals(1, rt.push(c, param, bytes(1)))
        rt.forget(c)
        rt.forget(c)
        repeat(Loopback.SLOTS - 1) { rt.command(iface, ord, bytes(1), null) }
        assertEquals(
            SendError.Busy,
            assertThrows<SendError> { rt.command(iface, ord, bytes(1), null) },
            "the slot is held until the provider reads the stream, however often the call is forgotten",
        )
        assertEquals(Pull.Chunk(1), rt.pullClaim(claim, param, out(8)))
        assertEquals(Pull.Failed(Transport.Interrupted), rt.pullClaim(claim, param, out(8)))
        rt.command(iface, ord, bytes(1), null)
    }

    @Test
    fun `a deferred forget wakes the outcome waiter and a later registration is woken at once`() {
        val rt = runtime()
        val c = rt.query(iface, ord, bytes(1), null)
        takeClaim(rt)
        assertEquals(1, rt.push(c, StreamNo(1u), bytes(1)))
        val outcome = Count()
        rt.wakeOn(Interest.Outcome(c), outcome)
        assertEquals(0, outcome.wakes, "nothing changed since the registration")
        rt.forget(c)
        assertEquals(1, outcome.wakes, "the deferred forget wakes the outcome waiter")
        val late = Count()
        rt.wakeOn(Interest.Outcome(c), late)
        assertEquals(1, late.wakes, "no outcome will be readable for a forgotten call whose slot is still held")
    }

    @Test
    fun `a providers push and end on the reply stream after a deferred forget are interrupted`() {
        val rt = runtime()
        val c = rt.command(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        rt.settle(claim, ok())
        assertEquals(1, rt.push(c, StreamNo(1u), bytes(1)))
        rt.forget(c)
        val interrupted = Transport.Interrupted
        assertEquals(
            PushError.Failed(interrupted),
            assertThrows<PushError> { rt.pushClaim(claim, REPLY_STREAM, bytes(1)) },
            "the forget answers before the ended reply stream does",
        )
        assertEquals(PushError.Failed(interrupted), assertThrows<PushError> { rt.endClaim(claim, REPLY_STREAM) })
    }

    @Test
    fun `a settlement refused through end claim answers too large`() {
        val rt = runtime()
        rt.query(iface, ord, bytes(1), null)
        val claim = takeClaim(rt)
        rt.failNextSettle()
        assertEquals(
            PushError.TooLarge(0),
            assertThrows<PushError> { rt.endClaim(claim, REPLY_STREAM) },
            "the injected settlement failure is reported as the push error of its kind",
        )
    }

    @Test
    fun `a dropped handlers claim stream waiter is woken`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        caller.query(iface, ord, bytes(1), null)
        val claim = takeClaim(handler)
        val waiter = Count()
        handler.wakeOn(Interest.ClaimStream(claim, StreamNo(1u)), waiter)
        assertEquals(0, waiter.wakes, "nothing changed since the registration")
        handler.close()
        assertEquals(1, waiter.wakes, "the dropped handler's claim stream waiter is woken: its claim is gone")
    }

    /**
     * The JVM has no destructor, so no waker is dropped by the close: what this
     * keeps of the Rust test is that a close with such a waker stored finishes.
     */
    @Test
    fun `a handler whose stored claim stream waker owns another handle drops without deadlock`() {
        val rt = runtime()
        val caller = rt.caller()
        val handler = rt.handler()
        caller.query(iface, ord, bytes(1), null)
        val claim = takeClaim(handler)
        handler.wakeOn(Interest.ClaimStream(claim, StreamNo(1u)), owning(rt.handler()))
        // The close takes the claim's stream waker, the last reference to the
        // handle it owns, and drops it after the lock is released.
        assertCloseFinishes(handler, "a handler with a claim stream waker")
    }

    // ---------------------------------------------------------------------
    // Kotlin's own: the ByteBuffer rule, split and close.
    // ---------------------------------------------------------------------

    @Test
    fun `an input buffer is read from its position and left where it was`() {
        val rt = runtime()
        val input = ByteBuffer.wrap(array(0, 0, 5, 6))
        input.position(2)
        rt.set(iface, ord, input)
        rt.commit()
        assertEquals(2, input.position(), "the caller's buffer is not consumed")

        val buf = out(8)
        rt.read(iface, ord, buf)
        assertArrayEquals(array(5, 6), buf.written(), "only the bytes from the position to the limit")
    }

    @Test
    fun `an output buffer is written from its position and advanced`() {
        val rt = runtime()
        rt.set(iface, ord, bytes(9, 9))
        rt.commit()
        val buf = ByteBuffer.allocate(8)
        buf.position(3)
        assertEquals(2, rt.read(iface, ord, buf).len)
        assertEquals(5, buf.position())
        assertEquals(9, buf.get(3).toInt())
    }

    @Test
    fun `a staged value is copied, so a later change to the caller's buffer does not reach it`() {
        val rt = runtime()
        val backing = array(1)
        rt.set(iface, ord, ByteBuffer.wrap(backing))
        backing[0] = 2
        rt.commit()
        val buf = out(8)
        rt.read(iface, ord, buf)
        assertArrayEquals(array(1), buf.written())
    }

    @Test
    fun `the aggregate refuses its port methods once split`() {
        val rt = runtime()
        val handles = rt.split()
        assertThrows<IllegalStateException> { rt.commit() }
        assertThrows<IllegalStateException> { rt.split() }
        handles.writer.set(iface, ord, bytes(1))
        handles.writer.commit()
        assertEquals(Provenance.Live, rt.reader().read(iface, ord, out(8)).provenance, "the factories still work")
    }

    @Test
    fun `a closed source receives nothing more`() {
        val rt = runtime()
        val source = rt.source()
        source.subscribe(iface, listOf(ord))
        source.close()
        rt.raise(iface, ord, bytes(1), null)
        assertNull(source.next(out(8)))
    }

    @Test
    fun `a claim is settled with a call error and nothing else`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1), null)
        val claim = rt.nextClaim(out(8))!!
        assertThrows<IllegalArgumentException> { rt.settle(claim.id, Result.failure(RuntimeException("no"))) }
        rt.settle(claim.id, ok())
    }

    // ---------------------------------------------------------------------

    private fun array(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(array(*values))

    private fun out(size: Int): ByteBuffer = ByteBuffer.allocate(size)

    private fun ok(vararg values: Int): Result<ByteBuffer> = Result.success(bytes(*values))

    /** The bytes a port wrote into this buffer: from 0 to its position. */
    private fun ByteBuffer.written(): ByteArray = ByteArray(position()).also {
        val view = duplicate()
        view.flip()
        view.get(it)
    }

    /** Every occurrence waiting on [source], as its payload. */
    private fun payloads(source: ridl.rt.port.EventSource): List<List<Byte>> =
        generateSequence { out(8).let { buf -> source.next(buf)?.let { buf.written().toList() } } }.toList()

    private fun drain(source: ridl.rt.port.EventSource): List<ULong> =
        generateSequence { source.next(out(8)) }.map { it.envelope.seq }.toList()
}
