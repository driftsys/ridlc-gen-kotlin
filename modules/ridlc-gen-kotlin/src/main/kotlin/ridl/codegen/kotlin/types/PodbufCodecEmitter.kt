package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.AnnotationSpec
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
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PodbufBox
import ridl.codegen.v1.ModelOuterClass.PodbufDescriptor
import ridl.codegen.v1.ModelOuterClass.PodbufField
import ridl.codegen.v1.ModelOuterClass.PodbufForm
import ridl.codegen.v1.ModelOuterClass.PodbufHead
import ridl.codegen.v1.ModelOuterClass.PodbufRefused
import ridl.codegen.v1.ModelOuterClass.PodbufSource
import ridl.codegen.v1.ModelOuterClass.SizeState
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.ModelOuterClass.Union
import ridl.codegen.v1.ModelOuterClass.Visibility

private const val PODBUF = "ridl.rt.podbuf"
private val PB_WRITER = ClassName(PODBUF, "Writer")
private val PB_PREAMBLE = ClassName(PODBUF, "Preamble")
private val PB_PREAMBLE_SIZE = MemberName(PODBUF, "PREAMBLE_SIZE")
private val PB_TAIL_RANGE = MemberName(PODBUF, "tailRange")
private val PB_PAYLOAD = ClassName("ridl.rt.payload", "Payload")
private val PB_ENCODING = ClassName("ridl.rt.encoding", "Encoding")
private val PB_BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
private val PB_REGEX = ClassName("kotlin.text", "Regex")

/**
 * `PodbufCodec.kt`: one podbuf codec per payload root of the package
 * (docs/design.md §4, ADR-0031), over `ridl.rt.podbuf`, and the encode,
 * verify and decode helpers of every head a root reaches. It is the Rust
 * podbuf codec of the pinned release (`codec_podbuf.rs`) spelled in Kotlin:
 * the layout is read from `Model.podbuf`, never computed, and the verify
 * order, the verdicts and the bytes are Rust's. As in [CodecEmitter],
 * `verify` also checks what the value objects check, because `decode`
 * builds them through `unchecked` and must never throw.
 *
 * Per head `T` (a struct, a union or a named tuple): `podbufEncodeT(value, w,
 * at)` writes `T`'s head at `at` and appends its tail, `podbufVerifyT(buffer,
 * at, size, tail)` checks a head its producer wrote `size` bytes of, below
 * whose `tail` no descriptor may point, and `podbufDecodeT(buffer, at, size)`
 * reads one `verify` passed; each is `internal`, so another package's codec
 * writes a head it lays out inline through them. Per root, `TPodbufView` is
 * what `verify` hands `decode`, and `object TPodbufCodec` is the `Payload`.
 * [PodbufForms] holds the write, the checks and the read of each form.
 */
class PodbufCodecEmitter(private val model: Model, private val options: Options) {
    private val pkg = options.kotlinPackage
    private val file = FileSpec.builder(pkg, "PodbufCodec").jvmName("PodbufCodec")
    private val forms = PodbufForms(model, pkg)
    private val wires = forms.wires
    private val inits = Inits(model, pkg, wires)
    private val errors = mutableListOf<String>()
    private val claims = mutableListOf<Claim>()

