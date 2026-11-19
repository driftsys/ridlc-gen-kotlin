package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Proto3Message
import ridl.codegen.v1.ModelOuterClass.Proto3MessageRef
import ridl.codegen.v1.ModelOuterClass.Proto3ScalarType
import ridl.codegen.v1.ModelOuterClass.Proto3Wire
import ridl.codegen.v1.ModelOuterClass.Proto3WireType
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.Plugin
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * docs/design.md §7, "The codec round-trips and the verifier refuses", for the
 * proto3 codec: every corpus package that selects `proto3`, held to the Rust
 * proto3 codec of the pinned release, as [PodbufCodecTest] holds podbuf.
 *
 * The generated `Proto3Codec.kt` encodes sample values of every root that has
 * a proto3 codec ([RoundTrip] in its proto3 mode). The corpus is
 * [CodecTest]'s — every truncation of each minimal sample, and each of its
 * bytes set to 0x00, 0xFF and itself plus one — the reader cases of the Rust
 * codec's header for every full sample and union arm ([readerCases]), and a
 * seeded random pass of [RANDOM] mutants of every sample. Each buffer is run
 * through `verify`, `decode` and `encode` again by Kotlin, at position 0 and at
 * position 8 of a larger buffer, which must agree, and once by the Rust codec,
 * whose verdicts are checked in as
 * `resources/proto3/<package>-codec-rust-verdicts.txt` (`just rust-verdicts`).
 *
 * Every sample must be `ok` in Rust with the bytes Kotlin encoded, no buffer
 * may meet an exception other than `VerifyError`, and every verdict must be
 * Rust's. A pattern is no exception: the Rust crate checks it under its
 * `validate-pattern` feature, a default one the verdict program keeps.
 */
class Proto3CodecTest {
    @TempDir
    lateinit var work: Path

    private fun compact(verdict: String): String = when {
        verdict.startsWith("ok ") -> "ok:" + digest(verdict.removePrefix("ok "))
        verdict.startsWith("err ") -> "err:" + verdict.removePrefix("err ")
        else -> "?:$verdict"
    }

    private fun digest(hex: String): String =
        MessageDigest.getInstance("SHA-256").digest(hex.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** The corpus packages that select `proto3` for this plugin. */
    private fun packages(): List<String> = Harness.packages().filter { "proto3" in Harness.encodings(it) }

    private fun requests(name: String): List<Plugin.CodegenRequest> =
        Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)

    /** The models of the [requests] whose package selects `proto3`. */
    private fun proto3Models(requests: List<Plugin.CodegenRequest>): List<Model> =
        requests.filter { request -> request.optionsList.any { it.key == "encodings" && "proto3" in it.value } }.map { it.model }

    private class Compiled(val samples: List<String>, val verdict: (String, String, Int) -> String)

    private fun compiled(name: String): Pair<Compiled, List<Model>> {
        val requests = requests(name)
        val sources = requests.flatMap { r -> Generator.generate(r).filesList.map { it.path to it.text } }.toMap()
        val models = proto3Models(requests)
        val compiled = Compiler.compile(sources + ("roundtrip/RoundTrip.kt" to RoundTrip.source(models, proto3 = true)), work.resolve("compile-$name"))
        assertTrue(compiled.ok, compiled.messages)
        val facade = compiled.classLoader!!.loadClass("ridl.conformance.roundtrip.RoundTrip")
        @Suppress("UNCHECKED_CAST")
        val samples = facade.getMethod("samples").invoke(null) as List<String>
        val method = facade.getMethod("verdictAt", String::class.java, String::class.java, Int::class.javaPrimitiveType)
        return Compiled(samples) { type, hex, shift -> method.invoke(null, type, hex, shift) as String } to models
    }

