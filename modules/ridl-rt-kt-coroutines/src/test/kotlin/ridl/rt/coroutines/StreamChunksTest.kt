package ridl.rt.coroutines

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.encoding.Encoding
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.payload.EncodeError
import ridl.rt.payload.Malformed
import ridl.rt.payload.Payload
import ridl.rt.payload.Rule
import ridl.rt.payload.VerifyError
import ridl.rt.payload.Violation
import java.nio.ByteBuffer

/** How the values of a stream map to its chunks: the part of `Streams.kt` no port is needed for. */
class StreamChunksTest {
    private fun ByteBuffer.bytes(): ByteArray = ByteArray(remaining()).also { duplicate().get(it) }

    @Test
    fun `bytes are split at the limit, and the chunks read back whole`() {
        val value = ByteArray(10) { it.toByte() }
        val chunks = byteChunks(4)
        val split = chunks.encode(value)
        assertEquals(listOf(4, 4, 2), split.map { it.remaining() })
        assertArrayEquals(value, split.flatMap { chunks.decode(it.duplicate()).toList() }.toByteArray())
    }

    @Test
    fun `bytes of exactly the limit are one chunk, and none are no chunk`() {
        assertEquals(1, byteChunks(4).encode(ByteArray(4)).size)
        assertTrue(byteChunks(4).encode(ByteArray(0)).isEmpty())
    }

    @Test
    fun `text is cut between characters, so every chunk decodes alone`() {
        // Each `é` is two bytes and each `😀` four, so a cut at 5 falls inside one.
        val value = "aéé😀😀é"
        val chunks = textChunks(5)
        val split = chunks.encode(value)
        assertTrue(split.all { it.remaining() <= 5 }, "no chunk is above the limit")
        assertEquals(value, split.joinToString("") { chunks.decode(it.duplicate()) })
    }

    @Test
    fun `text of no character is no chunk`() {
        assertTrue(textChunks(4).encode("").isEmpty())
    }

    @Test
    fun `a text chunk that is not UTF-8 is refused with the rule Utf8`() {
        assertEquals(
            Contract.InvalidValue(Violation("string", Rule.Utf8)),
            assertThrows<CallError> { textChunks(4).decode(ByteBuffer.wrap(byteArrayOf(0xC3.toByte()))) },
        )
    }

    @Test
    fun `a text limit below a whole character is refused`() {
        assertThrows<IllegalArgumentException> { textChunks(3) }
    }

    private class Element(private val verifyError: VerifyError?, private val encodeError: EncodeError?) : Payload<Int, Int> {
        override val encoding: Encoding = Encoding.FlatBuffers
        override val maxSize: Int = 4

        override fun encode(value: Int, out: ByteBuffer): Int {
            encodeError?.let { throw it }
            out.putInt(value)
            return 4
        }

        override fun verify(buf: ByteBuffer): Int {
            verifyError?.let { throw it }
            return buf.getInt()
        }

        override fun decode(view: Int): Int = view
    }

    @Test
    fun `an element is one chunk, read back by its codec`() {
        val chunks = elementChunks(Element(null, null))
        assertEquals(4, chunks.maxChunk)
        assertEquals(7, chunks.decode(chunks.encode(7).single()))
    }

    @Test
    fun `an element that breaks a constraint is refused with its violation`() {
        val violation = Violation("Level", Rule.Range)
        assertEquals(
            Contract.InvalidValue(violation),
            assertThrows<CallError> { elementChunks(Element(VerifyError.Contract(violation), null)).decode(ByteBuffer.allocate(4)) },
        )
        assertEquals(
            Contract.InvalidValue(violation),
            assertThrows<CallError> { elementChunks(Element(null, EncodeError.Contract(violation))).encode(1) },
        )
    }

    @Test
    fun `an element that is not well formed is corrupt`() {
        assertEquals(
            Transport.Corrupt,
            assertThrows<CallError> { elementChunks(Element(VerifyError.Structure(Malformed.OutOfBounds), null)).decode(ByteBuffer.allocate(4)) },
        )
    }
}
