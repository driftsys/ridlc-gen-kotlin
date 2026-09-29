package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.error.Contract
import ridl.rt.port.ReadError
import ridl.rt.port.SettleError
import ridl.rt.sample.Duration
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import java.nio.ByteBuffer

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
 * - `HandlerHandle.served` and `Loopback.split` are this runtime's own API;
 * - a handler that has served nothing is presented every call, this runtime's
 *   deliberate deviation from `Handler.serve`;
 * - provisioning a `fixed` has no port;
 * - which error the injected fault reports is left to a runtime;
 * - the threading model is ADR-0021 decision 12's, which the suite leaves out.
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

        first.raise(iface, ord, bytes(1))
        second.raise(iface, ord, bytes(2))
        first.raise(iface, ord, bytes(3))
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
        rt.caller().command(InterfaceNo(2u), other, bytes(1))
        assertEquals(InterfaceNo(2u), rt.handler().nextClaim(out(8))!!.iface)
    }

    @Test
    fun `the injected settle failure is too large with no capacity`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1))
        val claim = rt.nextClaim(out(8))!!
        rt.failNextSettle()
        assertEquals(
            SettleError.TooLarge(0),
            assertThrows<SettleError> { rt.settle(claim.id, Result.success(bytes())) },
            "the one fault this runtime injects",
        )
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
    // Kotlin's own: the ByteBuffer rule, split and close.
    // ---------------------------------------------------------------------

    @Test
    fun `an input buffer is read from its position and left where it was`() {
        val rt = runtime()
        val input = ByteBuffer.wrap(array(0, 0, 5, 6)).position(2)
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
        val buf = ByteBuffer.allocate(8).position(3)
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
        rt.raise(iface, ord, bytes(1))
        assertNull(source.next(out(8)))
    }

    @Test
    fun `a claim is settled with a call error and nothing else`() {
        val rt = runtime()
        rt.command(iface, ord, bytes(1))
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
    private fun ByteBuffer.written(): ByteArray = ByteArray(position()).also { duplicate().flip().get(it) }

    private fun drain(source: ridl.rt.port.EventSource): List<ULong> =
        generateSequence { source.next(out(8)) }.map { it.envelope.seq }.toList()
}
