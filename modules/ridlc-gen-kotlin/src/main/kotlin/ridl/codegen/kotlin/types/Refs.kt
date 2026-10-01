package ridl.codegen.kotlin.types

import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.TypeRef

/**
 * Whether [ref] names a declaration of another package than the one lowered:
 * `TypeRef.foreign`, which ridl 0.5.0 sets from the declaring package also on
 * a reference copied out of a foreign declaration (driftsys/ridl#586).
 */
internal fun Model.isForeign(ref: TypeRef): Boolean = ref.foreign

/**
 * The declaration [ref] names, from `Model.declarations` or `Model.foreign`.
 * A reference that resolves to nothing, whose `foreign` disagrees with its
 * package, past the end of its table, or to a foreign declaration of another
 * package than its own is refused, so a
 * malformed model is a diagnostic naming the reference, never an exception.
 */
internal fun Model.declarationOf(ref: TypeRef): Declaration {
    if (!ref.resolved) refuse("the reference `${ref.reference}` resolves to no declaration")
    val foreign = isForeign(ref)
    if (foreign != (ref.`package` != scope.`package`)) {
        refuse(
            "the reference `${ref.reference}` of package ${ref.`package`} is marked " +
                "${if (foreign) "foreign" else "local"} in package ${scope.`package`}",
        )
    }
    val size = if (foreign) foreignCount else declarationsCount
    if (ref.index !in 0 until size) {
        refuse(
            "the reference `${ref.reference}` resolves to index ${ref.index} of package ${scope.`package`}'s $size " +
                "${if (foreign) "foreign" else "local"} declarations",
        )
    }
    if (!foreign) return getDeclarations(ref.index)
    val entry = getForeign(ref.index)
    if (entry.`package` != ref.`package`) {
        refuse("the reference `${ref.reference}` of package ${ref.`package`} resolves to a declaration of package ${entry.`package`}")
    }
    return entry.declaration
}
