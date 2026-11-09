package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PodbufDescriptor
import ridl.codegen.v1.ModelOuterClass.PodbufForm
import ridl.codegen.v1.ModelOuterClass.PodbufHead
import ridl.codegen.v1.ModelOuterClass.PodbufSource
import ridl.codegen.v1.Plugin
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Random

/**
 * docs/design.md §7, "The codec round-trips and the verifier refuses", for the
 * podbuf codec: every corpus package that selects `podbuf`, held to the Rust
 * podbuf codec of the pinned release.
 *
 * The generated `PodbufCodec.kt` encodes sample values of every root that has
 * a podbuf codec ([RoundTrip] in its podbuf mode). The corpus is
 * [CodecTest]'s — every truncation of each minimal sample, and each of its
 * bytes set to 0x00, 0xFF and itself plus one — and a seeded random pass of
 * [RANDOM] mutants of every sample. Each buffer is run through `verify`,
 * `decode` and `encode` again by Kotlin, at position 0 and at position 8 of a
 * larger buffer, which must agree, and once by the Rust codec, whose verdicts
 * are checked in as `resources/podbuf/<package>-codec-rust-verdicts.txt`
 * (`just rust-verdicts`). A verdict is [CodecTest]'s: `ok:<digest16>` or
 * `err:<the VerifyError>`.
 *
 * Every sample must be `ok` in Rust with the bytes Kotlin encoded, no buffer
 * may meet an exception other than `VerifyError`, and every verdict must be
 * Rust's.
 */
class PodbufCodecTest {
    @TempDir
    lateinit var work: Path

    private fun compact(verdict: String): String = when {
        verdict.startsWith("ok ") -> "ok:" + digest(verdict.removePrefix("ok "))
        verdict.startsWith("err ") -> "err:" + verdict.removePrefix("err ")
        else -> "?:$verdict"
    }

    private fun digest(hex: String): String =
        MessageDigest.getInstance("SHA-256").digest(hex.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** The corpus packages that select `podbuf` for this plugin. */
    private fun packages(): List<String> = Harness.packages().filter { "podbuf" in Harness.encodings(it) }

    /** The requests for [name] and for each package it reaches. */
    private fun requests(name: String): List<Plugin.CodegenRequest> =
        Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)

    /** The models of the [requests] whose package selects `podbuf`. */
    private fun podbufModels(requests: List<Plugin.CodegenRequest>): List<Model> =
        requests.filter { request -> request.optionsList.any { it.key == "encodings" && "podbuf" in it.value } }.map { it.model }

