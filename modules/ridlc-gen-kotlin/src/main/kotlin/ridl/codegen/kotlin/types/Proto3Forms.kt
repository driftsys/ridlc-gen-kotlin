package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Proto3Message
import ridl.codegen.v1.ModelOuterClass.Proto3MessageRef
import ridl.codegen.v1.ModelOuterClass.Proto3ScalarType
import ridl.codegen.v1.ModelOuterClass.Proto3Wire
import ridl.codegen.v1.ModelOuterClass.Proto3WireType
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Type

private const val P3 = "ridl.rt.proto3"
internal val P3_WRITER = ClassName(P3, "Writer")
private val P3_READER = ClassName(P3, "Reader")
private val P3_VALUE = ClassName(P3, "Value")
private val P3_WIRE_TYPE = ClassName(P3, "WireType")
private val P3_VARINT_LEN = MemberName(P3, "varintLen")
private val P3_READ_VARINT = MemberName(P3, "readVarint")
private val P3_UTF8 = MemberName(P3, "utf8")
private val P3_BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
private val P3_BYTE_ORDER = ClassName("java.nio", "ByteOrder")
private val LINKED_HASH_MAP = ClassName("kotlin.collections", "LinkedHashMap")

private fun p3(name: String) = MemberName(P3, name)

/** The four helpers of one proto3 message, in the package that declares it. */
internal data class Proto3Functions(val pkg: String, val suffix: String) {
    val len get() = MemberName(pkg, "proto3Len$suffix")
    val encode get() = MemberName(pkg, "proto3Encode$suffix")
    val verify get() = MemberName(pkg, "proto3Verify$suffix")
    val decode get() = MemberName(pkg, "proto3Decode$suffix")
}

/**
 * A protobuf wire type, by the name `ridl.rt.proto3.WireType` and `Value`
 * give it. [width] is a fixed-width value's size in bytes.
 */
internal enum class P3WireType(val width: Int) { Varint(0), I64(8), Len(0), I32(4) }

/**
 * The wire of one type position, read from `Model.proto3` beside the model
 * type that gives its Kotlin domain. An enum is a [Num] of `INT64`, as the
 * Rust codec carries it (`codec_proto3.rs` `Repr::Enum`): its int32 is the
 * same varint sign-extended, read back through `narrowI32`.
 */
internal sealed interface P3Wire {
    /** `Proto3Wire.default_is_legal`: an absent value is its default, and a writer may omit the default. */
    val legal: Boolean

    data class Num(val scalar: Proto3ScalarType, val wire: P3WireType, val domain: Domain, override val legal: Boolean) : P3Wire

    data class Text(val domain: Domain, override val legal: Boolean) : P3Wire

    data class Bytes(val domain: Domain, override val legal: Boolean) : P3Wire

    data class Msg(val functions: Proto3Functions, override val legal: Boolean) : P3Wire

    data class Repeated(
        val element: P3Wire,
        val elementType: Type,
        val packed: Boolean,
        val min: Long,
        val max: Long,
        override val legal: Boolean,
    ) : P3Wire

    data class Keyed(
        val key: P3Wire,
        val keyType: Type,
        val value: P3Wire,
        val valueType: Type,
        val min: Long,
        val max: Long,
        override val legal: Boolean,
    ) : P3Wire
}

/**
 * One field of a message: its number, its wire, the model type of its
 * position, and how the value being encoded reaches it — [expr] over `value`.
 */
internal class P3Field(val number: Int, val wire: P3Wire, val type: Type, val optional: Boolean, val expr: String)

/** The pass of the two over a message's fields that a statement serves: counting its bytes, or writing them. */
internal enum class P3Pass { Len, Encode }

/** Where a value is written, which decides whether its default is omitted (`codec_proto3.rs` `Site`). */
internal enum class P3Site { Field, Entry, Element }

/** The file-private helpers a generated `Proto3Codec.kt` declares only when its code calls them. */
private enum class P3Helper { Empty, Bytes, U32, S32, I32 }

/**
 * The count, the write, the checks and the read of each proto3 wire, as
 * `codec_proto3.rs` emits them, for [Proto3CodecEmitter]. Every field number,
 * wire type, packing and default rule is read from the projection; the Kotlin
 * domain of a position, its typl constraints and its collection bounds are
 * read from the model type.
 */
internal class Proto3Forms(private val model: Model, private val pkg: String) {
    private val wires = Wires(model, pkg)

    /** The bodies of the inline string patterns the checks compile, in registration order. */
    val patterns = mutableListOf<String>()

    private var names = 0
    private val helpers = sortedSetOf<P3Helper>()

    fun fresh(stem: String): String = "$stem${names++}"

    // -- the projection --------------------------------------------------------------

    /**
     * [wire], the projection's wire of a position whose model type is [type].
     * `Proto3WireType` values are not the protobuf numbers, so each is mapped
     * by name, never shifted into a tag.
     */
    fun resolve(wire: Proto3Wire, type: Type, owner: String): P3Wire {
        val legal = wire.defaultIsLegal
        return when (wire.kindCase) {
            Proto3Wire.KindCase.SCALAR -> {
                val scalar = wire.scalar.type
                P3Wire.Num(scalar, coherent(scalar, wireType(wire.scalar.wireType, owner), owner), scalarDomain(type, owner), legal)
            }
            Proto3Wire.KindCase.ENUMERATION -> {
                val domain = scalarDomain(type, owner)
                if (domain !is Domain.EnumOf) refuse("the proto3 enum of `$owner` is written from no enum type")
                P3Wire.Num(Proto3ScalarType.PROTO3_SCALAR_TYPE_INT64, P3WireType.Varint, domain, legal)
            }
            Proto3Wire.KindCase.TEXT -> P3Wire.Text(sequenceDomain(type, text = true, owner), legal)
            Proto3Wire.KindCase.BYTES -> P3Wire.Bytes(sequenceDomain(type, text = false, owner), legal)
            Proto3Wire.KindCase.MESSAGE -> P3Wire.Msg(functionsOf(wire.message, owner), legal)
            Proto3Wire.KindCase.ARRAY -> {
                if (type.kindCase != Type.KindCase.ARRAY || !type.array.hasElement()) {
                    refuse("the proto3 array of `$owner` is written from no array type")
                }
                val element = type.array.element
                val resolved = resolve(wire.array.element, element, owner)
                P3Wire.Repeated(resolved, element, wire.array.packed, type.array.min, type.array.max, legal)
            }
            Proto3Wire.KindCase.MAP -> {
                if (type.kindCase != Type.KindCase.MAP || !type.map.hasKey() || !type.map.hasValue()) {
                    refuse("the proto3 map of `$owner` is written from no map type")
                }
                val map = type.map
                val key = resolve(wire.map.key, map.key, owner)
                P3Wire.Keyed(key, map.key, resolve(wire.map.value, map.value, owner), map.value, map.min, map.max, legal)
            }
            else -> refuse("a proto3 field of `$owner` carries no wire")
        }
    }

