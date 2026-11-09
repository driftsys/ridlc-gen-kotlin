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
import ridl.codegen.v1.ModelOuterClass.FloatWidth
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PodbufBox
import ridl.codegen.v1.ModelOuterClass.PodbufField
import ridl.codegen.v1.ModelOuterClass.PodbufForm
import ridl.codegen.v1.ModelOuterClass.PodbufHead
import ridl.codegen.v1.ModelOuterClass.PodbufRefused
import ridl.codegen.v1.ModelOuterClass.PodbufSource
import ridl.codegen.v1.ModelOuterClass.SizeState
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.ModelOuterClass.Visibility

private const val PODBUF = "ridl.rt.podbuf"
private val PB_BUFFER = ClassName(PODBUF, "Buffer")
private val PB_WRITER = ClassName(PODBUF, "Writer")
private val PB_PREAMBLE = ClassName(PODBUF, "Preamble")
private val PB_PREAMBLE_SIZE = MemberName(PODBUF, "PREAMBLE_SIZE")
private val PB_PAYLOAD = ClassName("ridl.rt.payload", "Payload")
private val PB_VERIFY_ERROR = ClassName("ridl.rt.payload", "VerifyError")
private val PB_ENCODE_ERROR = ClassName("ridl.rt.payload", "EncodeError")
private val PB_VIOLATION = ClassName("ridl.rt.payload", "Violation")
private val PB_RULE = ClassName("ridl.rt.payload", "Rule")
private val PB_ENCODING = ClassName("ridl.rt.encoding", "Encoding")
private val PB_BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
private val PB_REGEX = ClassName("kotlin.text", "Regex")

/** What a tail form gets until the tail is emitted. */
private const val TAIL_FORMS = "tail forms are not emitted yet"

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
 * Per head `T`: `podbufEncodeT(value, w, at)` writes `T`'s head at `at`,
 * `podbufVerifyT(buffer, at, size, tail)` checks a head its producer wrote
 * `size` bytes of, and `podbufDecodeT(buffer, at, size)` reads one `verify`
 * passed; each is `internal`, so another package's codec writes a head it
 * lays out inline through them. Per root, `TPodbufView` is what `verify`
 * hands `decode`, and `object TPodbufCodec` is the `Payload`.
 *
 * This stage emits the fixed-size forms: booleans, integers, floats, enums
 * and enum sets, inline heads and exact-length arrays. A head with a tail
 * descriptor refuses its package.
 */
