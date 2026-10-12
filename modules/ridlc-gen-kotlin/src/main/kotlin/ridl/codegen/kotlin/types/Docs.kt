// The KDoc of a generated item, from the doc fields the model carries
// (ADR-0026, ADR-0028 decision 6, #86): the doc text with each resolved link
// rewritten as a KDoc link, the generator's own sentence, a `Rules:` list,
// then the `@param`, `@see` and `@since` tags. The Rust backend's `docs.rs`
// is the reference; only the syntax is KDoc's.
//
// A link whose target is not an item of this output, or is `internal`,
// renders as a code span of the target's qualified name, so that no generated
// doc carries a link that does not resolve.
package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import ridl.codegen.v1.ModelOuterClass.Arm
import ridl.codegen.v1.ModelOuterClass.Constant
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DocLink
import ridl.codegen.v1.ModelOuterClass.EnumValue
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.Interface
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Param
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Rule
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Visibility

/** The doc fields of one carrier in the model. */
internal class DocParts(
    val doc: String,
    val links: List<DocLink>,
    val see: List<DocLink>,
    val since: List<String>,
    val rules: List<Rule>,
) {
    companion object {
        val NONE: DocParts = DocParts("", emptyList(), emptyList(), emptyList(), emptyList())

        fun of(c: Declaration): DocParts = DocParts(c.doc, c.linksList, c.seeList, c.sinceList, c.rulesList)

        fun of(c: Field): DocParts = DocParts(c.doc, c.linksList, c.seeList, c.sinceList, c.rulesList)

        fun of(c: EnumValue): DocParts = DocParts(c.doc, c.linksList, c.seeList, c.sinceList, c.rulesList)

        fun of(c: Arm): DocParts = DocParts(c.doc, c.linksList, c.seeList, c.sinceList, c.rulesList)

        fun of(c: Interface): DocParts = DocParts(c.doc, c.linksList, c.seeList, c.sinceList, c.rulesList)

        fun of(c: Interaction): DocParts = DocParts(c.doc, c.linksList, c.seeList, c.sinceList, c.rulesList)

        fun of(c: Param): DocParts = DocParts(c.doc, c.linksList, c.seeList, c.sinceList, c.rulesList)
    }
}

/**
 * Whether `Types.kt` spells [constant] as a property of `Constants_`: a regex,
 * a primitive other than `bytes`, or a value of a resolved scalar not backed
 * by `bytes`. A bytes constant, and one whose type does not resolve, has no
 * Kotlin spelling and is left out, as the Rust backend leaves it out.
 */
internal fun Model.constantEmitted(constant: Constant): Boolean = when (constant.typedCase) {
    Constant.TypedCase.REGEX_BODY -> true
    Constant.TypedCase.PRIMITIVE -> constant.primitive in SPELLED_PRIMITIVES
    Constant.TypedCase.NAMED -> {
        val ref = constant.named
        val target = if (ref.resolved) runCatching { declarationOf(ref) }.getOrNull() else null
        target != null && target.hasScalar() && target.scalar.class_ != ScalarClass.SCALAR_CLASS_BYTES
    }
    else -> false
}

private val SPELLED_PRIMITIVES = setOf(
    PrimitiveType.PRIMITIVE_TYPE_INTEGER,
    PrimitiveType.PRIMITIVE_TYPE_FLOAT,
    PrimitiveType.PRIMITIVE_TYPE_BOOLEAN,
    PrimitiveType.PRIMITIVE_TYPE_STRING,
)

/**
 * Resolves a doc link's target to the Kotlin path of an item of this output.
 * [pkg] is the Kotlin package the files are generated into; [faced] holds the
 * indices into `model.interfaces` of the interfaces whose face this output
 * holds.
 */
internal class Links(private val model: Model, private val pkg: String, private val faced: Set<Int>) {
    private val source = model.scope.`package`.ifEmpty { model.name.dotted }