    private fun wireType(type: Proto3WireType, owner: String): P3WireType = when (type) {
        Proto3WireType.PROTO3_WIRE_TYPE_VARINT -> P3WireType.Varint
        Proto3WireType.PROTO3_WIRE_TYPE_I64 -> P3WireType.I64
        Proto3WireType.PROTO3_WIRE_TYPE_LEN -> P3WireType.Len
        Proto3WireType.PROTO3_WIRE_TYPE_I32 -> P3WireType.I32
        else -> refuse("a proto3 scalar of `$owner` carries no wire type")
    }

    /** [wire], when it is the one protobuf gives [scalar]: the emitted conversions are written for that pair. */
    private fun coherent(scalar: Proto3ScalarType, wire: P3WireType, owner: String): P3WireType {
        val expected = when (scalar) {
            Proto3ScalarType.PROTO3_SCALAR_TYPE_FLOAT -> P3WireType.I32
            Proto3ScalarType.PROTO3_SCALAR_TYPE_DOUBLE -> P3WireType.I64
            Proto3ScalarType.PROTO3_SCALAR_TYPE_UNSPECIFIED, Proto3ScalarType.UNRECOGNIZED ->
                refuse("a proto3 scalar of `$owner` carries no type")
            else -> P3WireType.Varint
        }
        if (wire != expected) refuse("the proto3 projection gives a `$scalar` of `$owner` the wire type $wire")
        return wire
    }

    private fun scalarDomain(type: Type, owner: String): Domain = when (val wire = wires.of(type)) {
        is Wire.ScalarWire -> wire.domain
        else -> refuse("the proto3 scalar of `$owner` is written from no scalar type")
    }

    private fun sequenceDomain(type: Type, text: Boolean, owner: String): Domain = when (val wire = wires.of(type)) {
        is Wire.Text -> wire.domain.takeIf { text }
        is Wire.Bytes -> wire.domain.takeIf { !text }
        else -> null
    } ?: refuse("the proto3 ${if (text) "string" else "bytes"} of `$owner` is written from no ${if (text) "string" else "bytes"} type")

    /** The helpers of the message [ref] names: one of this package's projection, or a foreign struct or union's. */
    private fun functionsOf(ref: Proto3MessageRef, owner: String): Proto3Functions = when (ref.targetCase) {
        Proto3MessageRef.TargetCase.MESSAGE -> {
            val message = model.proto3.messagesList.getOrNull(ref.message)
                ?: refuse("a proto3 field of `$owner` names a message the projection does not carry")
            val suffix = when (message.sourceCase) {
                Proto3Message.SourceCase.STRUCT_DECLARATION -> model.getDeclarations(message.structDeclaration).name.camel
                Proto3Message.SourceCase.UNION_DECLARATION -> model.getDeclarations(message.unionDeclaration).name.camel
                Proto3Message.SourceCase.TUPLE -> tupleName(message.tuple)
                else -> refuse("a proto3 field of `$owner` names a message that is no struct, union or tuple")
            }
            Proto3Functions(pkg, suffix)
        }
        // A foreign message is laid out in its own package's projection, and written by that package's helpers.
        Proto3MessageRef.TargetCase.FOREIGN -> {
            val foreign = model.foreignList.getOrNull(ref.foreign)
                ?: refuse("a proto3 field of `$owner` names a foreign declaration the model does not carry")
            Proto3Functions(foreign.`package`, foreign.declaration.name.camel)
        }
        else -> refuse("a proto3 message reference of `$owner` names no message")
    }

    /** The Kotlin type of a position [type], for `decode`'s locals. */
    fun typeName(type: Type): TypeName {
        val base: TypeName = when (type.kindCase) {
            Type.KindCase.NAMED -> wires.declarationOf(type.named).let { (owner, declaration) -> ClassName(owner, declaration.name.camel) }
            Type.KindCase.PRIMITIVE -> when (type.primitive) {
                PrimitiveType.PRIMITIVE_TYPE_BOOLEAN -> BOOLEAN
                PrimitiveType.PRIMITIVE_TYPE_INTEGER -> LONG
                PrimitiveType.PRIMITIVE_TYPE_FLOAT -> DOUBLE
                PrimitiveType.PRIMITIVE_TYPE_STRING -> STRING
                PrimitiveType.PRIMITIVE_TYPE_BYTES -> BYTE_ARRAY
                else -> refuse("a primitive type is unspecified")
            }
            Type.KindCase.INLINE -> when (type.inline.class_) {
                ScalarClass.SCALAR_CLASS_INTEGER -> LONG
                ScalarClass.SCALAR_CLASS_BOOLEAN -> BOOLEAN
                ScalarClass.SCALAR_CLASS_STRING -> STRING
                ScalarClass.SCALAR_CLASS_BYTES -> BYTE_ARRAY
                else -> DOUBLE
            }
            Type.KindCase.TUPLE -> ClassName(pkg, tupleName(type.tuple.index))
            Type.KindCase.ARRAY -> LIST.parameterizedBy(typeName(type.array.element))
            Type.KindCase.MAP -> MAP.parameterizedBy(typeName(type.map.key), typeName(type.map.value))
            else -> refuse("a proto3 position carries no type")
        }
        return if (type.optional) base.copy(nullable = true) else base
    }

    private fun tupleName(index: Int): String = model.getTuples(index).name.rust.ifEmpty { refuse("a tuple has no induced name") }

    // -- the file's own helpers ----------------------------------------------------------

    private fun empty(): String {
        helpers += P3Helper.Empty
        return "PROTO3_EMPTY"
    }

    private fun bytesOf(buffer: String): String {
        helpers += P3Helper.Bytes
        return "proto3Bytes($buffer)"
    }