class PodbufCodecEmitter(private val model: Model, private val options: Options) {
    private val pkg = options.kotlinPackage
    private val file = FileSpec.builder(pkg, "PodbufCodec").jvmName("PodbufCodec")
    private val wires = Wires(model, pkg)
    private val inits = Inits(model, pkg, wires)
    private val errors = mutableListOf<String>()
    private val claims = mutableListOf<Claim>()
    private val patterns = mutableListOf<String>()
    private var names = 0

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
                    when (declaration.kindCase) {
                        Declaration.KindCase.STRUCT -> {
                            val name = declaration.name.declared
                            val fields = declaration.struct.slotsList.filter { it.hasField() }.map { it.field }
                            val emitted = guarded(name) {
                                record(declaration.name.camel, name, head) { source ->
                                    if (source.kindCase != PodbufSource.KindCase.FIELD) null else fields.firstOrNull { it.name.declared == source.field.name }
                                }
                            }
                            val bound = declaredBound(declaration, head = true)
                            if (emitted && bound != null) {
                                codec(declaration.name.camel, declaration.visibility, "declaration `$name`", head.size, bound)
                            }
                        }
                        // A union's arm is a tail descriptor.
                        Declaration.KindCase.UNION -> guarded(declaration.name.declared) { refuse(TAIL_FORMS) }
                        else -> {}
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
                else -> {}
            }
        }
        for (boxed in model.podbuf.boxesList) box(boxed)
        if (errors.isNotEmpty()) return EmittedTypes(path, null, errors)
        for ((index, body) in patterns.withIndex()) {
            file.addProperty(
                PropertySpec.builder("PODBUF_PATTERN_$index", PB_REGEX, KModifier.PRIVATE).initializer("%T(%S)", PB_REGEX, body).build(),
            )
        }
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

    private fun fresh(stem: String): String = "$stem${names++}"

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

    /** The three helpers of one head, internal to the generated module. */
    private fun helpers(functions: PodbufFunctions, type: ClassName, encode: CodeBlock, verify: CodeBlock, decode: CodeBlock) {
        file.addFunction(
            FunSpec.builder(functions.encode).addModifiers(KModifier.INTERNAL)
                .addParameter("value", type).addParameter("w", PB_WRITER).addParameter("at", INT)
                .addCode(encode).build(),
        )
        file.addFunction(
            FunSpec.builder(functions.verify).addModifiers(KModifier.INTERNAL)
                .addParameter("buffer", PB_BUFFER).addParameter("at", INT).addParameter("size", INT).addParameter("tail", INT)
                .addCode(withRegion(verify)).build(),
        )
        file.addFunction(
            FunSpec.builder(functions.decode).addModifiers(KModifier.INTERNAL)
                .addParameter("buffer", PB_BUFFER).addParameter("at", INT).addParameter("size", INT).returns(type)
                .addCode(withRegion(decode)).build(),
        )
    }

    /** [body], after the `region` it reads through when it reads one: the head, reading 0 past `size`. */
    private fun withRegion(body: CodeBlock): CodeBlock =
        if ("region." !in body.toString()) body else CodeBlock.builder().addStatement("val region = buffer.region(at, size)").add(body).build()

    /**
     * A struct's or a tuple's head: every placement written, checked and read
     * at the offset the projection gives it; a flags byte computed from the
     * optional fields it records; a deprecated field written as its init value.
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
            val init = try {
                inits.field(member)
            } catch (_: Refusal) {
                null
            }
            if (init == null || init.toString() == "null") return@associateWith null
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
            val form = formOf(p.field, owner)
            val present = present(p.member.type)
            if (!operand.nullable) {
                encode.add(writeForm(form, present, operand.expr, at, owner))
            } else {
                val o = fresh("present")
                val write = writeForm(form, present, o, at, owner)
                if (!write.isEmpty()) encode.beginControlFlow("%L?.let { %L ->", operand.expr, o).add(write).endControlFlow()
            }
        }
        val verify = CodeBlock.builder()
        val decode = CodeBlock.builder().add("return %T(\n", type).indent()
        for (p in placed) {
            val member = p.member ?: continue
            val form = formOf(p.field, owner)
            val off = p.field.offset.toString()
            val presence = presence(head, p.field, owner)
            if (presence == null) {
                verify.add(verifyForm(form, member.type, off, owner))
                decode.add("%L = %L,\n", escaped(member.name.property), decodeForm(form, member.type, off))
                continue
            }
            // An absent optional's bytes are zero; a present one is checked as any value.
            val inner = verifyForm(form, present(member.type), off, owner)
            val byte = fresh("byte")
            val nonZero = CodeBlock.of(
                "region.bytesAt(%L, %L).any { %L -> %L.toInt() != 0 }", off, p.field.size, byte, byte,
            )
            val refused = CodeBlock.of("throw %T.Contract(%T(%S, %T.Variant))", PB_VERIFY_ERROR, PB_VIOLATION, owner, PB_RULE)
            if (inner.isEmpty()) {
                verify.beginControlFlow("if (!(%L) && %L)", presence, nonZero).addStatement("%L", refused).endControlFlow()
            } else {
                verify.beginControlFlow("if (%L)", presence).add(inner)
                    .nextControlFlow("else if (%L)", nonZero).addStatement("%L", refused).endControlFlow()
            }
            decode.add(
                "%L = if (%L) %L else null,\n",
                escaped(member.name.property), presence, decodeForm(form, present(member.type), off),
            )
        }
        decode.unindent().add(")\n")
        helpers(PodbufFunctions(pkg, name), type, encode.build(), verify.build(), decode.build())
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

    /** The condition that an optional [field]'s flag bit is set, or `null` for a required field. */
    private fun presence(head: PodbufHead, field: PodbufField, owner: String): CodeBlock? {
        if (!field.hasOptional()) return null
        val bit = field.optional
        val flags = head.fieldsList.firstOrNull { it.source.kindCase == PodbufSource.KindCase.FLAGS && it.source.flags == bit.flags }
            ?: refuse("the podbuf head of `$owner` has no flags byte ${bit.flags}")
        return CodeBlock.of("(region.u8At(%L) and %L) != 0", flags.offset, 1 shl bit.bit)
    }

    /**
     * The codec of a named scalar, an enum or an enum set carried as a
     * payload root: the value alone at its offset of its one-field head
     * (`Model.podbuf.boxes`).
     */
    private fun box(boxed: PodbufBox) {
        val declaration = model.declarationsList.getOrNull(boxed.declaration) ?: return
        if (!boxed.hasHead()) return
        val head = boxed.head
        val owner = declaration.name.declared
        val name = declaration.name.camel
        val emitted = guarded(owner) {
            val field = head.fieldsList.firstOrNull() ?: refuse("the podbuf box of `$owner` has no field")
            val form = formOf(field, owner)
            val ref = TypeRef.newBuilder().setReference(owner).setResolved(true).setPackage(model.scope.`package`)
                .setForeign(false).setIndex(boxed.declaration)
            val type = Type.newBuilder().setNamed(ref).build()
            val off = field.offset.toString()
            helpers(
                PodbufFunctions(pkg, name),
                ClassName(pkg, name),
                writeForm(form, type, "value", position("at", field.offset.toLong()), owner),
                verifyForm(form, type, off, owner),
                CodeBlock.of("return %L\n", decodeForm(form, type, off)),
            )
        }
        val bound = declaredBound(declaration, head = false)?.takeIf { emitted } ?: return
        codec(name, declaration.visibility, "declaration `$owner`", head.size, bound)
    }

    // -- forms ----------------------------------------------------------------------

    private fun formOf(field: PodbufField, owner: String): PodbufForm =
        if (field.hasForm()) field.form else refuse("a podbuf field of `$owner` carries no form")

    /** [type] with its `optional` cleared: the type of a present value. */
    private fun present(type: Type): Type = if (type.optional) type.toBuilder().setOptional(false).build() else type

    /** The size in bytes of a value of [form], which is an inline array's stride. */
    private fun formSize(form: PodbufForm): Long = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN -> 1
        PodbufForm.KindCase.INT -> wires.intPrim(form.int.width).width.toLong()
        PodbufForm.KindCase.FLOAT -> floatPrim(form.float).width.toLong()
        PodbufForm.KindCase.ENUM -> wires.intPrim(form.enum.width).width.toLong()
        PodbufForm.KindCase.ENUM_SET -> wires.intPrim(form.enumSet.width).width.toLong()
        PodbufForm.KindCase.INLINE_HEAD -> headSize(form.inlineHead).toLong()
        PodbufForm.KindCase.INLINE_ARRAY -> formSize(form.inlineArray.element) * form.inlineArray.count
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> refuse(TAIL_FORMS)
        else -> refuse("a podbuf field carries no form")
    }

    private fun headSize(index: Int): Int = model.podbuf.headsList.getOrNull(index)?.size ?: 0

    private fun floatPrim(width: FloatWidth): Prim = when (width) {
        FloatWidth.FLOAT_WIDTH_F32 -> Prim.F32
        FloatWidth.FLOAT_WIDTH_F64 -> Prim.F64
        else -> refuse("a podbuf float carries no width")
    }

    /** The primitive a scalar, enum or enum set [form] is loaded and stored as. */
    private fun scalarPrim(form: PodbufForm): Prim = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN -> Prim.Bool
        PodbufForm.KindCase.INT -> wires.intPrim(form.int.width)
        PodbufForm.KindCase.FLOAT -> floatPrim(form.float)
        PodbufForm.KindCase.ENUM -> wires.intPrim(form.enum.width)
        PodbufForm.KindCase.ENUM_SET -> wires.intPrim(form.enumSet.width)
        else -> error("not a scalar form")
    }

    /** What a scalar type position holds in Kotlin. */
    private fun domainOf(type: Type): Domain = when (val wire = wires.of(type)) {
        is Wire.ScalarWire -> wire.domain
        is Wire.Bytes -> wire.domain
        is Wire.Text -> wire.domain
        else -> refuse("a podbuf scalar is written from no scalar type")
    }

    /** The helpers of the struct, union or tuple [type] names, in the package that declares it. */
    private fun headFunctions(type: Type): PodbufFunctions = when (type.kindCase) {
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

    /** The statements that write a present value [expr] of [form] at [at], from the type position [type]. */
    private fun writeForm(form: PodbufForm, type: Type, expr: String, at: String, owner: String): CodeBlock = when (form.kindCase) {
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
                    .addStatement(
                        "throw %T.Contract(%T(%S, %T.Range))",
                        PB_ENCODE_ERROR, PB_VIOLATION, (domain as Domain.EnumOf).declared, PB_RULE,
                    )
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
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> refuse(TAIL_FORMS)
        else -> refuse("a podbuf field carries no form")
    }

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
            .addStatement("throw %T.Contract(%T(%S, %T.Length))", PB_ENCODE_ERROR, PB_VIOLATION, checked, PB_RULE)
            .endControlFlow()
            .addStatement("w.putBytes(%L, %L)", at, bytes)
            .build()
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

    /** [base], a `Long` or `Double`, as the argument of `Writer.put<prim>`: Rust's `as`, which truncates. */
    private fun narrowed(prim: Prim, base: String): String = when (prim) {
        Prim.U8, Prim.U16, Prim.I32 -> "$base.toInt()"
        Prim.I8 -> "$base.toByte()"
        Prim.I16 -> "$base.toShort()"
        Prim.F32 -> "$base.toFloat()"
        Prim.Bool, Prim.U32, Prim.U64, Prim.I64, Prim.F64 -> base
    }

    // -- verifying ----------------------------------------------------------------------

    /**
     * The checks of a value of [form] at offset [off] of the head's `region`,
     * in the Rust codec's order: a boolean above 1 is `Range`; a strict
     * enum's undeclared value or enum set's undeclared bit is `Variant`, an
     * open one is unchecked; a typl constraint is checked as the value
     * object checks it; a nested head by its own helper.
     */
    private fun verifyForm(form: PodbufForm, type: Type, off: String, owner: String): CodeBlock = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN -> CodeBlock.builder()
            .beginControlFlow("if (region.u8At(%L) > 1)", off)
            .addStatement("throw %T.Contract(%T(%S, %T.Range))", PB_VERIFY_ERROR, PB_VIOLATION, owner, PB_RULE)
            .endControlFlow().build()
        PodbufForm.KindCase.INT, PodbufForm.KindCase.FLOAT, PodbufForm.KindCase.ENUM, PodbufForm.KindCase.ENUM_SET -> {
            val raw = fresh("raw")
            val check = checkDomain(domainOf(type), raw, owner, ::pattern)
            if (check.isEmpty()) check else CodeBlock.builder().addStatement("val %L = %L", raw, load(form, off)).add(check).build()
        }
        PodbufForm.KindCase.INLINE_HEAD -> if (headSize(form.inlineHead) == 0) {
            CodeBlock.of("")
        } else {
            val (at, size) = inlinePlace(off)
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
                val inner = verifyForm(array.element, type.array.element, o, owner)
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
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> refuse(TAIL_FORMS)
        else -> refuse("a podbuf field carries no form")
    }

    /** Registers an inline string scalar's pattern and names its compiled `Regex`. */
    private fun pattern(scalar: ridl.codegen.v1.ModelOuterClass.Scalar): String {
        patterns += javaPattern(scalar.constraint.pattern)
        return "PODBUF_PATTERN_${patterns.size - 1}"
    }

    /** The load of the scalar [form] at [off] of `region`, widened to the domain's `Long`, `Double` or `Boolean`. */
    private fun load(form: PodbufForm, off: String): String {
        val prim = scalarPrim(form)
        if (prim == Prim.Bool) return "(region.u8At($off) != 0)"
        return prim.widen("region.${prim.read}At($off)")
    }

    /**
     * The `at` and `size` of a head inline at [off] of this one: the bytes
     * past the producer's size are the parent's, so its size is what is left.
     */
    private fun inlinePlace(off: String): Pair<String, String> =
        if (off == "0") "at" to "size" else "at + $off" to "maxOf(size - $off, 0)"

    // -- decoding -------------------------------------------------------------------------

    /** The value of [form] at offset [off] of the head's `region`, which `verify` passed. */
    private fun decodeForm(form: PodbufForm, type: Type, off: String): CodeBlock = when (form.kindCase) {
        PodbufForm.KindCase.BOOLEAN, PodbufForm.KindCase.INT, PodbufForm.KindCase.FLOAT,
        PodbufForm.KindCase.ENUM, PodbufForm.KindCase.ENUM_SET,
        -> construct(domainOf(type), CodeBlock.of("%L", load(form, off)))
        PodbufForm.KindCase.INLINE_HEAD -> {
            val (at, size) = inlinePlace(off)
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
                    decodeForm(array.element, type.array.element, o),
                )
            } else {
                construct(domainOf(type), CodeBlock.of("region.bytesAt(%L, %L)", off, array.count))
            }
        }
        PodbufForm.KindCase.TAIL_DESCRIPTOR -> refuse(TAIL_FORMS)
        else -> refuse("a podbuf field carries no form")
    }
}

/** `base + off`, or `base` alone at offset 0. */
private fun position(base: String, off: Long): String = if (off == 0L) base else "$base + $off"

/** `i * stride`, or `i` alone for a stride of 1. */
private fun scaled(i: String, stride: Long): String = if (stride == 1L) i else "$i * $stride"

/** The offset of element [i] of an inline array at [off]: `off + i * stride`. */
private fun indexed(off: String, i: String, stride: Long): String = if (off == "0") scaled(i, stride) else "$off + ${scaled(i, stride)}"

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
