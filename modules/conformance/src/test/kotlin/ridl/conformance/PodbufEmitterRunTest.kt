package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

/**
 * The podbuf codec, compiled and run, over packages that select only
 * `podbuf`. The first encodes, verifies and decodes every fixed form
 * (booleans, integers and floats of each width, strict and `open` enums and
 * enum sets, inline structs and tuples, exact-length arrays and bytes,
 * optionals with their flags bits, and the box of a named scalar, an enum and
 * an enum set). The second carries the tail forms (strings, bytes, arrays,
 * maps, unions and the tuple a call sends) and is compiled once, beside a
 * package whose Kotlin package has a `region` segment, for the tail tests.
 * The Rust verdicts of the corpus come with the conformance suite; these
 * probes pin the rules the emitter alone decides.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PodbufEmitterRunTest {
    @TempDir
    lateinit var work: Path

    private val source = """
        package probe.podbuf

        /// A named scalar, carried as a root by its box.
        type Percent : integer [0..100]

        /// An open enum with a value above u32::MAX and none below 0: a `u64`,
        /// whose values of 2^63 and more are negative Longs.
        open enum Huge {
          ZERO = 0
          BIG  = 4294967296
        }

        /// A strict enum with a value above u32::MAX and none below 0: a `u64`.
        enum Far {
          NEAR = 0
          AWAY = 4294967296
        }

        /// A strict enum, one byte wide.
        enum Gear {
          PARK  = 0
          DRIVE = 3
        }

        /// An open enum, one byte wide.
        open enum Status {
          OFF = 0
          ON  = 1
        }

        /// An open enum, one signed byte wide.
        open enum Trim {
          DOWN = -1
          UP   = 1
        }

        /// A strict enum set.
        enumset Warn {
          LOW = 0
          HOT = 2
        }

        /// An open enum set, one byte wide.
        open enumset Bits {
          A = 0
          B = 1
        }

        /// A nested head.
        struct Point {
          x : integer [-32768..32767]
          y : integer [-32768..32767]
        }

        /// Every fixed form at once.
        struct Fixed {
          flag   : boolean
          small  : integer [0..255]
          signed : integer [-128..127]
          wide   : integer [0..4294967295]
          big    : integer [-9223372036854775808..9223372036854775807]
          ratio  : float [0.0..1.0]
          free   : float
          gear   : Gear
          status : Status
          trim   : Trim
          warn   : Warn
          bits   : Bits
          huge   : Huge
          far    : Far
          pct    : Percent
          at     : Point
          span   : (lo: integer [0..15], hi: integer [0..65535])
          grid   : [[integer [0..255]; 2]; 3]
          points : [Point; 2]
          digest : bytes [4]
          maybe  : integer [0..255]?
          spot : Point?
        }

        /// A struct whose one field is a u64 enum.
        struct HugeOnly {
          huge : Huge
        }
    """.trimIndent()

    private val probe = """
        @file:JvmName("PodbufFixedProbe")

        package probe.podbuf.check

        import probe.podbuf.Bits
        import probe.podbuf.Far
        import probe.podbuf.Fixed
        import probe.podbuf.FixedPodbufCodec
        import probe.podbuf.FixedSpan
        import probe.podbuf.Gear
        import probe.podbuf.GearPodbufCodec
        import probe.podbuf.Huge
        import probe.podbuf.HugeOnly
        import probe.podbuf.HugeOnlyPodbufCodec
        import probe.podbuf.Percent
        import probe.podbuf.PercentPodbufCodec
        import probe.podbuf.Point
        import probe.podbuf.Status
        import probe.podbuf.Trim
        import probe.podbuf.Warn
        import probe.podbuf.WarnPodbufCodec
        import ridl.rt.payload.EncodeError
        import ridl.rt.payload.Payload
        import ridl.rt.payload.VerifyError
        import java.nio.ByteBuffer

        private val failures = mutableListOf<String>()

        private fun expect(what: String, ok: Boolean) {
            if (!ok) failures += what
        }

        private fun <T, V> bytes(codec: Payload<T, V>, value: T): ByteArray {
            val out = ByteBuffer.allocate(codec.maxSize)
            val n = codec.encode(value, out)
            expect("encode advances by what it returns", out.position() == n)
            return out.array().copyOf(n)
        }

        private fun <T, V> roundTrip(what: String, codec: Payload<T, V>, value: T): ByteArray {
            val b = bytes(codec, value)
            val back = codec.decode(codec.verify(ByteBuffer.wrap(b)))
            expect("${'$'}what: decode(verify(encode(v))) == v, got ${'$'}back", back == value)
            expect("${'$'}what: a second encode gives the same bytes", bytes(codec, back).contentEquals(b))
            return b
        }

        private fun <T, V> verdict(codec: Payload<T, V>, b: ByteArray): String = try {
            codec.verify(ByteBuffer.wrap(b))
            "ok"
        } catch (e: VerifyError) {
            e.toString()
        } catch (e: Throwable) {
            "THREW ${'$'}e"
        }

        private fun le64(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        private val sample = Fixed(
            flag = true,
            small = 200,
            signed = -100,
            wide = 4000000000,
            big = Long.MIN_VALUE,
            ratio = 0.25,
            free = -1.5,
            gear = Gear.DRIVE,
            status = Status.fromValue(200),
            trim = Trim.fromValue(-100),
            warn = Warn.HOT,
            bits = Bits.of(0x80),
            huge = Huge.fromValue(-1),
            far = Far.AWAY,
            pct = Percent.of(42),
            at = Point(x = -3, y = 7),
            span = FixedSpan(lo = 15, hi = 65535),
            grid = listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L, 6L)),
            points = listOf(Point(x = 1, y = 2), Point(x = -1, y = -2)),
            digest = byteArrayOf(1, 2, 3, 4),
            maybe = 9,
            spot = null,
        )

        fun probe(): List<String> {
            // A u64 enum value at 2^63 is its bit pattern in the Long, on the wire and back.
            val top = Huge.fromValue(Long.MIN_VALUE)
            val huge = roundTrip("HugeOnly", HugeOnlyPodbufCodec, HugeOnly(top))
            expect("the u64 at 2^63 is written as its bits", le64(huge, 8) == Long.MIN_VALUE)
            val back = HugeOnlyPodbufCodec.decode(HugeOnlyPodbufCodec.verify(ByteBuffer.wrap(huge))).huge
            expect("the u64 at 2^63 reads back as the same bits, got ${'$'}back", back.value == Long.MIN_VALUE)

            val fixed = roundTrip("Fixed", FixedPodbufCodec, sample)
            roundTrip("Fixed, the other optionals", FixedPodbufCodec, Fixed(
                flag = false, small = 0, signed = 0, wide = 0, big = 0, ratio = 0.0, free = 0.0,
                gear = Gear.PARK, status = Status.OFF, trim = Trim.UP, warn = Warn.EMPTY, bits = Bits.EMPTY,
                huge = Huge.ZERO, far = Far.NEAR, pct = Percent.of(0), at = Point(x = 0, y = 0), span = FixedSpan(lo = 0, hi = 0),
                grid = listOf(listOf(0L, 0L), listOf(0L, 0L), listOf(0L, 0L)),
                points = listOf(Point(x = 0, y = 0), Point(x = 0, y = 0)),
                digest = byteArrayOf(0, 0, 0, 0), maybe = null, spot = Point(x = 5, y = -5),
            ))
            roundTrip("Percent's box", PercentPodbufCodec, Percent.of(100))
            roundTrip("Gear's box", GearPodbufCodec, Gear.DRIVE)
            roundTrip("Warn's box", WarnPodbufCodec, Warn.LOW + Warn.HOT)

            // Every byte of a buffer, one at a time, set to each hostile value: verify throws nothing but VerifyError.
            for (i in fixed.indices) {
                for (v in listOf(0x00, 0x01, 0x02, 0x7F, 0x80, 0xFF)) {
                    val m = fixed.copyOf()
                    m[i] = v.toByte()
                    val got = verdict(FixedPodbufCodec, m)
                    expect("byte ${'$'}i = ${'$'}v: ${'$'}got", !got.startsWith("THREW"))
                    if (got == "ok") {
                        val view = FixedPodbufCodec.verify(ByteBuffer.wrap(m))
                        try {
                            FixedPodbufCodec.decode(view)
                        } catch (e: Throwable) {
                            failures += "byte ${'$'}i = ${'$'}v: decode threw ${'$'}e"
                        }
                    }
                }
            }
            for (n in fixed.indices) {
                val got = verdict(FixedPodbufCodec, fixed.copyOf(n))
                expect("a truncation to ${'$'}n bytes: ${'$'}got", got.contains("Preamble"))
            }

            // Rust's verdicts on the head's own rules, at offsets the probe finds from the codec's own output.
            val gearBox = bytes(GearPodbufCodec, Gear.DRIVE)
            expect("a strict enum's undeclared value is Variant", verdict(GearPodbufCodec, gearBox.copyOf().also { it[8] = 2 })
                .contains("Variant"))
            val warnBox = bytes(WarnPodbufCodec, Warn.EMPTY)
            expect("a strict enum set's undeclared bit is Variant", verdict(WarnPodbufCodec, warnBox.copyOf().also { it[8] = 2 })
                .contains("Variant"))
            val pctBox = bytes(PercentPodbufCodec, Percent.of(1))
            expect("a named scalar out of its range is Range", verdict(PercentPodbufCodec, pctBox.copyOf().also { it[8] = 101 })
                .contains("Range"))
            expect("an unaligned buffer is refused", try {
                FixedPodbufCodec.verify(ByteBuffer.wrap(ByteArray(fixed.size + 1)).position(1) as ByteBuffer)
                false
            } catch (e: VerifyError) {
                e.toString().contains("Unaligned")
            })
            expect("an open enum value past its width is refused by encode", try {
                bytes(FixedPodbufCodec, Fixed(
                    flag = true, small = 0, signed = 0, wide = 0, big = 0, ratio = 0.0, free = 0.0,
                    gear = Gear.PARK, status = Status.fromValue(256), trim = Trim.UP, warn = Warn.EMPTY, bits = Bits.EMPTY,
                    huge = Huge.ZERO, far = Far.NEAR, pct = Percent.of(0), at = Point(x = 0, y = 0), span = FixedSpan(lo = 0, hi = 0),
                    grid = listOf(listOf(0L, 0L), listOf(0L, 0L), listOf(0L, 0L)),
                    points = listOf(Point(x = 0, y = 0), Point(x = 0, y = 0)),
                    digest = byteArrayOf(0, 0, 0, 0), maybe = null, spot = null,
                ))
                false
            } catch (e: EncodeError.Contract) {
                e.violation.toString().contains("Range") && e.violation.toString().contains("Status")
            })
            expect("a buffer too small for the head is Capacity", try {
                FixedPodbufCodec.encode(sample, ByteBuffer.allocate(8))
                false
            } catch (e: EncodeError.Capacity) {
                true
            })
            return failures
        }
    """.trimIndent()

    /** The generated sources of the package [name] holding [source], selecting only `podbuf`. */
    private fun generate(name: String, source: String, work: Path = this.work): Map<String, String> {
        val pkg = work.resolve("src-$name").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"$name\"\nversion = \"1.0.0\"\n\n[backend.kotlin]\nencodings = [\"podbuf\"]\n",
        )
        pkg.resolve("source.ridl").writeText(source + "\n")
        val requests = Harness.capturedRequests(pkg, name, work)
        val sources = requests.values.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        val path = name.replace('.', '/')
        assertTrue("$path/PodbufCodec.kt" in sources, sources.keys.toString())
        assertTrue("$path/Codec.kt" !in sources, sources.keys.toString())
        return sources
    }

    @Test
    fun `fixed-size heads round-trip, and verify throws nothing but VerifyError`() {
        val sources = generate("probe.podbuf", source)
        val compiled = Compiler.compile(sources + ("probe/podbuf/check/Probe.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages + "\n" + sources["probe/podbuf/PodbufCodec.kt"])
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("probe.podbuf.check.PodbufFixedProbe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }

    private val tailsSource = """
        package probe.tails

        /// A named string, at least one scalar value.
        type Name : string [1..8]

        /// A named bytes.
        type Blob : bytes [0..16]

        /// A weight.
        type Weight : integer [0..1000]

        enum Kind {
          A = 0
          B = 1
        }

        struct Point {
          x : integer [-32768..32767]
          y : integer [-32768..32767]
        }

        /// An arm type with no field.
        struct Empty {}

        /// A struct arm, an arm with no field, an enum arm and a string arm.
        union Shape {
          dot   : Point
          none  : Empty
          gear  : Kind
          label : Name
        }

        /// Tail order, depth first: a's data, the ws block, each element's data, then b's.
        struct Order {
          a  : string [0..4]
          ws : [string [0..3]; 0..2]
          b  : string [0..4]
        }

        /// Every tail form at once.
        struct Tails {
          label  : string [0..4]
          name   : Name
          data   : bytes [0..8]
          blob   : Blob
          xs     : [integer [0..65535]; 0..3]
          pts    : [Point; 0..2]
          words  : [string [0..3]; 0..2]
          byName : [Name : Weight; 0..4]
          ids    : [integer [0..255] : boolean; 0..4]
          shape  : Shape
          note   : string [0..8]?
          pair   : (a: string [0..4], b: integer [0..9])
        }

        struct Labels {
          byName : [Name : Weight; 0..4]
        }

        struct Floats {
          byFloat : [float : integer [0..255]; 0..4]
        }

        struct Note {
          text : string [0..8]
        }

        struct Nums {
          xs : [integer [0..65535]; 0..3]
        }

        struct Pts {
          pts : [Point; 0..2]
        }

        struct Words {
          ws : [string [0..3]; 0..2]
        }

        /// Deprecated fields: the encoder writes each one's init value, whatever the value holds, and an
        /// optional one with no init stays absent.
        struct Legacy {
          /// @deprecated "no longer read"
          legacy : integer [1..9] = 5
          /// @deprecated "no longer read"
          label : string [0..4] = "ab"
          /// @deprecated "no longer read"
          maybe : integer [0..9]?
          keep : integer [0..9]
        }

        interface Pump {
          /// Two parameters: the request is a tuple the call sends on its own.
          command set(name: Name, level: Weight) @[..50ms]
        }
    """.trimIndent()

    /** A package whose Kotlin package has a `region` segment: no helper may bind a `region` it does not read. */
    private val regionSource = """
        package probe.region

        struct Inner {
          x : integer [-32768..32767]
        }

        struct Outer {
          inner : Inner
        }
    """.trimIndent()

    private val tailsProbe = """
        @file:JvmName("PodbufTailsProbe")
        @file:Suppress("DEPRECATION")

        package probe.tails.check

        import probe.region.Inner
        import probe.region.Outer
        import probe.region.OuterPodbufCodec
        import probe.tails.Blob
        import probe.tails.Empty
        import probe.tails.Floats
        import probe.tails.FloatsPodbufCodec
        import probe.tails.Kind
        import probe.tails.Labels
        import probe.tails.Legacy
        import probe.tails.LegacyPodbufCodec
        import probe.tails.LabelsPodbufCodec
        import probe.tails.Name
        import probe.tails.Note
        import probe.tails.NotePodbufCodec
        import probe.tails.Nums
        import probe.tails.NumsPodbufCodec
        import probe.tails.Order
        import probe.tails.OrderPodbufCodec
        import probe.tails.Point
        import probe.tails.Pts
        import probe.tails.PtsPodbufCodec
        import probe.tails.PumpSetParams
        import probe.tails.PumpSetParamsPodbufCodec
        import probe.tails.Shape
        import probe.tails.ShapePodbufCodec
        import probe.tails.Tails
        import probe.tails.TailsPair
        import probe.tails.TailsPodbufCodec
        import probe.tails.Weight
        import probe.tails.Words
        import probe.tails.WordsPodbufCodec
        import ridl.rt.payload.ConstraintViolation
        import ridl.rt.payload.EncodeError
        import ridl.rt.payload.Payload
        import ridl.rt.payload.Rule
        import ridl.rt.payload.VerifyError
        import java.nio.ByteBuffer

        private class Check {
            val failures = mutableListOf<String>()

            fun expect(what: String, ok: Boolean) {
                if (!ok) failures += what
            }
        }

        private fun <T, V> bytes(codec: Payload<T, V>, value: T): ByteArray {
            val out = ByteBuffer.allocate(codec.maxSize)
            val n = codec.encode(value, out)
            return out.array().copyOf(n)
        }

        /** `ok`, the `Malformed` name of a structure fault, or `Contract <type> <rule>`; `THREW` for anything else. */
        private fun <T, V> verdict(codec: Payload<T, V>, b: ByteArray): String = try {
            codec.verify(ByteBuffer.wrap(b))
            "ok"
        } catch (e: VerifyError.Structure) {
            e.malformed.name
        } catch (e: VerifyError.Contract) {
            "Contract ${'$'}{e.violation.typeName} ${'$'}{e.violation.rule}"
        } catch (e: Throwable) {
            "THREW ${'$'}e"
        }

        /** `ok`, or `<type> <rule>` of the encoder's `Contract`; `THREW` for anything else. */
        private fun <T, V> encoded(codec: Payload<T, V>, value: T): String = try {
            bytes(codec, value)
            "ok"
        } catch (e: EncodeError.Contract) {
            "${'$'}{e.violation.typeName} ${'$'}{e.violation.rule}"
        } catch (e: Throwable) {
            "THREW ${'$'}e"
        }

        private fun <T, V> Check.roundTrip(what: String, codec: Payload<T, V>, value: T): ByteArray {
            val b = bytes(codec, value)
            val back = codec.decode(codec.verify(ByteBuffer.wrap(b)))
            expect("${'$'}what: decode(verify(encode(v))) == v, got ${'$'}back", back == value)
            expect("${'$'}what: a second encode gives the same bytes", bytes(codec, back).contentEquals(b))
            return b
        }

        /** Every byte set to each hostile value, and every truncation: nothing but VerifyError, and decode never throws after verify. */
        private fun <T, V> Check.hostile(what: String, codec: Payload<T, V>, b: ByteArray, head: Int) {
            for (i in b.indices) {
                for (v in listOf(0x00, 0x01, 0x02, 0x7F, 0x80, 0xFF)) {
                    val m = b.copyOf()
                    m[i] = v.toByte()
                    val got = verdict(codec, m)
                    expect("${'$'}what: byte ${'$'}i = ${'$'}v: ${'$'}got", !got.startsWith("THREW"))
                    if (got == "ok") {
                        try {
                            codec.decode(codec.verify(ByteBuffer.wrap(m)))
                        } catch (e: Throwable) {
                            failures += "${'$'}what: byte ${'$'}i = ${'$'}v: decode threw ${'$'}e"
                        }
                    }
                }
            }
            for (n in b.indices) {
                val got = verdict(codec, b.copyOf(n))
                // Below the head it is the preamble; past it, the last tail block is cut, and its descriptor refuses it.
                val ok = if (n < 8 + head) got == "Preamble" else got != "ok" && !got.startsWith("THREW")
                expect("${'$'}what: a truncation to ${'$'}n bytes: ${'$'}got", ok)
            }
        }

        private fun le32(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 3 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        private fun le64(b: ByteArray, at: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        private fun ByteArray.u16(at: Int, v: Int): ByteArray = copyOf().also {
            it[at] = v.toByte()
            it[at + 1] = (v shr 8).toByte()
        }

        private fun ByteArray.u32(at: Int, v: Long): ByteArray = copyOf().also {
            for (i in 0 until 4) it[at + i] = (v shr (8 * i)).toByte()
        }

        private fun ByteArray.grow(n: Int, fill: Int = 0): ByteArray = copyOf(size + n).also {
            for (i in size until size + n) it[i] = fill.toByte()
        }

        /** The string a text descriptor at buffer [at] names. */
        private fun textAt(b: ByteArray, at: Int): String {
            val off = le32(b, at).toInt()
            return String(b, off, le32(b, at + 4).toInt(), Charsets.UTF_8)
        }

        /** A `Floats` buffer by hand, as Rust's `scored`: entries of `{ key: f64, value: u8 }` at 16, 32, ... */
        private fun floats(vararg keys: Double): ByteArray {
            val b = ByteArray(16 + 16 * keys.size)
            b[0] = 8
            val head = b.u32(8, 16).u16(12, keys.size).u16(14, 16)
            for ((i, key) in keys.withIndex()) {
                val bits = java.lang.Double.doubleToRawLongBits(key)
                for (k in 0 until 8) head[16 + 16 * i + k] = (bits shr (8 * k)).toByte()
                head[16 + 16 * i + 8] = (i + 1).toByte()
            }
            return head
        }

        private val tails = Tails(
            label = "héé",
            name = Name.of("nom"),
            data = byteArrayOf(1, 2, 3),
            blob = Blob.of(byteArrayOf(9, 8, 7, 6, 5)),
            xs = listOf(1L, 65535L, 7L),
            pts = listOf(Point(x = -1, y = 2), Point(x = 3, y = -4)),
            words = listOf("abc", ""),
            byName = mapOf(Name.of("zed") to Weight.of(3), Name.of("alf") to Weight.of(1000)),
            ids = mapOf(200L to true, 7L to false),
            shape = Shape.Label(Name.of("lbl")),
            note = "note",
            pair = TailsPair(a = "pa", b = 9),
        )

        fun roundTrips(): List<String> = Check().apply {
            val b = roundTrip("Tails", TailsPodbufCodec, tails)
            hostile("Tails", TailsPodbufCodec, b, le32(b, 0).toInt())
            roundTrip("Tails, empty", TailsPodbufCodec, Tails(
                label = "", name = Name.of("n"), data = byteArrayOf(), blob = Blob.of(byteArrayOf()), xs = emptyList(),
                pts = emptyList(), words = emptyList(), byName = emptyMap(), ids = emptyMap(),
                shape = Shape.None(Empty()), note = null, pair = TailsPair(a = "", b = 0),
            ))
            for (shape in listOf(Shape.Dot(Point(x = 5, y = -6)), Shape.None(Empty()), Shape.Gear(Kind.B), Shape.Label(Name.of("hi")))) {
                val s = roundTrip("Shape ${'$'}shape", ShapePodbufCodec, shape)
                hostile("Shape ${'$'}shape", ShapePodbufCodec, s, 16)
            }
            val args = roundTrip("the tuple a call sends", PumpSetParamsPodbufCodec, PumpSetParams(name = Name.of("pump"), level = Weight.of(12)))
            hostile("PumpSetParams", PumpSetParamsPodbufCodec, args, le32(args, 0).toInt())
            roundTrip("a package with a region segment", OuterPodbufCodec, Outer(inner = Inner(x = -9)))
        }.failures

        fun tailOrder(): List<String> = Check().apply {
            val b = bytes(OrderPodbufCodec, Order(a = "x", ws = listOf("p", "q"), b = "y"))
            val expected = byteArrayOf(
                24, 0, 0, 0, 0, 0, 0, 0,
                32, 0, 0, 0, 1, 0, 0, 0, // a: off 32, len 1
                36, 0, 0, 0, 2, 0, 8, 0, // ws: off 36, len 2, elem_size 8
                56, 0, 0, 0, 1, 0, 0, 0, // b: off 56, len 1
                'x'.code.toByte(), 0, 0, 0, // a's data, then the gap to the block's alignment 4
                52, 0, 0, 0, 1, 0, 0, 0, 54, 0, 0, 0, 1, 0, 0, 0, // the ws block
                'p'.code.toByte(), 0, 'q'.code.toByte(), 0, // each element's data, after the block
                'y'.code.toByte(), 0, // b's data last
            )
            expect("the tail is in placement order, depth first: ${'$'}{b.toList()}", b.contentEquals(expected))
        }.failures

        fun mapOrder(): List<String> = Check().apply {
            val b = roundTrip("Labels", LabelsPodbufCodec, Labels(byName = mapOf(Name.of("b") to Weight.of(2), Name.of("a") to Weight.of(1))))
            expect("the first entry is the least key", textAt(b, 16) == "a" && textAt(b, 32) == "b")
            // Bytewise UTF-8 order, not Kotlin's UTF-16 order: U+FFFD (EF BF BD) before U+1F600 (F0 9F 98 80).
            val u = roundTrip("Labels, UTF-8 order", LabelsPodbufCodec, Labels(byName = mapOf(Name.of("😀") to Weight.of(1), Name.of("�") to Weight.of(2))))
            expect("keys are ordered by their UTF-8 bytes", textAt(u, 16) == "�")
            val swapped = b.copyOf()
            for (i in 0 until 16) {
                swapped[16 + i] = b[32 + i]
                swapped[32 + i] = b[16 + i]
            }
            expect("a reversed pair is KeyOrder", verdict(LabelsPodbufCodec, swapped) == "KeyOrder")
            expect("a repeated key is KeyOrder", verdict(LabelsPodbufCodec, b.u32(32, le32(b, 16))) == "KeyOrder")
        }.failures

        fun nanAndDuplicate(): List<String> = Check().apply {
            expect("a NaN key is refused as Range", encoded(FloatsPodbufCodec, Floats(byFloat = mapOf(1.0 to 1L, Double.NaN to 2L))) == "Floats Range")
            expect("a NaN key a producer wrote is Range", verdict(FloatsPodbufCodec, floats(Double.NaN)) == "Contract Floats Range")
            // A map key is a named string or a primitive (TYPL-209), and the value object already refuses the keys a
            // Kotlin map holds apart and podbuf takes for one (0.0 and -0.0, two byte arrays of one content), so the
            // encoder's own `Unique` check is not reached from a value: the duplicate is refused, as `Unique`, first.
            val duplicate = try {
                Floats(byFloat = mapOf(0.0 to 1L, -0.0 to 2L))
                null
            } catch (e: ConstraintViolation) {
                e.rule
            }
            expect("a duplicate key is refused as Unique, got ${'$'}duplicate", duplicate == Rule.Unique)
            expect("a repeated key a producer wrote is KeyOrder", verdict(FloatsPodbufCodec, floats(1.0, 1.0)) == "KeyOrder")
        }.failures

        fun zeroKeys(): List<String> = Check().apply {
            // codec_podbuf.rs:1740-1741 (`key_order`, editor-v0.17.0) sorts by `partial_cmp(..).unwrap_or(Equal)`, the
            // IEEE order, in a stable sort: -0.0 and 0.0 compare equal, so -0.0 sorts where 0.0 does and keeps its bits,
            // and both present is one key (`Unique`). Kotlin's `Double.compareTo` would put -0.0 before 0.0.
            val b = bytes(FloatsPodbufCodec, Floats(byFloat = mapOf(0.5 to 1L, -0.0 to 2L, -1.0 to 3L)))
            expect(
                "-1.0, then -0.0, then 0.5",
                listOf(le64(b, 16), le64(b, 32), le64(b, 48)) == listOf(-1.0, -0.0, 0.5).map { java.lang.Double.doubleToRawLongBits(it) },
            )
            // codec_podbuf.rs:3350 (`key_order_check`): a key not strictly above the one before, by `partial_cmp`, is KeyOrder.
            expect("0.0 then -0.0 is KeyOrder", verdict(FloatsPodbufCodec, floats(0.0, -0.0)) == "KeyOrder")
            expect("-0.0 then 0.0 is KeyOrder", verdict(FloatsPodbufCodec, floats(-0.0, 0.0)) == "KeyOrder")
            expect("-1.0 then -0.0 then 2.5 is in order", verdict(FloatsPodbufCodec, floats(-1.0, -0.0, 2.5)) == "ok")
            expect("2.5 then -1.0 is KeyOrder", verdict(FloatsPodbufCodec, floats(2.5, -1.0)) == "KeyOrder")
        }.failures

        fun nul(): List<String> = Check().apply {
            expect("an inline string holding U+0000 is Pattern", encoded(NotePodbufCodec, Note(text = "a\u0000b")) == "Note Pattern")
            expect("a named string holding U+0000 is Pattern, naming it", encoded(LabelsPodbufCodec, Labels(byName = mapOf(Name.of("\u0000") to Weight.of(1)))) == "Name Pattern")
            val b = bytes(NotePodbufCodec, Note(text = "abc"))
            expect("the NUL follows the data", b.size == 20 && b[19] == 0.toByte())
            expect("a string missing its NUL is Terminator", verdict(NotePodbufCodec, b.copyOf().also { it[19] = 'x'.code.toByte() }) == "Terminator")
            expect("a NUL inside the data is Terminator", verdict(NotePodbufCodec, b.copyOf().also { it[17] = 0 }) == "Terminator")
            expect("bytes that are not UTF-8 are Utf8", verdict(NotePodbufCodec, b.copyOf().also { it[17] = 0xFF.toByte() }) == "Utf8")
            expect("a string past the buffer's end is OutOfBounds", verdict(NotePodbufCodec, b.copyOf(19)) == "OutOfBounds")
            expect("an empty string with an off is OutOfBounds", verdict(NotePodbufCodec, bytes(NotePodbufCodec, Note(text = "")).u32(8, 16)) == "OutOfBounds")
        }.failures

        fun stride(): List<String> = Check().apply {
            val n = bytes(NumsPodbufCodec, Nums(xs = listOf(1L, 2L, 3L)))
            expect("an integer array's elem_size is its exact size", verdict(NumsPodbufCodec, n.u16(14, 4)) == "Stride")
            expect("the exact size is accepted", verdict(NumsPodbufCodec, n) == "ok")
            val p = bytes(PtsPodbufCodec, Pts(pts = listOf(Point(x = 1, y = 2))))
            expect("a head array's elem_size not a multiple of 8 is Stride", verdict(PtsPodbufCodec, p.u16(14, 12)) == "Stride")
            expect("a longer head stride, a newer producer's, is accepted", verdict(PtsPodbufCodec, p.grow(8).u16(14, 16)) == "ok")
            expect("an empty array with an elem_size is OutOfBounds", verdict(NumsPodbufCodec, bytes(NumsPodbufCodec, Nums(xs = emptyList())).u16(14, 2)) == "OutOfBounds")
        }.failures

        fun lenBound(): List<String> = Check().apply {
            // The block of three descriptors fits the buffer, and its third reads the strings' bytes as a descriptor that
            // points nowhere: were it read before the count, it would be OutOfBounds. The count refuses it first.
            val w = bytes(WordsPodbufCodec, Words(ws = listOf("abc", "xyz")))
            expect("three words fit the buffer", w.size == 40)
            expect("a len above the declared max is Length, before the elements are read", verdict(WordsPodbufCodec, w.u16(12, 3)) == "Contract Words Length")
            // A string's len above four bytes per scalar value of its bound is refused before its bytes are read:
            // its 33 bytes and the NUL lie in the buffer, all NUL, which a read would call Terminator.
            val t = bytes(NotePodbufCodec, Note(text = "abcdefgh")).grow(40)
            expect("a string's len above 4 * max is OutOfBounds, before its bytes are read", verdict(NotePodbufCodec, t.u32(12, 33)) == "OutOfBounds")
            expect("the buffer holds what the len names", t.size >= 16 + 34)
        }.failures

        fun deprecated(): List<String> = Check().apply {
            val b = bytes(LegacyPodbufCodec, Legacy(legacy = 7, label = "zz", maybe = 3, keep = 1))
            val back = LegacyPodbufCodec.decode(LegacyPodbufCodec.verify(ByteBuffer.wrap(b)))
            expect("deprecated fields read back as their init values, got ${'$'}back", back == Legacy(legacy = 5, label = "ab", maybe = null, keep = 1))
        }.failures

        fun emptyArm(): List<String> = Check().apply {
            val b = bytes(ShapePodbufCodec, Shape.None(Empty()))
            expect("an arm with no field writes a zero descriptor", b.size == 24 && le32(b, 12) == 0L && le32(b, 16) == 0L)
            val newer = b.grow(8, fill = 0xAB).u32(12, 24).u32(16, 8)
            expect("an arm with no field accepts the tail bytes a newer producer wrote", verdict(ShapePodbufCodec, newer) == "ok")
            expect("and reads as the arm", ShapePodbufCodec.decode(ShapePodbufCodec.verify(ByteBuffer.wrap(newer))) == Shape.None(Empty()))
            expect("its data inside the head is OutOfBounds", verdict(ShapePodbufCodec, b.grow(8).u32(12, 16).u32(16, 8)) == "OutOfBounds")
            expect("its data off alignment 8 is Unaligned", verdict(ShapePodbufCodec, b.grow(16).u32(12, 28).u32(16, 8)) == "Unaligned")
            expect("its len not a multiple of 8 is Union", verdict(ShapePodbufCodec, b.grow(16).u32(12, 24).u32(16, 12)) == "Union")
            expect("a len of 0 with an off is OutOfBounds", verdict(ShapePodbufCodec, b.u32(12, 24)) == "OutOfBounds")
            expect("a tag naming no arm is Union", verdict(ShapePodbufCodec, b.copyOf().also { it[8] = 9 }) == "Union")
        }.failures
    """.trimIndent()

    /** The scratch directory of the tails compile, which outlives each test's own `work`. */
    private val shared: Path by lazy { createTempDirectory("podbuf-tails") }

    @AfterAll
    fun removeShared() {
        shared.toFile().deleteRecursively()
    }

    private val tails: ClassLoader by lazy {
        val sources = generate("probe.tails", tailsSource, shared) + generate("probe.region", regionSource, shared)
        // Kotlin 2's compiler no longer warns of an unused local, so -Werror alone would not see a `region` bound and not read.
        val region = sources.getValue("probe/region/PodbufCodec.kt")
        for (helper in listOf("podbufVerifyOuter", "podbufDecodeOuter")) {
            assertTrue("region" !in region.substringAfter("fun $helper(").substringBefore("\n\n"), region)
        }
        val compiled = Compiler.compile(sources + ("probe/tails/check/Probe.kt" to tailsProbe), shared.resolve("compile-tails"))
        assertTrue(compiled.ok, compiled.messages + "\n" + sources["probe/tails/PodbufCodec.kt"] + "\n" + sources["probe/region/PodbufCodec.kt"])
        compiled.classLoader!!
    }

    private fun runTails(probe: String) {
        @Suppress("UNCHECKED_CAST")
        val failures = tails.loadClass("probe.tails.check.PodbufTailsProbe").getMethod(probe).invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `every tail form round-trips, and verify throws nothing but VerifyError`() = runTails("roundTrips")

    @Test
    fun `the tail is written in placement order, depth first`() = runTails("tailOrder")

    @Test
    fun `a map's entries are written in key order and a reversed pair is KeyOrder`() = runTails("mapOrder")

    @Test
    fun `a NaN map key is refused as Range, a duplicate as Unique`() = runTails("nanAndDuplicate")

    @Test
    fun `-0 and 0 keys are ordered as Rust orders them`() = runTails("zeroKeys")

    @Test
    fun `a string holding U+0000 is refused on encode as Pattern, a string missing its NUL is Terminator`() = runTails("nul")

    @Test
    fun `an array descriptor with another elem_size is Stride`() = runTails("stride")

    @Test
    fun `a len above the declared max is refused before the data is read`() = runTails("lenBound")

    @Test
    fun `a deprecated field is written as its init value`() = runTails("deprecated")

    @Test
    fun `a union arm with no field is accepted whatever its tail bytes`() = runTails("emptyArm")
}
