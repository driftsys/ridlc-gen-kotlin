package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The podbuf codec of fixed-size heads, compiled and run: a package with no
 * tail form, selecting only `podbuf`, whose generated `PodbufCodec.kt` encodes,
 * verifies and decodes every fixed form (booleans, integers and floats of
 * each width, strict and `open` enums and enum sets, inline structs and
 * tuples, exact-length arrays and bytes, optionals with their flags bits, and
 * the box of a named scalar, an enum and an enum set). The Rust verdicts of
 * the corpus come with the conformance suite; this probe pins the rules the
 * emitter alone decides.
 */
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

    @Test
    fun `fixed-size heads round-trip, and verify throws nothing but VerifyError`() {
        val pkg = work.resolve("src-podbuf").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"probe.podbuf\"\nversion = \"1.0.0\"\n\n[backend.kotlin]\nencodings = [\"podbuf\"]\n",
        )
        pkg.resolve("podbuf.ridl").writeText(source + "\n")
        val requests = Harness.capturedRequests(pkg, "podbuf", work)
        val sources = requests.values.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        assertTrue("probe/podbuf/PodbufCodec.kt" in sources, sources.keys.toString())
        assertTrue("probe/podbuf/Codec.kt" !in sources, sources.keys.toString())
        val compiled = Compiler.compile(sources + ("probe/podbuf/check/Probe.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages + "\n" + sources["probe/podbuf/PodbufCodec.kt"])
        @Suppress("UNCHECKED_CAST")
        val failures = compiled.classLoader!!.loadClass("probe.podbuf.check.PodbufFixedProbe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
