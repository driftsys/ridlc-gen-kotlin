package ridl.codegen.kotlin.types

import ridl.codegen.v1.ModelOuterClass.AbsentCause
import ridl.codegen.v1.ModelOuterClass.SizeState

/** The Rust proto3 size-state cause, or null when the state is bounded. */
internal fun proto3Unbounded(state: SizeState?): String? = when (state?.stateCase) {
    SizeState.StateCase.BOUNDED -> null
    SizeState.StateCase.ABSENT -> when (state.absent.cause) {
        AbsentCause.ABSENT_CAUSE_NO_BOUND ->
            "a member of it has no finite bound (NoBound): a string or bytes with no length bound; declare the bound in the type"
        AbsentCause.ABSENT_CAUSE_OVERFLOW ->
            "its bound does not fit a u32 (Overflow): a bound past u32::MAX, a sum or product of bounds that does not fit, or a " +
                "nesting past the sizer's depth limit; lower the bounds in the type"
        AbsentCause.ABSENT_CAUSE_REFUSED_MEMBER ->
            "a member of it is one proto3 cannot represent (RefusedMember): an optional array or map, an array of arrays or of " +
                "maps, a map whose value is a collection, a map key outside the integral and string scalars, a field number " +
                "proto3 reserves or exceeds, a name that does not resolve, a live enum value outside int32 in hand-built IR " +
                "(TYPL-225 rejects it in source), or a retired value outside int32 (legal in source)"
        AbsentCause.ABSENT_CAUSE_UNRESOLVED -> "it names a declaration the sizer did not resolve (Unresolved)"
        AbsentCause.ABSENT_CAUSE_NO_MESSAGE -> "no proto3 message defines it (NoMessage)"
        AbsentCause.ABSENT_CAUSE_ENCODING_UNDEFINED -> "no proto3 message defines it (EncodingUndefined)"
        // Rust reads a cause it does not know as `Unspecified`.
        else -> "no proto3 message defines it (Unspecified)"
    }
    SizeState.StateCase.UNBOUNDED -> "no finite bound exists for it"
    else -> "the lowered model carries no proto3 size state for it"
}

