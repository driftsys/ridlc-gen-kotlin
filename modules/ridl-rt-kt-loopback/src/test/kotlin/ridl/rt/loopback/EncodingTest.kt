package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.encoding.Encoding
import ridl.rt.port.Encoded
import ridl.rt.sample.Provenance
import java.nio.ByteBuffer

/**
 * The encoding a runtime's ports carry (driftsys/ridl#887):
 * `crates/ridl-loopback/tests/encoding.rs` at ridl 0.10.0, under the same
 * names but the last, which Rust calls `the_encoding_is_a_type_and_not_a_value`:
 * here the encoding is a value. The aggregate and every role handle present [Encoded], so a
 * generated face reads the encoding from its port. The default is
 * FlatBuffers; the constructor's `encoding` names another, where Rust names
 * it as the type parameter of `Loopback::<E>::with_encoding`.
 * `a_mut_borrow_of_a_handle_is_encoded` has no Kotlin spelling: Kotlin has
 * no borrows.
 */
class EncodingTest {
    private val catalog = CatalogRef("face.demo", CatalogHash(ByteArray(32)))

    /** The encoding [port] carries, read through [Encoded] alone. */
    private fun encodingOf(port: Encoded): String = port.encoding.encodingName

    @Test
    fun `the default encoding is flatbuffers`() {
        val rt = Loopback(catalog)
        assertEquals("flatbuffers", encodingOf(rt))
        assertEquals("flatbuffers", encodingOf(rt.attach()))
        assertEquals("flatbuffers", encodingOf(rt.handler()))
        assertEquals("flatbuffers", encodingOf(rt.split().handler))
    }

    @Test
    fun `with encoding types every handle that meets a face`() {
        val rt = Loopback(catalog, Encoding.Proto3)
        assertEquals("proto3", encodingOf(rt))
        assertEquals("proto3", encodingOf(rt.attach()))

        // Each of the six role handles the aggregate hands out.
        for (handle in listOf(rt.reader(), rt.writer(), rt.source(), rt.sink(), rt.caller(), rt.handler())) {
            assertEquals("proto3", encodingOf(handle), handle::class.simpleName)
        }

        // And the six it holds, as `split` hands them out.
        val handles = rt.split()
        for (handle in listOf(handles.reader, handles.writer, handles.source, handles.sink, handles.caller, handles.handler)) {
            assertEquals("proto3", encodingOf(handle), handle::class.simpleName)
        }
    }

    @Test
    fun `the encoding is not shared between runtimes`() {
        val proto = Loopback(catalog, Encoding.Proto3)
        val flat = Loopback(catalog)

        proto.set(InterfaceNo(1u), Ordinal(1u), ByteBuffer.wrap(byteArrayOf(1)))
        proto.commit()
        flat.set(InterfaceNo(1u), Ordinal(2u), ByteBuffer.wrap(byteArrayOf(2)))
        flat.commit()

        // Each runtime is its own store: what one published, the other has not.
        val out = ByteBuffer.allocate(8)
        val sample = proto.read(InterfaceNo(1u), Ordinal(1u), out)
        assertArrayEquals(byteArrayOf(1), out.array().copyOf(sample.len))
        assertEquals(Provenance.Init, proto.read(InterfaceNo(1u), Ordinal(2u), ByteBuffer.allocate(8)).provenance)
        val back = ByteBuffer.allocate(8)
        val flatSample = flat.read(InterfaceNo(1u), Ordinal(2u), back)
        assertArrayEquals(byteArrayOf(2), back.array().copyOf(flatSample.len))
        assertEquals(Provenance.Init, flat.read(InterfaceNo(1u), Ordinal(1u), ByteBuffer.allocate(8)).provenance)

        assertEquals("repr-c", encodingOf(Loopback(catalog, Encoding.ReprC)))
    }
}
