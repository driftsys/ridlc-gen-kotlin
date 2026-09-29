// The payload encodings: the spelling of `ridl_rt::encoding` (docs/design.md §3).
package ridl.rt.encoding

import ridl.rt.contract.EncodedSizes

/**
 * A payload encoding. `ridl_rt::encoding::Encoding` and its three marker
 * types, `FlatBuffers`, `Proto3` and `ReprC`.
 *
 * The set is closed, as the Rust trait is sealed: a new encoding is a decision
 * recorded in an ADR, not a class in another module. [tag] is the frame's
 * encoding tag and [encodingName] the Rust `Encoding::NAME`, spelled as the
 * cargo feature is.
 */
public enum class Encoding(public val tag: Int, public val encodingName: String) {
    /** FlatBuffers. */
    FlatBuffers(1, "flatbuffers"),

    /** proto3. */
    Proto3(2, "proto3"),

    /** `repr(C)`, the fixed-layout encoding of ADR-0020 decision 1. */
    ReprC(3, "repr-c"),
    ;

    /**
     * This encoding's field of [sizes]: a payload's largest encoded size in
     * this encoding, or `null` when the toolchain cannot size it.
     * `ridl_rt::encoding::Encoding::max_size`. A `when` with no `else`, so an
     * encoding added without saying which field it reads does not compile.
     */
    public fun maxSize(sizes: EncodedSizes): UInt? = when (this) {
        FlatBuffers -> sizes.flatbuffers
        Proto3 -> sizes.proto3
        ReprC -> sizes.reprC
    }

    public companion object {
        /** The encoding whose frame tag is [tag], or `null` for a tag no encoding has. */
        public fun fromTag(tag: Int): Encoding? = entries.firstOrNull { it.tag == tag }
    }
}
