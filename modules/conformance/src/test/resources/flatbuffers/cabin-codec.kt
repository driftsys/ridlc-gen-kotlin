// The K1b spike (docs/design.md §4, O-K1 option A): the FlatBuffers codecs of
// the cabin package's six payload types, written by hand in the shape the
// K2c emitter would generate, over ridl-rt-kt's Reader and Builder. Each
// mirrors the Rust codec the pinned release generates field for field, so its
// bytes and its verdicts can be compared with Rust's. Compiled with the
// generated veh/cabin/Types.kt, whose internal checks it calls.
@file:JvmName("CabinCodec")

package ridl.conformance.spike

import ridl.rt.encoding.Encoding
import ridl.rt.flatbuffers.Builder
import ridl.rt.flatbuffers.Field
import ridl.rt.flatbuffers.Pos
import ridl.rt.flatbuffers.Reader
import ridl.rt.flatbuffers.TableField
import ridl.rt.payload.Malformed
import ridl.rt.payload.Payload
import ridl.rt.payload.Rule
import ridl.rt.payload.VerifyError
import ridl.rt.payload.Violation
import veh.cabin.Average
import veh.cabin.Health
import veh.cabin.Level
import veh.cabin.Temperature
import veh.cabin.Warning
import veh.cabin.Window
import java.nio.ByteBuffer

/** What `verify` hands `decode`: the checked buffer and its root table. */
class FbView(val reader: Reader, val table: Int)

private fun missing(): Nothing = throw VerifyError.Structure(Malformed.MissingRequired)

private fun contract(type: String, rule: Rule?) {
    if (rule != null) throw VerifyError.Contract(Violation(type, rule))
}

/** The part every codec shares: the size limit, then the root, then the type's own walk. */
private abstract class FbCodec<T> : Payload<T, FbView> {
    override val encoding: Encoding get() = Encoding.FlatBuffers

    abstract fun write(value: T, builder: Builder): Pos

    abstract fun check(reader: Reader, table: Int)

    override fun encode(value: T, out: ByteBuffer): Int {
        val builder = Builder(out)
        return builder.finish(write(value, builder), 8)
    }

    override fun verify(buf: ByteBuffer): FbView {
        if (buf.remaining() > maxSize) throw VerifyError.Structure(Malformed.TooLarge)
        val reader = Reader(buf)
        val table = reader.root()
        check(reader, table)
        return FbView(reader, table)
    }
}

// A named scalar and an enum are rooted in a one-field box table (ADR-0019
// decision 8). The field is required: a box without it is MissingRequired.

private object TemperatureCodec : FbCodec<Temperature>() {
    override val maxSize = 43
    override fun write(value: Temperature, builder: Builder) =
        builder.pushTable(5, 4, 1, listOf(TableField(0, 4, Field.I8(value.value.toInt()))))
    override fun check(reader: Reader, table: Int) {
        val at = reader.field(table, 0, 1) ?: missing()
        contract("Temperature", Temperature.violation(reader.i8(at).toLong()))
    }
    override fun decode(view: FbView) =
        Temperature.unchecked(view.reader.field(view.table, 0, 1)?.let { view.reader.i8(it).toLong() } ?: 0L)
}

private object LevelCodec : FbCodec<Level>() {
    override val maxSize = 43
    override fun write(value: Level, builder: Builder) =
        builder.pushTable(5, 4, 1, listOf(TableField(0, 4, Field.U8(value.value.toInt()))))
    override fun check(reader: Reader, table: Int) {
        val at = reader.field(table, 0, 1) ?: missing()
        contract("Level", Level.violation(reader.u8(at).toLong()))
    }
    override fun decode(view: FbView) =
        Level.unchecked(view.reader.field(view.table, 0, 1)?.let { view.reader.u8(it).toLong() } ?: 0L)
}

private object WindowCodec : FbCodec<Window>() {
    override val maxSize = 46
    override fun write(value: Window, builder: Builder) =
        builder.pushTable(8, 4, 1, listOf(TableField(0, 4, Field.U32(value.value))))
    override fun check(reader: Reader, table: Int) {
        val at = reader.field(table, 0, 4) ?: missing()
        contract("Window", Window.violation(reader.u32(at)))
    }
    override fun decode(view: FbView) =
        Window.unchecked(view.reader.field(view.table, 0, 4)?.let { view.reader.u32(it) } ?: 0L)
}

private object AverageCodec : FbCodec<Average>() {
    override val maxSize = 44
    override fun write(value: Average, builder: Builder) =
        builder.pushTable(6, 4, 1, listOf(TableField(0, 4, Field.U16(value.value.toInt()))))
    override fun check(reader: Reader, table: Int) {
        val at = reader.field(table, 0, 2) ?: missing()
        contract("Average", Average.violation(reader.u16(at).toLong()))
    }
    override fun decode(view: FbView) =
        Average.unchecked(view.reader.field(view.table, 0, 2)?.let { view.reader.u16(it).toLong() } ?: 0L)
}

