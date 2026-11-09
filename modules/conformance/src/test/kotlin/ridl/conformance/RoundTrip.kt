package ridl.conformance

import ridl.codegen.kotlin.types.plain
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PodbufHead
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.SizeState
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.Visibility
import java.math.BigDecimal

/**
 * A Kotlin source, compiled with a package's generated code, that encodes
 * sample values of every public codec root and runs any buffer through
 * `verify`, `decode` and `encode` again: `fun samples(): List<String>` of
 * `pkg.Type label hex` lines, and `fun verdict(type, hex): String`,
 * `ok <hex>` or `err <error>`, the error spelled as Rust's `{:?}` spells it.
 *
 * Samples are written from the model: [SAMPLE_MINIMAL] leaves every optional
 * field out and every collection at its minimum, [SAMPLE_FULL] fills every
 * optional field and takes up to two elements, and a union has one sample per
 * arm. The `i`th value of a scalar is its minimum plus `i` steps, so map keys
 * and elements differ.
 */
object RoundTrip {
    const val SAMPLE_MINIMAL = 0
    const val SAMPLE_FULL = 1

    /**
     * The `i`th sample of a named string, by type, where the default's
     * letters would not do: `kt.podbuf.Glyph` keys a map with U+FFFD and
     * U+1F600, whose UTF-8 order differs from their UTF-16 order.
     */
    private val TEXT_SAMPLES: Map<String, List<String>> = mapOf("kt.podbuf.Glyph" to listOf("\uFFFD", "\uD83D\uDE00"))

    /**
     * The round trip over [models]' FlatBuffers codecs, `<Type>Codec`, or,
     * with [podbuf], over their podbuf codecs, `<Type>PodbufCodec`.
     */
    fun source(models: List<Model>, podbuf: Boolean = false): String {
        val samples = StringBuilder()
        val dispatch = StringBuilder()
        val suffix = if (podbuf) "PodbufCodec" else "Codec"
        for (model in models) {
            val pkg = model.name.dotted
            val tupleRoots = if (podbuf) podbufTupleRoots(model) else model.flatbuffers.rootsList.filter { it.hasMaxSize() && it.hasTuple() }.map { it.tuple }
            val declarationRoots = if (podbuf) podbufDeclarationRoots(model) else model.flatbuffers.rootsList.filter { it.hasMaxSize() && !it.hasTuple() }.map { it.declaration }
            for (index in tupleRoots) {
                // A call's induced argument or reply struct (driftsys/ridl#713): sampled as a struct is.
                val tuple = model.getTuples(index)
                if (tuple.visibility == Visibility.VISIBILITY_INTERNAL) continue
                val type = "$pkg.${tuple.name.rust}"
                val codec = "$type$suffix"
                dispatch.append("        \"$type\" -> roundTrip($codec, hex, shift)\n")
                val writer = Writer(model, models)
                for ((label, variant) in listOf("minimal" to SAMPLE_MINIMAL, "full" to SAMPLE_FULL)) {
                    samples.append("        \"$type $label \" + encode($codec, ${writer.record(type, tuple.fieldsList, variant, variant)}),\n")
                }
            }
            for (index in declarationRoots) {
                val declaration = model.getDeclarations(index)
                if (declaration.visibility == Visibility.VISIBILITY_INTERNAL) continue
                val type = "$pkg.${declaration.name.camel}"
                val codec = "$type$suffix"
                dispatch.append("        \"$type\" -> roundTrip($codec, hex, shift)\n")
                val writer = Writer(model, models)
                val values = if (declaration.hasUnion()) {
                    declaration.union.armsList.mapIndexed { i, arm ->
                        "arm-${arm.name.declared}" to "$type.${arm.name.camel}(${writer.named(arm.type, SAMPLE_FULL, i)})"
                    }
                } else {
                    listOf("minimal" to writer.declaration(pkg, declaration, SAMPLE_MINIMAL, 0), "full" to writer.declaration(pkg, declaration, SAMPLE_FULL, 1))
                }
                for ((label, value) in values) {
                    samples.append("        \"$type $label \" + encode($codec, $value),\n")
                }
            }
        }
        return """
            |@file:JvmName("RoundTrip")
            |
            |package ridl.conformance.roundtrip
            |
            |import ridl.rt.payload.Payload
            |import ridl.rt.payload.VerifyError
            |import java.nio.ByteBuffer
            |
            |private fun hex(bytes: ByteArray, from: Int, size: Int) = (from until from + size).joinToString("") { "%02x".format(bytes[it]) }
            |
            |private fun unhex(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            |
            |/** Encodes [value] into a buffer whose position is [shift], and spells the bytes written. */
            |private fun <T, V> encode(codec: Payload<T, V>, value: T, shift: Int = 0): String {
            |    val out = ByteBuffer.wrap(ByteArray(shift + (1 shl 16)), shift, 1 shl 16)
            |    val written = codec.encode(value, out)
            |    return hex(out.array(), shift, written)
            |}
            |
            |private fun debug(error: VerifyError): String = when (error) {
            |    is VerifyError.Structure -> "Structure(${'$'}{error.malformed.name})"
            |    is VerifyError.Contract ->
            |        "Contract(Violation { type_name: ${'$'}{error.violation.typeName?.let { "Some(\"${'$'}it\")" } ?: "None"}, rule: ${'$'}{error.violation.rule.name} })"
            |}
            |
            |/**
            | * Verifies the bytes [hex] spells, placed at position [shift] of a
            | * larger buffer, decodes them, and encodes the value again at [shift].
            | */
            |private fun <T, V> roundTrip(codec: Payload<T, V>, hex: String, shift: Int): String {
            |    val bytes = unhex(hex)
            |    val buffer = ByteBuffer.wrap(ByteArray(shift + bytes.size), shift, bytes.size)
            |    bytes.copyInto(buffer.array(), shift)
            |    val view = try {
            |        codec.verify(buffer)
            |    } catch (e: VerifyError) {
            |        return "err ${'$'}{debug(e)}"
            |    }
            |    return "ok " + encode(codec, codec.decode(view), shift)
            |}
            |
            |fun samples(): List<String> = listOf(
            |$samples)
            |
            |fun verdict(type: String, hex: String): String = verdictAt(type, hex, 0)
            |
            |fun verdictAt(type: String, hex: String, shift: Int): String = when (type) {
            |$dispatch        else -> error("no codec for ${'$'}type")
            |}
            |""".trimMargin()
    }