    private fun corpus(samples: List<String>, models: List<Model>): List<String> {
        val random = Random(SEED)
        return samples.flatMap { line ->
            val (type, label, hex) = line.split(' ')
            val bytes = hex.chunked(2)
            val lines = mutableListOf(line)
            if (label == "minimal" || label.startsWith("arm-")) {
                for (n in 0 until bytes.size) lines += "$type $label/trunc$n ${bytes.take(n).joinToString("")}"
                for (i in bytes.indices) {
                    for ((name, byte) in listOf("zero" to "00", "ones" to "ff", "inc" to "%02x".format((bytes[i].toInt(16) + 1) and 0xFF))) {
                        if (byte != bytes[i]) lines += "$type $label/$name$i ${bytes.toMutableList().also { it[i] = byte }.joinToString("")}"
                    }
                }
            }
            if (label == "full" || label.startsWith("arm-")) {
                for (case in readerCases(models, type, bytes)) lines += "$type $label/${case.name} ${case.bytes.joinToString("")}"
            }
            for (k in 0 until RANDOM) lines += "$type $label/random$k ${mutant(random, bytes).joinToString("")}"
            lines
        }
    }

    /** One record of a message: its field number, its wire type, its bytes from the tag on, and its payload. */
    private class Record(val number: Int, val wire: Int, val bytes: List<String>, val payload: List<String>)

    /** The top-level records of [bytes], or `null` when they do not parse as a message. */
    private fun records(bytes: List<String>): List<Record>? {
        val out = mutableListOf<Record>()
        var at = 0
        fun varint(): Long? {
            var value = 0L
            var shift = 0
            while (at < bytes.size && shift < 64) {
                val b = bytes[at++].toInt(16)
                value = value or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return value
                shift += 7
            }
            return null
        }
        while (at < bytes.size) {
            val start = at
            val tag = varint() ?: return null
            val wire = (tag and 7).toInt()
            var payload = at
            when (wire) {
                0 -> varint() ?: return null
                1 -> at += 8
                2 -> {
                    val n = varint() ?: return null
                    payload = at
                    at += n.toInt()
                }
                5 -> at += 4
                else -> return null
            }
            if (at > bytes.size || at < payload) return null
            out += Record((tag ushr 3).toInt(), wire, bytes.subList(start, at), bytes.subList(payload, at))
        }
        return out
    }

    private fun varint(value: Long): List<String> {
        val out = mutableListOf<String>()
        var v = value
        do {
            val b = (v and 0x7f).toInt()
            v = v ushr 7
            out += "%02x".format(if (v == 0L) b else b or 0x80)
        } while (v != 0L)
        return out
    }

    private fun tag(number: Int, wire: Int): List<String> = varint((number.toLong() shl 3) or wire.toLong())

    private fun len(number: Int, payload: List<String>): List<String> = tag(number, 2) + varint(payload.size.toLong()) + payload

    /** A reader case of the Rust header: its name, its bytes, and the verdict Kotlin and Rust give it. */
    private class Case(val name: String, val bytes: List<String>, val expected: String)

    /** The proto3 message of the root [type], `pkg.Name`, in [models]. */
    private fun messageOf(models: List<Model>, type: String): Pair<Model, Proto3Message>? {
        val model = models.firstOrNull { it.name.dotted == type.substringBeforeLast('.') } ?: return null
        val name = type.substringAfterLast('.')
        val message = model.proto3.messagesList.firstOrNull { m ->
            when (m.sourceCase) {
                Proto3Message.SourceCase.STRUCT_DECLARATION -> model.getDeclarations(m.structDeclaration).name.camel == name
                Proto3Message.SourceCase.UNION_DECLARATION -> model.getDeclarations(m.unionDeclaration).name.camel == name
                Proto3Message.SourceCase.ROOT_BOX -> model.getDeclarations(m.rootBox).name.camel == name
                Proto3Message.SourceCase.TUPLE -> model.getTuples(m.tuple).name.rust == name
                else -> false
            }
        } ?: return null
        return model to message
    }

    /** The name violations of [message] carry: the declared name, or a tuple's Rust name. */
    private fun ownerOf(model: Model, message: Proto3Message): String = when (message.sourceCase) {
        Proto3Message.SourceCase.STRUCT_DECLARATION -> model.getDeclarations(message.structDeclaration).name.declared
        Proto3Message.SourceCase.UNION_DECLARATION -> model.getDeclarations(message.unionDeclaration).name.declared
        Proto3Message.SourceCase.ROOT_BOX -> model.getDeclarations(message.rootBox).name.declared
        else -> model.getTuples(message.tuple).name.rust
    }

