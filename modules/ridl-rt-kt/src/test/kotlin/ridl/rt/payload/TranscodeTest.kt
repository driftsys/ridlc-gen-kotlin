package ridl.rt.payload

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.rt.encoding.Encoding
import java.nio.ByteBuffer

/** `ridl_rt::payload::transcode`: the tests of `payload.rs`, case for case. */
class TranscodeTest {
    private data class Speed(val value: Int)

    /** Two little-endian bytes from the position; every value is legal. */
    private object PodbufSpeed : Payload<Speed, ByteBuffer> {
        override val encoding: Encoding = Encoding.Podbuf

        override val maxSize: Int = 2

        override fun encode(value: Speed, out: ByteBuffer): Int {
            if (out.remaining() < 2) throw EncodeError.Capacity(2, out.remaining())
            out.put(value.value.toByte())
            out.put((value.value shr 8).toByte())
            return 2
        }

        override fun verify(buf: ByteBuffer): ByteBuffer {
            if (buf.remaining() != 2) throw VerifyError.Structure(Malformed.OutOfBounds)
            return buf.slice()
        }

        override fun decode(view: ByteBuffer): Speed = Speed(u16(view, 0))
    }

    /** A marker byte, then two little-endian bytes; a value above [SPEED_MAX] is refused. */
    private object Proto3Speed : Payload<Speed, ByteBuffer> {
        override val encoding: Encoding = Encoding.Proto3

        override val maxSize: Int = 3

        override fun encode(value: Speed, out: ByteBuffer): Int {
            if (value.value > SPEED_MAX) throw EncodeError.Contract(Violation("Speed", Rule.Range))
            if (out.remaining() < 3) throw EncodeError.Capacity(3, out.remaining())
            out.put(MARKER)
            out.put(value.value.toByte())
            out.put((value.value shr 8).toByte())
            return 3
        }

        override fun verify(buf: ByteBuffer): ByteBuffer {
            if (buf.remaining() != 3 || buf.get(buf.position()) != MARKER) throw VerifyError.Structure(Malformed.WireType)
            if (u16(buf, buf.position() + 1) > SPEED_MAX) throw VerifyError.Contract(Violation("Speed", Rule.Range))
            return buf.slice()
        }

        override fun decode(view: ByteBuffer): Speed = Speed(u16(view, 1))
    }

    private fun <T, V> encoded(codec: Payload<T, V>, value: T): ByteBuffer {
        val out = ByteBuffer.allocate(8)
        val written = codec.encode(value, out)
        return ByteBuffer.wrap(out.array(), 0, written)
    }

    private fun bytes(buffer: ByteBuffer, from: Int, size: Int): ByteArray = buffer.array().copyOfRange(from, from + size)

    @Test
    fun `transcode matches direct encode`() {
        val out = ByteBuffer.allocate(8)
        val written = transcode(PodbufSpeed, Proto3Speed, encoded(PodbufSpeed, Speed(120)), out)
        val direct = encoded(Proto3Speed, Speed(120))
        assertArrayEquals(bytes(direct, 0, direct.remaining()), bytes(out, 0, written))
        assertEquals(written, out.position())
        assertEquals(Speed(120), Proto3Speed.decode(Proto3Speed.verify(ByteBuffer.wrap(out.array(), 0, written))))
    }

    @Test
    fun `transcode reverse direction`() {
        val out = ByteBuffer.allocate(8)
        val written = transcode(Proto3Speed, PodbufSpeed, encoded(Proto3Speed, Speed(250)), out)
        val direct = encoded(PodbufSpeed, Speed(250))
        assertArrayEquals(bytes(direct, 0, direct.remaining()), bytes(out, 0, written))
        assertEquals(Speed(250), PodbufSpeed.decode(PodbufSpeed.verify(ByteBuffer.wrap(out.array(), 0, written))))
    }

    @Test
    fun `transcode same encoding is identity`() {
        val source = encoded(Proto3Speed, Speed(77))
        val out = ByteBuffer.allocate(8)
        val written = transcode(Proto3Speed, Proto3Speed, source.duplicate(), out)
        assertArrayEquals(bytes(source, 0, source.remaining()), bytes(out, 0, written))
    }

    @Test
    fun `transcode refuses bad input`() {
        val error = assertThrows<TranscodeError> {
            transcode(PodbufSpeed, Proto3Speed, ByteBuffer.wrap(byteArrayOf(1, 2, 3)), ByteBuffer.allocate(8))
        }
        assertEquals(TranscodeError.Verify(VerifyError.Structure(Malformed.OutOfBounds)), error)
    }

    @Test
    fun `transcode reports contract`() {
        // `PodbufSpeed` accepts 301; `Proto3Speed` refuses it.
        val error = assertThrows<TranscodeError> {
            transcode(PodbufSpeed, Proto3Speed, ByteBuffer.wrap(byteArrayOf(0x2d, 0x01)), ByteBuffer.allocate(8))
        }
        assertEquals(TranscodeError.Encode(EncodeError.Contract(Violation("Speed", Rule.Range))), error)
    }

    @Test
    fun `transcode reports capacity`() {
        val error = assertThrows<TranscodeError> {
            transcode(PodbufSpeed, Proto3Speed, encoded(PodbufSpeed, Speed(120)), ByteBuffer.allocate(1))
        }
        assertEquals(TranscodeError.Encode(EncodeError.Capacity(3, 1)), error)
    }

    private companion object {
        const val SPEED_MAX = 300
        const val MARKER: Byte = 0x08

        fun u16(buf: ByteBuffer, at: Int): Int = (buf.get(at).toInt() and 0xFF) or ((buf.get(at + 1).toInt() and 0xFF) shl 8)
    }
}