private object HealthCodec : FbCodec<Health>() {
    override val maxSize = 50
    override fun write(value: Health, builder: Builder) =
        builder.pushTable(16, 8, 1, listOf(TableField(0, 8, Field.I64(value.value))))
    override fun check(reader: Reader, table: Int) {
        val at = reader.field(table, 0, 8) ?: missing()
        if (Health.fromValue(reader.i64(at)) == null) contract("Health", Rule.Variant)
    }
    override fun decode(view: FbView) =
        view.reader.field(view.table, 0, 8)?.let { Health.fromValue(view.reader.i64(it)) } ?: Health.OK
}

// A struct is its own table, each field at the slot the projection gave it.

private object WarningCodec : FbCodec<Warning>() {
    override val maxSize = 60
    override fun write(value: Warning, builder: Builder) = builder.pushTable(
        16, 8, 2,
        listOf(TableField(0, 4, Field.U8(value.code.value.toInt())), TableField(1, 8, Field.I64(value.health.value))),
    )
    override fun check(reader: Reader, table: Int) {
        val code = reader.field(table, 0, 1) ?: missing()
        contract("Level", Level.violation(reader.u8(code).toLong()))
        val health = reader.field(table, 1, 8) ?: missing()
        if (Health.fromValue(reader.i64(health)) == null) contract("Health", Rule.Variant)
    }
    override fun decode(view: FbView): Warning {
        val r = view.reader
        return Warning(
            code = Level.unchecked(r.field(view.table, 0, 1)?.let { r.u8(it).toLong() } ?: 0L),
            health = r.field(view.table, 1, 8)?.let { Health.fromValue(r.i64(it)) } ?: Health.OK,
        )
    }
}

// The spike's entry points, called by reflection from SpikeTest: every one
// takes and returns strings, and formats as the Rust program does, so the two
// verdicts compare as text.

private fun codec(type: String): FbCodec<*> = when (type) {
    "Temperature" -> TemperatureCodec
    "Level" -> LevelCodec
    "Window" -> WindowCodec
    "Average" -> AverageCodec
    "Health" -> HealthCodec
    "Warning" -> WarningCodec
    else -> error("no codec for $type")
}

/** A decoded value as Rust's `{:?}` prints it. */
private fun debug(value: Any?): String = when (value) {
    is Temperature -> "Temperature(${value.value})"
    is Level -> "Level(${value.value})"
    is Window -> "Window(${value.value})"
    is Average -> "Average(${value.value})"
    is Health -> value.name
    is Warning -> "Warning { code: ${debug(value.code)}, health: ${debug(value.health)} }"
    else -> error("no format for $value")
}

/** A `VerifyError` as Rust's `{:?}` prints it. */
private fun debug(error: VerifyError): String = when (error) {
    is VerifyError.Structure -> "Structure(${error.malformed.name})"
    is VerifyError.Contract ->
        "Contract(Violation { type_name: \"${error.violation.typeName}\", rule: ${error.violation.rule.name} })"
}

private fun unhex(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

/**
 * What `verify` then `decode` make of [hex] as a [type]: `ok <value>` or
 * `err <error>`. Any other exception escapes, and the test counts it as the
 * failure option A must not have: a malformed buffer refused by something
 * other than `verify`.
 */
fun verdict(type: String, hex: String): String {
    @Suppress("UNCHECKED_CAST")
    val codec = codec(type) as FbCodec<Any?>
    val view = try {
        codec.verify(ByteBuffer.wrap(unhex(hex)))
    } catch (e: VerifyError) {
        return "err ${debug(e)}"
    }
    return "ok ${debug(codec.decode(view))}"
}

/** The Kotlin encoding of the sample [type] labelled [label], as hex. */
fun encode(type: String, label: String): String {
    val value: Any = when (type) {
        "Temperature" -> Temperature.of(label.toLong())
        "Level" -> Level.of(label.toLong())
        "Window" -> Window.of(label.toLong())
        "Average" -> Average.of(label.toLong())
        "Health" -> Health.valueOf(label)
        "Warning" -> label.split(',').let { (code, health) -> Warning(Level.of(code.toLong()), Health.valueOf(health)) }
        else -> error("no sample for $type")
    }
    @Suppress("UNCHECKED_CAST")
    val codec = codec(type) as FbCodec<Any>
    val out = ByteBuffer.allocate(codec.maxSize)
    val written = codec.encode(value, out)
    return hex(out.array().copyOf(written))
}
