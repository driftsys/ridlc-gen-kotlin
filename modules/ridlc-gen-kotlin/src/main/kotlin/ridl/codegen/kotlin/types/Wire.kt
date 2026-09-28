package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.ClassName
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.EnumValue
import ridl.codegen.v1.ModelOuterClass.FloatWidth
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef

/**
 * One inline FlatBuffers scalar: its width, the `Reader` method that reads it,
 * the `Field` that writes it, and how a `Long`, `Double` or `Boolean` domain
 * value becomes the field's argument — Rust's `as` casts, which truncate an
 * integer and round a `f64` to the nearest `f32`.
 */
internal enum class Prim(val width: Int, val read: String, val field: String) {
    Bool(1, "bool", "Bool"),
    U8(1, "u8", "U8"),
    I8(1, "i8", "I8"),
    U16(2, "u16", "U16"),
    I16(2, "i16", "I16"),
    U32(4, "u32", "U32"),
    I32(4, "i32", "I32"),
    U64(8, "u64", "U64"),
    I64(8, "i64", "I64"),
    F32(4, "f32", "F32"),
    F64(8, "f64", "F64"),
    ;

    /** [base], a `Long`, `Double` or `Boolean`, as this field's constructor argument. */
    fun raw(base: String): String = when (this) {
        Bool, U32, U64, I64, F64 -> base
        U8, I8, U16, I16, I32 -> "$base.toInt()"
        F32 -> "$base.toFloat()"
    }

    /** The value [read] returns, widened to the domain's `Long`, `Double` or `Boolean`. */
    fun widen(read: String): String = when (this) {
        Bool, U32, U64, I64, F64 -> read
        U8, I8, U16, I16, I32 -> "$read.toLong()"
        F32 -> "$read.toDouble()"
    }

    /** A `ByteBuffer` put of [raw] for a vector of these, little-endian. */
    fun put(buffer: String, raw: String): String = when (this) {
        Bool -> "$buffer.put(if ($raw) 1 else 0)"
        U8, I8 -> "$buffer.put($raw.toByte())"
        U16, I16 -> "$buffer.putShort($raw.toShort())"
        U32 -> "$buffer.putInt($raw.toInt())"
        I32 -> "$buffer.putInt($raw)"
        U64, I64 -> "$buffer.putLong($raw)"
        F32 -> "$buffer.putFloat($raw)"
        F64 -> "$buffer.putDouble($raw)"
    }
}

/** What a scalar or text position holds in Kotlin, which decides its checks and its construction. */
internal sealed interface Domain {
    /** A bare primitive: the position's type is the backing itself. */
    data object Primitive : Domain

    /** An inline constrained scalar: the backing, checked by its owner (Kotlin's own, beside Rust's none). */
    data class Inline(val scalar: Scalar) : Domain

    /** A named scalar: `.value` out, `unchecked` or the constructor in. */
    data class Named(val type: ClassName, val declared: String, val scalar: Scalar) : Domain

    /** An enum: `.value` out, `fromValue` in, the first member as the fallback. */
    data class EnumOf(val type: ClassName, val declared: String, val first: EnumValue) : Domain

    /** An enum set: `.bits` out, `ofOrNull` in, `EMPTY` as the fallback. */
    data class SetOf(val type: ClassName, val declared: String) : Domain
}

/** The wire shape of one type position, rebuilt from the type as the Rust codec's `wire()` does. */
internal sealed interface Wire {
    /** Its width inline in a table or a vector: a scalar's own, 4 for anything out of line. */
    val width: Int

    data class ScalarWire(val prim: Prim, val domain: Domain) : Wire {
        override val width get() = prim.width
    }

    data class Text(val domain: Domain) : Wire {
        override val width get() = 4
    }

    data class Bytes(val domain: Domain) : Wire {
        override val width get() = 4
    }

    /** A struct's or a tuple's table: [functions] names its encode, verify and decode helpers. */
    data class Table(val type: ClassName, val functions: Functions) : Wire {
        override val width get() = 4
    }

    data class UnionOf(val type: ClassName, val functions: Functions) : Wire {
        override val width get() = 4
    }

    data class Vector(val element: Wire, val min: Long, val max: Long) : Wire {
        override val width get() = 4
    }

    data class Map(val key: Wire, val value: Wire, val min: Long, val max: Long) : Wire {
        override val width get() = 4
    }
}

/** The three helpers of a table-shaped type, in the package that declares it. */
internal data class Functions(val pkg: String, val suffix: String) {
    val encode get() = "encode$suffix"
    val verify get() = "verify$suffix"
    val decode get() = "decode$suffix"
}

/**
 * Resolves type positions of [model] to [Wire]s, raising a [Refusal] for
 * every position the Rust codec emitter refuses (codec.rs `wire`,
 * `declaration_wire`, `scalar_wire`), so a package is generated in Kotlin
 * exactly when it is in Rust.
 */
internal class Wires(private val model: Model, private val pkg: String) {
    /** The declaration [ref] names, with the package that declares it: [pkg] when local. */
    fun declarationOf(ref: TypeRef): Pair<String, Declaration> {
        val declaration = model.declarationOf(ref)
        return (if (model.isForeign(ref)) ref.`package` else pkg) to declaration
    }

