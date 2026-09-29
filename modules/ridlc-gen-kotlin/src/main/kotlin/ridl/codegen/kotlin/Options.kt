package ridl.codegen.kotlin

import ridl.codegen.v1.Plugin.CodegenRequest

/** The encodings a codec can be generated for (docs/design.md §4, O-K1). */
enum class WireEncoding(val key: String) {
    FlatBuffers("flatbuffers"),
}

/**
 * The backend options this plugin reads (docs/design.md §2, step 3). Two keys
 * are known; any other key is an error diagnostic, as every in-tree backend
 * answers one.
 */
data class Options(
    /** `kotlin-package`: where the generated files are placed. Default: the ridl package's dotted name (O-K2). */
    val kotlinPackage: String,
    /** `wire-encoding`: the payload encoding the codec is generated for. Default: `flatbuffers`. */
    val wireEncoding: WireEncoding,
) {
    sealed interface Parsed {
        data class Ok(val options: Options) : Parsed

        data class Refused(val messages: List<String>) : Parsed
    }

    companion object {
        const val KOTLIN_PACKAGE: String = "kotlin-package"
        const val WIRE_ENCODING: String = "wire-encoding"

        private val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{Nd}_]*")

        fun parse(request: CodegenRequest): Parsed {
            val messages = mutableListOf<String>()
            var kotlinPackage = request.model.name.dotted
            var wireEncoding = WireEncoding.FlatBuffers
            for (option in request.optionsList) {
                when (option.key) {
                    KOTLIN_PACKAGE ->
                        if (isPackageName(option.value)) {
                            kotlinPackage = option.value
                        } else {
                            messages += "$PLUGIN: option `$KOTLIN_PACKAGE` is `${option.value}`, " +
                                "which is not a Kotlin package name"
                        }
                    WIRE_ENCODING ->
                        when (val encoding = WireEncoding.entries.firstOrNull { it.key == option.value }) {
                            null -> messages += "$PLUGIN: option `$WIRE_ENCODING` is `${option.value}`; " +
                                "the known value is ${WireEncoding.entries.joinToString { "`${it.key}`" }}"
                            else -> wireEncoding = encoding
                        }
                    else -> messages += "$PLUGIN: unknown option `${option.key}`; " +
                        "the known options are `$KOTLIN_PACKAGE` and `$WIRE_ENCODING`"
                }
            }
            return if (messages.isEmpty()) Parsed.Ok(Options(kotlinPackage, wireEncoding)) else Parsed.Refused(messages)
        }

        /** Dot-separated identifiers; a keyword segment is escaped by the emitter, not refused here. */
        fun isPackageName(value: String): Boolean =
            value.isNotEmpty() && value.split('.').all { IDENTIFIER.matches(it) }
    }
}
