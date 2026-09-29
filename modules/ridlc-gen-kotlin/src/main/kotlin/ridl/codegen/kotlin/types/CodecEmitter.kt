package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import ridl.codegen.kotlin.Options
import ridl.codegen.kotlin.PLUGIN
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.FbUnboundedCause
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.Visibility

private const val FB = "ridl.rt.flatbuffers"
private val BUILDER = ClassName(FB, "Builder")
private val READER = ClassName(FB, "Reader")
private val POS = ClassName(FB, "Pos")
private val TABLE_VIEW = ClassName(FB, "TableView")
private val TABLE_FIELD = ClassName(FB, "TableField")
private val FIELD = ClassName(FB, "Field")
private val PAYLOAD = ClassName("ridl.rt.payload", "Payload")
private val VERIFY_ERROR = ClassName("ridl.rt.payload", "VerifyError")
private val VIOLATION = ClassName("ridl.rt.payload", "Violation")
private val MALFORMED = ClassName("ridl.rt.payload", "Malformed")
private val RULE = ClassName("ridl.rt.payload", "Rule")
private val ENCODING = ClassName("ridl.rt.encoding", "Encoding")
private val BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
private val BYTE_ORDER = ClassName("java.nio", "ByteOrder")
private val REGEX = ClassName("kotlin.text", "Regex")

/** The FlatBuffers alignment of every finished buffer, as the Rust codec's `BUFFER_ALIGN`. */
private const val BUFFER_ALIGN = 8

/**
 * `Codec.kt`: one FlatBuffers codec per root of the package's projection
 * (docs/design.md §4, O-K1 option A), over `ridl.rt.flatbuffers`, and the
 * encode, verify and decode helpers of every table-shaped type a root
 * reaches. It is the Rust codec emitter of the pinned release spelled in
 * Kotlin — the same layout, the same bytes, the same verify order and the
 * same verdicts — with one difference: `verify` also checks what the value
 * objects check and the Rust verifier does not (a float's `step`, a NaN, an
 * inline scalar's constraints), because `decode` builds value objects and
 * must never throw.
 */
class CodecEmitter(private val model: Model, private val options: Options) {
    private val pkg = options.kotlinPackage
    private val file = FileSpec.builder(pkg, "Codec")
    private val wires = Wires(model, pkg)
    private val errors = mutableListOf<String>()
    private val withheld = mutableListOf<String>()
    private var names = 0
    private val patterns = mutableListOf<String>()

    fun emit(): EmittedTypes {
        val path = pkg.replace('.', '/') + "/Codec.kt"
        val roots = model.flatbuffers.rootsList
        val codecs = mutableListOf<Declaration>()
        for (root in roots) {
            val declaration = model.getDeclarations(root.declaration)
            val name = declaration.name.declared
            if (root.hasMaxSize()) {
                codecs += declaration
                continue
            }
            val unbounded = root.unbounded
            when (unbounded.cause) {
                FbUnboundedCause.FB_UNBOUNDED_CAUSE_EXEMPT -> withheld += name
                FbUnboundedCause.FB_UNBOUNDED_CAUSE_MEMBER ->
                    errors += "$PLUGIN: `${model.name.dotted}.$name.${unbounded.member}` has no finite FlatBuffers bound"
                FbUnboundedCause.FB_UNBOUNDED_CAUSE_UNTYPED ->
                    errors += "$PLUGIN: `${model.name.dotted}.$name.${unbounded.member}` carries no type, so " +
                        "`${model.name.dotted}.$name` has no FlatBuffers bound"
                FbUnboundedCause.FB_UNBOUNDED_CAUSE_LAYOUT ->
                    errors += "$PLUGIN: `${model.name.dotted}.$name` has no FlatBuffers table layout: ${unbounded.layoutMessage}"
                else -> if (root.hasUnbounded()) {
                    errors += "$PLUGIN: every member of `${model.name.dotted}.$name` is bounded on its own " +
                        "and its FlatBuffers total is not"
                } else {
                    withheld += name
                }
            }
        }
        if (errors.isNotEmpty()) return EmittedTypes(path, null, errors)
        for ((declaration, root) in codecs.zip(roots.filter { it.hasMaxSize() })) {
            guarded(declaration.name.declared) { root(declaration, root.maxSize) }
        }
        for (index in reachableTuples(codecs)) {
            val tuple = model.getTuples(index)
            guarded(tuple.name.rust) { record(tuple.name.rust, tuple.name.rust, tuple.fieldsList.mapIndexed { i, f -> i to f }, tuple.fieldsCount) }
        }
        if (errors.isNotEmpty()) return EmittedTypes(path, null, errors)
        for ((index, body) in patterns.withIndex()) {
            file.addProperty(
                PropertySpec.builder("PATTERN_$index", REGEX, KModifier.PRIVATE).initializer("%T(%S)", REGEX, body).build(),
            )
        }
        file.addFileComment("Generated by %L from `%L`. Do not edit.", PLUGIN, model.name.dotted)
        if (withheld.isNotEmpty()) {
            file.addFileComment(
                "\n\nNo codec for %L: each reaches a type in another package the projection cannot judge.",
                withheld.joinToString { "`$it`" },
            )
        }
        return EmittedTypes(path, file.build().toString(), emptyList())
    }