    /**
     * The reader cases `codec_proto3.rs` lists in its header, as mutants of
     * the canonical [bytes] of a full sample or a union arm of [type]: fields
     * in another order, a packed field unpacked and mixed, an unknown field,
     * each read as the sample is; a singular field twice (`Duplicate`), a
     * known field under another wire type (`WireType`), a 32-bit varint past
     * u32 (`Varint`), a fixed-width packed run one byte long (`OutOfBounds`),
     * a map key held twice (`Unique`), and a double field set to `-0.0`,
     * which is read and written again as it is.
     */
    private fun readerCases(models: List<Model>, type: String, bytes: List<String>): List<Case> {
        val (model, message) = messageOf(models, type) ?: return emptyList()
        val records = records(bytes) ?: return emptyList()
        val fields = message.fieldsList.associateBy { it.number }
        val same = "ok:" + digest(bytes.joinToString(""))
        fun rebuilt(replace: Record, with: List<String>) = records.flatMap { if (it === replace) with else it.bytes }
        val cases = mutableListOf<Case>()
        // The fields in reverse number order; a repeated field's records keep theirs, which is the elements' order.
        val byNumber = records.groupBy { it.number }
        if (byNumber.size >= 2) cases += Case("reorder", byNumber.values.reversed().flatten().flatMap { it.bytes }, same)
        if (UNKNOWN !in fields) cases += Case("unknown", bytes + tag(UNKNOWN, 0) + "01" + len(UNKNOWN, listOf("78")), same)
        for (record in records) {
            val wire = fields[record.number]?.wire ?: continue
            if (!wire.hasArray() || !wire.array.packed || record.wire != 2) continue
            val element = wire.array.element
            val width = widthOf(element)
            val elements = if (width == 0) varints(record.payload) else record.payload.chunked(width)
            val elementWire = when (width) {
                4 -> 5
                8 -> 1
                else -> 0
            }
            val n = record.number
            cases += Case("unpacked-$n", rebuilt(record, elements.flatMap { tag(n, elementWire) + it }), same)
            if (elements.size >= 2) cases += Case("mixed-$n", rebuilt(record, tag(n, elementWire) + elements.first() + len(n, elements.drop(1).flatten())), same)
            if (width != 0) cases += Case("oob-$n", rebuilt(record, len(n, record.payload + "00")), "err:Structure(OutOfBounds)")
        }
        val singular = records.firstOrNull { r -> fields[r.number]?.wire?.let { !it.hasArray() && !it.hasMap() } == true }
        if (singular != null) {
            val n = singular.number
            cases += Case("dup-$n", bytes + singular.bytes, "err:Structure(Duplicate)")
            cases += Case("wiretype-$n", rebuilt(singular, if (singular.wire == 2) tag(n, 0) + "00" else len(n, emptyList())), "err:Structure(WireType)")
        }
        records.firstOrNull { r -> fields[r.number]?.wire?.let(::narrowVarint) == true }?.let { r ->
            cases += Case("varint-${r.number}", rebuilt(r, tag(r.number, 0) + varint(1L shl 32)), "err:Structure(Varint)")
        }
        records.firstOrNull { r ->
            fields[r.number]?.wire?.let { it.hasScalar() && it.scalar.type == Proto3ScalarType.PROTO3_SCALAR_TYPE_DOUBLE && it.defaultIsLegal } == true
        }?.let { r ->
            val mutated = rebuilt(r, tag(r.number, 1) + listOf("00", "00", "00", "00", "00", "00", "00", "80"))
            cases += Case("minus-zero-${r.number}", mutated, "ok:" + digest(mutated.joinToString("")))
        }
        for (number in records.filter { fields[it.number]?.wire?.hasMap() == true }.map { it.number }.distinct()) {
            val entries = records.filter { it.number == number }
            if (entries.size < 2) continue
            val unique = "err:Contract(Violation { type_name: Some(\"${ownerOf(model, message)}\"), rule: Unique })"
            cases += Case("same-$number", rebuilt(entries[1], entries[0].bytes), unique)
        }
        return cases.map { Case("case-${it.name}", it.bytes, it.expected) }
    }

