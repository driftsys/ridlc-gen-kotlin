package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.BYTE_ARRAY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import ridl.codegen.kotlin.Options
import ridl.codegen.kotlin.PLUGIN
import ridl.codegen.v1.ModelOuterClass.Constant
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.Enum
import ridl.codegen.v1.ModelOuterClass.EnumSet
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.ModelOuterClass.Union
import ridl.codegen.v1.ModelOuterClass.Visibility

private val RULE = ClassName("ridl.rt.payload", "Rule")
private val CONSTRAINT_VIOLATION = ClassName("ridl.rt.payload", "ConstraintViolation")
private val REGEX = ClassName("kotlin.text", "Regex")

/** What [TypesEmitter.emit] produced: the file, or the refusals that replace it; and the package-level names it takes. */
class EmittedTypes(val path: String, val text: String?, val errors: List<String>, val claims: List<Claim> = emptyList())

/**
 * `Types.kt`: the value objects of one package (docs/design.md §4), written
 * with KotlinPoet (D-K2). One Kotlin declaration per model declaration and
 * per induced tuple, and the constants in one `object Constants_`.
 */
class TypesEmitter(private val model: Model, private val options: Options) {
    private val pkg = options.kotlinPackage
    private val file = FileSpec.builder(pkg, "Types").jvmName("Types")
    private val errors = mutableListOf<String>()
    private val claims = mutableListOf<Claim>()
    private var deprecated = false

