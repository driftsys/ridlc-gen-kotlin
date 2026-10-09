package ridl.rt.trace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * #65: the tests that set the trace propagation hook run in a JVM of their
 * own only when tagged `process-hook` (#52), and nothing else enforces the
 * tag. A test file that calls `setPropagation` without it would make `test`
 * order-dependent again, so this fails on one, whatever the test order.
 */
class ProcessHookTagTest {
    @Test
    fun `every test that sets the propagation hook is tagged process-hook`() {
        val untagged = Files.walk(Path.of("src/test/kotlin")).use { paths ->
            // This file names the call it looks for, so it skips itself.
            paths.filter { it.extension == "kt" && it.fileName.toString() != "ProcessHookTagTest.kt" }.toList()
                .filter { "setPropagation(" in it.readText() && "@Tag(\"process-hook\")" !in it.readText() }
                .map { it.fileName.toString() }
        }
        assertEquals(emptyList<String>(), untagged)
    }
}
