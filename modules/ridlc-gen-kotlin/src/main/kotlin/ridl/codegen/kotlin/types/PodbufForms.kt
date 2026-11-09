package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MemberName
import ridl.codegen.v1.ModelOuterClass.FloatWidth
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PodbufArray
import ridl.codegen.v1.ModelOuterClass.PodbufDescriptor
import ridl.codegen.v1.ModelOuterClass.PodbufForm
import ridl.codegen.v1.ModelOuterClass.PodbufMap
import ridl.codegen.v1.ModelOuterClass.PodbufSource
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.Type

private const val PODBUF = "ridl.rt.podbuf"
internal val PB_BUFFER = ClassName(PODBUF, "Buffer")
internal val PB_DESC = ClassName(PODBUF, "Desc")
internal val PB_ARRAY_DESC = ClassName(PODBUF, "ArrayDesc")
internal val PB_VERIFY_ERROR = ClassName("ridl.rt.payload", "VerifyError")
internal val PB_ENCODE_ERROR = ClassName("ridl.rt.payload", "EncodeError")
internal val PB_VIOLATION = ClassName("ridl.rt.payload", "Violation")
internal val PB_MALFORMED = ClassName("ridl.rt.payload", "Malformed")
internal val PB_RULE = ClassName("ridl.rt.payload", "Rule")
private val PB_TAIL_RANGE = MemberName(PODBUF, "tailRange")
private val PB_TEXT = MemberName(PODBUF, "text")
private val PB_HAS_NUL = MemberName(PODBUF, "hasNul")

/** The generated file's own helper that copies a descriptor's bytes out of the buffer. */
private const val BYTES_OF = "podbufBytes"

/** The generated file's own helper that orders two keys by their bytes. */
private const val COMPARE_BYTES = "podbufCompareBytes"

/**
 * Where a head's bytes are read: at [at] of the buffer, [size] bytes as the
 * producer wrote them, through the `Region` named [name]. Reading [region]
 * records that the code needs the region, so [binding] declares it only then.
 */
internal class PodbufPlace(val at: String, val size: String, private val name: String) {
    var used = false
        private set

    val region: String
        get() {
            used = true
            return name
        }

    /** The `at` and `size` of a head inline at [off] of this one: the bytes past the producer's size are the parent's. */
    fun inline(off: String): Pair<String, String> = if (off == "0") at to size else "$at + $off" to "maxOf($size - $off, 0)"

    /**
     * `val <name> = buffer.region(at, size)` when the code reads the region,
     * else nothing. Reading [region] is what marks it read, so call this only
     * once the code that reads this place is built: called before, it binds
     * nothing that code then names.
     */
    fun binding(): CodeBlock = if (used) CodeBlock.of("val %L = buffer.region(%L, %L)\n", name, at, size) else CodeBlock.of("")
}

/**
 * The write, the checks and the read of each podbuf form, at a position of a
 * head, an element, a map entry or a union arm, as `codec_podbuf.rs`'s
 * `write_form`, `verify_form` and `decode_expr` give them. A head's
 * [PodbufCodecEmitter] places them; nested heads go through their own helpers.
 */
internal class PodbufForms(private val model: Model, private val pkg: String) {
    val wires = Wires(model, pkg)

    /** The bodies of the inline string patterns the checks compile, in registration order. */
    val patterns = mutableListOf<String>()

    private var names = 0
    private var bytesOf = false
    private var compareBytes = false

    fun fresh(stem: String): String = "$stem${names++}"

    /** The file's own private helpers the forms called. */
    fun privateHelpers(): List<FunSpec> = listOfNotNull(
        FunSpec.builder(BYTES_OF).addModifiers(KModifier.PRIVATE)
            .addKdoc("The [len] bytes at [off] of [buffer], which `verify` checked lie in it.")
            .addParameter("buffer", PB_BUFFER).addParameter("off", LONG).addParameter("len", LONG).returns(BYTE_ARRAY)
            .addStatement("return buffer.region(off.toInt(), len.toInt()).bytesAt(0, len.toInt())")
            .build().takeIf { bytesOf },
        FunSpec.builder(COMPARE_BYTES).addModifiers(KModifier.PRIVATE)
            .addKdoc("The order of two map keys' bytes (ADR-0031 decision 5): unsigned, byte by byte, a prefix first.")
            .addParameter("a", BYTE_ARRAY).addParameter("b", BYTE_ARRAY).returns(INT)
            .beginControlFlow("for (i in 0 until minOf(a.size, b.size))")
            .addStatement("val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)")
            .addStatement("if (d != 0) return d")
            .endControlFlow()
            .addStatement("return a.size - b.size")
            .build().takeIf { compareBytes },
    )

    private fun bytesAt(off: String, len: String): String {
        bytesOf = true
        return "$BYTES_OF(buffer, $off, $len)"
    }

    // -- the model -------------------------------------------------------------------

    fun formOf(form: PodbufForm, owner: String): PodbufForm =
        if (form.kindCase != PodbufForm.KindCase.KIND_NOT_SET) form else refuse("a podbuf field of `$owner` carries no form")

    fun headSize(index: Int): Int = model.podbuf.headsList.getOrNull(index)?.size ?: 0

