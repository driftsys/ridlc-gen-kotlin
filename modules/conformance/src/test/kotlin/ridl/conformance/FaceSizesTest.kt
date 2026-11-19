package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Options
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Visibility
import ridl.rt.contract.Interface
import ridl.rt.encoding.Encoding
import java.nio.file.Path

/**
 * docs/design.md §5: a face's `sizes(encoding)` is the Rust descriptor's
 * `Sizes<E>` table, for every public interface of every corpus package that
 * selects an encoding, in each encoding the package carries. The generated
 * code is compiled and each table read from its descriptor; the Rust tables,
 * `RESERVATIONS`, `MAX_BUFFER_SIZE` and `EVENT_SOURCE_BUFFER_SIZE`, are read
 * from the Rust backend's crate of the same package by `just rust-verdicts`,
 * which checks them in as `resources/sizes/<package>-rust-sizes.txt`. Both
 * are built from each codec's own `maxSize`, so a codec bound or a row of
 * either backend off by one byte turns it red.
 */
class FaceSizesTest {
    @TempDir
    lateinit var work: Path

    /** The size tables of the public descriptors the build of corpus package [name] generates, one line each, sorted. */
    private fun tables(name: String): List<String> {
        val requests = Harness.capturedRequests(name, work.resolve("capture-$name")).values.map(Wire::readRequest)
        val responses = requests.map { request -> request to Generator.generate(request) }
        for ((request, response) in responses) {
            // A skipped interface is a warning: every corpus interface is faced, as the Rust backend faces it.
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message }, request.model.name.dotted)
        }
        val sources = responses.flatMap { (_, response) -> response.filesList.map { it.path to it.text } }.toMap()
        val compiled = Compiler.compile(sources, work.resolve("compile-$name"))
        assertTrue(compiled.ok, compiled.messages)
        val lines = mutableListOf<String>()
        for (request in requests) {
            val options = (Options.parse(request) as Options.Parsed.Ok).options
            val ifaces = request.model.interfacesList.filter { it.hasDeclared() && it.visibility != Visibility.VISIBILITY_INTERNAL }
            for (iface in ifaces) {
                val descriptor = compiled.classLoader!!.loadClass("${options.kotlinPackage}.${iface.declared.camel}")
                    .getField("INSTANCE").get(null) as Interface
                for (encoding in options.encodings.map { Encoding.valueOf(it.name) }) {
                    val sizes = descriptor.sizes(encoding)
                    lines += "${request.model.name.dotted}.${iface.declared.camel} ${encoding.name} " +
                        "reservations=${sizes.reservations} maxBuffer=${sizes.maxBufferSize} eventBuffer=${sizes.eventSourceBufferSize}"
                }
            }
        }
        return lines.sorted()
    }

    @TestFactory
    fun `every face's size tables are Rust's`(): List<DynamicTest> =
        Harness.packages().filter { Harness.encodings(it).isNotEmpty() }.map { name ->
            DynamicTest.dynamicTest(name) {
                val kotlin = tables(name)
                val resource = javaClass.getResource("/sizes/$name-rust-sizes.txt")
                if (kotlin.isEmpty()) {
                    assertEquals(null, resource, "$name has no public interface, and a Rust size table")
                    return@dynamicTest
                }
                checkNotNull(resource) { "no resources/sizes/$name-rust-sizes.txt: run `just rust-verdicts --write`" }
                assertEquals(resource.readText().lines().filter { it.isNotBlank() }, kotlin)
            }
        }
}
