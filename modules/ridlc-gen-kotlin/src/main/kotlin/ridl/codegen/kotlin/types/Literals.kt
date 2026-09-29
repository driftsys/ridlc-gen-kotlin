package ridl.codegen.kotlin.types

import java.math.BigDecimal

/**
 * The model's canonical decimal text as Kotlin literals. A text that is not
 * the number its class says is a refusal, not a silent `0`.
 */
internal object Literals {
    private val INTEGER = Regex("-?[0-9]+")

    fun long(text: String): String {
        if (!INTEGER.matches(text)) refuse("`$text` is not an integer")
        val value = text.toBigInteger()
        if (value < Long.MIN_VALUE.toBigInteger() || value > Long.MAX_VALUE.toBigInteger()) {
            refuse("`$text` does not fit a 64-bit signed integer")
        }
        // `-9223372036854775808L` is not a Kotlin literal: the minus is a
        // unary operator over a literal that overflows.
        return if (value == Long.MIN_VALUE.toBigInteger()) "Long.MIN_VALUE" else "${text}L"
    }

    fun double(text: String): String {
        val parsed = text.toBigDecimalOrNull() ?: refuse("`$text` is not a decimal number")
        if (parsed.toDouble().isInfinite()) refuse("`$text` does not fit a 64-bit float")
        return if (text.any { it == '.' || it == 'e' || it == 'E' }) text else "$text.0"
    }

    fun isLongMin(text: String): Boolean = text.toBigIntegerOrNull() == Long.MIN_VALUE.toBigInteger()

    fun isLongMax(text: String): Boolean = text.toBigIntegerOrNull() == Long.MAX_VALUE.toBigInteger()

    fun decimal(text: String): BigDecimal = text.toBigDecimalOrNull() ?: refuse("`$text` is not a decimal number")
}