    /** The width of a packed element of [wire]: 4 or 8 for a fixed-width scalar, 0 for a varint. */
    private fun widthOf(wire: Proto3Wire): Int = when {
        wire.hasScalar() && wire.scalar.wireType == Proto3WireType.PROTO3_WIRE_TYPE_I32 -> 4
        wire.hasScalar() && wire.scalar.wireType == Proto3WireType.PROTO3_WIRE_TYPE_I64 -> 8
        else -> 0
    }

    /** Whether a varint past u32 is too wide for [wire]: a 32-bit scalar, or an enum's int32. */
    private fun narrowVarint(wire: Proto3Wire): Boolean = wire.hasEnumeration() ||
        (wire.hasScalar() && wire.scalar.type in setOf(Proto3ScalarType.PROTO3_SCALAR_TYPE_UINT32, Proto3ScalarType.PROTO3_SCALAR_TYPE_SINT32))

    /** The varints of a packed run, each with its bytes. */
    private fun varints(payload: List<String>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var start = 0
        for (i in payload.indices) {
            if (payload[i].toInt(16) and 0x80 == 0) {
                out += payload.subList(start, i + 1)
                start = i + 1
            }
        }
        return out
    }

    /** One random mutant of [bytes]: one to four bytes set to any value, or the buffer cut short with one byte changed. */
    private fun mutant(random: Random, bytes: List<String>): List<String> {
        val out = bytes.toMutableList()
        if (out.isEmpty()) return out
        fun any() = "%02x".format(random.nextInt(256))
        if (random.nextInt(2) == 0) {
            repeat(1 + random.nextInt(4)) { out[random.nextInt(out.size)] = any() }
            return out
        }
        val cut = out.subList(0, random.nextInt(out.size)).toMutableList()
        if (cut.isNotEmpty()) cut[random.nextInt(cut.size)] = any()
        return cut
    }

    @TestFactory
    fun `the proto3 codec agrees with the Rust codec`(): List<DynamicTest> = packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val (compiled, models) = compiled(name)
            val corpus = corpus(compiled.samples, models)
            val out = Path.of(System.getProperty("spike.dir", work.toString())).resolve("$name-proto3-corpus.txt")
            Files.createDirectories(out.parent)
            Files.writeString(out, corpus.joinToString("\n", postfix = "\n"))

            val escaped = mutableListOf<String>()
            val shifted = mutableListOf<String>()
            val kotlin = corpus.map { line ->
                val (type, label, hex) = line.split(' ')
                fun at(shift: Int): String = try {
                    compact(compiled.verdict(type, hex, shift))
                } catch (e: InvocationTargetException) {
                    escaped += "$type $label at $shift: ${e.targetException}"
                    "threw:${e.targetException::class.simpleName}"
                }
                val zero = at(0)
                val eight = at(8)
                if (zero != eight) shifted += "$type $label: at 0 $zero, at 8 $eight"
                zero
            }
            assertEquals(emptyList<String>(), escaped.take(10), "${escaped.size} buffers met an exception other than VerifyError")
            assertEquals(emptyList<String>(), shifted.take(10), "${shifted.size} buffers read otherwise at position 8")

            val resource = javaClass.getResource("/proto3/$name-codec-rust-verdicts.txt")
                ?: error("no resources/proto3/$name-codec-rust-verdicts.txt: run `just rust-verdicts --write`")
            val rust = resource.readText().lines().filter { it.isNotBlank() }
            assertEquals(corpus.size, rust.size, "the Rust verdicts are for another corpus; regenerate them")

