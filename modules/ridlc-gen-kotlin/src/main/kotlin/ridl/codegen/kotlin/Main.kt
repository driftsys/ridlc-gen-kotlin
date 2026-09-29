package ridl.codegen.kotlin

import kotlin.system.exitProcess

/**
 * `ridlc-gen-kotlin`: one request on standard input, one response on standard
 * output (docs/design.md §2). Every failure the host can read is a response
 * and exit 0; anything thrown is written to standard error with its stack and
 * is exit 3, which the host reports as a failed plugin.
 */
fun main() {
    val status = try {
        val input = System.`in`.readBytes().toString(Charsets.UTF_8)
        val request = Wire.readRequest(input)
        System.err.println("$PLUGIN: request from ridlc ${request.toolchain.ifEmpty { "(unknown)" }}")
        val output = Wire.writeResponse(Generator.generate(request))
        System.out.write(output.toByteArray(Charsets.UTF_8))
        System.out.flush()
        0
    } catch (e: Throwable) {
        System.err.println("$PLUGIN: failed")
        e.printStackTrace()
        3
    }
    exitProcess(status)
}