    /** The size in bytes of a value of [form], which is an inline array's stride. */
    fun formSize(form: PodbufForm): Long = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN -> 1
        PodbufForm.KindCase.INT -> wires.intPrim(form.int.width).width.toLong()
        PodbufForm.KindCase.FLOAT -> floatPrim(form.float).width.toLong()
        PodbufForm.KindCase.ENUM -> wires.intPrim(form.enum.width).width.toLong()
        PodbufForm.KindCase.ENUM_SET -> wires.intPrim(form.enumSet.width).width.toLong()
        PodbufForm.KindCase.INLINE_HEAD -> headSize(form.inlineHead).toLong()
        PodbufForm.KindCase.INLINE_ARRAY -> formSize(form.inlineArray.element) * form.inlineArray.count
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> 8
        else -> refuse("a podbuf field carries no form")
    }

    /** The alignment in bytes of a value of [form]: where its data starts in the tail. */
    private fun formAlign(form: PodbufForm): Long = when (form.kindCase) {
        PodbufForm.KindCase.INLINE_HEAD -> 8
        PodbufForm.KindCase.INLINE_ARRAY -> if (form.inlineArray.hasElement()) formAlign(form.inlineArray.element) else 1
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> 4
        else -> maxOf(formSize(form), 1)
    }

    private fun floatPrim(width: FloatWidth): Prim = when (width) {
        FloatWidth.FLOAT_WIDTH_F32 -> Prim.F32
        FloatWidth.FLOAT_WIDTH_F64 -> Prim.F64
        else -> refuse("a podbuf float carries no width")
    }

    /** The primitive a scalar, enum or enum set [form] is loaded and stored as. */
    fun scalarPrim(form: PodbufForm): Prim = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN -> Prim.Bool
        PodbufForm.KindCase.INT -> wires.intPrim(form.int.width)
        PodbufForm.KindCase.FLOAT -> floatPrim(form.float)
        PodbufForm.KindCase.ENUM -> wires.intPrim(form.enum.width)
        PodbufForm.KindCase.ENUM_SET -> wires.intPrim(form.enumSet.width)
        else -> error("not a scalar form")
    }

    /** [type] with its `optional` cleared: the type of a present value. */
    fun present(type: Type): Type = if (type.optional) type.toBuilder().setOptional(false).build() else type

    /** What a scalar type position holds in Kotlin. */
    private fun domainOf(type: Type): Domain = when (val wire = wires.of(type)) {
        is Wire.ScalarWire -> wire.domain
        is Wire.Bytes -> wire.domain
        is Wire.Text -> wire.domain
        else -> refuse("a podbuf scalar is written from no scalar type")
    }

    /** What a string ([text]) or bytes position holds: a named or an inline scalar, never a bare one. */
    private fun sequenceOf(type: Type, text: Boolean): Domain {
        if (type.kindCase == Type.KindCase.PRIMITIVE &&
            (type.primitive == PrimitiveType.PRIMITIVE_TYPE_STRING || type.primitive == PrimitiveType.PRIMITIVE_TYPE_BYTES)
        ) {
            refuse("a bare string or bytes has no podbuf form")
        }
        return when (val wire = wires.of(type)) {
            is Wire.Text -> wire.domain.takeIf { text }
            is Wire.Bytes -> wire.domain.takeIf { !text }
            else -> null
        } ?: refuse(if (text) "a podbuf string is written from no string type" else "a podbuf bytes field is written from no bytes type")
    }

    /** The helpers of the struct, union or tuple [type] names, in the package that declares it. */
    fun headFunctions(type: Type): PodbufFunctions = when (type.kindCase) {
        Type.KindCase.NAMED -> {
            val (owner, declaration) = wires.declarationOf(type.named)
            if (!declaration.hasStruct() && !declaration.hasUnion()) refuse("an inline podbuf head is written from no struct, union or tuple")
            PodbufFunctions(owner, declaration.name.camel)
        }
        Type.KindCase.TUPLE -> {
            val name = model.tuplesList.getOrNull(type.tuple.index)?.name?.rust.orEmpty()
            if (name.isEmpty()) refuse("an inline tuple has no Kotlin class")
            PodbufFunctions(pkg, name)
        }
        else -> refuse("an inline podbuf head is written from no struct, union or tuple")
    }

    // -- encoding ---------------------------------------------------------------------

    /**
     * The statements that write a present value [expr] of [form] at [at],
     * from the type position [type], and append its tail data after what is
     * already written: the tail is in placement order, depth first
     * (ADR-0031 decision 4, "Tail order").
     */
    fun writeForm(form: PodbufForm, type: Type, expr: String, at: String, owner: String): CodeBlock = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN -> CodeBlock.of("w.putU8(%L, if (%L) 1 else 0)\n", at, base(domainOf(type), expr))
        PodbufForm.KindCase.INT, PodbufForm.KindCase.FLOAT -> {
            val prim = scalarPrim(form)
            CodeBlock.of("w.put%L(%L, %L)\n", prim.field, at, narrowed(prim, base(domainOf(type), expr)))
        }
        PodbufForm.KindCase.ENUM, PodbufForm.KindCase.ENUM_SET -> {
            val prim = scalarPrim(form)
            val domain = domainOf(type)
            val range = openRange(domain, form)
            if (range == null) {
                CodeBlock.of("w.put%L(%L, %L)\n", prim.field, at, narrowed(prim, base(domain, expr)))
            } else {
                // An open enum admits every Long; one the field cannot hold is refused, not truncated.
                val raw = fresh("raw")
                CodeBlock.builder()
                    .addStatement("val %L = %L", raw, base(domain, expr))
                    .beginControlFlow("if (%L !in %LL..%LL)", raw, range.first, range.last)
                    .add(encodeRefusal((domain as Domain.EnumOf).declared, RuleName.Range))
                    .endControlFlow()
                    .addStatement("w.put%L(%L, %L)", prim.field, at, narrowed(prim, raw))
                    .build()
            }
        }
        PodbufForm.KindCase.INLINE_HEAD -> if (headSize(form.inlineHead) == 0) {
            // A head with no field writes nothing.
            CodeBlock.of("")
        } else {
            CodeBlock.of("%M(%L, w, %L)\n", headFunctions(type).let { MemberName(it.pkg, it.encode) }, expr, at)
        }
        PodbufForm.KindCase.INLINE_ARRAY -> inlineArrayWrite(form, type, expr, at, owner)
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> {
            val descriptor = form.tailDescriptor
            when (descriptor.kindCase) {
                PodbufDescriptor.KindCase.TEXT -> textWrite(type, expr, at, owner)
                PodbufDescriptor.KindCase.BYTES -> bytesWrite(type, expr, at, owner)
                PodbufDescriptor.KindCase.ARRAY -> arrayWrite(descriptor.array, type, expr, at, owner)
                PodbufDescriptor.KindCase.MAP -> mapWrite(descriptor.map, type, expr, at, owner)
                PodbufDescriptor.KindCase.ARMS -> refuse("the podbuf layout of `$owner` holds an arm descriptor outside a union")
                else -> refuse("a podbuf descriptor carries no kind")
            }
        }
        else -> refuse("a podbuf field carries no form")
    }

    /** `throw EncodeError.Contract(Violation(owner, rule))`. */
    private fun encodeRefusal(owner: String, rule: RuleName): CodeBlock =
        CodeBlock.of("throw %T.Contract(%T(%S, %T.%L))\n", PB_ENCODE_ERROR, PB_VIOLATION, owner, PB_RULE, rule.name)

    /** `throw VerifyError.Contract(Violation(owner, rule))`. */
    fun verifyRefusal(owner: String, rule: RuleName): CodeBlock =
        CodeBlock.of("throw %T.Contract(%T(%S, %T.%L))\n", PB_VERIFY_ERROR, PB_VIOLATION, owner, PB_RULE, rule.name)

    /** `throw VerifyError.Structure(Malformed.<variant>)`. */
    fun malformed(variant: String): CodeBlock = CodeBlock.of("throw %T.Structure(%T.%L)\n", PB_VERIFY_ERROR, PB_MALFORMED, variant)

    /**
     * An exact-length array, each element at its stride; or exact-length
     * bytes, whose length is checked before they are copied in, as Rust
     * checks its `Vec<u8>`'s.
     */
    private fun inlineArrayWrite(form: PodbufForm, type: Type, expr: String, at: String, owner: String): CodeBlock {
        val array = form.inlineArray
        if (!array.hasElement()) refuse("an inline array carries no element form")
        if (type.kindCase == Type.KindCase.ARRAY) {
            if (!type.array.hasElement()) refuse("an array carries no element type")
            val i = fresh("i")
            val e = fresh("e")
            val write = writeForm(array.element, type.array.element, e, "$at + ${scaled(i, formSize(array.element))}", owner)
            if (write.isEmpty()) return write
            return CodeBlock.builder().beginControlFlow("%L.forEachIndexed { %L, %L ->", expr, i, e).add(write).endControlFlow().build()
        }
        val domain = domainOf(type)
        val checked = if (domain is Domain.Named) domain.declared else owner
        val bytes = fresh("bytes")
        return CodeBlock.builder()
            .addStatement("val %L = %L", bytes, base(domain, expr))
            .beginControlFlow("if (%L.size != %L)", bytes, array.count)
            .add(encodeRefusal(checked, RuleName.Length))
            .endControlFlow()
            .addStatement("w.putBytes(%L, %L)", at, bytes)
            .build()
    }

    /**
     * A bounded string: the descriptor at [at], the UTF-8 bytes and one NUL in
     * the tail; an empty string is a zero descriptor with no tail. An inline
     * string's length is checked against its bound, a named one's by its
     * constructor; a string holding U+0000 is refused as `Pattern`, because
     * its NUL could not be told from the terminator (ADR-0031 decision 5).
     */
    private fun textWrite(type: Type, expr: String, at: String, owner: String): CodeBlock {
        val domain = sequenceOf(type, text = true)
        val text = fresh("text")
        val utf8 = fresh("utf8")
        val data = fresh("data")
        return CodeBlock.builder()
            .addStatement("val %L = %L", text, base(domain, expr))
            .add(inlineLength(domain, "$text.codePointCount(0, $text.length)", owner))
            .addStatement("val %L = %L.encodeToByteArray()", utf8, text)
            .beginControlFlow("if (%M(%L))", PB_HAS_NUL, utf8)
            .add(encodeRefusal(if (domain is Domain.Named) domain.declared else owner, RuleName.Pattern))
            .endControlFlow()
            .beginControlFlow("if (%L.isNotEmpty())", utf8)
            .addStatement("val %L = w.tailData(%L, true)", data, utf8)
            .addStatement("w.putDesc(%L, %T(%L.toLong(), %L.size.toLong()))", at, PB_DESC, data, utf8)
            .endControlFlow()
            .build()
    }

    /** Bounded bytes: the descriptor at [at] and the bytes in the tail; empty bytes are a zero descriptor. */
    private fun bytesWrite(type: Type, expr: String, at: String, owner: String): CodeBlock {
        val domain = sequenceOf(type, text = false)
        val bytes = fresh("bytes")
        val data = fresh("data")
        return CodeBlock.builder()
            .addStatement("val %L = %L", bytes, base(domain, expr))
            .add(inlineLength(domain, "$bytes.size", owner))
            .beginControlFlow("if (%L.isNotEmpty())", bytes)
            .addStatement("val %L = w.tailData(%L, false)", data, bytes)
            .addStatement("w.putDesc(%L, %T(%L.toLong(), %L.size.toLong()))", at, PB_DESC, data, bytes)
            .endControlFlow()
            .build()
    }

    /** `codec.rs`'s `inline_length_check`: an anonymous string's or bytes' length against its declared bound. */
    private fun inlineLength(domain: Domain, length: String, owner: String): CodeBlock {
        if (domain !is Domain.Inline) return CodeBlock.of("")
        val c = domain.scalar.constraint
        if (!c.hasLenMin() && !c.hasLenMax()) return CodeBlock.of("")
        return countCheck(length, false, if (c.hasLenMin()) c.lenMin else 0, if (c.hasLenMax()) c.lenMax else -1) {
            encodeRefusal(owner, RuleName.Length)
        }
    }

    /**
     * A bounded array: the count is checked against its bound, the block of
     * elements is reserved at the element's alignment and the descriptor
     * written, then each element at its stride, its own tail after the block.
     */
    private fun arrayWrite(array: PodbufArray, type: Type, expr: String, at: String, owner: String): CodeBlock {
        if (!array.hasElement()) refuse("an array carries no element form")
        if (type.kindCase != Type.KindCase.ARRAY) refuse("a podbuf array of `$owner` is written from no array type")
        if (!type.array.hasElement()) refuse("an array carries no element type")
        val items = fresh("items")
        val block = fresh("block")
        val i = fresh("i")
        val e = fresh("e")
        val write = writeForm(array.element, type.array.element, e, "$block + ${scaled(i, array.elemSize.toLong())}", owner)
        val code = CodeBlock.builder().addStatement("val %L = %L", items, expr)
            .add(countCheck("$items.size", false, array.min, array.max) { encodeRefusal(owner, RuleName.Length) })
            .beginControlFlow("if (%L.isNotEmpty())", items)
            .add(block(array.elemSize, formAlign(array.element), items, block, at, owner))
        if (!write.isEmpty()) code.beginControlFlow("%L.forEachIndexed { %L, %L ->", items, i, e).add(write).endControlFlow()
        return code.endControlFlow().build()
    }

    /**
     * A map: the count is checked, a NaN key is refused as `Range`, the
     * entries are sorted by key in the order of ADR-0031 decision 5 and a
     * duplicate key is refused as `Unique`, then the block of entry heads is
     * written at alignment 8, each entry `{ key, value }` at its stride with
     * its own tail after the block.
     */
    private fun mapWrite(map: PodbufMap, type: Type, expr: String, at: String, owner: String): CodeBlock {
        if (type.kindCase != Type.KindCase.MAP) refuse("a podbuf map of `$owner` is written from no map type")
        if (!type.map.hasKey() || !type.map.hasValue()) refuse("a map carries no key or no value type")
        val entry = model.podbuf.headsList.getOrNull(map.entryHead) ?: refuse("the podbuf layout names an entry head it does not carry")
        val entries = fresh("entries")
        val sorted = fresh("sorted")
        val block = fresh("block")
        val i = fresh("i")
        val e = fresh("e")
        val ea = fresh("ea")
        val order = keyOrder(type.map.key, owner)
        val code = CodeBlock.builder().addStatement("val %L = %L", entries, expr)
            .add(countCheck("$entries.size", false, map.min, map.max) { encodeRefusal(owner, RuleName.Length) })
        order.nan?.let { nan ->
            val k = fresh("k")
            code.beginControlFlow("for (%L in %L.keys)", k, entries)
                .beginControlFlow("if (%L)", nan(k)).add(encodeRefusal(owner, RuleName.Range)).endControlFlow()
                .endControlFlow()
        }
        val a = fresh("a")
        val b = fresh("b")
        val j = fresh("j")
        code.addStatement("val %L = %L.entries.sortedWith(Comparator { %L, %L -> %L })", sorted, entries, a, b, order.compare("$a.key", "$b.key"))
            .beginControlFlow("for (%L in 1 until %L.size)", j, sorted)
            .beginControlFlow("if (%L)", order.equal("$sorted[$j - 1].key", "$sorted[$j].key"))
            .add(encodeRefusal(owner, RuleName.Unique))
            .endControlFlow()
            .endControlFlow()
        val writes = CodeBlock.builder()
        for (field in entry.fieldsList) {
            val form = formOf(field.form, owner)
            val (position, positionType) = when (field.source.kindCase) {
                PodbufSource.KindCase.KEY -> "$e.key" to type.map.key
                PodbufSource.KindCase.VALUE -> "$e.value" to type.map.value
                else -> refuse("the podbuf entry head of `$owner` holds a field that is neither the key nor the value")
            }
            writes.add(writeForm(form, positionType, position, position(ea, field.offset.toLong()), owner))
        }
        return code.beginControlFlow("if (%L.isNotEmpty())", sorted)
            .add(block(map.elemSize, 8, sorted, block, at, owner))
            .beginControlFlow("%L.forEachIndexed { %L, %L ->", sorted, i, e)
            .addStatement("val %L = %L + %L", ea, block, scaled(i, map.elemSize.toLong()))
            .add(writes.build())
            .endControlFlow()
            .endControlFlow()
            .build()
    }

    /**
     * Reserves the block of [collection]'s elements at [stride] bytes each,
     * at [align], as [block], and writes the array descriptor at [at]. The
     * count was checked against its bound, and the checker bounds the count
     * and the stride by the descriptor's `u16` words (RIDL-154).
     */
    private fun block(stride: Int, align: Long, collection: String, block: String, at: String, owner: String): CodeBlock {
        if (stride > 0xFFFF) {
            refuse("`$owner` holds an array or map whose element is $stride bytes, past the descriptor's limit of 65535 bytes")
        }
        val bytes = if (stride == 1) "$collection.size.toLong()" else "$collection.size.toLong() * $stride"
        return CodeBlock.builder()
            .addStatement("val %L = w.tail(%L, %L)", block, bytes, align)
            .addStatement("w.putArrayDesc(%L, %T(%L.toLong(), %L.size, %L))", at, PB_ARRAY_DESC, block, collection, stride)
            .build()
    }

    /** How two map keys compare and are one key, and whether a key is NaN, over the key expressions. */
    private class KeyOrder(val compare: (String, String) -> String, val equal: (String, String) -> String, val nan: ((String) -> String)?)

    /**
     * `codec_podbuf.rs`'s `key_order`: a string by its UTF-8 bytes and bytes
     * by their bytes, unsigned (not Kotlin's UTF-16 order); a float by
     * `partial_cmp`, the IEEE order in which `-0.0` and `0.0` are one key
     * (codec_podbuf.rs:1740-1741), once a NaN key is refused; any other key
     * by its `Long` or `Boolean` value.
     */
    private fun keyOrder(key: Type, owner: String): KeyOrder {
        val refused = "a podbuf map key of `$owner` must be a string, bytes, a boolean, an integer, a float, an enum or an enum set"
        val wire = if (key.kindCase == Type.KindCase.PRIMITIVE &&
            (key.primitive == PrimitiveType.PRIMITIVE_TYPE_STRING || key.primitive == PrimitiveType.PRIMITIVE_TYPE_BYTES)
        ) {
            refuse("a bare string or bytes has no podbuf form")
        } else {
            try {
                wires.of(key)
            } catch (_: Refusal) {
                refuse(refused)
            }
        }
        return when (wire) {
            is Wire.Text -> {
                compareBytes = true
                KeyOrder(
                    { a, b -> "$COMPARE_BYTES(${base(wire.domain, a)}.encodeToByteArray(), ${base(wire.domain, b)}.encodeToByteArray())" },
                    { a, b -> "${base(wire.domain, a)} == ${base(wire.domain, b)}" },
                    null,
                )
            }
            is Wire.Bytes -> {
                compareBytes = true
                KeyOrder(
                    { a, b -> "$COMPARE_BYTES(${base(wire.domain, a)}, ${base(wire.domain, b)})" },
                    { a, b -> "${base(wire.domain, a)}.contentEquals(${base(wire.domain, b)})" },
                    null,
                )
            }
            is Wire.ScalarWire -> if (wire.prim == Prim.F32 || wire.prim == Prim.F64) {
                // NaN is refused first, so the IEEE order is total here.
                KeyOrder(
                    { a, b ->
                        val x = fresh("x")
                        val y = fresh("y")
                        "${base(wire.domain, a)}.let { $x -> ${base(wire.domain, b)}.let { $y -> if ($x < $y) -1 else if ($x > $y) 1 else 0 } }"
                    },
                    { a, b -> "${base(wire.domain, a)} == ${base(wire.domain, b)}" },
                    { k -> "${base(wire.domain, k)}.isNaN()" },
                )
            } else {
                KeyOrder(
                    { a, b -> "${base(wire.domain, a)}.compareTo(${base(wire.domain, b)})" },
                    { a, b -> "${base(wire.domain, a)} == ${base(wire.domain, b)}" },
                    null,
                )
            }
            else -> refuse(refused)
        }
    }

    /**
     * The values an open enum's field of [form]'s width holds, or `null` when
     * the write needs no check: a strict enum, whose values fit by
     * construction; a 64-bit field, which holds every `Long` (a `u64` as its
     * bit pattern); an enum set, whose `wireBits` checks its own.
     */
    private fun openRange(domain: Domain, form: PodbufForm): LongRange? {
        if (domain !is Domain.EnumOf || !domain.open || form.kindCase != PodbufForm.KindCase.ENUM) return null
        return when (form.enum.width) {
            IntWidth.INT_WIDTH_U8 -> 0L..0xFFL
            IntWidth.INT_WIDTH_I8 -> Byte.MIN_VALUE.toLong()..Byte.MAX_VALUE.toLong()
            IntWidth.INT_WIDTH_U16 -> 0L..0xFFFFL
            IntWidth.INT_WIDTH_I16 -> Short.MIN_VALUE.toLong()..Short.MAX_VALUE.toLong()
            IntWidth.INT_WIDTH_U32 -> 0L..0xFFFF_FFFFL
            IntWidth.INT_WIDTH_I32 -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            else -> null
        }
    }

    // -- verifying ----------------------------------------------------------------------

    /**
     * The checks of a value of [form] at offset [off] of [place], in the Rust
     * codec's order (ADR-0031 decision 8): a boolean above 1 is `Range`; a
     * strict enum's undeclared value or enum set's undeclared bit is
     * `Variant`, an open one is unchecked; a typl constraint is checked as
     * the value object checks it; a nested head by its own helper; a
     * descriptor's bounds, stride, count, terminator and key order, then its
     * elements, entries or bytes with the same rules.
     */
    fun verifyForm(form: PodbufForm, type: Type, place: PodbufPlace, off: String, owner: String): CodeBlock = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN -> CodeBlock.builder()
            .beginControlFlow("if (%L.u8At(%L) > 1)", place.region, off)
            .add(verifyRefusal(owner, RuleName.Range))
            .endControlFlow().build()
        PodbufForm.KindCase.INT, PodbufForm.KindCase.FLOAT, PodbufForm.KindCase.ENUM, PodbufForm.KindCase.ENUM_SET -> {
            val raw = fresh("raw")
            val check = checkDomain(domainOf(type), raw, owner, ::pattern)
            if (check.isEmpty()) check else CodeBlock.builder().addStatement("val %L = %L", raw, load(form, place, off)).add(check).build()
        }
        PodbufForm.KindCase.INLINE_HEAD -> if (headSize(form.inlineHead) == 0) {
            CodeBlock.of("")
        } else {
            val (at, size) = place.inline(off)
            CodeBlock.of("%M(buffer, %L, %L, tail)\n", headFunctions(type).let { MemberName(it.pkg, it.verify) }, at, size)
        }
        PodbufForm.KindCase.INLINE_ARRAY -> {
            val array = form.inlineArray
            // Exact-length bytes have nothing to check: their length is the type's.
            if (type.kindCase != Type.KindCase.ARRAY) {
                CodeBlock.of("")
            } else {
                val i = fresh("i")
                val o = fresh("o")
                val inner = verifyForm(array.element, type.array.element, place, o, owner)
                if (inner.isEmpty()) {
                    inner
                } else {
                    CodeBlock.builder().beginControlFlow("for (%L in 0 until %L)", i, array.count)
                        .addStatement("val %L = %L", o, indexed(off, i, formSize(array.element)))
                        .add(inner)
                        .endControlFlow().build()
                }
            }
        }
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> {
            val descriptor = form.tailDescriptor
            when (descriptor.kindCase) {
                PodbufDescriptor.KindCase.TEXT -> textVerify(descriptor.text.max, type, place, off, owner)
                PodbufDescriptor.KindCase.BYTES -> bytesVerify(descriptor.bytes.min, descriptor.bytes.max, type, place, off, owner)
                PodbufDescriptor.KindCase.ARRAY -> {
                    val array = descriptor.array
                    if (!array.hasElement()) refuse("an array carries no element form")
                    if (type.kindCase != Type.KindCase.ARRAY || !type.array.hasElement()) refuse("a podbuf array is checked from no array type")
                    val stride = if (array.element.kindCase == PodbufForm.KindCase.INLINE_HEAD) {
                        { d: String -> "$d.elemSize % 8 != 0" }
                    } else {
                        { d: String -> "$d.elemSize != ${formSize(array.element)}" }
                    }
                    blockVerify(place, off, stride, formAlign(array.element), array.min, array.max, owner) { element, _ ->
                        verifyForm(array.element, type.array.element, element, "0", owner)
                    }
                }
                PodbufDescriptor.KindCase.MAP -> {
                    val map = descriptor.map
                    if (type.kindCase != Type.KindCase.MAP || !type.map.hasKey() || !type.map.hasValue()) {
                        refuse("a podbuf map is checked from no map type")
                    }
                    val (key, value) = entryFields(map, owner)
                    blockVerify(place, off, { d -> "$d.elemSize % 8 != 0" }, 8, map.min, map.max, owner) { entry, i ->
                        val keyForm = formOf(key.form, owner)
                        CodeBlock.builder()
                            .add(verifyForm(keyForm, type.map.key, entry, key.offset.toString(), owner))
                            .add(verifyForm(formOf(value.form, owner), type.map.value, entry, value.offset.toString(), owner))
                            .add(keyOrderCheck(keyForm, entry, i, key.offset.toString(), owner))
                            .build()
                    }
                }
                PodbufDescriptor.KindCase.ARMS -> refuse("the podbuf layout of `$owner` holds an arm descriptor outside a union")
                else -> refuse("a podbuf descriptor carries no kind")
            }
        }
        else -> refuse("a podbuf field carries no form")
    }

    /** The key and value fields of [map]'s entry head. */
    private fun entryFields(map: PodbufMap, owner: String) = model.podbuf.headsList.getOrNull(map.entryHead)?.let { entry ->
        val key = entry.fieldsList.firstOrNull { it.source.kindCase == PodbufSource.KindCase.KEY }
        val value = entry.fieldsList.firstOrNull { it.source.kindCase == PodbufSource.KindCase.VALUE }
        if (key == null || value == null) refuse("the podbuf entry head of `$owner` lacks its key or its value")
        key to value
    } ?: refuse("the podbuf layout names an entry head it does not carry")

    /**
     * A string descriptor (ADR-0031 decision 8 rules 4, 6 and 3): a `len` of
     * 0 is a zero descriptor with no data; otherwise `len` is at most four
     * bytes per scalar value of the bound, checked before the bytes are read,
     * and the data and its NUL lie in the tail; then the bytes (terminator,
     * no inner NUL, UTF-8), then the typl constraints, the scalar count
     * among them.
     */
    private fun textVerify(max: Long, type: Type, place: PodbufPlace, off: String, owner: String): CodeBlock {
        val domain = sequenceOf(type, text = true)
        val d = fresh("desc")
        val text = fresh("text")
        val max4 = if (max < 0 || max > Long.MAX_VALUE / 4) Long.MAX_VALUE else max * 4
        val check = checkDomain(domain, text, owner, ::pattern)
        val bytes = CodeBlock.builder()
            .beginControlFlow("if (%L.len > %LL)", d, max4).add(malformed("OutOfBounds")).endControlFlow()
            .addStatement("%M(%L.off, %L.len + 1, buffer.size, tail, 1)", PB_TAIL_RANGE, d, d)
            .addStatement("%M(%L)", PB_TEXT, bytesAt("$d.off", "$d.len + 1"))
            .build()
        val code = CodeBlock.builder().addStatement("val %L = %T.read(%L, %L)", d, PB_DESC, place.region, off)
        if (check.isEmpty()) {
            return code.beginControlFlow("if (%L.len == 0L)", d)
                .beginControlFlow("if (%L.off != 0L)", d).add(malformed("OutOfBounds")).endControlFlow()
                .nextControlFlow("else")
                .add(bytes)
                .endControlFlow()
                .build()
        }
        return code.beginControlFlow("val %L = if (%L.len == 0L)", text, d)
            .beginControlFlow("if (%L.off != 0L)", d).add(malformed("OutOfBounds")).endControlFlow()
            .addStatement("%S", "")
            .nextControlFlow("else")
            .add(bytes)
            .endControlFlow()
            .add(check)
            .build()
    }

    /**
     * A bytes descriptor (rule 4): a `len` of 0 is a zero descriptor;
     * otherwise the data lies in the tail. Then a named type's own checks, or
     * an inline one's count against the descriptor's bound.
     */
    private fun bytesVerify(min: Long, max: Long, type: Type, place: PodbufPlace, off: String, owner: String): CodeBlock {
        val domain = sequenceOf(type, text = false)
        val d = fresh("desc")
        val code = CodeBlock.builder().addStatement("val %L = %T.read(%L, %L)", d, PB_DESC, place.region, off)
            .beginControlFlow("if (%L.len == 0L)", d)
            .beginControlFlow("if (%L.off != 0L)", d).add(malformed("OutOfBounds")).endControlFlow()
            .nextControlFlow("else")
            .addStatement("%M(%L.off, %L.len, buffer.size, tail, 1)", PB_TAIL_RANGE, d, d)
            .endControlFlow()
        if (domain is Domain.Named) {
            if (!domain.scalar.plain) code.add(checkDomain(domain, bytesAt("$d.off", "$d.len"), owner, ::pattern))
        } else {
            code.add(countCheck("$d.len", true, min, max) { verifyRefusal(owner, RuleName.Length) })
        }
        return code.build()
    }

    /**
     * The block an array or map descriptor at [off] names (rules 4, 5 and
     * 9): a `len` of 0 is a zero descriptor; otherwise the stride is checked,
     * the block lies in the tail at [align], the count is within its bound,
     * and only then [inner] runs over each element at its stride.
     */
    private fun blockVerify(
        place: PodbufPlace,
        off: String,
        badStride: (String) -> String,
        align: Long,
        min: Long,
        max: Long,
        owner: String,
        inner: (PodbufPlace, String) -> CodeBlock,
    ): CodeBlock {
        val d = fresh("desc")
        val i = fresh("i")
        val ez = fresh("ez")
        val es = fresh("es")
        val element = PodbufPlace(es, ez, fresh("element"))
        val body = inner(element, i)
        val code = CodeBlock.builder().addStatement("val %L = %T.read(%L, %L)", d, PB_ARRAY_DESC, place.region, off)
            .beginControlFlow("if (%L.len == 0)", d)
            .beginControlFlow("if (%L.off != 0L || %L.elemSize != 0)", d, d).add(malformed("OutOfBounds")).endControlFlow()
            .nextControlFlow("else")
            .beginControlFlow("if (%L)", badStride(d)).add(malformed("Stride")).endControlFlow()
            .addStatement("%M(%L.off, %L.len.toLong() * %L.elemSize, buffer.size, tail, %L)", PB_TAIL_RANGE, d, d, d, align)
            .endControlFlow()
            .add(countCheck("$d.len", false, min, max) { verifyRefusal(owner, RuleName.Length) })
        if (!body.isEmpty()) {
            code.beginControlFlow("for (%L in 0 until %L.len)", i, d)
                .add(elementBindings(d, i, es, ez))
                .add(element.binding())
                .add(body)
                .endControlFlow()
        }
        return code.build()
    }

    /** Element [i] of the block descriptor [d] names: [es] its offset, [ez] its producer's size. */
    private fun elementBindings(d: String, i: String, es: String, ez: String): CodeBlock = CodeBlock.builder()
        .addStatement("val %L = %L.elemSize", ez, d)
        .addStatement("val %L = (%L.off + %L.toLong() * %L).toInt()", es, d, i, ez)
        .build()

    /**
     * Rule 8 and decision 5, over entry [i] at [entry]: a NaN float key is
     * `Range`, and each key is strictly above the previous entry's, bytewise
     * for a string or bytes and numeric at its width otherwise, else
     * `KeyOrder`.
     */
    private fun keyOrderCheck(form: PodbufForm, entry: PodbufPlace, i: String, off: String, owner: String): CodeBlock {
        val r = entry.region
        val q = fresh("previous")
        val code = CodeBlock.builder()
        val refused = "a podbuf map key of `$owner` must be a string, bytes, a boolean, an integer, a float, an enum or an enum set"
        val outOfOrder: CodeBlock = when (form.kindCase) {
            PodbufForm.KindCase.BOOLEAN -> CodeBlock.of("%L.u8At(%L) >= %L.u8At(%L)", q, off, r, off)
            PodbufForm.KindCase.INT, PodbufForm.KindCase.ENUM, PodbufForm.KindCase.ENUM_SET -> {
                val read = "${scalarPrim(form).read}At"
                if (scalarPrim(form) == Prim.U64) {
                    // A u64 is unsigned on the wire, and Rust compares it as one.
                    CodeBlock.of("(%L.%L(%L) xor Long.MIN_VALUE) >= (%L.%L(%L) xor Long.MIN_VALUE)", q, read, off, r, read, off)
                } else {
                    CodeBlock.of("%L.%L(%L) >= %L.%L(%L)", q, read, off, r, read, off)
                }
            }
            PodbufForm.KindCase.FLOAT -> {
                val read = "${scalarPrim(form).read}At"
                code.beginControlFlow("if (%L.%L(%L).isNaN())", r, read, off).add(verifyRefusal(owner, RuleName.Range)).endControlFlow()
                // codec_podbuf.rs:3350: anything but `Less` by `partial_cmp`, so an equal pair, -0.0 and 0.0 among them.
                CodeBlock.of("!(%L.%L(%L) < %L.%L(%L))", q, read, off, r, read, off)
            }
            PodbufForm.KindCase.TAIL_DESCRIPTOR -> when (form.tailDescriptor.kindCase) {
                PodbufDescriptor.KindCase.TEXT, PodbufDescriptor.KindCase.BYTES -> {
                    // Both keys were checked, so their bytes lie in the buffer.
                    compareBytes = true
                    val ka = fresh("key")
                    val kb = fresh("key")
                    CodeBlock.of(
                        "%L(%T.read(%L, %L).let { %L -> %L }, %T.read(%L, %L).let { %L -> %L }) >= 0",
                        COMPARE_BYTES, PB_DESC, q, off, ka, bytesAt("$ka.off", "$ka.len"), PB_DESC, r, off, kb, bytesAt("$kb.off", "$kb.len"),
                    )
                }
                else -> refuse(refused)
            }
            else -> refuse(refused)
        }
        return code.beginControlFlow("if (%L > 0)", i)
            .addStatement("val %L = buffer.region(maxOf(%L - %L, 0), %L)", q, entry.at, entry.size, entry.size)
            .beginControlFlow("if (%L)", outOfOrder)
            .add(malformed("KeyOrder"))
            .endControlFlow()
            .endControlFlow()
            .build()
    }

    /** Registers an inline string scalar's pattern and names its compiled `Regex`. */
    private fun pattern(scalar: Scalar): String {
        patterns += javaPattern(scalar.constraint.pattern)
        return "PODBUF_PATTERN_${patterns.size - 1}"
    }

    /** The load of the scalar [form] at [off] of [place], widened to the domain's `Long`, `Double` or `Boolean`. */
    private fun load(form: PodbufForm, place: PodbufPlace, off: String): String {
        val prim = scalarPrim(form)
        if (prim == Prim.Bool) return "(${place.region}.u8At($off) != 0)"
        return prim.widen("${place.region}.${prim.read}At($off)")
    }

    // -- decoding -------------------------------------------------------------------------

    /** The value of [form] at offset [off] of [place], which `verify` passed. */
    fun decodeForm(form: PodbufForm, type: Type, place: PodbufPlace, off: String): CodeBlock = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN, PodbufForm.KindCase.INT, PodbufForm.KindCase.FLOAT,
        PodbufForm.KindCase.ENUM, PodbufForm.KindCase.ENUM_SET,
        -> construct(domainOf(type), CodeBlock.of("%L", load(form, place, off)))
        PodbufForm.KindCase.INLINE_HEAD -> {
            val (at, size) = place.inline(off)
            CodeBlock.of("%M(buffer, %L, %L)", headFunctions(type).let { MemberName(it.pkg, it.decode) }, at, size)
        }
        PodbufForm.KindCase.INLINE_ARRAY -> {
            val array = form.inlineArray
            if (type.kindCase == Type.KindCase.ARRAY) {
                val i = fresh("i")
                val o = fresh("o")
                CodeBlock.of(
                    "%M(%L) { %L ->\nval %L = %L\n%L\n}",
                    LIST_OF_SIZE, array.count, i, o, indexed(off, i, formSize(array.element)),
                    decodeForm(array.element, type.array.element, place, o),
                )
            } else {
                construct(domainOf(type), CodeBlock.of("%L.bytesAt(%L, %L)", place.region, off, array.count))
            }
        }
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> {
            val descriptor = form.tailDescriptor
            when (descriptor.kindCase) {
                PodbufDescriptor.KindCase.TEXT, PodbufDescriptor.KindCase.BYTES -> {
                    val text = descriptor.kindCase == PodbufDescriptor.KindCase.TEXT
                    val d = fresh("desc")
                    val bytes = bytesAt("$d.off", "$d.len") + if (text) ".decodeToString()" else ""
                    construct(
                        sequenceOf(type, text),
                        CodeBlock.of("%T.read(%L, %L).let { %L -> %L }", PB_DESC, place.region, off, d, bytes),
                    )
                }
                PodbufDescriptor.KindCase.ARRAY -> {
                    if (type.kindCase != Type.KindCase.ARRAY || !type.array.hasElement()) refuse("a podbuf array is decoded into no array type")
                    blockDecode(place, off, "%M(%L.len)", LIST_OF_SIZE) { element ->
                        decodeForm(descriptor.array.element, type.array.element, element, "0")
                    }
                }
                PodbufDescriptor.KindCase.MAP -> {
                    if (type.kindCase != Type.KindCase.MAP || !type.map.hasKey() || !type.map.hasValue()) {
                        refuse("a podbuf map is decoded into no map type")
                    }
                    val (key, value) = entryFields(descriptor.map, "a map")
                    blockDecode(place, off, "(0 until %L.len).associate", null) { entry ->
                        CodeBlock.of(
                            "%L to %L",
                            decodeForm(formOf(key.form, "a map"), type.map.key, entry, key.offset.toString()),
                            decodeForm(formOf(value.form, "a map"), type.map.value, entry, value.offset.toString()),
                        )
                    }
                }
                PodbufDescriptor.KindCase.ARMS -> refuse("an arm descriptor is decoded through the union")
                else -> refuse("a podbuf descriptor carries no kind")
            }
        }
        else -> refuse("a podbuf field carries no form")
    }

    /**
     * The elements of the block the array descriptor at [off] of [place]
     * names, each read by [element] at its stride: `List(len) { .. }` for an
     * array, `(0 until len).associate { .. }` for a map, which keeps the
     * entries' order.
     */
    private fun blockDecode(
        place: PodbufPlace,
        off: String,
        collect: String,
        member: MemberName?,
        element: (PodbufPlace) -> CodeBlock,
    ): CodeBlock {
        val d = fresh("desc")
        val i = fresh("i")
        val ez = fresh("ez")
        val es = fresh("es")
        val at = PodbufPlace(es, ez, fresh("element"))
        val read = element(at)
        val head = if (member != null) CodeBlock.of(collect, member, d) else CodeBlock.of(collect, d)
        return CodeBlock.of(
            "%T.read(%L, %L).let { %L ->\n%L { %L ->\n%L%L%L\n}\n}",
            PB_ARRAY_DESC, place.region, off, d, head, i, elementBindings(d, i, es, ez), at.binding(), read,
        )
    }
}

