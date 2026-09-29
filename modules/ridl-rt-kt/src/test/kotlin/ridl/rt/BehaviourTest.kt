package ridl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.Kind
import ridl.rt.encoding.Encoding
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.payload.ConstraintViolation
import ridl.rt.payload.Rule
import ridl.rt.payload.Violation
import ridl.rt.port.ReadError
import ridl.rt.sample.Cause
import ridl.rt.sample.Detection
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Sample
import ridl.rt.sample.Timestamp

/** The tests of `crates/ridl-rt/src`, spelled in Kotlin, and the JVM-only properties §3 adds. */
class BehaviourTest {
    private fun hash(fill: Int) = CatalogHash(ByteArray(32) { fill.toByte() })

    @Test
    fun `catalog refs are equal only when name and hash are equal`() {
        val base = CatalogRef("vehicle", hash(1))
        assertEquals(base, CatalogRef("vehicle", hash(1)))
        assertEquals(base.hashCode(), CatalogRef("vehicle", hash(1)).hashCode())
        assertNotEquals(base, CatalogRef("vehicle", hash(2)))
        assertNotEquals(base, CatalogRef("cabin", hash(1)))
    }

    @Test
    fun `a catalog hash is 32 bytes and cannot be changed through its array`() {
        assertThrows<IllegalArgumentException> { CatalogHash(ByteArray(31)) }
        val bytes = ByteArray(32)
        val h = CatalogHash(bytes)
        bytes[0] = 9
        h.toByteArray()[1] = 9
        assertEquals(CatalogHash(ByteArray(32)), h)
    }

    @Test
    fun `kind values are the language order from one`() {
        assertEquals(listOf(1, 2, 3, 4, 5), Kind.entries.map { it.value })
        assertEquals(listOf("Signal", "Event", "Command", "Query", "Fixed"), Kind.entries.map { it.name })
        assertEquals(Kind.Query, Kind.fromValue(4))
        assertEquals(null, Kind.fromValue(0))
    }

    @Test
    fun `each encoding has the frame's tag and the cargo feature's name`() {
        assertEquals(listOf(1, 2, 3), Encoding.entries.map { it.tag })
        assertEquals(listOf("flatbuffers", "proto3", "repr-c"), Encoding.entries.map { it.encodingName })
        assertEquals(Encoding.Proto3, Encoding.fromTag(2))
    }

    @Test
    fun `only a live value that is not stale is usable`() {
        val violation = Violation("Speed", Rule.Range)
        val provenances = listOf(
            Provenance.Init,
            Provenance.Live,
            Provenance.Invalid(Cause.Declared),
            Provenance.Invalid(Cause.Detected(Detection.InvalidValue(violation))),
            Provenance.Invalid(Cause.Detected(Detection.Corrupt)),
        )
        val freshnesses = listOf(Freshness.Fresh, Freshness.Stale(Duration(1)), Freshness.Unbounded)
        for (provenance in provenances) {
            for (freshness in freshnesses) {
                val sample = Sample(0, provenance, freshness, Envelope(Timestamp(0), 0u))
                val expected = provenance == Provenance.Live && freshness !is Freshness.Stale
                assertEquals(expected, sample.usable(), "$provenance with $freshness")
            }
        }
    }

    @Test
    fun `a call error names its two arms and every variant of both`() {
        // A `when` with no `else` over every variant: a variant added to or
        // removed from either arm fails this test to compile.
        fun name(e: CallError): String = when (e) {
            is Contract.InvalidValue -> "invalid"
            Contract.PreconditionFailed -> "precondition"
            Contract.ContractBroken -> "broken"
            Contract.UnknownInteraction -> "unknown"
            Transport.Timeout -> "timeout"
            Transport.Undelivered -> "undelivered"
            Transport.Down -> "down"
            Transport.Corrupt -> "corrupt"
            Transport.Busy -> "busy"
        }
        assertEquals("precondition", name(Contract.PreconditionFailed))
        assertEquals("corrupt", name(Transport.Corrupt))
    }

    @Test
    fun `a call outcome travels in a Kotlin Result`() {
        val outcome: Result<Unit> = Result.failure(Contract.ContractBroken)
        assertEquals(Contract.ContractBroken, outcome.exceptionOrNull())
    }

    @Test
    fun `an error is a value with no stack trace`() {
        assertEquals(0, ReadError.Detached.stackTrace.size)
        assertEquals(0, ReadError.Short(4).stackTrace.size)
        assertEquals(ReadError.Short(4), ReadError.Short(4))
        assertEquals(ReadError.Contract(Contract.UnknownInteraction), ReadError.Contract(Contract.UnknownInteraction))
    }

    @Test
    fun `a constraint violation carries the violation a payload check reports`() {
        val e = ConstraintViolation("Level", Rule.Range, 101L)
        assertEquals(Violation("Level", Rule.Range), e.violation)
        assertTrue(e.message!!.contains("Level"))
    }
}
