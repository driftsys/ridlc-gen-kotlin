package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.joinToCode
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.Init
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef

/**
 * The typl init value of a type (typl §5.8) as a Kotlin expression, from the
 * model's `Init` facts: what a signal's channel holds before its first
 * publication (ridl §4.4), and what `Signal.init()` returns. The Rust backend
 * calls the payload type's `Default`, which its `defaults.rs` builds from the
 * same facts; a type whose init is not derivable has none, and is refused.
 */
internal class Inits(private val model: Model, private val pkg: String, private val wires: Wires) {
    fun ofRef(ref: TypeRef): CodeBlock {
        val (owner, declaration) = wires.declarationOf(ref)
        return ofDeclaration(owner, declaration, model.isForeign(ref))
    }

    fun ofDeclaration(owner: String, declaration: Declaration, foreign: Boolean): CodeBlock {
        val type = ClassName(owner, declaration.name.camel)
        val name = declaration.name.declared
        return when (declaration.kindCase) {
            Declaration.KindCase.SCALAR -> {
                if (!declaration.init.derivable) refuse("`$name` has no derivable init value; declare `= value`")
                named(type, declaration.scalar, declaration.init.value, foreign)
            }
            Declaration.KindCase.ENUM -> {
                val enum = declaration.enum
                if (!enum.hasInitMember()) refuse("the enum `$name` has no init member")
                CodeBlock.of("%T.%N", type, enumEntry(enum.getValues(enum.initMember).name))
            }
            Declaration.KindCase.ENUM_SET -> CodeBlock.of("%T.EMPTY", type)
            Declaration.KindCase.STRUCT ->
                record(type, declaration.struct.slotsList.filter { it.hasField() }.map { it.field })
            Declaration.KindCase.UNION -> {
                val arm = declaration.union.armsList.firstOrNull() ?: refuse("the union `$name` has no arm")
                CodeBlock.of("%T(%L)", type.nestedClass(arm.name.camel), ofRef(arm.type))
            }
            else -> refuse("`$name` is not a type")
        }
    }

    /** A named scalar holding the init text [value], through the constructor that accepts it. */
    fun named(type: ClassName, scalar: Scalar, value: String, foreign: Boolean): CodeBlock {
        val literal = literal(scalar.class_, value)
        return when {
            scalar.plain -> CodeBlock.of("%T(%L)", type, literal)
            foreign -> CodeBlock.of("%T.of(%L)", type, literal)
            else -> CodeBlock.of("%T.unchecked(%L)", type, literal)
        }
    }

    private fun record(type: ClassName, fields: List<Field>): CodeBlock =
        CodeBlock.of("%T(%L)", type, fields.map { field ->
            CodeBlock.of("%N = %L", field.name.property, field(field))
        }.joinToCode(", "))

    /**
     * A field's init: its declared `= value` when it has one, else its type's.
     * An optional field with a declared init holds that value, not absence
     * (driftsys/ridl#654).
     */
    private fun field(field: Field): CodeBlock {
        val type = if (field.hasDeclaredInit() && field.type.optional) field.type.toBuilder().setOptional(false).build() else field.type
        if (field.hasDeclaredInit() && type.kindCase == Type.KindCase.NAMED) {
            val (owner, declaration) = wires.declarationOf(type.named)
            val declared = ClassName(owner, declaration.name.camel)
            if (declaration.hasScalar()) {
                return named(declared, declaration.scalar, field.declaredInit, model.isForeign(type.named))
            }
            if (declaration.hasEnum()) {
                // The checker writes the member's value as canonical integer
                // text; a member is selected by value, its identity (typl §8).
                val value = field.declaredInit.toLongOrNull()
                val member = declaration.enum.valuesList.firstOrNull { it.value == value }
                    ?: refuse("field `${field.name.declared}`'s init `${field.declaredInit}` is no member of `${declaration.name.declared}`")
                return CodeBlock.of("%T.%N", declared, enumEntry(member.name))
            }
        }
        return position(type, field.init)
    }

    private fun position(type: Type, init: Init?): CodeBlock {
        if (type.optional) return CodeBlock.of("null")
        return when (type.kindCase) {
            Type.KindCase.NAMED -> ofRef(type.named)
            Type.KindCase.PRIMITIVE -> {
                val scalarClass = when (type.primitive) {
                    PrimitiveType.PRIMITIVE_TYPE_BOOLEAN -> ScalarClass.SCALAR_CLASS_BOOLEAN
                    PrimitiveType.PRIMITIVE_TYPE_INTEGER -> ScalarClass.SCALAR_CLASS_INTEGER
                    PrimitiveType.PRIMITIVE_TYPE_FLOAT -> ScalarClass.SCALAR_CLASS_FLOAT
                    PrimitiveType.PRIMITIVE_TYPE_STRING -> ScalarClass.SCALAR_CLASS_STRING
                    else -> ScalarClass.SCALAR_CLASS_BYTES
                }
                literal(scalarClass, init?.takeIf { it.hasValue() }?.value ?: "")
            }
            Type.KindCase.INLINE -> {
                if (init != null && init.oneLevel && !init.derivable) refuse("an inline field has no derivable init value")
                literal(type.inline.class_, init?.takeIf { it.hasValue() }?.value ?: "")
            }
            Type.KindCase.TUPLE -> {
                val tuple = model.getTuples(type.tuple.index)
                record(ClassName(pkg, tuple.name.rust), tuple.fieldsList)
            }
            Type.KindCase.ARRAY -> {
                val count = type.array.min
                if (count == 0L) {
                    CodeBlock.of("emptyList()")
                } else {
                    CodeBlock.of("%M(%L) { %L }", LIST_OF_SIZE, count, position(type.array.element, null))
                }
            }
            Type.KindCase.MAP -> {
                if (type.map.min > 0) refuse("a map with a minimum of ${type.map.min} entries has no init value")
                CodeBlock.of("emptyMap()")
            }
            else -> refuse("a type position has no init value")
        }
    }

    /** The canonical init text of a scalar class as a Kotlin literal; an empty text is the class's zero. */
    private fun literal(scalarClass: ScalarClass, value: String): CodeBlock = when (scalarClass) {
        ScalarClass.SCALAR_CLASS_INTEGER -> CodeBlock.of("%L", Literals.long(value.ifEmpty { "0" }))
        ScalarClass.SCALAR_CLASS_BOOLEAN -> CodeBlock.of("%L", if (value == "true") "true" else "false")
        ScalarClass.SCALAR_CLASS_STRING -> CodeBlock.of("%S", value)
        // A bytes init is the UTF-8 encoding of its text (driftsys/ridl#654).
        ScalarClass.SCALAR_CLASS_BYTES -> if (value.isEmpty()) {
            CodeBlock.of("%T(0)", BYTE_ARRAY)
        } else {
            CodeBlock.of("%S.encodeToByteArray()", value)
        }
        else -> CodeBlock.of("%L", Literals.double(value.ifEmpty { "0" }))
    }
}
