package ridl.rt.trace

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** `crates/ridl-rt/tests/trace.rs`, `propagation.rs` and `propagation_unset.rs`. */
class TraceTest {
    private fun context(byte: Int) = TraceContext(ByteArray(16) { byte.toByte() }, ByteArray(8) { byte.toByte() }, byte.toUByte())

    @Test
    fun `a trace context is a 16-byte trace id, an 8-byte span id and a flags byte`() {
        assertThrows<IllegalArgumentException> { TraceContext(ByteArray(15), ByteArray(8), 0u) }
        assertThrows<IllegalArgumentException> { TraceContext(ByteArray(16), ByteArray(9), 0u) }
        val zero = TraceContext(ByteArray(16), ByteArray(8), 0u)
        assertArrayEquals(ByteArray(16), zero.traceId, "an all-zero id is carried like any other")
    }

    @Test
    fun `equality is over the bytes, and the bytes cannot change after it is built`() {
        val bytes = ByteArray(16) { 1 }
        val ctx = TraceContext(bytes, ByteArray(8) { 1 }, 1u)
        bytes[0] = 9
        ctx.traceId[0] = 9
        assertEquals(context(1), ctx)
        assertEquals(context(1).hashCode(), ctx.hashCode())
        assertNotEquals(context(2), ctx)
        assertNotEquals(TraceContext(ByteArray(16) { 1 }, ByteArray(8) { 1 }, 2u), ctx, "the flags count")
        assertEquals(
            "TraceContext(traceId=01010101010101010101010101010101, spanId=0101010101010101, flags=01)",
            ctx.toString(),
        )
    }

    /** The hook is the process's, so one test covers its whole life. */
    @Test
    fun `the hook is unset, then set once, and the second attempt is refused`() {
        val a = Marker(1)
        assertNull(propagation())
        setPropagation(a)
        assertSame(a, propagation())
        val refused = assertThrows<AlreadySet> { setPropagation(Marker(2)) }
        assertEquals("a trace propagation hook is already registered", refused.message)
        assertEquals(context(1), propagation()?.current(), "the first hook is kept")
    }

    private inner class Marker(private val byte: Int) : Propagation {
        override fun current(): TraceContext = context(byte)

        override fun enter(received: TraceContext?) {}

        override fun leave() {}
    }
}
