package ridl.sample.cabin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DemoTest {
    @Test
    fun `the four round trips carry their values`() {
        assertEquals(listOf("signal ok 21", "event ok 5", "command ok 42", "query ok 7"), demo())
    }

    @Test
    fun `the round trips carry their values when suspended, across threads`() {
        assertEquals(listOf("coroutine signal ok 21,22", "coroutine query ok 7", "coroutine command ok 42", "coroutine event ok 5"), coroutineDemo())
    }

    @Test
    fun `the sample uses only the public clients`() {
        val root = java.nio.file.Path.of("src/main/kotlin/ridl/sample/cabin")
        for (file in listOf("Main.kt", "CoroutineDemo.kt")) {
            val text = root.resolve(file).toFile().readText()
            for (internal in listOf("PollClient", "dispatch(", "Ack(", "Reply(", "Correlation")) {
                assertEquals(false, internal in text, "$file uses $internal")
            }
        }
    }
}
