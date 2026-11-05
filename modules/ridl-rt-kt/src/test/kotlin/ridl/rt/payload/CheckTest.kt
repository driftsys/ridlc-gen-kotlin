package ridl.rt.payload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import ridl.rt.encoding.Encoding
import ridl.rt.sample.Detection
import java.nio.ByteBuffer

/** `ridl_rt::payload::check`: a contract violation stays one, and every other verify error is corruption. */
class CheckTest {
    private class Failing(private val error: VerifyError?) : Payload<Unit, Unit> {
        override val encoding: Encoding = Encoding.FlatBuffers

        override val maxSize: Int = 0

        override fun encode(value: Unit, out: ByteBuffer): Int = 0

        override fun verify(buf: ByteBuffer) {
            error?.let { throw it }
        }

        override fun decode(view: Unit) = Unit
    }

    private val bytes = ByteBuffer.allocate(0)

    @Test
    fun `a payload that verifies passes`() {
        assertDoesNotThrow { check(Failing(null), bytes) }
    }

    @Test
    fun `a contract violation is an invalid value`() {
        val violation = Violation("Level", Rule.Range)
        assertEquals(
            Detection.InvalidValue(violation),
            assertThrows<Detection> { check(Failing(VerifyError.Contract(violation)), bytes) },
        )
    }

    @Test
    fun `any other verify error is corruption`() {
        assertEquals(
            Detection.Corrupt,
            assertThrows<Detection> { check(Failing(VerifyError.Structure(Malformed.OutOfBounds)), bytes) },
        )
    }
}