    /** The private helpers the file's code called. */
    fun privateHelpers(): Pair<List<PropertySpec>, List<FunSpec>> {
        val properties = listOfNotNull(
            PropertySpec.builder("PROTO3_EMPTY", P3_BYTE_BUFFER, KModifier.PRIVATE)
                .addKdoc("The bytes of an absent message, which is the message decoded from zero bytes.")
                .initializer("%T.allocate(0).asReadOnlyBuffer()", P3_BYTE_BUFFER).build().takeIf { P3Helper.Empty in helpers },
        )
        val functions = helpers.mapNotNull { helper ->
            when (helper) {
                P3Helper.Empty -> null
                P3Helper.Bytes -> FunSpec.builder("proto3Bytes").addModifiers(KModifier.PRIVATE)
                    .addKdoc("A copy of the bytes from [buffer]'s position to its limit, leaving its position.")
                    .addParameter("buffer", P3_BYTE_BUFFER).returns(BYTE_ARRAY)
                    .addStatement("val bytes = ByteArray(buffer.remaining())")
                    .addStatement("buffer.duplicate().get(bytes)")
                    .addStatement("return bytes").build()
                // `decode` is total: a read `verify` refuses is discharged with 0, as Rust's `unwrap_or(0)`.
                P3Helper.U32 -> FunSpec.builder("proto3U32").addModifiers(KModifier.PRIVATE)
                    .addParameter("raw", U_LONG).returns(LONG)
                    .addStatement("return if (raw <= 0xFFFF_FFFFuL) raw.toLong() else 0L").build()
                P3Helper.S32 -> FunSpec.builder("proto3S32").addModifiers(KModifier.PRIVATE)
                    .addParameter("raw", U_LONG).returns(LONG)
                    .addStatement("return if (raw <= 0xFFFF_FFFFuL) %M(raw.toUInt()).toLong() else 0L", p3("unzigzag32")).build()
                P3Helper.I32 -> FunSpec.builder("proto3I32").addModifiers(KModifier.PRIVATE)
                    .addParameter("raw", U_LONG).returns(LONG)
                    .addStatement("val value = raw.toLong()")
                    .addStatement("return if (value in Int.MIN_VALUE..Int.MAX_VALUE) value else 0L").build()
            }
        }
        return properties to functions
    }

    // -- the scalars ----------------------------------------------------------------------

    /** The language value (`Long`, `Double`, `Boolean`) of the domain value [expr]: an enum set's every bit. */
    private fun language(domain: Domain, expr: String): String = if (domain is Domain.SetOf) "$expr.bits" else base(domain, expr)

    /**
     * The value the writer puts after the tag, from the language value [s]:
     * a varint's or an `I64`'s `ULong`, an `I32`'s `UInt`. Zero is the proto3
     * default for every scalar, and a float is its bits, so `-0.0` is written.
     */
    private fun raw(wire: P3Wire.Num, s: String): CodeBlock = when (wire.scalar) {
        Proto3ScalarType.PROTO3_SCALAR_TYPE_BOOL -> CodeBlock.of("(if (%L) 1uL else 0uL)", s)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_SINT32 -> CodeBlock.of("%M(%L.toInt()).toULong()", p3("zigzag32"), s)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_SINT64 -> CodeBlock.of("%M(%L)", p3("zigzag64"), s)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_FLOAT -> CodeBlock.of("%L.toFloat().toRawBits().toUInt()", s)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_DOUBLE -> CodeBlock.of("%L.toRawBits().toULong()", s)
        else -> CodeBlock.of("%L.toULong()", s)
    }

    private fun zero(wire: P3Wire.Num): String = if (wire.wire == P3WireType.I32) "0u" else "0uL"

    /** The bytes the value [r] takes after the tag. */
    private fun valueLen(wire: P3Wire.Num, r: String): CodeBlock = when (wire.wire) {
        P3WireType.Varint -> CodeBlock.of("%M(%L)", P3_VARINT_LEN, r)
        else -> CodeBlock.of("%L", wire.wire.width)
    }

    private fun write(wire: P3Wire.Num, r: String): CodeBlock = when (wire.wire) {
        P3WireType.Varint -> CodeBlock.of("w.varint(%L)\n", r)
        P3WireType.I32 -> CodeBlock.of("w.fixed32(%L)\n", r)
        else -> CodeBlock.of("w.fixed64(%L)\n", r)
    }

    /**
     * The language value read from the raw wire value [raw], and whether the
     * read refuses a varint too wide for its scalar with `Malformed.Varint`.
     */
    private fun widen(wire: P3Wire.Num, raw: String): Pair<CodeBlock, CodeBlock?> = when (wire.scalar) {
        Proto3ScalarType.PROTO3_SCALAR_TYPE_BOOL -> CodeBlock.of("(%L != 0uL)", raw) to null
        Proto3ScalarType.PROTO3_SCALAR_TYPE_UINT32 -> narrowing("narrowU32", raw)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_SINT32 -> narrowing("unzigzag32Checked", raw)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_SINT64 -> CodeBlock.of("%M(%L)", p3("unzigzag64"), raw) to null
        Proto3ScalarType.PROTO3_SCALAR_TYPE_INT64 -> narrowing("narrowI32", raw)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_FLOAT -> CodeBlock.of("Float.fromBits(%L.toInt()).toDouble()", raw) to null
        Proto3ScalarType.PROTO3_SCALAR_TYPE_DOUBLE -> CodeBlock.of("Double.fromBits(%L.toLong())", raw) to null
        else -> CodeBlock.of("%L.toLong()", raw) to null
    }

    /** The read of [raw] through the runtime's [function], which refuses a varint too wide, and that read as a statement. */
    private fun narrowing(function: String, raw: String): Pair<CodeBlock, CodeBlock> =
        CodeBlock.of("%M(%L).toLong()", p3(function), raw) to CodeBlock.of("%M(%L)\n", p3(function), raw)

    /** The language value `decode` reads from [raw], which cannot fail: a read `verify` refuses is discharged with 0. */
    private fun widenTotal(wire: P3Wire.Num, raw: String): CodeBlock = when (wire.scalar) {
        Proto3ScalarType.PROTO3_SCALAR_TYPE_UINT32 -> helper(P3Helper.U32, "proto3U32", raw)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_SINT32 -> helper(P3Helper.S32, "proto3S32", raw)
        Proto3ScalarType.PROTO3_SCALAR_TYPE_INT64 -> helper(P3Helper.I32, "proto3I32", raw)
        else -> widen(wire, raw).first
    }

    private fun helper(helper: P3Helper, name: String, raw: String): CodeBlock {
        helpers += helper
        return CodeBlock.of("%L(%L)", name, raw)
    }

    /** The language zero: what an absent value or a key an entry omits is. */
    private fun languageZero(wire: P3Wire.Num): String = when (wire.scalar) {
        Proto3ScalarType.PROTO3_SCALAR_TYPE_BOOL -> "false"
        Proto3ScalarType.PROTO3_SCALAR_TYPE_FLOAT, Proto3ScalarType.PROTO3_SCALAR_TYPE_DOUBLE -> "0.0"
        else -> "0L"
    }