    /**
     * The fully qualified Kotlin path of [target], a canonical qualified name
     * `pkg.Name` with an optional `.member`, or `null` when the target is not
     * an item of this output or is `internal`.
     *
     * A declaration of the package is its class in [pkg], a constant its
     * property of `Constants_` when `Types.kt` spells it. A foreign
     * declaration is its class in the package the faces name it by, the ridl
     * package's dotted name; a foreign constant is not, as its spelling is
     * another run's. A member is the class's path and the member's Kotlin
     * name: a field's property, an enum value's or an enum set bit's entry, a
     * union arm's nested class. An interface with a face in this output is its
     * descriptor object, and one of its signals, commands and queries the
     * method of `<Iface>Client`; an event and a fixed member have none.
     *
     * A path with a segment Kotlin writes in backticks, a keyword, is `null`
     * as well, as the Rust backend drops a path with a raw identifier: a KDoc
     * link through it is not one every tool resolves.
     */
    fun path(target: String): String? = resolve(target)?.takeIf { path -> path.split('.').all { escaped(it) == it } }

    private fun resolve(target: String): String? {
        val local = model.declarationsList.map { Triple(source, pkg, it) }
        val foreign = model.foreignList.map { Triple(it.`package`, it.`package`, it.declaration) }
        for ((owner, kotlinPackage, declaration) in local + foreign) {
            val member = rest(target, "$owner.${declaration.name.declared}") ?: continue
            if (declaration.visibility == Visibility.VISIBILITY_INTERNAL) return null
            if (declaration.hasConstant()) {
                val emitted = owner == source && member.isEmpty() && model.constantEmitted(declaration.constant)
                return if (emitted) "$kotlinPackage.$CONSTANTS.${declaration.name.declared}" else null
            }
            val type = "$kotlinPackage.${declaration.name.camel}"
            if (member.isEmpty()) return type
            return memberName(declaration, member.removePrefix("."))?.let { "$type.$it" }
        }
        for ((index, iface) in model.interfacesList.withIndex()) {
            if (!iface.hasDeclared()) continue
            val member = rest(target, "$source.${iface.declared.declared}") ?: continue
            if (index !in faced || iface.visibility == Visibility.VISIBILITY_INTERNAL) return null
            val name = iface.declared.camel
            if (member.isEmpty()) return "$pkg.$name"
            val interaction = iface.slotsList.filter { it.hasInteraction() }.map { it.interaction }
                .firstOrNull { it.name.declared == member.removePrefix(".") } ?: return null
            if (!(interaction.hasSignal() || interaction.hasCommand() || interaction.hasQuery())) return null
            return "$pkg.${name}Client.${interaction.name.camel.replaceFirstChar(Char::lowercaseChar)}"
        }
        return null
    }

    /** What follows [qualified] in [target]: empty, or `.member`; `null` when [target] names something else. */
    private fun rest(target: String, qualified: String): String? {
        val rest = target.removePrefix(qualified)
        return if (rest.length == target.length || (rest.isNotEmpty() && !rest.startsWith('.'))) null else rest
    }

    /** The Kotlin name of [member], a declared name, in [declaration], or `null` when it has none. */
    private fun memberName(declaration: Declaration, member: String): String? = when {
        declaration.hasStruct() -> declaration.struct.slotsList.filter { it.hasField() }.map { it.field }
            .firstOrNull { it.name.declared == member }?.name?.property
        declaration.hasEnum() -> declaration.enum.valuesList.firstOrNull { it.name.declared == member }?.let { enumEntry(it.name) }
        declaration.hasEnumSet() -> declaration.enumSet.bitsList.firstOrNull { it.name.declared == member }?.let { enumSetBit(it.name) }
        declaration.hasUnion() -> declaration.union.armsList.firstOrNull { it.name.declared == member }?.name?.camel
        else -> null
    }

