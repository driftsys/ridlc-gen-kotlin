package ridl.codegen.kotlin

import com.google.protobuf.util.JsonFormat
import com.google.protobuf.util.withRecursionLimit
import ridl.codegen.v1.Plugin.CodegenRequest
import ridl.codegen.v1.Plugin.CodegenResponse

/**
 * The two messages on the pipe, in canonical protobuf JSON
 * (docs/design.md §2, steps 1 and 4).
 */
object Wire {
    /**
     * The IR specification §4 requires a reader to accept 516 levels of
     * nesting and recommends 1,000; the parser's default is 100.
     */
    const val RECURSION_LIMIT: Int = 1000

    /**
     * The parser recurses once per JSON level, several frames deep, and 1,000
     * levels overflow the JVM's default 1 MiB thread stack. The stack is
     * reserved, not committed, so a request that nests shallowly costs
     * nothing for it.
     */
    const val PARSER_STACK_BYTES: Long = 256L * 1024 * 1024

    private val parser: JsonFormat.Parser =
        JsonFormat.parser().ignoringUnknownFields().withRecursionLimit(RECURSION_LIMIT)

    private val printer: JsonFormat.Printer = JsonFormat.printer()

    /** Parses a request leniently: an unknown key, at any depth, is ignored (IR specification §8). */
    fun readRequest(json: String): CodegenRequest = onDeepStack {
        CodegenRequest.newBuilder().also { parser.merge(json, it) }.build()
    }

    /** Renders a response, pretty-printed so a response is readable in a fixture. */
    fun writeResponse(response: CodegenResponse): String = printer.print(response) + "\n"

    /** Runs [block] on a thread whose stack is [PARSER_STACK_BYTES], and returns or rethrows its outcome. */
    fun <T> onDeepStack(block: () -> T): T {
        var outcome: Result<T>? = null
        val thread = Thread(null, { outcome = runCatching(block) }, "$PLUGIN-reader", PARSER_STACK_BYTES)
        thread.start()
        thread.join()
        return outcome!!.getOrThrow()
    }
}