    /**
     * The `encode` check of an open enum's integer outside the int32 proto3
     * carries it as, or an open enum set's outside the 32-bit scalar its
     * width maps to, as the Rust `encode_check` refuses it: such a value
     * comes from another encoding or from `fromValue`, never from this wire.
     */
    private fun encodeCheck(wire: P3Wire.Num, s: String): CodeBlock {
        val domain = wire.domain
        val (declared, range) = when {
            domain is Domain.EnumOf && domain.open -> domain.declared to (Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            domain is Domain.SetOf && domain.open && wire.scalar == Proto3ScalarType.PROTO3_SCALAR_TYPE_UINT32 ->
                domain.declared to (0L..0xFFFF_FFFFL)
            domain is Domain.SetOf && domain.open && wire.scalar == Proto3ScalarType.PROTO3_SCALAR_TYPE_SINT32 ->
                domain.declared to (Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            else -> return CodeBlock.of("")
        }
        return CodeBlock.builder().beginControlFlow("if (%L !in %LL..%LL)", s, range.first, range.last)
            .add(encodeRefusal(declared, RuleName.Range)).endControlFlow().build()
    }

    // -- len and encode ----------------------------------------------------------------------

    /**
     * The statements that count ([P3Pass.Len], into the `Long` [acc]) or write
     * ([P3Pass.Encode], to `w`) the present value [expr] of [wire] as field
     * [number] at [site], for the message [owner] names.
     */
    fun put(pass: P3Pass, number: Int, wire: P3Wire, expr: String, site: P3Site, acc: String, owner: String): CodeBlock {
        // At a field, an implicit-presence value is omitted at its default only when that default is legal, since it
        // otherwise never holds it; inside a map entry it is always omitted; an element or an arm is always written.
        val test = when (site) {
            P3Site.Field -> wire.legal
            P3Site.Entry -> true
            P3Site.Element -> false
        }
        return when (wire) {
            is P3Wire.Num -> scalarPut(pass, number, wire, expr, test, acc)
            is P3Wire.Text -> sequencePut(pass, number, wire.domain, text = true, expr, test, acc, owner)
            is P3Wire.Bytes -> sequencePut(pass, number, wire.domain, text = false, expr, test, acc, owner)
            is P3Wire.Msg -> messagePut(pass, number, wire, expr, site == P3Site.Entry, acc)
            is P3Wire.Repeated -> arrayPut(pass, number, wire, expr, acc, owner)
            is P3Wire.Keyed -> mapPut(pass, number, wire, expr, acc, owner)
        }
    }

    private fun tag(number: Int, wire: P3WireType): CodeBlock = CodeBlock.of("w.tag(%Lu, %T.%L)\n", number, P3_WIRE_TYPE, wire.name)

    /** `acc += <tag> + <length varint> + <length>`, the count of one length-delimited record of [length] bytes. */
    private fun record(acc: String, number: Int, length: String): CodeBlock =
        CodeBlock.of("%L += %L + %M(%L.toULong()) + %L\n", acc, tagLen(number), P3_VARINT_LEN, length, length)

    private fun scalarPut(pass: P3Pass, number: Int, wire: P3Wire.Num, expr: String, test: Boolean, acc: String): CodeBlock {
        val s = fresh("s")
        val r = fresh("r")
        val action = when (pass) {
            P3Pass.Len -> CodeBlock.of("%L += %L + %L\n", acc, tagLen(number), valueLen(wire, r))
            P3Pass.Encode -> CodeBlock.builder().add(encodeCheck(wire, s)).add(tag(number, wire.wire)).add(write(wire, r)).build()
        }
        val code = CodeBlock.builder().addStatement("val %L = %L", s, language(wire.domain, expr)).addStatement("val %L = %L", r, raw(wire, s))
        return guardedBy(code, if (test) CodeBlock.of("%L != %L", r, zero(wire)) else null, action)
    }

    /** [code], then [action], under [condition] when there is one. */
    private fun guardedBy(code: CodeBlock.Builder, condition: CodeBlock?, action: CodeBlock): CodeBlock =
        if (condition == null) code.add(action).build() else code.beginControlFlow("if (%L)", condition).add(action).endControlFlow().build()

    /**
     * A string ([text]) or bytes. `encode` refuses a string holding an
     * unpaired surrogate as `Utf8`, which a Rust `str` cannot hold and
     * `encodeToByteArray` would write as `?`, then an anonymous one's length
     * outside its bound, as the Rust `inline_length_check`.
     */
    private fun sequencePut(
        pass: P3Pass,
        number: Int,
        domain: Domain,
        text: Boolean,
        expr: String,
        test: Boolean,
        acc: String,
        owner: String,
    ): CodeBlock {
        val b = fresh("b")
        val code = CodeBlock.builder()
        val length: String
        if (text) {
            val t = fresh("t")
            code.addStatement("val %L = %L", t, base(domain, expr))
            val checked = if (domain is Domain.Named) domain.declared else owner
            if (pass == P3Pass.Encode) code.add(wellFormedCheck(t) { encodeRefusal(checked, it) })
            code.addStatement("val %L = %L.encodeToByteArray()", b, t)
            length = "$t.codePointCount(0, $t.length)"
        } else {
            code.addStatement("val %L = %L", b, base(domain, expr))
            length = "$b.size"
        }
        val action = when (pass) {
            P3Pass.Len -> record(acc, number, "$b.size")
            P3Pass.Encode -> CodeBlock.builder().add(inlineLength(domain, length, owner)).add(tag(number, P3WireType.Len))
                .addStatement("w.varint(%L.size.toULong())", b)
                .addStatement("w.bytes(%T.wrap(%L))", P3_BYTE_BUFFER, b).build()
        }
        return guardedBy(code, if (test) CodeBlock.of("%L.isNotEmpty()", b) else null, action)
    }

    private fun inlineLength(domain: Domain, length: String, owner: String): CodeBlock {
        if (domain !is Domain.Inline) return CodeBlock.of("")
        val c = domain.scalar.constraint
        if (!c.hasLenMin() && !c.hasLenMax()) return CodeBlock.of("")
        return countCheck(length, false, if (c.hasLenMin()) c.lenMin else 0, if (c.hasLenMax()) c.lenMax else -1) {
            encodeRefusal(owner, RuleName.Length)
        }
    }

    /** A nested message: always written at a field, as a record that may be empty; omitted when empty in an [entry]. */
    private fun messagePut(pass: P3Pass, number: Int, wire: P3Wire.Msg, expr: String, entry: Boolean, acc: String): CodeBlock {
        val l = fresh("l")
        val action = when (pass) {
            P3Pass.Len -> record(acc, number, l)
            P3Pass.Encode -> CodeBlock.builder().add(tag(number, P3WireType.Len))
                .addStatement("w.varint(%L.toULong())", l)
                .addStatement("%M(%L, w)", wire.functions.encode, expr).build()
        }
        val code = CodeBlock.builder().addStatement("val %L = %M(%L)", l, wire.functions.len, expr)
        return guardedBy(code, if (entry) CodeBlock.of("%L != 0L", l) else null, action)
    }

    /**
     * An array: its count checked by `encode`; a packed element makes one
     * record, not written when the array is empty, any other element one
     * record each. A fixed-length array is always written, and one of no
     * element writes nothing.
     */
    private fun arrayPut(pass: P3Pass, number: Int, wire: P3Wire.Repeated, expr: String, acc: String, owner: String): CodeBlock {
        val fixed = wire.min == wire.max
        if (fixed && wire.max == 0L) return CodeBlock.of("")
        val c = fresh("c")
        val code = CodeBlock.builder().addStatement("val %L = %L", c, expr)
        if (pass == P3Pass.Encode) code.add(countCheck("$c.size", false, wire.min, wire.max) { encodeRefusal(owner, RuleName.Length) })
        val element = wire.element
        val e = fresh("e")
        if (!wire.packed) {
            return code.beginControlFlow("for (%L in %L)", e, c)
                .add(put(pass, number, element, e, P3Site.Element, acc, owner))
                .endControlFlow().build()
        }
        if (element !is P3Wire.Num) refuse("a packed proto3 array of `$owner` holds no scalar")
        val l = fresh("l")
        val packed = CodeBlock.builder()
        if (element.wire == P3WireType.Varint) {
            val s = fresh("s")
            packed.addStatement("var %L = 0L", l).beginControlFlow("for (%L in %L)", e, c)
                .addStatement("val %L = %L", s, language(element.domain, e))
                .addStatement("%L += %M(%L)", l, P3_VARINT_LEN, raw(element, s))
                .endControlFlow()
        } else {
            packed.addStatement("val %L = %L.size.toLong() * %L", l, c, element.wire.width)
        }
        when (pass) {
            P3Pass.Len -> packed.add(record(acc, number, l))
            P3Pass.Encode -> {
                val s = fresh("s")
                val r = fresh("r")
                packed.add(tag(number, P3WireType.Len)).addStatement("w.varint(%L.toULong())", l)
                    .beginControlFlow("for (%L in %L)", e, c)
                    .addStatement("val %L = %L", s, language(element.domain, e))
                    .add(encodeCheck(element, s))
                    .addStatement("val %L = %L", r, raw(element, s))
                    .add(write(element, r))
                    .endControlFlow()
            }
        }
        return guardedBy(code, if (fixed) null else CodeBlock.of("%L.isNotEmpty()", c), packed.build())
    }

    /**
     * A map: its count checked by `encode`, then one entry message per pair
     * in the map's order, the key at 1 and the value at 2 under the entry
     * rule. The Rust encoder also refuses a key held twice; a Kotlin `Map`
     * holds each key once, by the equality its wire value has, so no pair can.
     */
    private fun mapPut(pass: P3Pass, number: Int, wire: P3Wire.Keyed, expr: String, acc: String, owner: String): CodeBlock {
        val c = fresh("c")
        val k = fresh("k")
        val v = fresh("v")
        val m = fresh("m")
        val code = CodeBlock.builder().addStatement("val %L = %L", c, expr)
        if (pass == P3Pass.Encode) code.add(countCheck("$c.size", false, wire.min, wire.max) { encodeRefusal(owner, RuleName.Length) })
        code.beginControlFlow("for ((%L, %L) in %L)", k, v, c)
            .addStatement("var %L = 0L", m)
            .add(put(P3Pass.Len, 1, wire.key, k, P3Site.Entry, m, owner))
            .add(put(P3Pass.Len, 2, wire.value, v, P3Site.Entry, m, owner))
        when (pass) {
            P3Pass.Len -> code.add(record(acc, number, m))
            P3Pass.Encode -> code.add(tag(number, P3WireType.Len)).addStatement("w.varint(%L.toULong())", m)
                .add(put(P3Pass.Encode, 1, wire.key, k, P3Site.Entry, m, owner))
                .add(put(P3Pass.Encode, 2, wire.value, v, P3Site.Entry, m, owner))
        }
        return code.endControlFlow().build()
    }

    // -- verify ------------------------------------------------------------------------------

    /**
     * The walk of the message bytes [buf] over [fields] (`message_walk`): a
     * known field checked by number, a singular one refused when written
     * twice, a repeated one counted, an unknown one skipped; then the absence
     * rule and the count bounds, field by field.
     */
    fun walk(owner: String, fields: List<P3Field>, buf: String): CodeBlock {
        val reader = fresh("reader")
        val field = fresh("field")
        val code = CodeBlock.builder()
        if (fields.isEmpty()) {
            // No field to match: the walk checks the message's structure and nothing else.
            return code.addStatement("val %L = %T(%L)", reader, P3_READER, buf)
                .beginControlFlow("while (%L.next() != null)", reader).endControlFlow().build()
        }
        val seen = fresh("seen")
        code.addStatement("val %L = BooleanArray(%L)", seen, fields.size)
        val counters = fields.map { f -> if (f.wire is P3Wire.Repeated || f.wire is P3Wire.Keyed) fresh("count") else null }
        counters.filterNotNull().forEach { code.addStatement("var %L = 0", it) }
        code.addStatement("val %L = %T(%L)", reader, P3_READER, buf)
            .beginControlFlow("while (true)")
            .addStatement("val %L = %L.next() ?: break", field, reader)
            .beginControlFlow("when (%L.number)", field)
        fields.forEachIndexed { i, f ->
            code.beginControlFlow("%Lu ->", f.number)
                .add(verifyArm(owner, f, "$seen[$i]", counters[i], "$field.value", buf))
                .endControlFlow()
        }
        code.addStatement("else -> {}").endControlFlow().endControlFlow()
        fields.forEachIndexed { i, f ->
            val absent = verifyAbsent(owner, f)
            if (!absent.isEmpty()) code.beginControlFlow("if (!%L[%L])", seen, i).add(absent).endControlFlow()
            val counter = counters[i]
            val wire = f.wire
            if (counter != null && !f.optional) {
                val (min, max) = if (wire is P3Wire.Repeated) wire.min to wire.max else (wire as P3Wire.Keyed).min to wire.max
                code.add(countCheck(counter, false, min, max) { verifyRefusal(owner, RuleName.Length) })
            }
        }
        return code.build()
    }

    private fun verifyArm(owner: String, f: P3Field, seen: String, counter: String?, value: String, buf: String): CodeBlock {
        val wire = f.wire
        val code = CodeBlock.builder()
        when (wire) {
            is P3Wire.Repeated -> code.addStatement("%L = true", seen).add(elementVerify(owner, wire, counter!!, value))
            is P3Wire.Keyed -> code.addStatement("%L = true", seen).add(entryVerify(owner, f.number, wire, counter!!, value, buf))
            else -> code.beginControlFlow("if (%L)", seen).add(malformed("Duplicate")).endControlFlow()
                .addStatement("%L = true", seen)
                .add(present(owner, wire, value))
        }
        return code.build()
    }

    private fun malformed(variant: String): CodeBlock = CodeBlock.of("throw %T.Structure(%T.%L)\n", PB_VERIFY_ERROR, PB_MALFORMED, variant)

    private fun verifyRefusal(owner: String, rule: RuleName): CodeBlock =
        CodeBlock.of("throw %T.Contract(%T(%S, %T.%L))\n", PB_VERIFY_ERROR, PB_VIOLATION, owner, PB_RULE, rule.name)

    private fun encodeRefusal(owner: String, rule: RuleName): CodeBlock =
        CodeBlock.of("throw %T.Contract(%T(%S, %T.%L))\n", PB_ENCODE_ERROR, PB_VIOLATION, owner, PB_RULE, rule.name)

    /** `Value.<wire>`, the class a field's value of [wire] is read as. */
    private fun valueClass(wire: P3WireType): ClassName = P3_VALUE.nestedClass(wire.name)

    private fun wireOf(wire: P3Wire): P3WireType = if (wire is P3Wire.Num) wire.wire else P3WireType.Len

    /** The checks of one present singular [wire] whose `Value` is [value]: its wire type, then its constraints. */
    private fun present(owner: String, wire: P3Wire, value: String): CodeBlock {
        val type = valueClass(wireOf(wire))
        val v = fresh("v")
        val checks = when (wire) {
            is P3Wire.Num -> numChecks(owner, wire, "$v.value")
            is P3Wire.Text -> {
                val t = fresh("t")
                val check = checkDomain(wire.domain, t, owner, ::pattern)
                if (check.isEmpty()) {
                    CodeBlock.of("%M(%L.bytes)\n", P3_UTF8, v)
                } else {
                    CodeBlock.builder().addStatement("val %L = %M(%L.bytes)", t, P3_UTF8, v).add(check).build()
                }
            }
            is P3Wire.Bytes -> {
                val a = fresh("a")
                val check = checkDomain(wire.domain, a, owner, ::pattern)
                if (check.isEmpty()) check else CodeBlock.builder().addStatement("val %L = %L", a, bytesOf("$v.bytes")).add(check).build()
            }
            is P3Wire.Msg -> CodeBlock.of("%M(%L.bytes)\n", wire.functions.verify, v)
            else -> refuse("a proto3 collection of `$owner` is not a singular value")
        }
        if (checks.isEmpty()) {
            return CodeBlock.builder().beginControlFlow("if (%L !is %T)", value, type).add(malformed("WireType")).endControlFlow().build()
        }
        return CodeBlock.builder().addStatement("val %L = %L", v, value)
            .beginControlFlow("if (%L !is %T)", v, type).add(malformed("WireType")).endControlFlow()
            .add(checks).build()
    }

    /** The checks of one raw scalar value [raw]: the widening, which may refuse a varint too wide, then the constraint. */
    private fun numChecks(owner: String, wire: P3Wire.Num, raw: String): CodeBlock {
        val x = fresh("x")
        // An open enum or enum set admits every value the wire carries.
        val check = checkDomain(wire.domain, x, owner, ::pattern)
        val (widened, fallible) = widen(wire, raw)
        return when {
            !check.isEmpty() -> CodeBlock.builder().addStatement("val %L = %L", x, widened).add(check).build()
            fallible != null -> fallible
            else -> CodeBlock.of("")
        }
    }

    /**
     * One record of a repeated field whose `Value` is [value], counted in
     * [counter]: a packed run, read element by element, or one element.
     */
    private fun elementVerify(owner: String, wire: P3Wire.Repeated, counter: String, value: String): CodeBlock {
        val element = wire.element
        val v = fresh("v")
        val code = CodeBlock.builder().addStatement("val %L = %L", v, value)
        if (!wire.packed || element !is P3Wire.Num) {
            return code.add(present(owner, element, v)).addStatement("%L += 1", counter).build()
        }
        val raw = fresh("raw")
        val checks = numChecks(owner, element, raw)
        code.beginControlFlow("when (%L)", v)
            .beginControlFlow("is %T ->", valueClass(P3WireType.Len)).add(packedRun(element, checks, raw, counter, v)).endControlFlow()
            .beginControlFlow("is %T ->", valueClass(element.wire))
        if (!checks.isEmpty()) code.addStatement("val %L = %L.value", raw, v).add(checks)
        return code.addStatement("%L += 1", counter).endControlFlow()
            .beginControlFlow("else ->").add(malformed("WireType")).endControlFlow()
            .endControlFlow().build()
    }

    /**
     * A packed run of [element]s in the `Value.Len` [v]: a fixed-width run whose
     * length is not a whole number of elements is `OutOfBounds` before any is
     * read; a varint run is read to its end, refusing a truncated last varint.
     */
    private fun packedRun(element: P3Wire.Num, checks: CodeBlock, raw: String, counter: String, v: String): CodeBlock {
        val run = fresh("run")
        val code = CodeBlock.builder()
        if (element.wire == P3WireType.Varint) {
            code.addStatement("val %L = %L.bytes", run, v).beginControlFlow("while (%L.hasRemaining())", run)
            if (checks.isEmpty()) {
                code.addStatement("%M(%L)", P3_READ_VARINT, run)
            } else {
                code.addStatement("val %L = %M(%L)", raw, P3_READ_VARINT, run).add(checks)
            }
            return code.addStatement("%L += 1", counter).endControlFlow().build()
        }
        val width = element.wire.width
        code.addStatement("val %L = %L.bytes.order(%T.LITTLE_ENDIAN)", run, v, P3_BYTE_ORDER)
            .beginControlFlow("if (%L.remaining() %% %L != 0)", run, width).add(malformed("OutOfBounds")).endControlFlow()
        if (checks.isEmpty()) return code.addStatement("%L += %L.remaining() / %L", counter, run, width).build()
        val i = fresh("i")
        return code.beginControlFlow("for (%L in 0 until %L.remaining() / %L)", i, run, width)
            .addStatement("val %L = %L", raw, fixedRead(element, run, "$width * $i"))
            .add(checks)
            .addStatement("%L += 1", counter)
            .endControlFlow().build()
    }

    private fun fixedRead(element: P3Wire.Num, run: String, at: String): String =
        if (element.wire == P3WireType.I32) "$run.getInt($at).toUInt()" else "$run.getLong($at).toULong()"

    /**
     * One map entry whose `Value` is [value], counted in [counter]: the entry's
     * own walk, the count against the declared maximum, then its key against
     * the keys of the entries before it in the message [buf], read again
     * rather than kept, so no index is allocated from an untrusted count.
     */
    private fun entryVerify(owner: String, number: Int, wire: P3Wire.Keyed, counter: String, value: String, buf: String): CodeBlock {
        val v = fresh("v")
        val k = fresh("key")
        val left = fresh("left")
        val prev = fresh("previous")
        val p = fresh("p")
        val pv = fresh("pv")
        val code = CodeBlock.builder().addStatement("val %L = %L", v, value)
            .beginControlFlow("if (%L !is %T)", v, valueClass(P3WireType.Len)).add(malformed("WireType")).endControlFlow()
            .add(walk(owner, entryFields(wire), "$v.bytes"))
        if (wire.max in 0..Int.MAX_VALUE.toLong()) {
            code.beginControlFlow("if (%L >= %L)", counter, wire.max).add(verifyRefusal(owner, RuleName.Length)).endControlFlow()
        }
        val pk = fresh("key")
        return code.add(keyOf(wire.key, "$v.bytes", k))
            .addStatement("var %L = %L", left, counter)
            .addStatement("val %L = %T(%L)", prev, P3_READER, buf)
            .beginControlFlow("while (%L > 0)", left)
            .addStatement("val %L = %L.next() ?: break", p, prev)
            .beginControlFlow("if (%L.number == %Lu)", p, number)
            .addStatement("%L -= 1", left)
            .addStatement("val %L = %L.value", pv, p)
            .beginControlFlow("if (%L is %T)", pv, valueClass(P3WireType.Len))
            .add(keyOf(wire.key, "$pv.bytes", pk))
            .beginControlFlow("if (%L == %L)", pk, k).add(verifyRefusal(owner, RuleName.Unique)).endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .endControlFlow()
            .addStatement("%L += 1", counter).build()
    }

    /** The two fields of a map entry message: the key at 1 and the value at 2, neither optional. */
    fun entryFields(wire: P3Wire.Keyed): List<P3Field> =
        listOf(P3Field(1, wire.key, wire.keyType, false, "key"), P3Field(2, wire.value, wire.valueType, false, "value"))

    /**
     * The statements that read the key of the map entry in [bytes] into the
     * local [kk], as two keys compare: a scalar's language value, a string's
     * bytes, the default when the entry omits it.
     */
    private fun keyOf(key: P3Wire, bytes: String, kk: String): CodeBlock {
        val reader = fresh("reader")
        val field = fresh("field")
        val kv = fresh("v")
        val (init, type, read) = when (key) {
            is P3Wire.Num -> Triple(CodeBlock.of("%L", languageZero(key)), valueClass(key.wire), widenTotal(key, "$kv.value"))
            is P3Wire.Text, is P3Wire.Bytes -> Triple(CodeBlock.of("%L", empty()), valueClass(P3WireType.Len), CodeBlock.of("%L.bytes", kv))
            else -> refuse("a proto3 map key is no scalar and no string")
        }
        return CodeBlock.builder()
            .addStatement("var %L = %L", kk, init)
            .addStatement("val %L = %T(%L)", reader, P3_READER, bytes)
            .beginControlFlow("while (true)")
            .addStatement("val %L = %L.next() ?: break", field, reader)
            .beginControlFlow("if (%L.number == 1u)", field)
            .addStatement("val %L = %L.value", kv, field)
            .addStatement("if (%L is %T) %L = %L", kv, type, kk, read)
            .endControlFlow()
            .endControlFlow()
            .build()
    }

    /**
     * What `verify` does with an absent non-optional field: nothing when its
     * default is legal, `MissingRequired` when not; for a message, the check
     * of the empty message in its place, which a union refuses; for a string
     * whose empty value is legal, its patterns over the empty string, so an
     * absent field reads as a present empty one would.
     */
    private fun verifyAbsent(owner: String, f: P3Field): CodeBlock {
        if (f.optional) return CodeBlock.of("")
        val wire = f.wire
        return when {
            wire is P3Wire.Msg -> CodeBlock.of("%M(%L)\n", wire.functions.verify, empty())
            wire is P3Wire.Text && wire.legal -> {
                val scalar = when (val domain = wire.domain) {
                    is Domain.Named -> domain.scalar
                    is Domain.Inline -> domain.scalar
                    else -> null
                }
                val patterned = scalar != null && scalar.constraint.patterns().isNotEmpty()
                if (patterned) checkDomain(wire.domain, "\"\"", owner, ::pattern) else CodeBlock.of("")
            }
            wire.legal -> CodeBlock.of("")
            else -> malformed("MissingRequired")
        }
    }

    /** The walk of a union's bytes: exactly one arm, written once; no arm is `MissingRequired`. */
    fun unionWalk(owner: String, arms: List<P3Field>): CodeBlock {
        val arm = fresh("arm")
        val reader = fresh("reader")
        val field = fresh("field")
        val code = CodeBlock.builder().addStatement("var %L = false", arm)
            .addStatement("val %L = %T(buf)", reader, P3_READER)
            .beginControlFlow("while (true)")
            .addStatement("val %L = %L.next() ?: break", field, reader)
            .beginControlFlow("when (%L.number)", field)
        for (a in arms) {
            code.beginControlFlow("%Lu ->", a.number)
                .beginControlFlow("if (%L)", arm).add(malformed("Duplicate")).endControlFlow()
                .addStatement("%L = true", arm)
                .add(present(owner, a.wire, "$field.value"))
                .endControlFlow()
        }
        return code.addStatement("else -> {}").endControlFlow().endControlFlow()
            .beginControlFlow("if (!%L)", arm).add(malformed("MissingRequired")).endControlFlow().build()
    }

    /** Registers one clause of an inline string scalar, by its normalised pattern, and names its compiled `Regex`. */
    private fun pattern(body: String): String {
        patterns += javaPattern(body)
        return "PROTO3_PATTERN_${patterns.size - 1}"
    }

    // -- decode ------------------------------------------------------------------------------

    /**
     * The locals of `decode` over [fields], each at its default — `null` for
     * a `?` field — and the walk that reads every known field into its local.
     * The caller adds the value built from the locals.
     */
    fun decodeWalk(fields: List<P3Field>): Pair<List<String>, CodeBlock.Builder> {
        val code = CodeBlock.builder()
        val locals = fields.map { f ->
            val local = fresh("f")
            when {
                f.optional -> code.addStatement("var %L: %T = null", local, typeName(f.type))
                f.wire is P3Wire.Repeated || f.wire is P3Wire.Keyed ->
                    code.addStatement("val %L = %L", local, decodeDefault(f.wire, f.type))
                else -> code.addStatement("var %L: %T = %L", local, typeName(f.type), decodeDefault(f.wire, f.type))
            }
            local
        }
        if (fields.isEmpty()) return locals to code
        val reader = fresh("reader")
        val field = fresh("field")
        code.addStatement("val %L = %T(buf)", reader, P3_READER)
            .beginControlFlow("while (true)")
            .addStatement("val %L = %L.next() ?: break", field, reader)
            .beginControlFlow("when (%L.number)", field)
        fields.forEachIndexed { i, f ->
            code.beginControlFlow("%Lu ->", f.number).add(decodeArm(f, locals[i], "$field.value")).endControlFlow()
        }
        code.addStatement("else -> {}").endControlFlow().endControlFlow()
        return locals to code
    }

    /** `decode` of a union: the first arm at its default until the bytes name an arm, which `verify` required. */
    fun unionDecode(type: ClassName, arms: List<Pair<ClassName, P3Field>>): CodeBlock {
        val out = fresh("out")
        val reader = fresh("reader")
        val field = fresh("field")
        val (firstVariant, first) = arms.first()
        val code = CodeBlock.builder().addStatement("var %L: %T = %T(%L)", out, type, firstVariant, decodeDefault(first.wire, first.type))
            .addStatement("val %L = %T(buf)", reader, P3_READER)
            .beginControlFlow("while (true)")
            .addStatement("val %L = %L.next() ?: break", field, reader)
            .beginControlFlow("when (%L.number)", field)
        for ((variant, arm) in arms) {
            val v = fresh("v")
            code.beginControlFlow("%Lu ->", arm.number).addStatement("val %L = %L.value", v, field)
                .addStatement("if (%L is %T) %L = %T(%L)", v, valueClass(wireOf(arm.wire)), out, variant, decodePresent(arm.wire, v))
                .endControlFlow()
        }
        return code.addStatement("else -> {}").endControlFlow().endControlFlow().addStatement("return %L", out).build()
    }

    /** The domain value an absent non-optional value of [wire] decodes as: its proto3 default in its own type. */
    fun decodeDefault(wire: P3Wire, type: Type): CodeBlock = when (wire) {
        is P3Wire.Num -> when (val domain = wire.domain) {
            is Domain.EnumOf -> if (domain.open) {
                CodeBlock.of("%T.fromValue(0L)", domain.type)
            } else {
                // `verify` refused an absent field of an enum with no zero member; the first stands in where it is unreachable.
                CodeBlock.of("%T.%N", domain.type, enumEntry((domain.zero ?: domain.first!!).name))
            }
            is Domain.SetOf -> CodeBlock.of("%T.EMPTY", domain.type)
            else -> construct(domain, CodeBlock.of("%L", languageZero(wire)))
        }
        is P3Wire.Text -> construct(wire.domain, CodeBlock.of("%S", ""))
        is P3Wire.Bytes -> construct(wire.domain, CodeBlock.of("ByteArray(0)"))
        is P3Wire.Msg -> CodeBlock.of("%M(%L)", wire.functions.decode, empty())
        is P3Wire.Repeated -> CodeBlock.of("%T<%T>()", ARRAY_LIST, typeName(type.array.element))
        is P3Wire.Keyed -> CodeBlock.of("%T<%T, %T>()", LINKED_HASH_MAP, typeName(type.map.key), typeName(type.map.value))
    }

    /** The domain value of one present singular [wire] from the `Value` [v], already of its class. */
    fun decodePresent(wire: P3Wire, v: String): CodeBlock = when (wire) {
        is P3Wire.Num -> construct(wire.domain, widenTotal(wire, "$v.value"))
        is P3Wire.Text -> construct(wire.domain, CodeBlock.of("%M(%L.bytes)", P3_UTF8, v))
        is P3Wire.Bytes -> construct(wire.domain, CodeBlock.of("%L", bytesOf("$v.bytes")))
        is P3Wire.Msg -> CodeBlock.of("%M(%L.bytes)", wire.functions.decode, v)
        else -> refuse("a proto3 collection is read element by element")
    }

    /** The statements that read one known field whose `Value` is [value] into the local [binding]. */
    fun decodeArm(f: P3Field, binding: String, value: String): CodeBlock {
        val v = fresh("v")
        val code = CodeBlock.builder().addStatement("val %L = %L", v, value)
        return when (val wire = f.wire) {
            is P3Wire.Repeated -> {
                val element = wire.element
                if (wire.packed && element is P3Wire.Num) {
                    code.beginControlFlow("when (%L)", v)
                        .beginControlFlow("is %T ->", valueClass(P3WireType.Len)).add(packedDecode(element, binding, v)).endControlFlow()
                        .addStatement("is %T -> %L.add(%L)", valueClass(element.wire), binding, decodePresent(element, v))
                        .addStatement("else -> {}")
                        .endControlFlow().build()
                } else {
                    val read = decodePresent(element, v)
                    code.addStatement("if (%L is %T) %L.add(%L)", v, valueClass(wireOf(element)), binding, read).build()
                }
            }
            is P3Wire.Keyed ->
                code.beginControlFlow("if (%L is %T)", v, valueClass(P3WireType.Len)).add(entryDecode(wire, binding, v)).endControlFlow().build()
            else -> code.addStatement("if (%L is %T) %L = %L", v, valueClass(wireOf(wire)), binding, decodePresent(wire, v)).build()
        }
    }

    private fun packedDecode(element: P3Wire.Num, binding: String, v: String): CodeBlock {
        val run = fresh("run")
        if (element.wire == P3WireType.Varint) {
            val raw = fresh("raw")
            return CodeBlock.builder().addStatement("val %L = %L.bytes", run, v)
                .beginControlFlow("while (%L.hasRemaining())", run)
                .addStatement("val %L = %M(%L)", raw, P3_READ_VARINT, run)
                .addStatement("%L.add(%L)", binding, construct(element.domain, widenTotal(element, raw)))
                .endControlFlow().build()
        }
        val i = fresh("i")
        val width = element.wire.width
        return CodeBlock.builder().addStatement("val %L = %L.bytes.order(%T.LITTLE_ENDIAN)", run, v, P3_BYTE_ORDER)
            .beginControlFlow("for (%L in 0 until %L.remaining() / %L)", i, run, width)
            .addStatement("%L.add(%L)", binding, construct(element.domain, widenTotal(element, fixedRead(element, run, "$width * $i"))))
            .endControlFlow().build()
    }

    private fun entryDecode(wire: P3Wire.Keyed, binding: String, v: String): CodeBlock {
        val k = fresh("k")
        val x = fresh("x")
        val reader = fresh("reader")
        val field = fresh("field")
        val ev = fresh("v")
        return CodeBlock.builder()
            .addStatement("var %L = %L", k, decodeDefault(wire.key, wire.keyType))
            .addStatement("var %L = %L", x, decodeDefault(wire.value, wire.valueType))
            .addStatement("val %L = %T(%L.bytes)", reader, P3_READER, v)
            .beginControlFlow("while (true)")
            .addStatement("val %L = %L.next() ?: break", field, reader)
            .addStatement("val %L = %L.value", ev, field)
            .beginControlFlow("when (%L.number)", field)
            .addStatement("1u -> if (%L is %T) %L = %L", ev, valueClass(wireOf(wire.key)), k, decodePresent(wire.key, ev))
            .addStatement("2u -> if (%L is %T) %L = %L", ev, valueClass(wireOf(wire.value)), x, decodePresent(wire.value, ev))
            .addStatement("else -> {}")
            .endControlFlow()
            .endControlFlow()
            .addStatement("%L[%L] = %L", binding, k, x).build()
    }
}

private val U_LONG = ClassName("kotlin", "ULong")

/** The bytes the tag of field [number] takes: the varint of the number shifted past the three wire-type bits. */
internal fun tagLen(number: Int): Int {
    var tag = number.toLong() shl 3
    var bytes = 1
    while (tag >= 0x80) {
        tag = tag ushr 7
        bytes += 1
    }
    return bytes
}
