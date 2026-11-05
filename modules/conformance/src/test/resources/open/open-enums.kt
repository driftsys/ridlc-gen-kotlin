// What an open enum and an open enum set do beyond the round trip the corpus
// gives every codec: `an_open_enum_round_trips_an_undeclared_value` and its
// neighbours in `crates/ridl-backend-rust/tests/flatbuffers_conformance.rs`.
// Compiled with the generated open/demo sources.
@file:JvmName("OpenEnumsProbe")

package ridl.conformance.probe.open

import open.demo.DtcStatus
import open.demo.Empty
import open.demo.EmptyMsg
import open.demo.EmptyMsgCodec
import open.demo.Flags
import open.demo.FlagsList
import open.demo.FlagsListCodec
import open.demo.OpenList
import open.demo.OpenListCodec
import open.demo.OpenMsg
import open.demo.OpenMsgCodec
import open.demo.StrictMsgCodec
import ridl.rt.payload.EncodeError
import ridl.rt.payload.Payload
import ridl.rt.payload.Rule
import ridl.rt.payload.VerifyError
import ridl.rt.payload.Violation
import java.nio.ByteBuffer

private val failures = mutableListOf<String>()

private fun expect(label: String, condition: Boolean) {
    if (!condition) failures += label
}

private fun <T> expectEqual(label: String, expected: T, actual: T) {
    if (expected != actual) failures += "$label: expected $expected, got $actual"
}

private fun <T> bytes(codec: Payload<T, *>, value: T): ByteBuffer {
    val out = ByteBuffer.allocate(codec.maxSize)
    codec.encode(value, out)
    out.flip()
    return out
}

private fun <T, V> decoded(codec: Payload<T, V>, buf: ByteBuffer): T = codec.decode(codec.verify(buf))

private fun array(buf: ByteBuffer): List<Byte> = ByteArray(buf.remaining()).also { buf.duplicate().get(it) }.toList()

fun probe(): List<String> {
    // An undeclared value keeps its integer, and writes back unchanged.
    val message = OpenMsg(DtcStatus.fromValue(7), Flags.of(0b1000_0001))
    val written = bytes(OpenMsgCodec, message)
    val read = decoded(OpenMsgCodec, written)
    expect("an undeclared value reads as Unknown", read.status is DtcStatus.Unknown)
    expectEqual("Unknown keeps the integer", 7L, read.status.value)
    expectEqual("an undeclared value passes through", array(written), array(bytes(OpenMsgCodec, read)))
    expectEqual("two Unknown of one value are equal", DtcStatus.fromValue(7), read.status)
    expectEqual("Unknown hashes as its value", DtcStatus.fromValue(7).hashCode(), read.status.hashCode())

    // A value has one representation.
    expectEqual("a declared value is its member", DtcStatus.ACTIVE, DtcStatus.fromValue(1))
    expectEqual("0 is its member", DtcStatus.INACTIVE, DtcStatus.fromValue(0))
    expectEqual("a member keeps its integer", 1L, DtcStatus.ACTIVE.value)

    // An open enum set keeps every bit, and says which are undeclared.
    expectEqual("every bit is kept", 0b1000_0001L, read.flags.bits)
    expectEqual("the undeclared bits", 0b1000_0000L, read.flags.unknownBits)
    expectEqual("a declared bit is not unknown", 0L, Flags.A.unknownBits)

    // A bit the field's width cannot hold is refused, not dropped.
    val refused = runCatching { bytes(OpenMsgCodec, OpenMsg(DtcStatus.INACTIVE, Flags.of(1L shl 9))) }.exceptionOrNull()
    expectEqual("a bit above the width is refused", EncodeError.Contract(Violation("Flags", Rule.Range)), refused)

    // The wire carries the raw integer: the strict message has the same
    // layout, and refuses what the open one wrote.
    val strict = runCatching { StrictMsgCodec.verify(written) }.exceptionOrNull()
    expect("the strict codec refuses it: $strict", strict is VerifyError.Contract && strict.violation.rule == Rule.Variant)

    // An open enum with no declared value: every value is Unknown.
    val empty = decoded(EmptyMsgCodec, bytes(EmptyMsgCodec, EmptyMsg(Empty.fromValue(3))))
    expectEqual("an empty open enum keeps its integer", 3L, empty.e.value)
    expect("an empty open enum has no entry", Empty.entries.isEmpty())

    // The element site of the codecs.
    val statuses = OpenList(listOf(DtcStatus.ACTIVE, DtcStatus.fromValue(9)))
    expectEqual("elements keep their integers", statuses.statuses.map { it.value }, decoded(OpenListCodec, bytes(OpenListCodec, statuses)).statuses.map { it.value })
    val sets = FlagsList(listOf(Flags.A, Flags.of(0b100)))
    expectEqual("set elements keep their bits", sets.sets.map { it.bits }, decoded(FlagsListCodec, bytes(FlagsListCodec, sets)).sets.map { it.bits })
    return failures
}