    private fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (refusal: Refusal) {
            errors += "$PLUGIN: `${model.name.dotted}.$name` has no FlatBuffers codec: ${refusal.message}"
        }
    }

    private fun fresh(stem: String): String = "$stem${names++}"

    // -- the roots ------------------------------------------------------------

    private fun root(declaration: Declaration, maxSize: Int) {
        val name = declaration.name.camel
        val type = ClassName(pkg, name)
        val functions = Functions(pkg, name)
        when (declaration.kindCase) {
            Declaration.KindCase.STRUCT -> {
                val slots = model.flatbuffers.tablesList.firstOrNull {
                    it.hasStructDeclaration() && model.getDeclarations(it.structDeclaration) == declaration
                }?.vtableSlots ?: refuse("the projection has no table for it")
                val fields = declaration.struct.slotsList.filter { it.hasField() }.map { (it.ordinal - 1) to it.field }
                record(name, declaration.name.declared, fields, slots)
            }
            Declaration.KindCase.UNION -> union(declaration)
            else -> box(declaration)
        }
        val codec = TypeSpec.objectBuilder("${name}Codec")
            .addKdoc("The FlatBuffers codec of [%T].", type)
            .addSuperinterface(PAYLOAD.parameterizedBy(type, TABLE_VIEW))
            .addProperty(
                PropertySpec.builder("encoding", ENCODING, KModifier.OVERRIDE).initializer("%T.FlatBuffers", ENCODING).build(),
            )
            .addProperty(PropertySpec.builder("maxSize", INT, KModifier.OVERRIDE).initializer("%L", maxSize).build())
            .addFunction(
                FunSpec.builder("encode").addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", type).addParameter("out", BYTE_BUFFER).returns(INT)
                    .addStatement("val builder = %T(out)", BUILDER)
                    .addStatement("return builder.finish(%L(value, builder), %L)", functions.encode, BUFFER_ALIGN)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("verify").addModifiers(KModifier.OVERRIDE)
                    .addParameter("buf", BYTE_BUFFER).returns(TABLE_VIEW)
                    .beginControlFlow("if (buf.remaining() > maxSize)")
                    .addStatement("throw %T.Structure(%T.TooLarge)", VERIFY_ERROR, MALFORMED)
                    .endControlFlow()
                    .addStatement("val reader = %T(buf)", READER)
                    .addStatement("val table = reader.root()")
                    .addStatement("%L(reader, table)", functions.verify)
                    .addStatement("return %T(reader, table)", TABLE_VIEW)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("decode").addModifiers(KModifier.OVERRIDE)
                    .addParameter("view", TABLE_VIEW).returns(type)
                    .addStatement("return %L(view.reader, view.table)", functions.decode)
                    .build(),
            )
        if (declaration.visibility == Visibility.VISIBILITY_INTERNAL) codec.addModifiers(KModifier.INTERNAL)
        file.addType(codec.build())
    }

    /** The three helpers of a table-shaped type, internal to the generated module. */
    private fun helpers(functions: Functions, type: ClassName, encode: CodeBlock, verify: CodeBlock, decode: CodeBlock) {
        file.addFunction(
            FunSpec.builder(functions.encode).addModifiers(KModifier.INTERNAL)
                .addParameter("value", type).addParameter("builder", BUILDER).returns(POS)
                .addCode(encode).build(),
        )
        file.addFunction(
            FunSpec.builder(functions.verify).addModifiers(KModifier.INTERNAL)
                .addParameter("reader", READER).addParameter("table", INT)
                .addCode(verify).build(),
        )
        file.addFunction(
            FunSpec.builder(functions.decode).addModifiers(KModifier.INTERNAL)
                .addParameter("reader", READER).addParameter("table", INT).returns(type)
                .addCode(decode).build(),
        )
    }

    /**
     * A struct's or a tuple's table: one slot per live field at its id,
     * placed by [Layout.place]; a required field always written, an optional
     * one only when present; verified slot by slot in declaration order.
     */
    private fun record(name: String, owner: String, fields: List<Pair<Int, Field>>, slots: Int) {
        val type = ClassName(pkg, name)
        val functions = Functions(pkg, name)
        if (slots > 0xFFFF) refuse("its FlatBuffers vtable has $slots slots, over a u16")
        val resolved = fields.map { (id, field) ->
            if (!field.hasType()) refuse("the field `${field.name.declared}` carries no type")
            if (id > 0xFFFF) refuse("the field `${field.name.declared}` has a slot over a u16")
            Slot(id, field, wires.of(field.type), field.type.optional)
        }
        val layout = Layout.place(resolved.map { it.wire.width })
        val encode = CodeBlock.builder()
        if (resolved.isEmpty()) {
            encode.addStatement("return builder.pushTable(4, 4, %L, emptyList())", slots)
        } else {
            encode.addStatement("val fields = ArrayList<%T>(%L)", TABLE_FIELD, resolved.size)
            for ((slot, offset) in resolved.zip(layout.offsets)) {
                val property = "value.${slot.property}"
                if (slot.optional) {
                    val present = fresh("present")
                    encode.beginControlFlow("%L?.let { %L ->", property, present)
                        .addStatement("fields += %T(%L, %L, %L)", TABLE_FIELD, slot.id, offset, field(slot.wire, present, owner))
                        .endControlFlow()
                } else {
                    encode.addStatement("fields += %T(%L, %L, %L)", TABLE_FIELD, slot.id, offset, field(slot.wire, property, owner))
                }
            }
            encode.addStatement("return builder.pushTable(%L, %L, %L, fields)", layout.size, layout.align, slots)
        }
        val verify = CodeBlock.builder()
        for (slot in resolved) {
            val at = fresh("at")
            verify.addStatement("val %L = reader.field(table, %L, %L)", at, slot.id, slot.wire.width)
            if (slot.optional) {
                verify.beginControlFlow("if (%L != null)", at).add(verifyAt(slot.wire, at, owner)).endControlFlow()
            } else {
                verify.beginControlFlow("if (%L == null)", at)
                    .addStatement("throw %T.Structure(%T.MissingRequired)", VERIFY_ERROR, MALFORMED)
                    .endControlFlow()
                    .add(verifyAt(slot.wire, at, owner))
            }
        }
        val arguments = resolved.map { slot ->
            val at = fresh("at")
            if (slot.optional) {
                CodeBlock.of("%L = reader.field(table, %L, %L)?.let { %L -> %L }", slot.property, slot.id, slot.wire.width, at, decodeAt(slot.wire, at))
            } else {
                CodeBlock.of("%L = (reader.field(table, %L, %L) ?: 0).let { %L -> %L }", slot.property, slot.id, slot.wire.width, at, decodeAt(slot.wire, at))
            }
        }
        val decode = CodeBlock.builder().add("return %T(\n", type).indent()
        arguments.forEach { decode.add("%L,\n", it) }
        decode.unindent().add(")\n")
        helpers(functions, type, encode.build(), verify.build(), decode.build())
    }

    private class Slot(val id: Int, field: Field, val wire: Wire, val optional: Boolean) {
        val property = field.name.camel.replaceFirstChar(Char::lowercaseChar)
    }

    /**
     * A union's wrapper table: the arm's ordinal at slot 0 as a `u8`, the arm
     * at slot 1 as an offset — to its own table for a struct or union arm, to
     * a one-slot box otherwise. A missing slot or an undeclared ordinal is
     * `Malformed.Union`.
     */
    private fun union(declaration: Declaration) {
        val union = declaration.union
        if (union.armsCount == 0) refuse("a union with no arm has no FlatBuffers codec")
        val name = declaration.name.camel
        val type = ClassName(pkg, name)
        val owner = declaration.name.declared
        val arms = union.armsList.map { arm ->
            if (!arm.hasType()) refuse("the arm `${arm.name.declared}` carries no type")
            if (arm.ordinal > 255) refuse("the arm `${arm.name.declared}` has ordinal ${arm.ordinal}, over a u8")
            Triple(arm, type.nestedClass(arm.name.camel), wires.named(arm.type))
        }
        val encode = CodeBlock.builder()
        val tag = fresh("tag")
        val at = fresh("pos")
        encode.add("val (%L, %L) = when (value) {\n", tag, at).indent()
        for ((arm, armClass, wire) in arms) {
            encode.add("is %T -> %L to %L\n", armClass, arm.ordinal, armPos(wire, "value.value", owner))
        }
        encode.unindent().add("}\n")
        encode.addStatement(
            "return builder.pushTable(12, 4, 2, listOf(%T(0, 4, %T.U8(%L)), %T(1, 8, %T.Offset(%L))))",
            TABLE_FIELD, FIELD, tag, TABLE_FIELD, FIELD, at,
        )
        val verify = CodeBlock.builder()
            .addStatement("val discriminant = reader.field(table, 0, 1)")
            .addStatement("val value = reader.field(table, 1, 4)")
            .beginControlFlow("if (discriminant == null || value == null)")
            .addStatement("throw %T.Structure(%T.Union)", VERIFY_ERROR, MALFORMED)
            .endControlFlow()
            .beginControlFlow("when (reader.u8(discriminant))")
        for ((arm, _, wire) in arms) {
            verify.beginControlFlow("%L ->", arm.ordinal).add(verifyArm(wire, "value", owner)).endControlFlow()
        }
        verify.addStatement("else -> throw %T.Structure(%T.Union)", VERIFY_ERROR, MALFORMED).endControlFlow()
        val decode = CodeBlock.builder()
            .addStatement("val discriminant = reader.field(table, 0, 1)?.let { reader.u8(it) } ?: 0")
            .addStatement("val value = reader.field(table, 1, 4) ?: 0")
            .add("return when (discriminant) {\n").indent()
        for ((arm, armClass, wire) in arms) {
            decode.add("%L -> %T(%L)\n", arm.ordinal, armClass, decodeArm(wire, "value"))
        }
        val (_, firstClass, firstWire) = arms.first()
        decode.add("else -> %T(%L)\n", firstClass, decodeArm(firstWire, "value")).unindent().add("}\n")
        helpers(Functions(pkg, name), type, encode.build(), verify.build(), decode.build())
    }

    /** A named scalar's, an enum's or an enum set's root: a one-slot box table. */
    private fun box(declaration: Declaration) {
        val name = declaration.name.camel
        val type = ClassName(pkg, name)
        val owner = declaration.name.declared
        val wire = wires.ofDeclaration(pkg, declaration)
        val layout = Layout.place(listOf(wire.width))
        val encode = CodeBlock.builder().addStatement(
            "return builder.pushTable(%L, %L, 1, listOf(%T(0, %L, %L)))",
            layout.size, layout.align, TABLE_FIELD, layout.offsets[0], field(wire, "value", owner),
        )
        val verify = CodeBlock.builder()
            .addStatement("val at = reader.field(table, 0, %L)", wire.width)
            .addStatement("  ?: throw %T.Structure(%T.MissingRequired)", VERIFY_ERROR, MALFORMED)
            .add(verifyAt(wire, "at", owner))
        val decode = CodeBlock.builder()
            .addStatement("return (reader.field(table, 0, %L) ?: 0).let { at -> %L }", wire.width, decodeAt(wire, "at"))
        helpers(Functions(pkg, name), type, encode.build(), verify.build(), decode.build())
    }

    /** A union arm's position: its own table, or a box around a scalar, an enum or an enum set. */
    private fun armPos(wire: Wire, expr: String, owner: String): CodeBlock = when (wire) {
        is Wire.Table, is Wire.UnionOf -> pos(wire, expr, owner)
        else -> {
            val layout = Layout.place(listOf(wire.width))
            CodeBlock.of(
                "builder.pushTable(%L, %L, 1, listOf(%T(0, %L, %L)))",
                layout.size, layout.align, TABLE_FIELD, layout.offsets[0], field(wire, expr, owner),
            )
        }
    }

    private fun verifyArm(wire: Wire, at: String, owner: String): CodeBlock = when (wire) {
        is Wire.Table, is Wire.UnionOf -> verifyAt(wire, at, owner)
        else -> {
            val box = fresh("box")
            val inner = fresh("at")
            CodeBlock.builder()
                .addStatement("val %L = reader.follow(%L)", box, at)
                .addStatement("val %L = reader.field(%L, 0, %L)", inner, box, wire.width)
                .addStatement("  ?: throw %T.Structure(%T.MissingRequired)", VERIFY_ERROR, MALFORMED)
                .add(verifyAt(wire, inner, owner))
                .build()
        }
    }

    private fun decodeArm(wire: Wire, at: String): CodeBlock = when (wire) {
        is Wire.Table, is Wire.UnionOf -> decodeAt(wire, at)
        else -> {
            val inner = fresh("at")
            CodeBlock.of("(reader.field(reader.follow(%L), 0, %L) ?: 0).let { %L -> %L }", at, wire.width, inner, decodeAt(wire, inner))
        }
    }

    // -- encoding --------------------------------------------------------------

    /** The `Field` a table slot holding [expr] of [wire] carries, its children pushed first. */
    private fun field(wire: Wire, expr: String, owner: String): CodeBlock = when (wire) {
        is Wire.ScalarWire -> CodeBlock.of("%T.%L(%L)", FIELD, wire.prim.field, wire.prim.raw(base(wire.domain, expr)))
        else -> CodeBlock.of("%T.Offset(%L)", FIELD, pos(wire, expr, owner))
    }

    /** The out-of-line object of [expr]: pushed, and its position. */
    private fun pos(wire: Wire, expr: String, owner: String): CodeBlock = when (wire) {
        is Wire.ScalarWire -> error("a scalar is inline")
        is Wire.Text -> CodeBlock.of("builder.pushString(%L)", base(wire.domain, expr))
        is Wire.Bytes -> CodeBlock.of("builder.pushVector(%L, 1)", base(wire.domain, expr))
        is Wire.Table -> CodeBlock.of("%M(%L, builder)", MemberName(wire.functions.pkg, wire.functions.encode), expr)
        is Wire.UnionOf -> CodeBlock.of("%M(%L, builder)", MemberName(wire.functions.pkg, wire.functions.encode), expr)
        is Wire.Vector -> when (val element = wire.element) {
            is Wire.ScalarWire -> {
                val bytes = fresh("bytes")
                val e = fresh("e")
                CodeBlock.of(
                    "run {\nval %L = %T.allocate(%L.size * %L).order(%T.LITTLE_ENDIAN)\nfor (%L in %L) %L\nbuilder.pushVector(%L.array(), %L)\n}",
                    bytes, BYTE_BUFFER, expr, element.prim.width, BYTE_ORDER,
                    e, expr, element.prim.put(bytes, element.prim.raw(base(element.domain, e))),
                    bytes, element.prim.width,
                )
            }
            else -> {
                val e = fresh("e")
                CodeBlock.of("builder.pushOffsetVector(%L.map { %L -> %L })", expr, e, pos(element, e, owner))
            }
        }
        is Wire.Map -> {
            val k = fresh("k")
            val v = fresh("v")
            val layout = Layout.place(listOf(wire.key.width, wire.value.width))
            val keyField = fresh("key")
            CodeBlock.of(
                "builder.pushOffsetVector(%L.map { (%L, %L) ->\nval %L = %L\nbuilder.pushTable(%L, %L, 2, listOf(%T(0, %L, %L), %T(1, %L, %L)))\n})",
                expr, k, v, keyField, field(wire.key, k, owner),
                layout.size, layout.align, TABLE_FIELD, layout.offsets[0], keyField,
                TABLE_FIELD, layout.offsets[1], field(wire.value, v, owner),
            )
        }
    }

    /** The backing [expr] of a [domain] value holds: a `Long`, `Double`, `Boolean`, `String` or `ByteArray`. */
    private fun base(domain: Domain, expr: String): String = when (domain) {
        Domain.Primitive, is Domain.Inline -> expr
        is Domain.Named -> if (domain.scalar.class_ == ScalarClass.SCALAR_CLASS_BYTES) "$expr.toByteArray()" else "$expr.value"
        is Domain.EnumOf -> "$expr.value"
        is Domain.SetOf -> "$expr.bits"
    }

    // -- verifying ---------------------------------------------------------------

    /** The checks of [wire] at position [at], in the Rust codec's order. */
    private fun verifyAt(wire: Wire, at: String, owner: String): CodeBlock {
        val code = CodeBlock.builder()
        when (wire) {
            is Wire.ScalarWire -> {
                val raw = fresh("raw")
                code.addStatement("val %L = %L", raw, wire.prim.widen("reader.${wire.prim.read}($at)"))
                code.add(domainCheck(wire.domain, raw, owner))
            }
            is Wire.Text -> {
                val text = fresh("text")
                code.addStatement("val %L = reader.string(%L)", text, at)
                code.add(domainCheck(wire.domain, text, owner))
            }
            is Wire.Bytes -> {
                val bytes = fresh("bytes")
                code.addStatement("val %L = reader.bytes(%L)", bytes, at)
                code.add(domainCheck(wire.domain, bytes, owner))
            }
            is Wire.Table, is Wire.UnionOf -> {
                val functions = if (wire is Wire.Table) wire.functions else (wire as Wire.UnionOf).functions
                code.addStatement("%M(reader, reader.follow(%L))", MemberName(functions.pkg, functions.verify), at)
            }
            is Wire.Vector -> {
                val v = fresh("vector")
                code.addStatement("val %L = reader.vector(%L, %L)", v, at, wire.element.width)
                count(code, "$v.len", wire.min, wire.max, owner)
                val i = fresh("i")
                code.beginControlFlow("for (%L in 0 until %L.len)", i, v)
                    .add(verifyAt(wire.element, "$v.element($i, ${wire.element.width})", owner))
                    .endControlFlow()
            }
            is Wire.Map -> {
                val v = fresh("vector")
                code.addStatement("val %L = reader.vector(%L, 4)", v, at)
                count(code, "$v.len", wire.min, wire.max, owner)
                val i = fresh("i")
                val entry = fresh("entry")
                val key = fresh("key")
                val value = fresh("value")
                code.beginControlFlow("for (%L in 0 until %L.len)", i, v)
                    .addStatement("val %L = reader.follow(%L.element(%L, 4))", entry, v, i)
                    .addStatement("val %L = reader.field(%L, 0, %L)", key, entry, wire.key.width)
                    .addStatement("  ?: throw %T.Structure(%T.MissingRequired)", VERIFY_ERROR, MALFORMED)
                    .add(verifyAt(wire.key, key, owner))
                    .addStatement("val %L = reader.field(%L, 1, %L)", value, entry, wire.value.width)
                    .addStatement("  ?: throw %T.Structure(%T.MissingRequired)", VERIFY_ERROR, MALFORMED)
                    .add(verifyAt(wire.value, value, owner))
                    .endControlFlow()
            }
        }
        return code.build()
    }

    /** The Rust codec's `count_check`, reported against the enclosing table's owner. */
    private fun count(code: CodeBlock.Builder, len: String, min: Long, max: Long, owner: String) {
        val condition = when {
            min == max -> "$len != $max"
            min == 0L -> "$len > $max"
            else -> "$len < $min || $len > $max"
        }
        code.beginControlFlow("if (%L)", condition)
            .addStatement("throw %T.Contract(%T(%S, %T.Length))", VERIFY_ERROR, VIOLATION, owner, RULE)
            .endControlFlow()
    }

    /** The contract checks of a value read at a position: its type's, or its owner's for an inline scalar. */
    private fun domainCheck(domain: Domain, value: String, owner: String): CodeBlock = when (domain) {
        Domain.Primitive -> CodeBlock.of("")
        is Domain.Inline -> inlineChecks(domain.scalar, value, owner)
        is Domain.Named -> if (domain.scalar.vacuous) {
            CodeBlock.of("")
        } else {
            CodeBlock.of(
                "%T.violation(%L)?.let { throw %T.Contract(%T(%S, it)) }\n",
                domain.type, value, VERIFY_ERROR, VIOLATION, domain.declared,
            )
        }
        is Domain.EnumOf -> CodeBlock.builder()
            .beginControlFlow("if (%T.fromValue(%L) == null)", domain.type, value)
            .addStatement("throw %T.Contract(%T(%S, %T.Variant))", VERIFY_ERROR, VIOLATION, domain.declared, RULE)
            .endControlFlow().build()
        is Domain.SetOf -> CodeBlock.builder()
            .beginControlFlow("if (%L and %T.DECLARED_MASK.inv() != 0L)", value, domain.type)
            .addStatement("throw %T.Contract(%T(%S, %T.Variant))", VERIFY_ERROR, VIOLATION, domain.declared, RULE)
            .endControlFlow().build()
    }

    private fun inlineChecks(scalar: Scalar, value: String, owner: String): CodeBlock {
        val pattern = if (scalar.checksPattern()) {
            patterns += scalar.constraint.pattern
            "PATTERN_${patterns.size - 1}"
        } else {
            null
        }
        return scalarChecks(scalar, value, pattern) { rule ->
            CodeBlock.of("throw %T.Contract(%T(%S, %T.%L))\n", VERIFY_ERROR, VIOLATION, owner, RULE, rule)
        } ?: CodeBlock.of("")
    }

    // -- decoding ------------------------------------------------------------------

    /** The value of [wire] at position [at] of a verified buffer. */
    private fun decodeAt(wire: Wire, at: String): CodeBlock = when (wire) {
        is Wire.ScalarWire -> construct(wire.domain, CodeBlock.of("%L", wire.prim.widen("reader.${wire.prim.read}($at)")))
        is Wire.Text -> construct(wire.domain, CodeBlock.of("reader.string(%L)", at))
        is Wire.Bytes -> construct(wire.domain, CodeBlock.of("reader.bytes(%L)", at))
        is Wire.Table -> CodeBlock.of("%M(reader, reader.follow(%L))", MemberName(wire.functions.pkg, wire.functions.decode), at)
        is Wire.UnionOf -> CodeBlock.of("%M(reader, reader.follow(%L))", MemberName(wire.functions.pkg, wire.functions.decode), at)
        is Wire.Vector -> {
            val v = fresh("vector")
            val i = fresh("i")
            CodeBlock.of(
                "reader.vector(%L, %L).let { %L -> List(%L.len) { %L -> %L } }",
                at, wire.element.width, v, v, i, decodeAt(wire.element, "$v.element($i, ${wire.element.width})"),
            )
        }
        is Wire.Map -> {
            val v = fresh("vector")
            val i = fresh("i")
            val entry = fresh("entry")
            val key = fresh("key")
            val value = fresh("value")
            CodeBlock.of(
                "reader.vector(%L, 4).let { %L -> (0 until %L.len).associate { %L ->\nval %L = reader.follow(%L.element(%L, 4))\n" +
                    "(reader.field(%L, 0, %L) ?: 0).let { %L -> %L } to (reader.field(%L, 1, %L) ?: 0).let { %L -> %L }\n} }",
                at, v, v, i, entry, v, i,
                entry, wire.key.width, key, decodeAt(wire.key, key),
                entry, wire.value.width, value, decodeAt(wire.value, value),
            )
        }
    }

    /** [raw], read from a verified buffer, as the domain value. */
    private fun construct(domain: Domain, raw: CodeBlock): CodeBlock = when (domain) {
        Domain.Primitive, is Domain.Inline -> raw
        is Domain.Named -> if (domain.scalar.vacuous) CodeBlock.of("%T(%L)", domain.type, raw) else CodeBlock.of("%T.unchecked(%L)", domain.type, raw)
        is Domain.EnumOf -> CodeBlock.of("(%T.fromValue(%L) ?: %T.%L)", domain.type, raw, domain.type, domain.first.name.declared)
        is Domain.SetOf -> CodeBlock.of("(%T.ofOrNull(%L) ?: %T.EMPTY)", domain.type, raw, domain.type)
    }

    // -- tuples ---------------------------------------------------------------------

    /**
     * The tuples the emitted struct codecs reach, as the Rust codec's
     * `reachable_tuples` walks them: from each struct's fields, through
     * tuples, arrays and maps, never through a named reference.
     */
    private fun reachableTuples(roots: List<Declaration>): List<Int> {
        val seen = LinkedHashSet<Int>()
        val queue = ArrayDeque<Type>()
        roots.filter { it.hasStruct() }.forEach { d -> d.struct.slotsList.filter { it.hasField() && it.field.hasType() }.forEach { queue += it.field.type } }
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            when (type.kindCase) {
                Type.KindCase.TUPLE -> {
                    val index = type.tuple.index
                    val tuple = model.getTuples(index)
                    if (tuple.name.rust.isNotEmpty() && seen.add(index)) tuple.fieldsList.filter { it.hasType() }.forEach { queue += it.type }
                }
                Type.KindCase.ARRAY -> if (type.array.hasElement()) queue += type.array.element
                Type.KindCase.MAP -> {
                    if (type.map.hasKey()) queue += type.map.key
                    if (type.map.hasValue()) queue += type.map.value
                }
                else -> {}
            }
        }
        return seen.toList()
    }
}