            for ((index, line) in corpus.withIndex()) {
                val (_, label, hex) = line.split(' ')
                if ('/' !in label) assertEquals("ok:" + digest(hex), rust[index], "$line: Rust re-encodes the sample Kotlin encoded")
            }
            val disagreements = corpus.indices.filter { kotlin[it] != rust[it] }
                .map { "${corpus[it].substringBeforeLast(' ')}\n  kotlin: ${kotlin[it]}\n  rust:   ${rust[it]}" }
            assertEquals(emptyList<String>(), disagreements.take(10), "${disagreements.size} disagreements")
            println("$name: ${corpus.size} proto3 buffers, ${kotlin.count { it.startsWith("err:") }} refused")
        }
    }

    @Test
    fun `the reader cases of the Rust codec's header get their verdicts`() {
        val (compiled, models) = compiled("kt-proto3")
        val wrong = mutableListOf<String>()
        val reached = sortedSetOf<String>()
        for (line in compiled.samples) {
            val (type, label, hex) = line.split(' ')
            if (label != "full" && !label.startsWith("arm-")) continue
            for (case in readerCases(models, type, hex.chunked(2))) {
                reached += case.name.removePrefix("case-").substringBefore('-').let { if (it == "minus") "minus-zero" else it }
                val verdict = compact(compiled.verdict(type, case.bytes.joinToString(""), 0))
                if (verdict != case.expected) wrong += "$type $label/${case.name}: $verdict, expected ${case.expected}"
            }
        }
        assertEquals(emptyList<String>(), wrong)
        assertEquals(READER_CASES, reached)
    }

    @Test
    fun `every proto3 wire kind is reached by the corpus`() {
        val reached = sortedSetOf<String>()
        for (name in packages()) {
            for (model in proto3Models(requests(name))) Reach(model, reached).walk()
        }
        assertEquals(emptySet<String>(), EXPECTED - reached, "reached: $reached")
    }

    /** Collects the proto3 wire kinds of one model's projection, and the type positions behind them, into [reached]. */
    private class Reach(private val model: Model, private val reached: MutableSet<String>) {
        fun walk() {
            assertTrue(model.hasProto3(), "${model.name.dotted} carries no proto3 projection")
            for (message in model.proto3.messagesList) {
                reached += message.sourceCase.name.lowercase()
                message.fieldsList.forEach { wire(it.wire) }
            }
            for (declaration in model.declarationsList.filter { it.hasStruct() }) {
                for (slot in declaration.struct.slotsList.filter { it.hasField() }) type(slot.field.type)
            }
        }

        private fun type(type: Type) {
            if (type.optional) reached += "optional"
            if (type.hasNamed() && !type.named.foreign && model.getDeclarations(type.named.index).hasEnumSet()) reached += "enum set"
            if (type.hasArray() && type.array.min == type.array.max) reached += "fixed-length array"
        }

        private fun wire(wire: Proto3Wire) {
            if (!wire.defaultIsLegal) reached += "default not legal"
            when (wire.kindCase) {
                Proto3Wire.KindCase.SCALAR -> reached += "scalar ${wire.scalar.type.name.removePrefix("PROTO3_SCALAR_TYPE_")}"
                Proto3Wire.KindCase.TEXT -> reached += "text"
                Proto3Wire.KindCase.BYTES -> reached += "bytes"
                Proto3Wire.KindCase.ENUMERATION -> reached += "enum"
                Proto3Wire.KindCase.MESSAGE -> reached += if (wire.message.targetCase == Proto3MessageRef.TargetCase.FOREIGN) "foreign message" else "message"
                Proto3Wire.KindCase.ARRAY -> {
                    val element = wire.array.element
                    reached += when {
                        !wire.array.packed -> "unpacked array"
                        element.hasScalar() -> "packed array of ${element.scalar.wireType.name.removePrefix("PROTO3_WIRE_TYPE_")}"
                        else -> "packed array of ${element.kindCase.name.lowercase()}"
                    }
                    wire(element)
                }
                Proto3Wire.KindCase.MAP -> {
                    val key = wire.map.key
                    reached += "map with a ${if (key.hasScalar()) key.scalar.type.name.removePrefix("PROTO3_SCALAR_TYPE_") else key.kindCase.name.lowercase()} key"
                    if (wire.map.value.hasMessage()) reached += "map with a message value"
                    wire(key)
                    wire(wire.map.value)
                }
                else -> reached += "unknown wire ${wire.kindCase}"
            }
        }
    }

    /**
     * #133, task 6 not done: a package that selects proto3 and declares an
     * interface gets no face for it, with one warning naming why, and keeps
     * its codecs, among them the codec of the struct induced for a call,
     * which compiles and writes the canonical proto3 bytes.
     */
    @Test
    fun `an interface under proto3 has no face, and its induced struct has a codec`() {
        val pkg = work.resolve("src-probe").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"probe.proto3\"\nversion = \"1.0.0\"\n\n[backend.kotlin]\nencodings = [\"proto3\"]\n",
        )
        pkg.resolve("source.ridl").writeText(
            """
            package probe.proto3

            /// A level.
            type Level : integer [0..10]
            /// A name.
            type Name : string [1..8]

            /// A meter.
            interface Meter {
              /// Sets the level and the name.
              command set(level: Level, name: Name) @[..50ms]
            }
            """.trimIndent() + "\n",
        )
        val request = Wire.readRequest(Harness.capturedRequests(pkg, "probe", work).getValue("probe.proto3"))
        val response = Generator.generate(request)
        assertEquals(
            listOf(
                "ridlc-gen-kotlin: interface `probe.proto3.Meter` has no generated face: " +
                    "the Kotlin face does not carry the proto3 encoding yet (#133)",
            ),
            response.diagnosticsList.map { it.message },
        )
        assertTrue(response.diagnosticsList.all { it.severity == Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING })
        val sources = response.filesList.associate { it.path to it.text }
        assertEquals(setOf("probe/proto3/Types.kt", "probe/proto3/Proto3Codec.kt"), sources.keys)
        val tuple = request.model.tuplesList.single { it.name.rust.isNotEmpty() }.name.rust
        val run = """
            package probe.run

            import probe.proto3.$tuple
            import probe.proto3.${tuple}Proto3Codec
            import probe.proto3.Level
            import probe.proto3.Name

            fun run(): String {
                val out = java.nio.ByteBuffer.allocate(${tuple}Proto3Codec.maxSize)
                val value = $tuple(Level.of(3L), Name.of("ab"))
                val written = ${tuple}Proto3Codec.encode(value, out)
                out.flip()
                val decoded = ${tuple}Proto3Codec.decode(${tuple}Proto3Codec.verify(out))
                val hex = (0 until written).joinToString("") { "%02x".format(out.get(it)) }
                return hex + " " + (decoded == value)
            }
        """.trimIndent()
        val compiled = Compiler.compile(sources + ("probe/run/Run.kt" to run), work.resolve("compile-probe"))
        assertTrue(compiled.ok, compiled.messages)
        val result = compiled.classLoader!!.loadClass("probe.run.RunKt").getMethod("run").invoke(null)
        // Field 1, a uint32 3; field 2, the two bytes of "ab".
        assertEquals("0803120261" + "62 true", result)
    }

    private companion object {
        const val SEED = 133L
        const val RANDOM = 200

        /** A field number no corpus message uses, below the reserved range. */
        const val UNKNOWN = 2047

        val READER_CASES: Set<String> = setOf("reorder", "unknown", "unpacked", "mixed", "oob", "dup", "wiretype", "varint", "minus-zero", "same")

        /**
         * Every wire kind of the proto3 projection: each scalar type a source
         * reaches — `INT64` is an enum's own, which the projection spells as
         * an enum — text, bytes, enums and enum sets, messages of this package
         * and of another, every message source, packed varint and fixed-width
         * arrays, unpacked arrays, maps keyed by a string, an integer and a
         * boolean, with a message value, optionals, and defaults that are not
         * legal.
         */
        val EXPECTED: Set<String> = setOf(
            "scalar BOOL", "scalar UINT32", "scalar UINT64", "scalar SINT32", "scalar SINT64", "scalar FLOAT", "scalar DOUBLE",
            "text", "bytes", "enum", "enum set", "message", "foreign message",
            "struct_declaration", "union_declaration", "root_box", "tuple",
            "packed array of VARINT", "packed array of I32", "packed array of I64", "packed array of enumeration", "unpacked array",
            "fixed-length array",
            "map with a text key", "map with a SINT32 key", "map with a UINT64 key", "map with a BOOL key", "map with a message value",
            "optional", "default not legal",
        )
    }
}