    /** `[text][path]` when the target is an item of this output, and a code span of the qualified target otherwise. */
    fun rendered(link: DocLink): String = path(link.target)?.let { "[${link.text}][$it]" } ?: "`${link.target}`"

    /**
     * [doc] with the `len` bytes at each link's `offset` replaced by the
     * link's rendering, from the last link to the first, so that each earlier
     * offset stays valid. The offsets count UTF-8 bytes. A link whose range is
     * empty, out of bounds, not on a character boundary, or overlapping a link
     * already replaced leaves the text as it is.
     */
    fun linked(doc: String, links: List<DocLink>): String {
        var bytes = doc.toByteArray(Charsets.UTF_8)
        var limit = bytes.size
        for (link in links.sortedByDescending { it.offset }) {
            // `uint32` fields: one past `Int.MAX_VALUE` reads negative, and is out of bounds as well.
            val start = link.offset
            val end = start.toLong() + link.len.toLong()
            if (link.len <= 0 || start < 0 || end > limit || !boundary(bytes, start) || !boundary(bytes, end.toInt())) continue
            val rendered = rendered(link).toByteArray(Charsets.UTF_8)
            bytes = bytes.copyOfRange(0, start) + rendered + bytes.copyOfRange(end.toInt(), bytes.size)
            limit = start
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** Whether [at] starts a UTF-8 character of [bytes], or is its end. */
    private fun boundary(bytes: ByteArray, at: Int): Boolean =
        at == bytes.size || (at in bytes.indices && (bytes[at].toInt() and 0xC0) != 0x80)

    /**
     * The KDoc of one carrier, each part omitted when it is empty: the doc
     * text, [sentence] as its own paragraph, a `Rules:` list of each rule's
     * text verbatim, then one `@param` per entry of [params] that has a doc
     * or a rule, its rules listed under it, one `@see` per see target, and
     * one `@since` per version. A see target that is not an item of this
     * output is a code span after `@see`.
     */
    fun kdoc(parts: DocParts, sentence: String? = null, params: List<Pair<String, DocParts>> = emptyList()): String {
        val paragraphs = mutableListOf<String>()
        if (parts.doc.isNotEmpty()) paragraphs += linked(parts.doc, parts.links)
        if (!sentence.isNullOrEmpty()) paragraphs += sentence
        if (parts.rules.isNotEmpty()) paragraphs += rules(parts.rules)
        val tags = mutableListOf<String>()
        for ((name, param) in params) {
            if (param.doc.isEmpty() && param.rules.isEmpty()) continue
            tags += listOf("@param $name ${linked(param.doc, param.links)}".trimEnd()) +
                param.rules.map { "- ${it.text}" }
        }
        tags += parts.see.map { see -> "@see ${path(see.target) ?: "`${see.target}`"}" }
        tags += parts.since.map { "@since $it" }
        if (tags.isNotEmpty()) paragraphs += tags.joinToString("\n")
        return paragraphs.joinToString("\n\n")
    }

    private fun rules(rules: List<Rule>): String = (listOf("Rules:") + rules.map { "- ${it.text}" }).joinToString("\n")
}

/** Adds [kdoc], a rendered KDoc, unless it is empty. */
internal fun TypeSpec.Builder.doc(kdoc: String): TypeSpec.Builder = apply { if (kdoc.isNotEmpty()) addKdoc("%L", kdoc) }

internal fun FunSpec.Builder.doc(kdoc: String): FunSpec.Builder = apply { if (kdoc.isNotEmpty()) addKdoc("%L", kdoc) }

internal fun PropertySpec.Builder.doc(kdoc: String): PropertySpec.Builder = apply { if (kdoc.isNotEmpty()) addKdoc("%L", kdoc) }

internal fun ParameterSpec.Builder.doc(kdoc: String): ParameterSpec.Builder = apply { if (kdoc.isNotEmpty()) addKdoc("%L", kdoc) }
