package ridl.codegen.kotlin.types

import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.TypeRef

/**
 * Whether [ref] names a declaration of another package than the one lowered.
 *
 * WORKAROUND for driftsys/ridl#586: the schema's `TypeRef.foreign` says
 * this, but the pinned release's lowering leaves it false on a reference it
 * copies out of a foreign declaration, such as the arm of an imported union,
 * while re-indexing it into `Model.foreign`. `TypeRef.package` is right in
 * both cases, so foreignness is read from it. Return `ref.foreign` again once
 * the pin carries the fix.
 */
internal fun Model.isForeign(ref: TypeRef): Boolean = ref.`package` != scope.`package`

/**
 * The declaration [ref] names, from `Model.declarations` or `Model.foreign`.
 * A reference that resolves to nothing, past the end of its table, or to a
 * foreign declaration of another package than its own is refused, so a
 * malformed model is a diagnostic naming the reference, never an exception.
 */
internal fun Model.declarationOf(ref: TypeRef): Declaration {
    if (!ref.resolved) refuse("the reference `${ref.reference}` resolves to no declaration")
    val foreign = isForeign(ref)
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
