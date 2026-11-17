package ridl.descriptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `verify` is total: over any bytes it returns a catalog or throws a
 * [VerifyError], and a catalog it returns encodes to bytes it verifies again.
 * The Rust test is `verify_is_total_over_arbitrary_bytes` in
 * `crates/ridl-descriptor/tests/verify.rs`, with the same inputs.
 */
class TotalityTest {
    private fun probe(bytes: ByteArray) {
        val catalog = try {
            verify(bytes)
        } catch (_: VerifyError) {
            return
        }
        verify(encode(catalog))
    }

    private fun minimal(): ByteArray = encode(Catalog(SCHEMA_VERSION, "p", ByteArray(32), "0.0.0", emptyList(), emptyList(), null))

    private fun size(encoding: Encoding, bytes: Long, state: SizeState, cause: UnboundedCause) = MaxSize(encoding, bytes, state, cause)

    /** A catalog with one table at every nesting level. */
    private fun nested(): ByteArray = encode(
        Catalog(
            SCHEMA_VERSION, "p", ByteArray(32), "0.0.0",
            listOf(
                Interface(
                    "I", 1, false,
                    listOf(
                        Member(
                            "m", 1, Kind.Signal,
                            listOf(Payload("value", "DeepTypeNameMarker", listOf(size(Encoding.FlatBuffers, 4, SizeState.Bounded, UnboundedCause.Unspecified)))),
                            null, null, null,
                        ),
                    ),
                    emptyList(),
                ),
            ),
            emptyList(), null,
        ),
    )

    /**
     * A catalog in which every vector of tables holds two elements, and the
     * last element of each has every field `verify` reads present, with every
     * enum other than its default. [provisional] and [timing] vary two fields.
     */
    private fun full(provisional: Boolean = true, timing: Boolean = true): ByteArray = encode(
        Catalog(
            SCHEMA_VERSION, "p", ByteArray(32), "0.0.0",
            listOf(
                Interface("I0", 3, false, emptyList(), emptyList()),
                Interface(
                    "I", 1, provisional,
                    listOf(
                        Member("m0", 2, Kind.Signal, emptyList(), null, null, null),
                        Member(
                            "m", 1, Kind.Event,
                            listOf(
                                Payload("r0", "T0", emptyList()),
                                Payload(
                                    "value", "T",
                                    listOf(
                                        size(Encoding.Proto3, 0, SizeState.Bounded, UnboundedCause.Unspecified),
                                        size(Encoding.FlatBuffers, 4, SizeState.Unbounded, UnboundedCause.Member),
                                    ),
                                ),
                            ),
                            if (timing) Timing(TimingMode.StrictPeriodic, "1", "2") else null,
                            7,
                            listOf(
                                StreamPayload(0, StreamForm.Elements, "E0", emptyList()),
                                StreamPayload(
                                    2, StreamForm.Text, "E",
                                    listOf(
                                        size(Encoding.Proto3, 8, SizeState.Bounded, UnboundedCause.Unspecified),
                                        size(Encoding.Podbuf, 9, SizeState.Bounded, UnboundedCause.Unspecified),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    listOf(5, 6),
                ),
            ),
            listOf(RetiredInterface("R0", 4), RetiredInterface("R", 2)),
            listOf(EarlierCatalog(ByteArray(32) { 1 }), EarlierCatalog(ByteArray(32) { 2 })),
        ),
    )

    @Test
    fun `the encoder round-trips a full catalog`() {
        val catalog = verify(full())
        assertEquals(7L, catalog.interfaces[1].members[1].limit)
        assertEquals(StreamForm.Text, catalog.interfaces[1].members[1].streams!![1].form)
        assertEquals(listOf(5L, 6L), catalog.interfaces[1].reservedOrdinals)
    }

    @Test
    fun `verify is total over arbitrary bytes`() {
        for (len in 0..2) {
            for (n in 0 until (1 shl (8 * len))) probe(ByteArray(len) { (n ushr (8 * it)).toByte() })
        }
        val identifier = FILE_IDENTIFIER.toByteArray()
        for (len in listOf(3, 4, 7, 8, 15, 16, 31, 32, 63, 64, 127, 256)) {
            for (byte in listOf(0, 0xff, 0x80, 0x55, 0xaa)) {
                val bytes = ByteArray(len) { byte.toByte() }
                probe(bytes)
                if (len >= 8) {
                    // Keep the identifier so that the body reader is also reached.
                    identifier.copyInto(bytes, 4)
                    probe(bytes)
                }
            }
            val bytes = ByteArray(len) { it.toByte() }
            probe(bytes)
            if (len >= 8) {
                identifier.copyInto(bytes, 4)
                probe(bytes)
            }
        }
        val replacements = listOf(0, 1, 2, 3, 4, 7, 8, 15, 16, 31, 32, 63, 64, 127, 128, 254, 255)
        for (bytes in listOf(minimal(), nested(), full(), full(provisional = false, timing = false))) {
            verify(bytes)
            probe(bytes)
            for (end in bytes.indices) probe(bytes.copyOfRange(0, end))
            for (at in bytes.indices) {
                val original = bytes[at].toInt() and 0xff
                for (replacement in replacements + (0 until 8).map { original xor (1 shl it) }) {
                    bytes[at] = replacement.toByte()
                    probe(bytes)
                }
                bytes[at] = original.toByte()
            }
        }
    }
}
