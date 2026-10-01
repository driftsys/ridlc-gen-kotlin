package ridl.conformance

import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
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

    fun source(models: List<Model>): String {
        val samples = StringBuilder()
        val dispatch = StringBuilder()
        for (model in models) {
            val pkg = model.name.dotted
            for (root in model.flatbuffers.rootsList.filter { it.hasMaxSize() }) {
                val declaration = model.getDeclarations(root.declaration)
                if (declaration.visibility == Visibility.VISIBILITY_INTERNAL) continue
                val type = "$pkg.${declaration.name.camel}"
                val codec = "${type}Codec"
                dispatch.append("        \"$type\" -> roundTrip($codec, hex)\n")
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
            |import ridl.rt.flatbuffers.TableView
            |import ridl.rt.payload.Payload
            |import ridl.rt.payload.VerifyError
            |import java.nio.ByteBuffer
            |
            |private fun hex(bytes: ByteArray, size: Int) = (0 until size).joinToString("") { "%02x".format(bytes[it]) }
            |
            |private fun unhex(hex: String) = ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
            |
            |private fun <T> encode(codec: Payload<T, TableView>, value: T): String {
            |    val out = ByteBuffer.allocate(1 shl 16)
            |    val written = codec.encode(value, out)
            |    return hex(out.array(), written)
            |}
            |
            |private fun debug(error: VerifyError): String = when (error) {
            |    is VerifyError.Structure -> "Structure(${'$'}{error.malformed.name})"
            |    is VerifyError.Contract ->
            |        "Contract(Violation { type_name: \"${'$'}{error.violation.typeName}\", rule: ${'$'}{error.violation.rule.name} })"
            |}
            |
            |private fun <T> roundTrip(codec: Payload<T, TableView>, hex: String): String {
            |    val view = try {
            |        codec.verify(ByteBuffer.wrap(unhex(hex)))
            |    } catch (e: VerifyError) {
            |        return "err ${'$'}{debug(e)}"
            |    }
            |    return "ok " + encode(codec, codec.decode(view))
            |}
            |
            |fun samples(): List<String> = listOf(
            |$samples)
            |
            |fun verdict(type: String, hex: String): String = when (type) {
            |$dispatch        else -> error("no codec for ${'$'}type")
            |}
            |""".trimMargin()
    }

    /** Kotlin expressions for sample values of the types [model] declares or reaches. */
    private class Writer(private val model: Model, private val models: List<Model>) {
        fun declaration(pkg: String, declaration: Declaration, variant: Int, i: Int): String {
            val type = "$pkg.${declaration.name.camel}"
            return when {
                declaration.hasScalar() -> scalar(declaration.scalar, i, "$type", pkg, declaration)
                declaration.hasEnum() -> {
                    val values = declaration.enum.valuesList
                    "$type.${values[(values.size - 1 - i % values.size + values.size) % values.size].name.declared}"
                }
                declaration.hasEnumSet() -> if (variant == SAMPLE_MINIMAL) "$type.EMPTY" else "$type.of($type.DECLARED_MASK)"
                declaration.hasStruct() -> {
                    val fields = declaration.struct.slotsList.filter { it.hasField() }.map { it.field }
                    "$type(" + fields.joinToString(", ") { f ->
                        "${f.name.camel.replaceFirstChar(Char::lowercaseChar)} = ${position(f.type, variant, i)}"
                    } + ")"
                }
                declaration.hasUnion() -> {
                    val arm = declaration.union.getArms(i % declaration.union.armsCount)
                    "$type.${arm.name.camel}(${named(arm.type, variant, i)})"
                }
                else -> error("no sample for ${declaration.name.declared}")
            }
        }

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
                    val sample = key?.let { Probe.SAMPLES[it] }
                    val text = when {
                        sample != null -> sample
                        c.hasPattern() -> error("no sample for the patterned $key")
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
                    if (c.hasMax() && v > BigDecimal(c.max)) v = min
                    v.toPlainString().let { if ('.' in it) it else "$it.0" }
                }
            }
            return when {
                type == null -> raw
                scalar.vacuous -> "$type($raw)"
                else -> "$type.of($raw)"
            }
        }
    }
}