    /**
     * The structs and unions with a head, and the named scalars, enums and
     * enum sets with a box, whose podbuf bound a Kotlin `Int` holds: the
     * declarations the package's `PodbufCodec.kt` gives a codec, in
     * declaration order.
     */
    private fun podbufDeclarationRoots(model: Model): List<Int> {
        val headed = model.podbuf.headsList.filter { it.sourceCase == PodbufHead.SourceCase.DECLARATION }.map { it.declaration }
        val boxed = model.podbuf.boxesList.map { it.declaration }
        return (headed + boxed).toSortedSet().filter { index ->
            val state = model.getDeclarations(index).podbuf
            state.stateCase == SizeState.StateCase.BOUNDED && Integer.toUnsignedLong(state.bounded) <= Int.MAX_VALUE
        }
    }

    /** The induced tuples a command or a query sends on their own, with a podbuf bound: the Rust backend's `tuple_roots`. */
    private fun podbufTupleRoots(model: Model): List<Int> {
        val roots = sortedSetOf<Int>()
        for (slot in model.interfacesList.flatMap { it.slotsList }.filter { it.hasInteraction() }) {
            val interaction = slot.interaction
            val payloads = when {
                interaction.hasCommand() -> with(interaction.command) { listOf(Triple(hasRequestTuple(), requestTuple, requestSizes)) }
                interaction.hasQuery() -> with(interaction.query) {
                    listOf(Triple(hasRequestTuple(), requestTuple, requestSizes), Triple(hasReplyTuple(), replyTuple, replySizes))
                }
                else -> emptyList()
            }
            for ((has, tuple, sizes) in payloads) {
                if (has && sizes.podbuf.stateCase == SizeState.StateCase.BOUNDED) roots += tuple.index
            }
        }
        return roots.toList()
    }

    /** Kotlin expressions for sample values of the types [model] declares or reaches. */
    private class Writer(private val model: Model, private val models: List<Model>) {
        fun declaration(pkg: String, declaration: Declaration, variant: Int, i: Int): String {
            val type = "$pkg.${declaration.name.camel}"
            return when {
                declaration.hasScalar() -> scalar(declaration.scalar, i, "$type", pkg, declaration)
                declaration.hasEnum() -> {
                    val values = declaration.enum.valuesList
                    // An open enum's full sample is an undeclared value, so Rust's verdict covers one.
                    if (declaration.enum.open && (variant == SAMPLE_FULL || values.isEmpty())) {
                        "$type.fromValue(${100 + i}L)"
                    } else {
                        "$type.${values[(values.size - 1 - i % values.size + values.size) % values.size].name.declared}"
                    }
                }
                declaration.hasEnumSet() -> when {
                    variant == SAMPLE_MINIMAL -> "$type.EMPTY"
                    // Bit 6 fits every width, and the fixture declares bit 0 alone.
                    declaration.enumSet.open -> "$type.of($type.DECLARED_MASK or 64L)"
                    else -> "$type.of($type.DECLARED_MASK)"
                }
                declaration.hasStruct() -> record(type, declaration.struct.slotsList.filter { it.hasField() }.map { it.field }, variant, i)
                declaration.hasUnion() -> {
                    val arm = declaration.union.getArms(i % declaration.union.armsCount)
                    "$type.${arm.name.camel}(${named(arm.type, variant, i)})"
                }
                else -> error("no sample for ${declaration.name.declared}")
            }
        }

        /** A struct's or a tuple's constructor call over [fields]. */
        fun record(type: String, fields: List<ridl.codegen.v1.ModelOuterClass.Field>, variant: Int, i: Int): String =
            "$type(" + fields.joinToString(", ") { f ->
                "${f.name.camel.replaceFirstChar(Char::lowercaseChar)} = ${position(f.type, variant, i)}"
            } + ")"