    fun emit(): EmittedTypes {
        val path = pkg.replace('.', '/') + "/PodbufCodec.kt"
        if (!model.hasPodbuf()) return EmittedTypes(path, null, listOf("$PLUGIN: the model carries no podbuf projection"))
        // A refused head has no layout to write from, and neither has a head
        // that needs it: the Rust codec refuses the package, naming the first.
        model.podbuf.refusedList.firstOrNull()?.let { return EmittedTypes(path, null, listOf(refusal(it))) }
        val heads = model.podbuf.headsList
        val tupleBounds = podbufTupleBounds(model)
        for (head in heads) {
            when (head.sourceCase) {
                PodbufHead.SourceCase.DECLARATION -> {
                    val declaration = model.declarationsList.getOrNull(head.declaration) ?: continue
                    val name = declaration.name.declared
                    val emitted = when (declaration.kindCase) {
                        Declaration.KindCase.STRUCT -> {
                            val fields = declaration.struct.slotsList.filter { it.hasField() }.map { it.field }
                            guarded(name) {
                                record(declaration.name.camel, name, head) { source ->
                                    if (source.kindCase != PodbufSource.KindCase.FIELD) null else fields.firstOrNull { it.name.declared == source.field.name }
                                }
                            }
                        }
                        Declaration.KindCase.UNION -> guarded(name) { union(declaration, declaration.union, head) }
                        else -> continue
                    }
                    val bound = declaredBound(declaration, head = true)
                    if (emitted && bound != null) {
                        codec(declaration.name.camel, declaration.visibility, "declaration `$name`", head.size, bound)
                    }
                }
                PodbufHead.SourceCase.TUPLE -> {
                    val tuple = model.tuplesList.getOrNull(head.tuple) ?: continue
                    val name = tuple.name.rust
                    if (name.isEmpty()) continue
                    val emitted = guarded(name) {
                        record(name, name, head) { source ->
                            if (source.kindCase != PodbufSource.KindCase.POSITION) null else tuple.fieldsList.getOrNull(source.position - 1)
                        }
                    }
                    // A tuple no interaction sends on its own has helpers and no codec: only another head's codec calls them.
                    val bound = tupleBounds[head.tuple]?.let { maxSize(name, it) }
                    if (emitted && bound != null) {
                        codec(name, tuple.visibility, "the struct induced for a call", head.size, bound)
                    }
                }
                // A map entry's and a union arm's head are written by the head that holds them, and a
                // foreign or nested head by the package that declares it.
                else -> {}
            }
        }
        for (boxed in model.podbuf.boxesList) box(boxed)
        if (errors.isNotEmpty()) return EmittedTypes(path, null, errors)
        for ((index, body) in forms.patterns.withIndex()) {
            file.addProperty(
                PropertySpec.builder("PODBUF_PATTERN_$index", PB_REGEX, KModifier.PRIVATE).initializer("%T(%S)", PB_REGEX, body).build(),
            )
        }
        forms.privateHelpers().forEach(file::addFunction)
        if (deprecated()) file.addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "DEPRECATION").build())
        file.addFileComment("Generated by %L from `%L`. Do not edit.", PLUGIN, model.name.dotted)
        return EmittedTypes(path, file.build().toString(), emptyList(), claims)
    }

    /** `codec_podbuf.rs`'s refusal of a package with a refused head, in its words. */
    private fun refusal(refused: PodbufRefused): String {
        val what = when (refused.sourceCase) {
            PodbufRefused.SourceCase.DECLARATION ->
                model.declarationsList.getOrNull(refused.declaration)?.let { "`${model.name.dotted}.${it.name.declared}`" }
            PodbufRefused.SourceCase.TUPLE -> model.tuplesList.getOrNull(refused.tuple)?.let { "the tuple `${it.name.rust}`" }
            else -> null
        } ?: "a type"
        return "$PLUGIN: $what cannot be laid out as a podbuf head, so the package gets no podbuf codec: ${refused.cause}"
    }

    /** Whether the code reaches a deprecated declaration or field, which Kotlin warns of. */
    private fun deprecated(): Boolean {
        val declarations = model.declarationsList + model.foreignList.map { it.declaration }
        return declarations.any { d -> d.hasDeprecated() || d.struct.slotsList.any { it.hasField() && it.field.hasDeprecated() } } ||
            model.tuplesList.any { t -> t.fieldsList.any { it.hasDeprecated() } }
    }

    /** Runs [block], reporting a [Refusal] as the package's error; whether it passed. */
    private fun guarded(name: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (refusal: Refusal) {
        errors += "$PLUGIN: `${model.name.dotted}.$name` has no podbuf codec: ${refusal.message}"
        false
    }

    private fun fresh(stem: String): String = forms.fresh(stem)

    // -- bounds -------------------------------------------------------------------

    /**
     * A declaration's podbuf bound as a `maxSize`, or the error Rust gives a
     * root with no finite bound: with its cause for a struct or a union
     * ([head]), bare for a box.
     */
    private fun declaredBound(declaration: Declaration, head: Boolean): Int? {
        val name = declaration.name.declared
        val bound = if (declaration.hasPodbuf()) bounded(declaration.podbuf) else null
        if (bound == null) {
            errors += "$PLUGIN: `${model.name.dotted}.$name` has no finite podbuf bound" + if (head) {
                ": its largest buffer does not fit a u32 offset, or an array or map of it passes a descriptor limit"
            } else {
                ""
            }
            return null
        }
        return maxSize(name, bound)
    }

    /**
     * [bound] as a `Payload.maxSize`, an `Int`: the model carries a `uint32`,
     * and a bound of 2^31 or more refuses the package (a departure from Rust,
     * whose `MAX_SIZE` is a `usize`).
     */
    private fun maxSize(name: String, bound: Long): Int? {
        if (bound > Int.MAX_VALUE) {
            errors += "$PLUGIN: `${model.name.dotted}.$name` has a podbuf bound of $bound bytes, " +
                "above the largest a Kotlin `Payload.maxSize` holds"
            return null
        }
        return bound.toInt()
    }

    // -- the roots ----------------------------------------------------------------

    /** The `Payload` object of [name] and its view, whose helpers are already emitted. */
    private fun codec(name: String, visibility: Visibility, source: String, headSize: Int, maxSize: Int) {
        val type = ClassName(pkg, name)
        val functions = PodbufFunctions(pkg, name)
        val view = ClassName(pkg, "${name}PodbufView")
        val codec = ClassName(pkg, "${name}PodbufCodec")
        claims += Claim(codec.simpleName, "the podbuf codec of $source")
        claims += Claim(view.simpleName, "the podbuf view of $source")
        val viewSpec = TypeSpec.classBuilder(view)
            .addKdoc("A `%L` in podbuf bytes that [%T.verify] checked, which [%T.decode] reads.", name, codec, codec)
            .primaryConstructor(
                FunSpec.constructorBuilder().addModifiers(KModifier.INTERNAL)
                    .addParameter("buffer", PB_BUFFER).addParameter("at", INT).addParameter("size", INT).build(),
            )
            .addProperty(PropertySpec.builder("buffer", PB_BUFFER, KModifier.INTERNAL).initializer("buffer").build())
            .addProperty(PropertySpec.builder("at", INT, KModifier.INTERNAL).initializer("at").build())
            .addProperty(PropertySpec.builder("size", INT, KModifier.INTERNAL).initializer("size").build())
        val codecSpec = TypeSpec.objectBuilder(codec)
            .addKdoc("The podbuf codec of [%T].", type)
            .addSuperinterface(PB_PAYLOAD.parameterizedBy(type, view))
            .addProperty(
                PropertySpec.builder("encoding", PB_ENCODING, KModifier.OVERRIDE).initializer("%T.Podbuf", PB_ENCODING).build(),
            )
            .addProperty(PropertySpec.builder("maxSize", INT, KModifier.OVERRIDE).initializer("%L", maxSize).build())
            .addFunction(
                FunSpec.builder("encode").addModifiers(KModifier.OVERRIDE)
                    .addParameter("value", type).addParameter("out", PB_BYTE_BUFFER).returns(INT)
                    .addStatement("val w = %T(out)", PB_WRITER)
                    .addStatement("%M(value, w, w.reserveHead(%L))", MemberName(pkg, functions.encode), headSize)
                    .addStatement("return w.finish()")
                    .build(),
            )
            .addFunction(
                // No length check past what the preamble and the head reference:
                // an older reader accepts the longer buffer of a newer writer.
                FunSpec.builder("verify").addModifiers(KModifier.OVERRIDE)
                    .addParameter("buf", PB_BYTE_BUFFER).returns(view)
                    .addStatement("val buffer = %T(buf)", PB_BUFFER)
                    .addStatement("val size = %T.read(buffer).headSize", PB_PREAMBLE)
                    .addStatement("%M(buffer, %M, size, %M + size)", MemberName(pkg, functions.verify), PB_PREAMBLE_SIZE, PB_PREAMBLE_SIZE)
                    .addStatement("return %T(buffer, %M, size)", view, PB_PREAMBLE_SIZE)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("decode").addModifiers(KModifier.OVERRIDE)
                    .addParameter("view", view).returns(type)
                    .addStatement("return %M(view.buffer, view.at, view.size)", MemberName(pkg, functions.decode))
                    .build(),
            )
        if (visibility == Visibility.VISIBILITY_INTERNAL) {
            viewSpec.addModifiers(KModifier.INTERNAL)
            codecSpec.addModifiers(KModifier.INTERNAL)
        }
        file.addType(viewSpec.build())
        file.addType(codecSpec.build())
    }

    /** A head's own place in `verify` and `decode`: `at`, `size`, read through `region`. */
    private fun headPlace() = PodbufPlace("at", "size", "region")

    /**
     * The three helpers of one head, internal to the generated module.
     * [verify] and [decode] read through [verifyPlace] and [decodePlace],
     * whose `region` is declared only when the body reads it.
     */
    private fun helpers(
        functions: PodbufFunctions,
        type: ClassName,
        encode: CodeBlock,
        verify: CodeBlock,
        verifyPlace: PodbufPlace,
        decode: CodeBlock,
        decodePlace: PodbufPlace,
    ) {
        file.addFunction(
            FunSpec.builder(functions.encode).addModifiers(KModifier.INTERNAL)
                .addParameter("value", type).addParameter("w", PB_WRITER).addParameter("at", INT)
                .addCode(encode).build(),
        )
        file.addFunction(
            FunSpec.builder(functions.verify).addModifiers(KModifier.INTERNAL)
                .addParameter("buffer", PB_BUFFER).addParameter("at", INT).addParameter("size", INT).addParameter("tail", INT)
                .addCode(verifyPlace.binding()).addCode(verify).build(),
        )
        file.addFunction(
            FunSpec.builder(functions.decode).addModifiers(KModifier.INTERNAL)
                .addParameter("buffer", PB_BUFFER).addParameter("at", INT).addParameter("size", INT).returns(type)
                .addCode(decodePlace.binding()).addCode(decode).build(),
        )
    }

    /**
     * A struct's or a tuple's head: every placement written, checked and read
     * at the offset the projection gives it, its tail appended in placement
     * order; a flags byte computed from the optional fields it records; a
     * deprecated field written as its init value.
     */
    private fun record(name: String, owner: String, head: PodbufHead, fieldOf: (PodbufSource) -> Field?) {
        val type = ClassName(pkg, name)
        val placed = head.fieldsList.map { field ->
            when (field.source.kindCase) {
                PodbufSource.KindCase.FIELD, PodbufSource.KindCase.POSITION -> {
                    val member = fieldOf(field.source) ?: refuse("the podbuf layout of `$owner` names a field the type does not have")
                    if (!member.hasType()) refuse("a field of `$owner` carries no type")
                    Placed(field, member)
                }
                PodbufSource.KindCase.FLAGS -> Placed(field, null)
                PodbufSource.KindCase.TAG, PodbufSource.KindCase.ARM -> refuse("the podbuf head of `$owner` holds a union's field")
                PodbufSource.KindCase.KEY, PodbufSource.KindCase.VALUE -> refuse("the podbuf head of `$owner` holds a map entry's field")
                else -> refuse("a podbuf field of `$owner` has no source")
            }
        }
        val encode = CodeBlock.builder()
        // What each field is written from: the property, or a deprecated field's init value (`null` when it has none).
        val operands = placed.associateWith { p ->
            val member = p.member ?: return@associateWith null
            if (!member.hasDeprecated()) return@associateWith Operand("value.${escaped(member.name.property)}", member.type.optional)
            // An optional field with no declared init has none: it stays absent, its bytes zero (Inits.field's `null`).
            if (member.type.optional && !member.hasDeclaredInit()) return@associateWith null
            val init = try {
                inits.field(member)
            } catch (_: Refusal) {
                return@associateWith null
            }
            val binding = fresh("deprecated")
            encode.addStatement("val %L = %L", binding, init)
            Operand(binding, false)
        }
        for (p in placed) {
            val at = position("at", p.field.offset.toLong())
            if (p.member == null) {
                encode.add(flagsWrite(placed, operands, p.field.source.flags, at))
                continue
            }
            val operand = operands[p] ?: continue
            val form = forms.formOf(p.field.form, owner)
            val present = forms.present(p.member.type)
            if (!operand.nullable) {
                encode.add(forms.writeForm(form, present, operand.expr, at, owner))
            } else {
                val o = fresh("present")
                val write = forms.writeForm(form, present, o, at, owner)
                if (!write.isEmpty()) encode.beginControlFlow("%L?.let { %L ->", operand.expr, o).add(write).endControlFlow()
            }
        }
        val verifyPlace = headPlace()
        val decodePlace = headPlace()
        val verify = CodeBlock.builder()
        val decode = CodeBlock.builder().add("return %T(\n", type).indent()
        for (p in placed) {
            val member = p.member ?: continue
            val form = forms.formOf(p.field.form, owner)
            val off = p.field.offset.toString()
            if (!p.field.hasOptional()) {
                verify.add(forms.verifyForm(form, member.type, verifyPlace, off, owner))
                decode.add("%L = %L,\n", escaped(member.name.property), forms.decodeForm(form, member.type, decodePlace, off))
                continue
            }
            // ADR-0031 decision 8 rule 2: an absent optional's bytes are zero; a present one is checked as any value.
            val inner = forms.verifyForm(form, forms.present(member.type), verifyPlace, off, owner)
            val byte = fresh("byte")
            val nonZero = CodeBlock.of(
                "%L.bytesAt(%L, %L).any { %L -> %L.toInt() != 0 }", verifyPlace.region, off, p.field.size, byte, byte,
            )
            val presence = presence(head, p.field, verifyPlace, owner)
            if (inner.isEmpty()) {
                verify.beginControlFlow("if (!(%L) && %L)", presence, nonZero).add(forms.verifyRefusal(owner, RuleName.Variant)).endControlFlow()
            } else {
                verify.beginControlFlow("if (%L)", presence).add(inner)
                    .nextControlFlow("else if (%L)", nonZero).add(forms.verifyRefusal(owner, RuleName.Variant)).endControlFlow()
            }
            decode.add(
                "%L = if (%L) %L else null,\n",
                escaped(member.name.property), presence(head, p.field, decodePlace, owner),
                forms.decodeForm(form, forms.present(member.type), decodePlace, off),
            )
        }
        decode.unindent().add(")\n")
        helpers(PodbufFunctions(pkg, name), type, encode.build(), verify.build(), verifyPlace, decode.build(), decodePlace)
    }

    private class Placed(val field: PodbufField, val member: Field?)

    /** What a field is written from: an expression, `null`-able when the field is optional. */
    private class Operand(val expr: String, val nullable: Boolean)

    /** The statements that write flags byte [index]: bit `i` set when the optional field it records is present. */
    private fun flagsWrite(placed: List<Placed>, operands: Map<Placed, Operand?>, index: Int, at: String): CodeBlock {
        val bits = placed.mapNotNull { p ->
            if (!p.field.hasOptional() || p.field.optional.flags != index) return@mapNotNull null
            val operand = operands[p] ?: return@mapNotNull null
            operand to (1 shl p.field.optional.bit)
        }
        if (bits.isEmpty()) return CodeBlock.of("")
        val flags = fresh("flags")
        val code = CodeBlock.builder().addStatement("var %L = 0", flags)
        for ((operand, mask) in bits) {
            // A deprecated field with an init value is always present.
            if (operand.nullable) {
                code.addStatement("if (%L != null) %L = %L or %L", operand.expr, flags, flags, mask)
            } else {
                code.addStatement("%L = %L or %L", flags, flags, mask)
            }
        }
        return code.addStatement("w.putU8(%L, %L)", at, flags).build()
    }

    /** The condition that the optional [field]'s flag bit is set, read at [place]. */
    private fun presence(head: PodbufHead, field: PodbufField, place: PodbufPlace, owner: String): CodeBlock {
        val bit = field.optional
        val flags = head.fieldsList.firstOrNull { it.source.kindCase == PodbufSource.KindCase.FLAGS && it.source.flags == bit.flags }
            ?: refuse("the podbuf head of `$owner` has no flags byte ${bit.flags}")
        return CodeBlock.of("(%L.u8At(%L) and %L) != 0", place.region, flags.offset, 1 shl bit.bit)
    }

    /**
     * A union's head: the arm's ordinal as the tag, and a descriptor to the
     * arm's head in the tail, which a struct or union arm's own helpers write
     * and read and whose one value field a scalar, enum or enum set arm's
     * head holds. An arm whose head has no field is a zero descriptor, and
     * `verify` only bounds-checks its descriptor, so the arm a newer producer
     * gave a field is read (ADR-0031 decision 3).
     */
    private fun union(declaration: Declaration, union: Union, head: PodbufHead) {
        val owner = declaration.name.declared
        val name = declaration.name.camel
        val type = ClassName(pkg, name)
        if (union.armsCount == 0) refuse("`$owner` declares no arm, so it decodes no value")
        val tag = head.fieldsList.firstOrNull { it.source.kindCase == PodbufSource.KindCase.TAG }
            ?: refuse("the podbuf head of `$owner` has no tag")
        val tagPrim = if (tag.form.kindCase == PodbufForm.KindCase.INT) wires.intPrim(tag.form.int.width) else refuse("the podbuf tag of `$owner` is not an integer")
        val armField = head.fieldsList.firstOrNull { it.source.kindCase == PodbufSource.KindCase.ARM }
            ?: refuse("the podbuf head of `$owner` has no arm descriptor")
        if (armField.form.kindCase != PodbufForm.KindCase.TAIL_DESCRIPTOR) refuse("the podbuf arm field of `$owner` is no descriptor")
        if (armField.form.tailDescriptor.kindCase != PodbufDescriptor.KindCase.ARMS) refuse("the podbuf arm field of `$owner` is no arm descriptor")
        val layouts = armField.form.tailDescriptor.arms.armsList
        val arms = union.armsList.map { arm ->
            val armHead = layouts.firstOrNull { it.ordinal == arm.ordinal }?.let { model.podbuf.headsList.getOrNull(it.head) }
                ?: refuse("the podbuf layout of `$owner` has no head for arm `${arm.name.declared}`")
            if (!arm.hasType()) refuse("arm `${arm.name.declared}` of `$owner` names no type")
            val armType = Type.newBuilder().setNamed(arm.type).build()
            val (_, declared) = wires.declarationOf(arm.type)
            val value = if (declared.hasStruct() || declared.hasUnion()) {
                null
            } else {
                armHead.fieldsList.firstOrNull { it.source.kindCase == PodbufSource.KindCase.ARM }
                    ?: refuse("the podbuf arm head of `$owner` has no value field")
            }
            UnionArm(type.nestedClass(arm.name.camel), tagLiteral(tagPrim, arm.ordinal), armHead, armType, value)
        }
        val tagAt = position("at", tag.offset.toLong())
        val descAt = position("at", armField.offset.toLong())
        val encode = CodeBlock.builder().beginControlFlow("when (value)")
        for (arm in arms) {
            encode.beginControlFlow("is %T ->", arm.variant).addStatement("w.put%L(%L, %L)", tagPrim.field, tagAt, arm.tag)
            if (arm.head.size > 0) {
                val block = fresh("block")
                encode.addStatement("val %L = w.tail(%L, 8)", block, arm.head.size)
                    .addStatement("w.putDesc(%L, %T(%L.toLong(), %L))", descAt, PB_DESC, block, "${arm.head.size}L")
                if (arm.value == null) {
                    val functions = forms.headFunctions(arm.type)
                    encode.addStatement("%M(value.value, w, %L)", MemberName(functions.pkg, functions.encode), block)
                } else {
                    encode.add(forms.writeForm(forms.formOf(arm.value.form, owner), arm.type, "value.value", position(block, arm.value.offset.toLong()), owner))
                }
            }
            encode.endControlFlow()
        }
        encode.endControlFlow()

        val verifyPlace = headPlace()
        val d = fresh("desc")
        val load = tagPrim.widen("${verifyPlace.region}.${tagPrim.read}At(${tag.offset})")
        val verify = CodeBlock.builder()
            .addStatement("val %L = %T.read(%L, %L)", d, PB_DESC, verifyPlace.region, armField.offset)
            .beginControlFlow("if (%L.len %% 8 != 0L)", d).add(forms.malformed("Union")).endControlFlow()
            .beginControlFlow("when (%L)", load)
        for (arm in arms) {
            verify.beginControlFlow("%L ->", "${arm.tag.removeSuffix("L")}L")
                .beginControlFlow("if (%L.len == 0L)", d)
                .beginControlFlow("if (%L.off != 0L)", d).add(forms.malformed("OutOfBounds")).endControlFlow()
                .nextControlFlow("else")
                .addStatement("%M(%L.off, %L.len, buffer.size, tail, 8)", PB_TAIL_RANGE, d, d)
                .endControlFlow()
            if (arm.head.size > 0) {
                if (arm.value == null) {
                    val functions = forms.headFunctions(arm.type)
                    verify.addStatement("%M(buffer, %L.off.toInt(), %L.len.toInt(), tail)", MemberName(functions.pkg, functions.verify), d, d)
                } else {
                    val place = PodbufPlace("$d.off.toInt()", "$d.len.toInt()", fresh("arm"))
                    val check = forms.verifyForm(forms.formOf(arm.value.form, owner), arm.type, place, arm.value.offset.toString(), owner)
                    verify.add(place.binding()).add(check)
                }
            }
            verify.endControlFlow()
        }
        verify.add("else -> %L", forms.malformed("Union")).endControlFlow()

        val decodePlace = headPlace()
        val read = fresh("desc")
        var described = false
        fun build(arm: UnionArm): CodeBlock {
            if (arm.value == null) {
                described = true
                val functions = forms.headFunctions(arm.type)
                return CodeBlock.of("%T(%M(buffer, %L.off.toInt(), %L.len.toInt()))", arm.variant, MemberName(functions.pkg, functions.decode), read, read)
            }
            val place = PodbufPlace("$read.off.toInt()", "$read.len.toInt()", fresh("arm"))
            val value = forms.decodeForm(forms.formOf(arm.value.form, owner), arm.type, place, arm.value.offset.toString())
            if (!place.used) return CodeBlock.of("%T(%L)", arm.variant, value)
            described = true
            return CodeBlock.of(
                "buffer.region(%L.off.toInt(), %L.len.toInt()).let { %L -> %T(%L) }",
                read, read, place.region, arm.variant, value,
            )
        }
        val matches = CodeBlock.builder()
        for (arm in arms) matches.add("%L -> %L\n", "${arm.tag.removeSuffix("L")}L", build(arm))
        matches.add("else -> %L\n", build(arms.first()))
        val decode = CodeBlock.builder()
        if (described) decode.addStatement("val %L = %T.read(%L, %L)", read, PB_DESC, decodePlace.region, armField.offset)
        decode.add("return when (%L) {\n", tagPrim.widen("${decodePlace.region}.${tagPrim.read}At(${tag.offset})")).indent()
            .add(matches.build()).unindent().add("}\n")
        helpers(PodbufFunctions(pkg, name), type, encode.build(), verify.build(), verifyPlace, decode.build(), decodePlace)
    }

    /** One arm of a union: its class, its tag literal, its head, its type position and, for a scalar arm, its value field. */
    private class UnionArm(val variant: ClassName, val tag: String, val head: PodbufHead, val type: Type, val value: PodbufField?)

    /** The arm [ordinal] as the argument of `Writer.put<prim>` for an unsigned tag. */
    private fun tagLiteral(prim: Prim, ordinal: Int): String = when (prim) {
        Prim.U32, Prim.U64 -> "${ordinal}L"
        else -> "$ordinal"
    }

    /**
     * The codec of a named scalar, an enum or an enum set carried as a
     * payload root: the value alone at its offset of its one-field head
     * (`Model.podbuf.boxes`). Its bound is checked first, as Rust's `boxed`
     * checks it, so a box with no bound is refused before its head is read.
     */
    private fun box(boxed: PodbufBox) {
        val declaration = model.declarationsList.getOrNull(boxed.declaration) ?: return
        if (!boxed.hasHead()) return
        val bound = declaredBound(declaration, head = false) ?: return
        val head = boxed.head
        val owner = declaration.name.declared
        val name = declaration.name.camel
        val emitted = guarded(owner) {
            val field = head.fieldsList.firstOrNull() ?: refuse("the podbuf box of `$owner` has no field")
            val form = forms.formOf(field.form, owner)
            val ref = TypeRef.newBuilder().setReference(owner).setResolved(true).setPackage(model.scope.`package`)
                .setForeign(false).setIndex(boxed.declaration)
            val type = Type.newBuilder().setNamed(ref).build()
            val off = field.offset.toString()
            val verifyPlace = headPlace()
            val decodePlace = headPlace()
            helpers(
                PodbufFunctions(pkg, name),
                ClassName(pkg, name),
                forms.writeForm(form, type, "value", position("at", field.offset.toLong()), owner),
                forms.verifyForm(form, type, verifyPlace, off, owner),
                verifyPlace,
                CodeBlock.of("return %L\n", forms.decodeForm(form, type, decodePlace, off)),
                decodePlace,
            )
        }
        if (emitted) codec(name, declaration.visibility, "declaration `$owner`", head.size, bound)
    }
}

