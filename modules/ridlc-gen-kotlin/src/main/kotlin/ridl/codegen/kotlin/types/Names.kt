package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.MemberName
import ridl.codegen.v1.ModelOuterClass.Spellings

// The names the plugin chooses itself (driftsys/ridlc-gen-kotlin#16, the rule
// of driftsys/ridl's generated-name collision design): a name the plugin
// chose never refuses a package, so each is spelled where no ridl name can
// reach it. A ridl identifier is `[A-Za-z][A-Za-z0-9_]*` and `camel_case`
// removes every `_`, so a package-level name the plugin chooses ends in `_`.

/** The object the package's constants are properties of. */
internal const val CONSTANTS: String = "Constants_"

/** The base class of every `<Iface><Member>Call`. */
internal const val INTERACTION_CALL: String = "InteractionCall_"

/**
 * Names the JVM class of the file's top-level functions `<file>Kt_`: the
 * default, `FacesKt` for `Faces.kt`, is a class a declaration can also name.
 */
internal fun FileSpec.Builder.jvmName(file: String): FileSpec.Builder = addAnnotation(
    AnnotationSpec.builder(JvmName::class).useSiteTarget(AnnotationSpec.UseSiteTarget.FILE).addMember("%S", "${file}Kt_").build(),
)

// Every other name the generated code writes goes through `%T`, `%M` or `%N`,
// never as text: a declaration named `Long` or `List` would take a bare
// `Long.MAX_VALUE` or `List(n) { … }`.

/** `List(size) { … }`, the function. */
internal val LIST_OF_SIZE: MemberName = MemberName("kotlin.collections", "List")

/** `kotlin.collections.ArrayList`. */
internal val ARRAY_LIST: ClassName = ClassName("kotlin.collections", "ArrayList")

/** Kotlin's hard keywords: a name among them is written in backticks. */
private val KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
    "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "when", "while",
)

/**
 * [name] as Kotlin source writes it where KotlinPoet's `%N` does not reach,
 * inside an expression built as text: in backticks when it is a keyword.
 */
internal fun escaped(name: String): String = if (name in KEYWORDS) "`$name`" else name

/** The property a struct or tuple field is: its `camel_case` with a lower-case first letter. */
internal val Spellings.property: String get() = camel.replaceFirstChar(Char::lowercaseChar)

/**
 * The members Kotlin or the plugin gives every enum class, which an entry
 * shares a scope with: Kotlin's `name`, `ordinal` and `entries`, and the
 * plugin's `value` and companion.
 */
private val ENUM_MEMBERS = setOf("name", "ordinal", "entries", "value", "Companion")

/** The fixed properties of an enum set's companion, beside which its bits are declared. */
private val ENUM_SET_MEMBERS = setOf("EMPTY", "DECLARED_MASK")

/**
 * An injective escape: a name that is one of [reserved] followed by zero or
 * more `_` gets one more `_`, so `value` is `value_` and `value_` is
 * `value__`, and every other name is kept.
 */
private fun escapedFrom(reserved: Set<String>, name: String): String =
    if (name.trimEnd('_') in reserved) "${name}_" else name

/** An enum value's entry: its declared name, escaped from the enum's own members. */
internal fun enumEntry(name: Spellings): String = escapedFrom(ENUM_MEMBERS, name.declared)

/** An enum set bit's companion property: its declared name, escaped from the companion's own. */
internal fun enumSetBit(name: Spellings): String = escapedFrom(ENUM_SET_MEMBERS, name.declared)