        fun named(ref: ridl.codegen.v1.ModelOuterClass.TypeRef, variant: Int, i: Int): String {
            val (pkg, declaration) = if (ref.foreign) {
                model.getForeign(ref.index).let { it.`package` to it.declaration }
            } else {
                model.name.dotted to model.getDeclarations(ref.index)
            }
            return declaration(pkg, declaration, variant, i)
        }

        fun position(type: Type, variant: Int, i: Int): String {
            if (type.optional && variant == SAMPLE_MINIMAL) return "null"
            return when (type.kindCase) {
                Type.KindCase.NAMED -> named(type.named, variant, i)
                Type.KindCase.PRIMITIVE -> when (type.primitive) {
                    PrimitiveType.PRIMITIVE_TYPE_BOOLEAN -> if (i % 2 == 0) "true" else "false"
                    PrimitiveType.PRIMITIVE_TYPE_INTEGER -> "${i + 1}L"
                    else -> "${i + 1}.5"
                }
                Type.KindCase.INLINE -> scalar(type.inline, i, null, null, null)
                Type.KindCase.TUPLE -> {
                    val tuple = model.getTuples(type.tuple.index)
                    "${model.name.dotted}.${tuple.name.rust}(" + tuple.fieldsList.joinToString(", ") { f ->
                        "${f.name.camel.replaceFirstChar(Char::lowercaseChar)} = ${position(f.type, variant, i)}"
                    } + ")"
                }
                Type.KindCase.ARRAY -> {
                    val count = count(type.array.min, type.array.max, variant)
                    "listOf(" + (0 until count).joinToString(", ") { position(type.array.element, variant, i + it) } + ")"
                }
                Type.KindCase.MAP -> {
                    val count = count(type.map.min, type.map.max, variant)
                    "mapOf(" + (0 until count).joinToString(", ") {
                        "${position(type.map.key, variant, i + it)} to ${position(type.map.value, variant, i + it)}"
                    } + ")"
                }
                else -> error("no sample for $type")
            }
        }

        private fun count(min: Long, max: Long, variant: Int): Int = when {
            min == max -> min.toInt()
            variant == SAMPLE_MINIMAL -> min.toInt()
            else -> minOf(max, maxOf(min, 2)).toInt()
        }

        /**
         * The `i`th value of a scalar: a named one through its constructor or
         * `of`, an inline one as its backing. [type] and [declaration] are
         * null for an inline scalar.
         */
        fun scalar(scalar: Scalar, i: Int, type: String?, pkg: String?, declaration: Declaration?): String {
            val c = scalar.constraint
            val raw = when (scalar.class_) {
                ScalarClass.SCALAR_CLASS_INTEGER -> {
                    val min = if (c.hasMin()) c.min.toBigInteger() else 0.toBigInteger()
                    val max = if (c.hasMax()) c.max.toBigInteger() else min + 100.toBigInteger()
                    val v = (min + i.toBigInteger()).min(max)
                    if (v == Long.MIN_VALUE.toBigInteger()) "Long.MIN_VALUE" else "${v}L"
                }
                ScalarClass.SCALAR_CLASS_BOOLEAN -> if (i % 2 == 0) "true" else "false"
                ScalarClass.SCALAR_CLASS_STRING -> {
                    val key = if (declaration != null) "$pkg.${declaration.name.camel}" else null
                    val sample = key?.let { TEXT_SAMPLES[it]?.let { values -> values[i % values.size] } ?: Probe.SAMPLES[it] }
                    val text = when {
                        sample != null -> sample
                        c.hasPattern() -> Probe.PATTERN_SAMPLES[c.pattern] ?: error("no sample for the patterned $key")
                        else -> {
                            // A non-ASCII letter, so a sample carries multibyte UTF-8.
                            val length = maxOf(c.lenMin.toInt(), minOf(c.lenMax.toInt(), 2))
                            (0 until length).joinToString("") { if ((it + i) % 2 == 0) "a" else "é" }
                        }
                    }
                    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                }
                ScalarClass.SCALAR_CLASS_BYTES -> {
                    val length = maxOf(c.lenMin.toInt(), minOf(c.lenMax.toInt(), 3))
                    "byteArrayOf(" + (0 until length).joinToString(", ") { "${(it * 37 + i * 11 + 1) % 128}" } + ")"
                }
                else -> {
                    val min = if (c.hasMin()) BigDecimal(c.min) else BigDecimal.ZERO
                    val step = if (c.hasStep()) BigDecimal(c.step) else BigDecimal.ONE
                    var v = min + step * BigDecimal(i)
                    // Past the maximum, or past a `Double` for a step too large for one, the minimum stands in.
                    if ((c.hasMax() && v > BigDecimal(c.max)) || v.toDouble().isInfinite()) v = min
                    v.toPlainString().let { if ('.' in it) it else "$it.0" }
                }
            }
            return when {
                type == null -> raw
                scalar.plain -> "$type($raw)"
                else -> "$type.of($raw)"
            }
        }
    }
}