/** The three podbuf helpers of a head, in the package that declares it. */
internal data class PodbufFunctions(val pkg: String, val suffix: String) {
    val encode get() = "podbufEncode$suffix"
    val verify get() = "podbufVerify$suffix"
    val decode get() = "podbufDecode$suffix"
}

/** A payload root a podbuf bound is asked of: a declaration, or an induced tuple, by index. */
internal sealed interface PodbufRoot {
    data class Declared(val index: Int) : PodbufRoot

    data class Tuple(val index: Int) : PodbufRoot
}

/**
 * The `maxSize` of [root]'s podbuf codec: the bound the model carries for a
 * declaration, or for the interaction whose payload a tuple is; `null` when
 * the root has no codec, because it has no finite bound or one an `Int`
 * does not hold.
 */
internal fun podbufMaxSize(model: Model, root: PodbufRoot): Int? {
    val bound = when (root) {
        is PodbufRoot.Declared -> model.declarationsList.getOrNull(root.index)?.takeIf { it.hasPodbuf() }?.let { bounded(it.podbuf) }
        is PodbufRoot.Tuple -> podbufTupleBounds(model)[root.index]
    }
    return bound?.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

/** A bounded size state's bound, an unsigned 32-bit value, or `null`. */
private fun bounded(state: SizeState): Long? =
    if (state.stateCase == SizeState.StateCase.BOUNDED) Integer.toUnsignedLong(state.bounded) else null

/**
 * The induced tuples the interactions of the package send on their own,
 * each with its podbuf bound when that is bounded: the Rust backend's
 * `tuple_roots` over the podbuf state.
 */
internal fun podbufTupleBounds(model: Model): Map<Int, Long> {
    val roots = mutableMapOf<Int, Long>()
    for (face in model.interfacesList) {
        for (slot in face.slotsList) {
            if (!slot.hasInteraction()) continue
            val interaction = slot.interaction
            val payloads = when {
                interaction.hasCommand() -> with(interaction.command) {
                    listOf(requestTuple.takeIf { hasRequestTuple() } to requestSizes)
                }
                interaction.hasQuery() -> with(interaction.query) {
                    listOf(requestTuple.takeIf { hasRequestTuple() } to requestSizes, replyTuple.takeIf { hasReplyTuple() } to replySizes)
                }
                else -> emptyList()
            }
            for ((tuple, sizes) in payloads) {
                if (tuple == null || !sizes.hasPodbuf()) continue
                bounded(sizes.podbuf)?.let { roots[tuple.index] = it }
            }
        }
    }
    return roots
}
