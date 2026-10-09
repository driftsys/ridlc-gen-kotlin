package ridl.rt.trace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `crates/ridl-rt/tests/propagation.rs` and `propagation_unset.rs`. The hook
 * is set once per process and never cleared, so this class runs in a JVM of
 * its own, the `processHookTest` task, as each of those is a test binary of
 * its own in Rust (#52); one test covers the hook's whole life.
 */
@Tag("process-hook")
class PropagationTest {
    private fun context(byte: Int) = TraceContext(ByteArray(16) { byte.toByte() }, ByteArray(8) { byte.toByte() }, byte.toUByte())

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
