// The well-formedness check behind `Rule.Utf8` that generated value objects
// and encoders call: Kotlin's own, as a Rust `str` cannot be ill-formed.
package ridl.rt.payload

/**
 * Whether [value] holds only Unicode scalar values: every high surrogate is
 * followed by a low one and every low surrogate follows a high one (typl
 * §4.4). A Kotlin `String` may hold an unpaired surrogate, which UTF-8 cannot
 * carry and `String.toByteArray` and `encodeToByteArray` replace without an
 * error, so a generated encoder or constructor checks it here first. The
 * TypeScript runtime's `isWellFormedString`.
 */
public fun isWellFormedString(value: String): Boolean {
    var i = 0
    while (i < value.length) {
        val unit = value[i]
        if (Character.isHighSurrogate(unit)) {
            if (i + 1 == value.length || !Character.isLowSurrogate(value[i + 1])) return false
            i += 2
        } else if (Character.isLowSurrogate(unit)) {
            return false
        } else {
            i += 1
        }
    }
    return true
}
