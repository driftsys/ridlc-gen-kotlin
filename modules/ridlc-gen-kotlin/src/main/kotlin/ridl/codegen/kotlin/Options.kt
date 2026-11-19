package ridl.codegen.kotlin

import ridl.codegen.v1.Plugin.CodegenRequest

/**
 * The encodings a codec can be generated for (docs/design.md §4, O-K1), in
 * the Rust backend's order: its `WireEncoding`, the order it emits codecs and
 * `Sizes<E>` tables in (`descriptors.rs`), and the encoding tags 1, 2 and 3.
 * The face sorts the encodings it carries by this order, so its size tables
 * follow Rust's whatever the manifest's order (#133).
 */
enum class WireEncoding(val key: String) {
    FlatBuffers("flatbuffers"),
    Proto3("proto3"),
    Podbuf("podbuf"),
}

/**
 * The backend options this plugin reads (docs/design.md §2, step 3). Two keys
 * are known; any other key is an error diagnostic, as every in-tree backend
 * answers one.
 */
data class Options(
    /** `kotlin-package`: where the generated files are placed. Default: the ridl package's dotted name (O-K2). */
    val kotlinPackage: String,
    /**
     * `encodings`: the encodings the codec is generated for, in the order the
     * manifest names them. Default: none, and the package is then its types
     * alone, with no codec and no face, as every backend of the family
     * generates it (#83, driftsys/ridl#851).
     */
    val encodings: List<WireEncoding>,
) {
    sealed interface Parsed {
        data class Ok(val options: Options) : Parsed

        data class Refused(val messages: List<String>) : Parsed
    }

    companion object {
        const val KOTLIN_PACKAGE: String = "kotlin-package"
        const val ENCODINGS: String = "encodings"

        private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{Nd}_]*")

        /** The encoding names the family knows (ADR-0020), each one this plugin has a codec for. */
        private const val ENCODING_NAMES: String = "`flatbuffers`, `proto3` and `podbuf`"

        fun parse(request: CodegenRequest): Parsed {
            val messages = mutableListOf<String>()
            var kotlinPackage = request.model.name.dotted
            var encodings: List<WireEncoding>? = null
            for (option in request.optionsList) {
                when (option.key) {
                    KOTLIN_PACKAGE ->
                        if (isPackageName(option.value)) {
                            kotlinPackage = option.value
                        } else {
                            messages += "$PLUGIN: option `$KOTLIN_PACKAGE` is `${option.value}`, " +
                                "which is not a Kotlin package name"
                        }
                    ENCODINGS -> encodings = parseEncodings(option.value, messages)
                    else -> messages += "$PLUGIN: unknown option `${option.key}`; " +
                        "the known options are `$KOTLIN_PACKAGE` and `$ENCODINGS`"
                }
            }
            val options = Options(kotlinPackage, encodings ?: emptyList())
            return if (messages.isEmpty()) Parsed.Ok(options) else Parsed.Refused(messages)
        }

        /**
         * The `encodings` value as the host sends it, the manifest's names
         * joined by `,`, read as the Rust backend reads it: an empty element,
         * a name given twice and a name that is no encoding are each refused.
         */
        private fun parseEncodings(value: String, messages: MutableList<String>): List<WireEncoding> {
            val encodings = mutableListOf<WireEncoding>()
            for (name in value.split(',')) {
                val encoding = WireEncoding.entries.firstOrNull { it.key == name }
                when {
                    name.isEmpty() -> messages +=
                        "$PLUGIN: `$ENCODINGS` names an empty element; each element is one of $ENCODING_NAMES"
                    encoding in encodings -> messages +=
                        "$PLUGIN: `$ENCODINGS` names `$name` twice; each encoding is named once"
                    encoding != null -> encodings += encoding
                    else -> messages +=
                        "$PLUGIN: `$ENCODINGS` names `$name`, which is not an encoding; the names are $ENCODING_NAMES"
                }
            }
            return encodings
        }

        /** Dot-separated identifiers; a keyword segment is escaped by the emitter, not refused here. */
        fun isPackageName(value: String): Boolean =
            value.isNotEmpty() && value.split('.').all { IDENTIFIER.matches(it) }
    }
}