/** [base], a `Long` or `Double`, as the argument of `Writer.put<prim>`: Rust's `as`, which truncates. */
private fun narrowed(prim: Prim, base: String): String = when (prim) {
    Prim.U8, Prim.U16, Prim.I32 -> "$base.toInt()"
    Prim.I8 -> "$base.toByte()"
    Prim.I16 -> "$base.toShort()"
    Prim.F32 -> "$base.toFloat()"
    Prim.Bool, Prim.U32, Prim.U64, Prim.I64, Prim.F64 -> base
}

/**
 * The condition under which a count [len] breaks `min..max`, as `codec.rs`'s
 * `count_condition` states it, and [refusal] under it; nothing when it bounds
 * nothing. [long] says [len] is a `Long`; a negative [max] (a `u64` past
 * `Long.MAX_VALUE`) or one past what [len] can hold bounds nothing above.
 */
internal fun countCheck(len: String, long: Boolean, min: Long, max: Long, refusal: () -> CodeBlock): CodeBlock {
    val cap = if (long) Long.MAX_VALUE else Int.MAX_VALUE.toLong()
    val high = max.takeIf { it in 0..cap }
    fun literal(v: Long) = if (long) "${v}L" else "$v"
    val condition = when {
        high != null && min == max -> "$len != ${literal(high)}"
        else -> listOfNotNull(
            when {
                min <= 0 -> null
                min > cap -> "true"
                else -> "$len < ${literal(min)}"
            },
            high?.let { "$len > ${literal(it)}" },
        ).joinToString(" || ")
    }
    if (condition.isEmpty()) return CodeBlock.of("")
    return CodeBlock.builder().beginControlFlow("if (%L)", condition).add(refusal()).endControlFlow().build()
}

/** `base + off`, or `base` alone at offset 0. */
internal fun position(base: String, off: Long): String = if (off == 0L) base else "$base + $off"

/** `i * stride`, or `i` alone for a stride of 1. */
private fun scaled(i: String, stride: Long): String = if (stride == 1L) i else "$i * $stride"

/** The offset of element [i] of an inline array at [off]: `off + i * stride`. */
private fun indexed(off: String, i: String, stride: Long): String = if (off == "0") scaled(i, stride) else "$off + ${scaled(i, stride)}"