    fun emit(): EmittedTypes {
        val path = pkg.replace('.', '/') + "/Types.kt"
        for (collision in model.tupleCollisionsList) {
            errors += "$PLUGIN: two tuples of `${model.name.dotted}` are both named `${collision.name}`"
        }
        val constants = TypeSpec.objectBuilder(CONSTANTS)
            .addKdoc("The constants of `%L`.", model.name.dotted)
        for (declaration in model.declarationsList) {
            guarded(declaration.name.declared) {
                if (declaration.name.camel.isEmpty()) refuse("the declaration has no name")
                when (declaration.kindCase) {
                    Declaration.KindCase.SCALAR -> file.addType(scalar(declaration, declaration.scalar))
                    Declaration.KindCase.CONSTANT -> constant(declaration, declaration.constant)?.let(constants::addProperty)
                    Declaration.KindCase.STRUCT -> file.addType(struct(declaration, declaration.struct))
                    Declaration.KindCase.ENUM -> file.addType(enum(declaration, declaration.enum))
                    Declaration.KindCase.ENUM_SET -> file.addType(enumSet(declaration, declaration.enumSet))
                    Declaration.KindCase.UNION -> file.addType(union(declaration, declaration.union))
                    else -> refuse("its kind is not one this plugin reads")
                }
                if (!declaration.hasConstant()) claims += Claim(declaration.name.camel, "declaration `${declaration.name.declared}`")
            }
        }
        for ((index, tuple) in model.tuplesList.withIndex()) {
            val name = tuple.name.rust
            if (name.isEmpty()) continue
            guarded(name) {
                file.addType(
                    record(
                        name = name,
                        declared = name,
                        visibility = tuple.visibility,
                        fields = tuple.fieldsList,
                        doc = "A tuple, induced from `${tuplePath(index)}`.",
                        deprecation = null,
                    ),
                )
                claims += Claim(name, "the tuple induced from `${tuplePath(index)}`")
            }
        }
        val built = constants.build()
        if (built.propertySpecs.isNotEmpty()) file.addType(built)
        if (errors.isNotEmpty()) return EmittedTypes(path, null, errors)
        if (deprecated) {
            file.addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "DEPRECATION").build())
        }
        file.addFileComment("Generated by %L from `%L`. Do not edit.", PLUGIN, model.name.dotted)
        return EmittedTypes(path, file.build().toString(), emptyList(), claims)
    }

    private fun guarded(name: String, block: () -> Unit) {
        try {
            block()
        } catch (refusal: Refusal) {
            errors += "$PLUGIN: `${model.name.dotted}.$name` cannot be generated: ${refusal.message}"
        }
    }

    // -- names and types ---------------------------------------------------

    private fun ref(ref: TypeRef): ClassName {
        if (!ref.resolved) refuse("the reference `${ref.reference}` resolves to no declaration")
        val declaration = declarationOf(ref)
        if (declaration.hasConstant()) refuse("the reference `${ref.reference}` names a constant, not a type")
        val owner = if (model.isForeign(ref)) ref.`package` else pkg
        return ClassName(owner, declaration.name.camel)
    }

    private fun declarationOf(ref: TypeRef): Declaration = model.declarationOf(ref)

    private fun kotlinType(type: Type): TypeName {
        val base: TypeName = when (type.kindCase) {
            Type.KindCase.NAMED -> ref(type.named)
            Type.KindCase.PRIMITIVE -> primitive(type.primitive)
            Type.KindCase.INLINE -> backing(type.inline)
            Type.KindCase.TUPLE -> {
                val name = model.getTuples(type.tuple.index).name.rust
                if (name.isEmpty()) refuse("a tuple has no induced name")
                ClassName(pkg, name)
            }
            Type.KindCase.ARRAY -> LIST.parameterizedBy(kotlinType(type.array.element))
            Type.KindCase.MAP -> MAP.parameterizedBy(kotlinType(type.map.key), kotlinType(type.map.value))
            Type.KindCase.STREAM -> refuse("a stream has no port in ridl-rt yet (O-K5, driftsys/ridl#336)")
            else -> refuse("a type position carries no type")
        }
        return if (type.optional) base.copy(nullable = true) else base
    }

    private fun primitive(primitive: PrimitiveType): TypeName = when (primitive) {
        PrimitiveType.PRIMITIVE_TYPE_BOOLEAN -> BOOLEAN
        PrimitiveType.PRIMITIVE_TYPE_INTEGER -> LONG
        PrimitiveType.PRIMITIVE_TYPE_FLOAT -> DOUBLE
        PrimitiveType.PRIMITIVE_TYPE_STRING -> STRING
        PrimitiveType.PRIMITIVE_TYPE_BYTES -> BYTE_ARRAY
        else -> refuse("a primitive type is unspecified")
    }

    /** The Kotlin type a scalar is held in: every integer a `Long`, every float a `Double`, as Rust's i64 and f64. */
    private fun backing(scalar: Scalar): TypeName = when (scalar.class_) {
        ScalarClass.SCALAR_CLASS_INTEGER -> LONG
        ScalarClass.SCALAR_CLASS_FLOAT, ScalarClass.SCALAR_CLASS_UNSPECIFIED -> DOUBLE
        ScalarClass.SCALAR_CLASS_BOOLEAN -> BOOLEAN
        ScalarClass.SCALAR_CLASS_STRING -> STRING
        ScalarClass.SCALAR_CLASS_BYTES -> BYTE_ARRAY
        else -> refuse("scalar class `${scalar.class_}` is not one this plugin reads")
    }

    private fun TypeSpec.Builder.header(declaration: Declaration): TypeSpec.Builder = apply {
        if (declaration.doc.isNotEmpty()) addKdoc("%L", declaration.doc)
        if (declaration.visibility == Visibility.VISIBILITY_INTERNAL) addModifiers(KModifier.INTERNAL)
        if (declaration.hasDeprecated()) addDeprecation(declaration.deprecated)
    }

    private fun TypeSpec.Builder.addDeprecation(message: String) {
        deprecated = true
        addAnnotation(
            AnnotationSpec.builder(Deprecated::class).addMember("%S", message.ifEmpty { "deprecated" }).build(),
        )
    }

    // -- scalars -----------------------------------------------------------

    /**
     * A named scalar: an inline value class over its backing, whose `of`
     * checks every constraint and throws `ConstraintViolation`, whose
     * `ofOrNull` answers `null` instead, and whose `internal unchecked` is
     * the decoder's. A vacuous scalar constrains nothing and has a public
     * constructor instead (E10.4). Bytes, which a value class cannot compare
     * by content, are a final class holding a private copy.
     */
    private fun scalar(declaration: Declaration, scalar: Scalar): TypeSpec {
        val name = declaration.name.camel
        val self = ClassName(pkg, name)
        val backing = backing(scalar)
        val bytes = backing == BYTE_ARRAY
        val companion = TypeSpec.companionObjectBuilder()
        val pattern = if (scalar.checksPattern()) {
            companion.addProperty(
                PropertySpec.builder("PATTERN", REGEX, KModifier.PRIVATE)
                    .initializer("%T(%S)", REGEX, javaPattern(scalar.constraint.pattern)).build(),
            )
            "PATTERN"
        } else {
            null
        }
        val checks = scalarChecks(scalar, "value", pattern) { rule -> CodeBlock.of("return %T.%L\n", RULE, rule) }
        val builder = if (bytes) bytesScalar(self, checks == null) else valueScalar(self, backing, checks == null)
        builder.header(declaration)
        if (!scalar.constraint.hasPattern() && scalar.constraint.hasPatternConst()) {
            builder.addKdoc(
                "\n\nThe pattern `%L` did not resolve, so it is not checked.",
                scalar.constraint.patternConst,
            )
        }
        if (checks != null) {
            val construct = if (bytes) "%T(value.copyOf())" else "%T(value)"
            companion.addFunction(
                FunSpec.builder("violation")
                    .addKdoc("The rule [value] breaks, or `null` when it breaks none.")
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("value", backing)
                    .returns(RULE.copy(nullable = true))
                    .addCode(checks)
                    .addStatement("return null")
                    .build(),
            )
            companion.addFunction(
                FunSpec.builder("of")
                    .addKdoc("[value] as a `%L`.\n\n@throws %T when it breaks a constraint of `%L`.", name, CONSTRAINT_VIOLATION, name)
                    .addParameter("value", backing)
                    .returns(self)
                    .addStatement("val rule = violation(value)")
                    .beginControlFlow("if (rule != null)")
                    .addStatement("throw %T(%S, rule, %L)", CONSTRAINT_VIOLATION, declaration.name.declared, if (bytes) "value.contentToString()" else "value")
                    .endControlFlow()
                    .addStatement("return $construct", self)
                    .build(),
            )
            companion.addFunction(
                FunSpec.builder("ofOrNull")
                    .addKdoc("[value] as a `%L`, or `null` when it breaks a constraint.", name)
                    .addParameter("value", backing)
                    .returns(self.copy(nullable = true))
                    .addStatement("return if (violation(value) == null) $construct else null", self)
                    .build(),
            )
            companion.addFunction(
                FunSpec.builder("unchecked")
                    .addKdoc("[value] as a `%L`, unchecked: for a decoder whose verifier has checked it.", name)
                    .addModifiers(KModifier.INTERNAL)
                    .addParameter("value", backing)
                    .returns(self)
                    .addStatement("return $construct", self)
                    .build(),
            )
        }
        val builtCompanion = companion.build()
        if (builtCompanion.funSpecs.isNotEmpty() || builtCompanion.propertySpecs.isNotEmpty()) {
            builder.addType(builtCompanion)
        }
        return builder.build()
    }

    private fun valueScalar(self: ClassName, backing: TypeName, vacuous: Boolean): TypeSpec.Builder {
        val constructor = FunSpec.constructorBuilder().addParameter("value", backing)
        if (!vacuous) constructor.addModifiers(KModifier.PRIVATE)
        return TypeSpec.classBuilder(self)
            .addModifiers(KModifier.VALUE)
            .addAnnotation(JvmInline::class)
            .primaryConstructor(constructor.build())
            .addProperty(PropertySpec.builder("value", backing).initializer("value").build())
    }

    private fun bytesScalar(self: ClassName, vacuous: Boolean): TypeSpec.Builder {
        val constructor = FunSpec.constructorBuilder().addParameter("bytes", BYTE_ARRAY)
        if (!vacuous) constructor.addModifiers(KModifier.PRIVATE)
        return TypeSpec.classBuilder(self)
            .primaryConstructor(constructor.build())
            .addProperty(
                PropertySpec.builder("bytes", BYTE_ARRAY, KModifier.PRIVATE)
                    .initializer(if (vacuous) "bytes.copyOf()" else "bytes").build(),
            )
            .addProperty(PropertySpec.builder("size", INT).getter(FunSpec.getterBuilder().addStatement("return bytes.size").build()).build())
            .addFunction(FunSpec.builder("toByteArray").addKdoc("A copy of the bytes.").returns(BYTE_ARRAY).addStatement("return bytes.copyOf()").build())
            .addFunction(
                FunSpec.builder("equals").addModifiers(KModifier.OVERRIDE)
                    .addParameter("other", ANY_NULLABLE).returns(BOOLEAN)
                    .addStatement("return other is %T && bytes.contentEquals(other.bytes)", self).build(),
            )
            .addFunction(
                FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT)
                    .addStatement("return bytes.contentHashCode()").build(),
            )
            .addFunction(
                FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING)
                    .addStatement("return %P", "${self.simpleName}(\${bytes.contentToString()})").build(),
            )
    }

    // -- constants ---------------------------------------------------------

    /**
     * One constant as a property of `object Constants_`: a `const val` for a
     * primitive or a regex, a `val` holding the value object for a named
     * scalar. A bytes constant, and one whose type does not resolve, has no
     * Kotlin spelling and is left out, as the Rust backend leaves it out.
     */
    private fun constant(declaration: Declaration, constant: Constant): PropertySpec? {
        val name = declaration.name.declared
        val property = when (constant.typedCase) {
            Constant.TypedCase.REGEX_BODY ->
                PropertySpec.builder(name, STRING, KModifier.CONST).initializer("%S", constant.regexBody)
            Constant.TypedCase.PRIMITIVE -> {
                val (type, literal) = primitiveLiteral(constant.primitive, constant.value) ?: return null
                PropertySpec.builder(name, type, KModifier.CONST).initializer(literal)
            }
            Constant.TypedCase.NAMED -> {
                val ref = constant.named
                if (!ref.resolved) return null
                val target = declarationOf(ref)
                if (!target.hasScalar()) return null
                val scalar = target.scalar
                val type = ref(ref)
                val (_, literal) = primitiveLiteral(scalarPrimitive(scalar), constant.value) ?: return null
                val initializer = when {
                    scalar.plain -> CodeBlock.of("%T(%L)", type, literal)
                    model.isForeign(ref) -> CodeBlock.of("%T.of(%L)", type, literal)
                    else -> CodeBlock.of("%T.unchecked(%L)", type, literal)
                }
                PropertySpec.builder(name, type).initializer(initializer)
            }
            else -> return null
        }
        if (declaration.doc.isNotEmpty()) property.addKdoc("%L", declaration.doc)
        if (declaration.visibility == Visibility.VISIBILITY_INTERNAL) property.addModifiers(KModifier.INTERNAL)
        if (declaration.hasDeprecated()) {
            deprecated = true
            property.addAnnotation(
                AnnotationSpec.builder(Deprecated::class)
                    .addMember("%S", declaration.deprecated.ifEmpty { "deprecated" }).build(),
            )
        }
        return property.build()
    }

    private fun scalarPrimitive(scalar: Scalar): PrimitiveType = when (scalar.class_) {
        ScalarClass.SCALAR_CLASS_INTEGER -> PrimitiveType.PRIMITIVE_TYPE_INTEGER
        ScalarClass.SCALAR_CLASS_BOOLEAN -> PrimitiveType.PRIMITIVE_TYPE_BOOLEAN
        ScalarClass.SCALAR_CLASS_STRING -> PrimitiveType.PRIMITIVE_TYPE_STRING
        ScalarClass.SCALAR_CLASS_BYTES -> PrimitiveType.PRIMITIVE_TYPE_BYTES
        else -> PrimitiveType.PRIMITIVE_TYPE_FLOAT
    }

    private fun primitiveLiteral(primitive: PrimitiveType, value: String): Pair<TypeName, CodeBlock>? = when (primitive) {
        PrimitiveType.PRIMITIVE_TYPE_INTEGER -> LONG to CodeBlock.of("%L", Literals.long(value))
        PrimitiveType.PRIMITIVE_TYPE_FLOAT -> DOUBLE to CodeBlock.of("%L", Literals.double(value))
        PrimitiveType.PRIMITIVE_TYPE_BOOLEAN -> when (value) {
            "true", "false" -> BOOLEAN to CodeBlock.of("%L", value)
            else -> refuse("`$value` is not a boolean")
        }
        PrimitiveType.PRIMITIVE_TYPE_STRING -> STRING to CodeBlock.of("%S", value)
        else -> null
    }

    // -- enums and enum sets -----------------------------------------------

    /** An enum class over the declared discriminants; an undefined one is `null` from `fromValue` (E10.5). */
    private fun enum(declaration: Declaration, enum: Enum): TypeSpec {
        val self = ClassName(pkg, declaration.name.camel)
        if (enum.valuesCount == 0) refuse("an enum with no value has no Kotlin enum class")
        val builder = TypeSpec.enumBuilder(self).header(declaration)
            .primaryConstructor(FunSpec.constructorBuilder().addParameter("value", LONG).build())
            .addProperty(PropertySpec.builder("value", LONG).initializer("value").build())
        for (value in enum.valuesList) {
            val constant = TypeSpec.anonymousClassBuilder()
                .addSuperclassConstructorParameter("%L", Literals.long(value.value.toString()))
            if (value.doc.isNotEmpty()) constant.addKdoc("%L", value.doc)
            builder.addEnumConstant(enumEntry(value.name), constant.build())
        }
        builder.addType(
            TypeSpec.companionObjectBuilder()
                .addFunction(
                    FunSpec.builder("fromValue")
                        .addKdoc("The member whose discriminant is [value], or `null` for one no member declares.")
                        .addParameter("value", LONG)
                        .returns(self.copy(nullable = true))
                        .addStatement("return entries.firstOrNull { it.value == value }")
                        .build(),
                ).build(),
        )
        return builder.build()
    }

    /**
     * An enum set: an inline value class over the members' bits, one
     * constant per member, and `contains`, `plus` and `minus`. A bit no
     * member declares is refused by `of` with the rule `Variant`, as the
     * Rust backend's `TryFrom` refuses it.
     */
    private fun enumSet(declaration: Declaration, set: EnumSet): TypeSpec {
        val self = ClassName(pkg, declaration.name.camel)
        val companion = TypeSpec.companionObjectBuilder()
            .addProperty(
                PropertySpec.builder("DECLARED_MASK", LONG, KModifier.CONST)
                    .addKdoc("Every bit a member declares.")
                    .initializer("%L", Literals.long(set.declaredMask.toString())).build(),
            )
            .addProperty(PropertySpec.builder("EMPTY", self).addKdoc("The set of no member.").initializer("%T(0L)", self).build())
        for (bit in set.bitsList) {
            if (bit.value !in 0..63) refuse("the bit of `${bit.name.declared}` is ${bit.value}, outside 0..63")
            val property = PropertySpec.builder(enumSetBit(bit.name), self).initializer("%T(1L shl %L)", self, bit.value)
            if (bit.doc.isNotEmpty()) property.addKdoc("%L", bit.doc)
            companion.addProperty(property.build())
        }
        companion
            .addFunction(
                FunSpec.builder("of")
                    .addKdoc("The set whose bits are [bits].\n\n@throws %T when a bit is one no member declares.", CONSTRAINT_VIOLATION)
                    .addParameter("bits", LONG).returns(self)
                    .beginControlFlow("if (bits and DECLARED_MASK.inv() != 0L)")
                    .addStatement("throw %T(%S, %T.Variant, bits)", CONSTRAINT_VIOLATION, declaration.name.declared, RULE)
                    .endControlFlow()
                    .addStatement("return %T(bits)", self).build(),
            )
            .addFunction(
                FunSpec.builder("ofOrNull").addKdoc("The set whose bits are [bits], or `null` when a bit is undeclared.")
                    .addParameter("bits", LONG).returns(self.copy(nullable = true))
                    .addStatement("return if (bits and DECLARED_MASK.inv() == 0L) %T(bits) else null", self).build(),
            )
            .addFunction(
                FunSpec.builder("unchecked").addModifiers(KModifier.INTERNAL)
                    .addParameter("bits", LONG).returns(self)
                    .addStatement("return %T(bits)", self).build(),
            )
        return TypeSpec.classBuilder(self).header(declaration)
            .addModifiers(KModifier.VALUE)
            .addAnnotation(JvmInline::class)
            .primaryConstructor(FunSpec.constructorBuilder().addModifiers(KModifier.PRIVATE).addParameter("bits", LONG).build())
            .addProperty(PropertySpec.builder("bits", LONG).initializer("bits").build())
            .addFunction(
                FunSpec.builder("contains").addModifiers(KModifier.OPERATOR)
                    .addKdoc("Whether every member of [other] is in this set.")
                    .addParameter("other", self).returns(BOOLEAN)
                    .addStatement("return bits and other.bits == other.bits").build(),
            )
            .addFunction(
                FunSpec.builder("plus").addModifiers(KModifier.OPERATOR)
                    .addParameter("other", self).returns(self)
                    .addStatement("return %T(bits or other.bits)", self).build(),
            )
            .addFunction(
                FunSpec.builder("minus").addModifiers(KModifier.OPERATOR)
                    .addParameter("other", self).returns(self)
                    .addStatement("return %T(bits and other.bits.inv())", self).build(),
            )
            .addType(companion.build())
            .build()
    }

    // -- unions ------------------------------------------------------------

    /** A sealed interface with one nested value class per arm, named by the arm's spelling. */
    private fun union(declaration: Declaration, union: Union): TypeSpec {
        val self = ClassName(pkg, declaration.name.camel)
        if (union.armsCount == 0) refuse("a union with no arm has no Kotlin spelling")
        val builder = TypeSpec.interfaceBuilder(self).addModifiers(KModifier.SEALED).header(declaration)
        for (arm in union.armsList) {
            val armName = arm.name.camel
            if (armName == self.simpleName) refuse("its arm `${arm.name.declared}` has the union's own name")
            val armType = ref(arm.type)
            val nested = TypeSpec.classBuilder(armName)
                .addModifiers(KModifier.VALUE)
                .addAnnotation(JvmInline::class)
                .addSuperinterface(self)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("value", armType).build())
                .addProperty(PropertySpec.builder("value", armType).initializer("value").build())
            if (arm.doc.isNotEmpty()) nested.addKdoc("%L", arm.doc)
            builder.addType(nested.build())
        }
        return builder.build()
    }

    // -- structs and tuples --------------------------------------------------

    private fun struct(declaration: Declaration, struct: Struct): TypeSpec = record(
        name = declaration.name.camel,
        declared = declaration.name.declared,
        visibility = declaration.visibility,
        fields = struct.slotsList.filter { it.hasField() }.map { it.field },
        doc = declaration.doc,
        deprecation = if (declaration.hasDeprecated()) declaration.deprecated else null,
    )

    private fun tuplePath(index: Int): String {
        val tuple = model.getTuples(index)
        return when {
            tuple.hasDeclaration() ->
                (listOf(model.getDeclarations(tuple.declaration.declaration).name.declared) + tuple.declaration.segmentsList)
                    .joinToString(".")
            tuple.hasInteraction() -> tuple.interaction.segmentsList.joinToString(".")
            else -> "an unknown position"
        }
    }

    /**
     * A struct or a tuple: a final class with `equals`, `hashCode` and
     * `toString` and no `copy` or `componentN`, which a later field reorder
     * would break (E10.6's argument for Kotlin's derived members). Its fields
     * are already valid value objects; what it checks at construction is what
     * no field type carries: an array's or a map's bounds, and an inline
     * scalar's constraints, reported as a `ConstraintViolation` naming this
     * type. Collections and bytes are copied in, so a caller's later change
     * cannot break what was checked.
     */
    private fun record(
        name: String,
        declared: String,
        visibility: Visibility,
        fields: List<Field>,
        doc: String,
        deprecation: String?,
    ): TypeSpec {
        val self = ClassName(pkg, name)
        val builder = TypeSpec.classBuilder(self)
        if (doc.isNotEmpty()) builder.addKdoc("%L", doc)
        if (visibility == Visibility.VISIBILITY_INTERNAL) builder.addModifiers(KModifier.INTERNAL)
        if (deprecation != null) builder.addDeprecation(deprecation)
        val constructor = FunSpec.constructorBuilder()
        val init = CodeBlock.builder()
        val members = mutableListOf<Member>()
        val patterns = Patterns(name)
        val properties = mutableMapOf<String, String>()
        for (field in fields) {
            if (!field.hasType()) refuse("the field `${field.name.declared}` carries no type")
            if (field.name.camel.isEmpty()) refuse("a field has no name")
            val property = field.name.property
            // Two fields one Kotlin property cannot hold: `XY` and `x_y` are
            // both `xY`. Both names are the source's, so it is refused.
            properties.put(property, field.name.declared)?.let {
                refuse("its fields `$it` and `${field.name.declared}` are both the property `$property`; rename one of them")
            }
            val type = kotlinType(field.type)
            val parameter = ParameterSpec.builder(property, type)
            if (type.isNullable) parameter.defaultValue("null")
            if (field.doc.isNotEmpty()) parameter.addKdoc("%L", field.doc)
            constructor.addParameter(parameter.build())
            val bytes = type.copy(nullable = false) == BYTE_ARRAY
            val stored = if (bytes) "_$property" else property
            val copy = copyOf(escaped(property), field.type)
            if (bytes) {
                builder.addProperty(PropertySpec.builder(stored, type, KModifier.PRIVATE).initializer(copy).build())
                builder.addProperty(
                    PropertySpec.builder(property, type).apply { if (field.doc.isNotEmpty()) addKdoc("%L", field.doc) }
                        .getter(FunSpec.getterBuilder().addStatement("return %L", copyOf("this.$stored", field.type)).build()).build(),
                )
            } else {
                val prop = PropertySpec.builder(property, type).initializer(copy)
                if (field.doc.isNotEmpty()) prop.addKdoc("%L", field.doc)
                if (field.hasDeprecated()) {
                    deprecated = true
                    prop.addAnnotation(
                        AnnotationSpec.builder(Deprecated::class).addMember("%S", field.deprecated.ifEmpty { "deprecated" }).build(),
                    )
                }
                builder.addProperty(prop.build())
            }
            members += Member(property, stored, bytes)
            // `this.`: in `init`, the constructor parameter of the same name
            // shadows the property, and the property holds the copy.
            checks("this.${escaped(stored)}", field.type, declared, patterns, depth = 0)?.let(init::add)
        }
        patterns.declare(builder)
        builder.primaryConstructor(constructor.build())
        if (!init.isEmpty()) builder.addInitializerBlock(init.build())
        builder.addFunction(equalsOf(self, members))
        builder.addFunction(hashCodeOf(members))
        builder.addFunction(toStringOf(name, members))
        return builder.build()
    }

    private class Member(val name: String, val stored: String, val bytes: Boolean)

    /** The compiled patterns of a record's inline string fields, in a private companion. */
    private class Patterns(private val owner: String) {
        private val bodies = mutableListOf<String>()

        fun of(body: String): String {
            bodies += javaPattern(body)
            return "PATTERN_${bodies.size - 1}"
        }

        fun declare(builder: TypeSpec.Builder) {
            if (bodies.isEmpty()) return
            val companion = TypeSpec.companionObjectBuilder()
            for ((index, body) in bodies.withIndex()) {
                companion.addProperty(
                    PropertySpec.builder("PATTERN_$index", REGEX, KModifier.PRIVATE).initializer("%T(%S)", REGEX, body).build(),
                )
            }
            builder.addType(companion.addKdoc("The patterns of `%L`'s inline fields.", owner).build())
        }
    }

    /** An expression copying [expr] of [type] deep enough that the caller keeps no handle into it. */
    private fun copyOf(expr: String, type: Type, depth: Int = 0): String {
        val safe = if (type.optional) "?." else "."
        return when (type.kindCase) {
            Type.KindCase.ARRAY -> {
                val element = copyOf("e$depth", type.array.element, depth + 1)
                if (element == "e$depth") "$expr${safe}toList()" else "$expr${safe}map { e$depth -> $element }"
            }
            Type.KindCase.MAP -> {
                val key = copyOf("k$depth", type.map.key, depth + 1)
                val value = copyOf("v$depth", type.map.value, depth + 1)
                if (key == "k$depth" && value == "v$depth") {
                    "$expr${safe}toMap()"
                } else {
                    "$expr${safe}entries${if (type.optional) "?." else "."}associate { (k$depth, v$depth) -> $key to $value }"
                }
            }
            else -> if (kotlinType(type).copy(nullable = false) == BYTE_ARRAY) "$expr${safe}copyOf()" else expr
        }
    }

    /** The construction checks of one field, or `null` when its type carries every constraint itself. */
    private fun checks(expr: String, type: Type, owner: String, patterns: Patterns, depth: Int): CodeBlock? {
        if (type.optional) {
            val inner = checks("it$depth", type.toBuilder().setOptional(false).build(), owner, patterns, depth + 1)
                ?: return null
            return CodeBlock.builder().beginControlFlow("%L?.let { it$depth ->", expr).add(inner).endControlFlow().build()
        }
        val fail = { rule: String, value: String -> CodeBlock.of("throw %T(%S, %T.%L, %L)\n", CONSTRAINT_VIOLATION, owner, RULE, rule, value) }
        return when (type.kindCase) {
            Type.KindCase.INLINE -> {
                val pattern = if (type.inline.checksPattern()) patterns.of(type.inline.constraint.pattern) else null
                val shown = if (type.inline.class_ == ScalarClass.SCALAR_CLASS_BYTES) "$expr.contentToString()" else expr
                scalarChecks(type.inline, expr, pattern) { rule -> fail(rule.name, shown) }
            }
            Type.KindCase.ARRAY -> {
                val code = CodeBlock.builder()
                count("$expr.size", type.array.min, type.array.max)?.let {
                    code.beginControlFlow("if (%L)", it).add(fail("Length", "$expr.size")).endControlFlow()
                }
                checks("e$depth", type.array.element, owner, patterns, depth + 1)?.let {
                    code.beginControlFlow("for (e$depth in %L)", expr).add(it).endControlFlow()
                }
                code.build().takeUnless { it.isEmpty() }
            }
            Type.KindCase.MAP -> {
                val code = CodeBlock.builder()
                count("$expr.size", type.map.min, type.map.max)?.let {
                    code.beginControlFlow("if (%L)", it).add(fail("Length", "$expr.size")).endControlFlow()
                }
                val key = checks("k$depth", type.map.key, owner, patterns, depth + 1)
                val value = checks("v$depth", type.map.value, owner, patterns, depth + 1)
                if (key != null || value != null) {
                    code.beginControlFlow("for ((k$depth, v$depth) in %L)", expr)
                    key?.let(code::add)
                    value?.let(code::add)
                    code.endControlFlow()
                }
                // #38: a Kotlin map holds keys apart that `verify` takes for
                // one, as driftsys/ridl#654 compares them: 0.0 and -0.0, and two
                // byte arrays of one content. Each key is compared with the
                // keys before it, as `verify` compares an entry's.
                keysCollide(type.map.key)?.let { collide ->
                    val keys = "keys$depth"
                    code.addStatement("val %L = %L.keys.toList()", keys, expr)
                        .beginControlFlow("for (i$depth in %L.indices)", keys)
                        .beginControlFlow("for (j$depth in 0 until i$depth)")
                        .beginControlFlow("if (%L)", collide.same("$keys[j$depth]", "$keys[i$depth]"))
                        .add(fail("Unique", collide.shown("$keys[i$depth]")))
                        .endControlFlow()
                        .endControlFlow()
                        .endControlFlow()
                }
                code.build().takeUnless { it.isEmpty() }
            }
            else -> null
        }
    }

    /**
     * How two map keys of [key]'s type compare when Kotlin's `equals` holds
     * them apart and ridl's rule takes them for one, or `null` when the two
     * agree. A float compares by IEEE equality, so 0.0 and -0.0 are one key and
     * a NaN is no key's equal; a `bytes` compares by content. Every other key
     * compares as ridl's rule does, and a named key is a string (TYPL-209).
     */
    private fun keysCollide(key: Type): KeyEquality? {
        val ieee = KeyEquality({ a, b -> "$a == $b" }, { it })
        val content = KeyEquality({ a, b -> "$a.contentEquals($b)" }, { "$it.contentToString()" })
        return when (key.kindCase) {
            Type.KindCase.PRIMITIVE -> when (key.primitive) {
                PrimitiveType.PRIMITIVE_TYPE_FLOAT -> ieee
                PrimitiveType.PRIMITIVE_TYPE_BYTES -> content
                else -> null
            }
            Type.KindCase.INLINE -> when (key.inline.class_) {
                ScalarClass.SCALAR_CLASS_FLOAT, ScalarClass.SCALAR_CLASS_UNSPECIFIED -> ieee
                ScalarClass.SCALAR_CLASS_BYTES -> content
                else -> null
            }
            else -> null
        }
    }

    /** Two keys' comparison under ridl's rule, and how a refused key is shown. */
    private class KeyEquality(val same: (String, String) -> String, val shown: (String) -> String)

    /**
     * The condition under which a count breaks its bounds, as the Rust
     * codec's `count_check` states it: exactly `N` when the bounds are equal,
     * at most `max` from 0, and between them otherwise. A maximum of 0 with a
     * positive minimum bounds nothing above.
     */
    private fun count(size: String, min: Long, max: Long): String? = when {
        min == max -> "$size != $min"
        max == 0L -> if (min > 0) "$size < $min" else null
        min == 0L -> "$size > $max"
        else -> "$size < $min || $size > $max"
    }

    private fun equalsOf(self: ClassName, members: List<Member>): FunSpec {
        val body = CodeBlock.builder().add("return this === other || other is %T", self)
        for (m in members) {
            // `this.`: a field may be named `other`, like the parameter.
            if (m.bytes) body.add(" &&\n    this.%N.contentEquals(other.%N)", m.stored, m.stored) else body.add(" &&\n    this.%N == other.%N", m.name, m.name)
        }
        return FunSpec.builder("equals").addModifiers(KModifier.OVERRIDE)
            .addParameter("other", ANY_NULLABLE).returns(BOOLEAN)
            .addCode(body.add("\n").build()).build()
    }

    private fun hashCodeOf(members: List<Member>): FunSpec {
        val body = CodeBlock.builder()
        if (members.isEmpty()) {
            body.addStatement("return 0")
        } else {
            body.addStatement("var result = %L", hashOf(members.first()))
            for (m in members.drop(1)) body.addStatement("result = 31 * result + %L", hashOf(m))
            body.addStatement("return result")
        }
        return FunSpec.builder("hashCode").addModifiers(KModifier.OVERRIDE).returns(INT).addCode(body.build()).build()
    }

    // `this.`: a field may be named `result`, like the local.
    private fun hashOf(m: Member): String =
        if (m.bytes) "this.${escaped(m.stored)}.contentHashCode()" else "this.${escaped(m.name)}.hashCode()"

    private fun toStringOf(name: String, members: List<Member>): FunSpec {
        val text = members.joinToString(", ", "$name(", ")") { m ->
            if (m.bytes) "${m.name}=\${this.${escaped(m.stored)}.contentToString()}" else "${m.name}=\${this.${escaped(m.name)}}"
        }
        return FunSpec.builder("toString").addModifiers(KModifier.OVERRIDE).returns(STRING)
            .addStatement("return %P", text).build()
    }

    private companion object {
        val ANY_NULLABLE = com.squareup.kotlinpoet.ANY.copy(nullable = true)
    }
}