    private fun corpus(samples: List<String>): List<String> {
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
            for (k in 0 until RANDOM) lines += "$type $label/random$k ${mutant(random, bytes).joinToString("")}"
            lines
        }
    }

    /**
     * One random mutant of [bytes]: one to four bytes set to any value, a
     * 32-bit word at a multiple of 4 set to a small number (an offset, a
     * length or a tag), or the buffer cut short with one byte changed.
     */
    private fun mutant(random: Random, bytes: List<String>): List<String> {
        val out = bytes.toMutableList()
        if (out.isEmpty()) return out
        fun any() = "%02x".format(random.nextInt(256))
        when (random.nextInt(3)) {
            0 -> repeat(1 + random.nextInt(4)) { out[random.nextInt(out.size)] = any() }
            1 -> {
                val at = random.nextInt((out.size + 3) / 4) * 4
                val value = random.nextInt(80)
                for (b in 0 until 4) if (at + b < out.size) out[at + b] = "%02x".format((value shr (8 * b)) and 0xFF)
            }
            else -> {
                val cut = out.subList(0, random.nextInt(out.size)).toMutableList()
                if (cut.isNotEmpty()) cut[random.nextInt(cut.size)] = any()
                return cut
            }
        }
        return out
    }

    @TestFactory
    fun `the podbuf codec agrees with the Rust codec`(): List<DynamicTest> = packages().map { name ->
        DynamicTest.dynamicTest(name) {
            val requests = requests(name)
            val sources = requests.flatMap { r -> Generator.generate(r).filesList.map { it.path to it.text } }.toMap()
            val models = podbufModels(requests)
            val compiled = Compiler.compile(sources + ("roundtrip/RoundTrip.kt" to RoundTrip.source(models, podbuf = true)), work.resolve("compile-$name"))
            assertTrue(compiled.ok, compiled.messages)
            val facade = compiled.classLoader!!.loadClass("ridl.conformance.roundtrip.RoundTrip")
            @Suppress("UNCHECKED_CAST")
            val samples = facade.getMethod("samples").invoke(null) as List<String>
            val verdict = facade.getMethod("verdictAt", String::class.java, String::class.java, Int::class.javaPrimitiveType)

            val corpus = corpus(samples)
            val out = Path.of(System.getProperty("spike.dir", work.toString())).resolve("$name-podbuf-corpus.txt")
            Files.createDirectories(out.parent)
            Files.writeString(out, corpus.joinToString("\n", postfix = "\n"))

            val escaped = mutableListOf<String>()
            val shifted = mutableListOf<String>()
            val kotlin = corpus.map { line ->
                val (type, label, hex) = line.split(' ')
                fun at(shift: Int): String = try {
                    compact(verdict.invoke(null, type, hex, shift) as String)
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

            val resource = javaClass.getResource("/podbuf/$name-codec-rust-verdicts.txt")
                ?: error("no resources/podbuf/$name-codec-rust-verdicts.txt: run `just rust-verdicts --write`")
            val rust = resource.readText().lines().filter { it.isNotBlank() }
            assertEquals(corpus.size, rust.size, "the Rust verdicts are for another corpus; regenerate them")

            for ((index, line) in corpus.withIndex()) {
                val (_, label, hex) = line.split(' ')
                if ('/' !in label) assertEquals("ok:" + digest(hex), rust[index], "$line: Rust re-encodes the sample Kotlin encoded")
            }
            val disagreements = corpus.indices.filter { kotlin[it] != rust[it] }
                .map { "${corpus[it].substringBeforeLast(' ')}\n  kotlin: ${kotlin[it]}\n  rust:   ${rust[it]}" }
            assertEquals(emptyList<String>(), disagreements.take(10), "${disagreements.size} disagreements")
            println("$name: ${corpus.size} podbuf buffers, ${kotlin.count { it.startsWith("err:") }} refused")
        }
    }

    @Test
    fun `every podbuf form is reached by the corpus`() {
        val reached = sortedSetOf<String>()
        for (name in packages()) {
            for (model in podbufModels(requests(name))) Reach(model, reached).walk()
        }
        val missing = EXPECTED - reached
        assertEquals(emptySet<String>(), missing, "reached: $reached")
    }

    /** Collects the podbuf forms, descriptor kinds and interaction kinds of one model into [reached]. */
    private class Reach(private val model: Model, private val reached: MutableSet<String>) {
        private val declarations: List<Declaration> = model.declarationsList + model.foreignList.map { it.declaration }

        fun walk() {
            assertTrue(model.hasPodbuf(), "${model.name.dotted} carries no podbuf projection")
            for (head in model.podbuf.headsList) {
                if (head.sourceCase == PodbufHead.SourceCase.TUPLE) reached += "tuple head"
                head.fieldsList.forEach { field ->
                    if (field.hasOptional()) reached += "optional with a flag bit"
                    form(field.form)
                }
            }
            for (boxed in model.podbuf.boxesList) {
                reached += "box"
                boxed.head.fieldsList.forEach { form(it.form) }
            }
            for (slot in model.interfacesList.flatMap { it.slotsList }.filter { it.hasInteraction() }) {
                val interaction = slot.interaction
                when {
                    interaction.hasSignal() -> reached += "signal"
                    interaction.hasEvent() -> reached += "event"
                    interaction.hasCommand() -> if (interaction.command.hasRequestTuple()) reached += "command with an induced request"
                    interaction.hasQuery() -> reached += "query"
                }
            }
        }

        private fun width(width: IntWidth) = if (width == IntWidth.INT_WIDTH_U64 || width == IntWidth.INT_WIDTH_I64) "64-bit" else "narrow"

        private fun declared(reference: String): Declaration? = declarations.firstOrNull { it.name.declared == reference.substringAfterLast('.') }

        private fun form(form: PodbufForm) {
            when (form.kindCase) {
                PodbufForm.KindCase.BOOLEAN -> reached += "boolean"
                PodbufForm.KindCase.INT -> reached += "int ${form.int.width.name.removePrefix("INT_WIDTH_")}"
                PodbufForm.KindCase.FLOAT -> reached += "float ${form.float.name.removePrefix("FLOAT_WIDTH_")}"
                PodbufForm.KindCase.ENUM -> {
                    reached += "enum ${width(form.enum.width)}"
                    declared(form.enum.reference)?.let { reached += if (it.enum.open) "enum open" else "enum strict" }
                }
                PodbufForm.KindCase.ENUM_SET -> {
                    reached += "enum set ${width(form.enumSet.width)}"
                    declared(form.enumSet.reference)?.let { reached += if (it.enumSet.open) "enum set open" else "enum set strict" }
                }
                PodbufForm.KindCase.INLINE_HEAD -> {
                    val head = model.podbuf.getHeads(form.inlineHead)
                    reached += when (head.sourceCase) {
                        PodbufHead.SourceCase.DECLARATION ->
                            if (model.getDeclarations(head.declaration).hasUnion()) "inline union" else "inline struct"
                        PodbufHead.SourceCase.TUPLE, PodbufHead.SourceCase.NESTED_TUPLE -> "inline tuple"
                        PodbufHead.SourceCase.FOREIGN -> "inline foreign head"
                        else -> "inline ${head.sourceCase}"
                    }
                }
                PodbufForm.KindCase.INLINE_ARRAY -> {
                    val element = form.inlineArray.element
                    reached += if (element.hasInt() && element.int.width == IntWidth.INT_WIDTH_U8) "inline u8 array" else "inline array"
                    form(element)
                }
                PodbufForm.KindCase.TAIL_DESCRIPTOR -> descriptor(form.tailDescriptor)
                else -> reached += "unknown form ${form.kindCase}"
            }
        }

        private fun descriptor(descriptor: PodbufDescriptor) {
            when (descriptor.kindCase) {
                PodbufDescriptor.KindCase.TEXT -> reached += "string"
                PodbufDescriptor.KindCase.BYTES -> reached += "bytes"
                PodbufDescriptor.KindCase.ARRAY -> {
                    reached += "array"
                    form(descriptor.array.element)
                }
                PodbufDescriptor.KindCase.MAP -> {
                    reached += "map"
                    val entry = model.podbuf.getHeads(descriptor.map.entryHead)
                    val key = entry.fieldsList.first { it.source.kindCase == PodbufSource.KindCase.KEY }.form
                    when {
                        key.hasTailDescriptor() && key.tailDescriptor.hasText() -> reached += "map with a string key"
                        key.hasFloat() -> reached += "map with a float key ${key.float.name.removePrefix("FLOAT_WIDTH_")}"
                        else -> reached += "map with a ${key.kindCase} key"
                    }
                    entry.fieldsList.forEach { form(it.form) }
                }
                PodbufDescriptor.KindCase.ARMS -> {
                    reached += "union"
                    for (arm in descriptor.arms.armsList) {
                        val head = model.podbuf.getHeads(arm.head)
                        when {
                            head.size == 0 -> reached += "union arm with no field"
                            head.fieldsList.any { it.source.kindCase == PodbufSource.KindCase.FIELD } -> reached += "union struct arm"
                        }
                        head.fieldsList.forEach { form(it.form) }
                    }
                }
                else -> reached += "unknown descriptor ${descriptor.kindCase}"
            }
        }
    }

    private companion object {
        const val SEED = 134L
        const val RANDOM = 200

        /** Every form of the podbuf layout law, each integer and float width apart, and every interaction kind. */
        val EXPECTED: Set<String> = setOf(
            "boolean",
            "int U8", "int I8", "int U16", "int I16", "int U32", "int I32", "int U64", "int I64",
            "float F32", "float F64",
            "enum narrow", "enum 64-bit", "enum strict", "enum open",
            "enum set narrow", "enum set 64-bit", "enum set strict", "enum set open",
            "optional with a flag bit",
            "inline struct", "inline tuple", "inline foreign head",
            "inline array", "inline u8 array",
            "string", "bytes", "array", "map",
            "map with a string key", "map with a float key F32", "map with a float key F64",
            "union", "union arm with no field", "union struct arm",
            "tuple head", "box",
            "command with an induced request", "signal", "event", "query",
        )
    }
}
