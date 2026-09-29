package ridl.codegen.kotlin.types

/**
 * A fact of the model the emitter cannot turn into Kotlin. It is thrown where
 * it is found, caught per declaration, and reported as one error diagnostic,
 * so every declaration the plugin refuses is named in one response.
 */
class Refusal(message: String) : Exception(message)

internal fun refuse(message: String): Nothing = throw Refusal(message)