    fun of(type: Type): Wire = when (type.kindCase) {
        Type.KindCase.NAMED -> named(type.named)
        Type.KindCase.PRIMITIVE -> when (type.primitive) {
            PrimitiveType.PRIMITIVE_TYPE_BOOLEAN -> Wire.ScalarWire(Prim.Bool, Domain.Primitive)
            PrimitiveType.PRIMITIVE_TYPE_INTEGER -> Wire.ScalarWire(Prim.I64, Domain.Primitive)
            PrimitiveType.PRIMITIVE_TYPE_FLOAT -> Wire.ScalarWire(Prim.F64, Domain.Primitive)
            else -> refuse("a bare `string` or `bytes` has no FlatBuffers bound; declare a bounded type")
        }
        Type.KindCase.INLINE -> scalar(type.inline, Domain.Inline(type.inline))
        Type.KindCase.TUPLE -> {
            val name = model.getTuples(type.tuple.index).name.rust
            if (name.isEmpty()) refuse("a tuple has no induced name")
            Wire.Table(ClassName(pkg, name), Functions(pkg, name))
        }
        Type.KindCase.ARRAY -> {
            if (!type.array.hasElement()) refuse("an array carries no element type")
            if (type.array.element.optional) refuse("an array's element cannot be optional in FlatBuffers")
            Wire.Vector(of(type.array.element), type.array.min, type.array.max)
        }
        Type.KindCase.MAP -> {
            if (!type.map.hasKey() || !type.map.hasValue()) refuse("a map carries no key or no value type")
            if (type.map.key.optional || type.map.value.optional) refuse("a map's key and value cannot be optional in FlatBuffers")
            Wire.Map(of(type.map.key), of(type.map.value), type.map.min, type.map.max)
        }
        else -> refuse("FlatBuffers cannot carry this type position")
    }

    fun named(ref: TypeRef): Wire {
        val (owner, declaration) = declarationOf(ref)
        return ofDeclaration(owner, declaration, ref.reference)
    }

    /** The wire of a declaration of package [owner], as a position naming it or as a root. */
    fun ofDeclaration(owner: String, declaration: Declaration, reference: String = declaration.name.declared): Wire {
        val type = ClassName(owner, declaration.name.camel)
        return when (declaration.kindCase) {
            Declaration.KindCase.SCALAR ->
                scalar(declaration.scalar, Domain.Named(type, declaration.name.declared, declaration.scalar))
            Declaration.KindCase.ENUM -> {
                if (declaration.enum.valuesCount == 0) refuse("the enum `$reference` has no value")
                Wire.ScalarWire(Prim.I64, Domain.EnumOf(type, declaration.name.declared, declaration.enum.getValues(0)))
            }
            Declaration.KindCase.ENUM_SET ->
                Wire.ScalarWire(intPrim(declaration.enumSet.width), Domain.SetOf(type, declaration.name.declared))
            Declaration.KindCase.STRUCT -> Wire.Table(type, Functions(owner, declaration.name.camel))
            Declaration.KindCase.UNION -> Wire.UnionOf(type, Functions(owner, declaration.name.camel))
            else -> refuse("the reference `$reference` does not name a type")
        }
    }

    /** A scalar declaration's wire, from its class and declared width. */
    fun scalar(scalar: Scalar, domain: Domain): Wire = when (scalar.class_) {
        ScalarClass.SCALAR_CLASS_INTEGER -> {
            if (!scalar.hasIntWidth()) refuse("a numeric scalar carries no width")
            Wire.ScalarWire(intPrim(scalar.intWidth), domain)
        }
        ScalarClass.SCALAR_CLASS_FLOAT, ScalarClass.SCALAR_CLASS_UNSPECIFIED -> when {
            !scalar.hasFloatWidth() -> refuse("a numeric scalar carries no width")
            scalar.floatWidth == FloatWidth.FLOAT_WIDTH_F32 -> Wire.ScalarWire(Prim.F32, domain)
            scalar.floatWidth == FloatWidth.FLOAT_WIDTH_F64 -> Wire.ScalarWire(Prim.F64, domain)
            else -> refuse("a float scalar's width is unspecified")
        }
        ScalarClass.SCALAR_CLASS_BOOLEAN -> Wire.ScalarWire(Prim.Bool, domain)
        ScalarClass.SCALAR_CLASS_STRING -> Wire.Text(domain)
        ScalarClass.SCALAR_CLASS_BYTES -> Wire.Bytes(domain)
        else -> refuse("scalar class `${scalar.class_}` is not one this plugin reads")
    }

    private fun intPrim(width: IntWidth): Prim = when (width) {
        IntWidth.INT_WIDTH_U8 -> Prim.U8
        IntWidth.INT_WIDTH_I8 -> Prim.I8
        IntWidth.INT_WIDTH_U16 -> Prim.U16
        IntWidth.INT_WIDTH_I16 -> Prim.I16
        IntWidth.INT_WIDTH_U32 -> Prim.U32
        IntWidth.INT_WIDTH_I32 -> Prim.I32
        IntWidth.INT_WIDTH_U64 -> Prim.U64
        IntWidth.INT_WIDTH_I64 -> Prim.I64
        else -> refuse("an integer's width is unspecified")
    }
}

/**
 * The Rust codec's `place`: fields at their widths, in declaration order,
 * each aligned to its own width from the table's start, after the four bytes
 * of the vtable offset. The table ends at its last field, and aligns to its
 * widest field and at least 4.
 */
internal class Layout(val offsets: List<Int>, val size: Int, val align: Int) {
    companion object {
        fun place(widths: List<Int>): Layout {
            var cursor = 4
            var align = 4
            val offsets = widths.map { w ->
                cursor = (cursor + w - 1) / w * w
                val at = cursor
                cursor += w
                align = maxOf(align, w)
                at
            }
            if (cursor > 0xFFFF) refuse("its FlatBuffers table is $cursor bytes, over the 65,535 a vtable can state")
            return Layout(offsets, maxOf(cursor, 4), align)
        }
    }
}
