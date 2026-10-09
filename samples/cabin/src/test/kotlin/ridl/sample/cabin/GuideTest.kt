package ridl.sample.cabin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * #47: the excerpts of `docs/guide.md` cannot drift. Each `kotlin` or `ridl`
 * block follows an `<!-- excerpt: <path> -->` line, blank lines aside, naming a file of this
 * sample, its generated code under `build/generated/ridl` included, and is
 * found in that file verbatim, in order, a `// ...` line standing for the
 * lines it leaves out. Indentation is not compared: `prim fmt` re-indents the
 * Kotlin of a Markdown block.
 */
class GuideTest {
    private val guide: Path = Path.of(System.getProperty("guide.file", "../../docs/guide.md"))

    private class Excerpt(val source: String, val lines: List<String>, val at: Int)

    private fun excerpts(): List<Excerpt> {
        val lines = Files.readAllLines(guide)
        val marker = Regex("""^<!-- excerpt: (\S+) -->$""")
        val found = mutableListOf<Excerpt>()
        var i = 0
        while (i < lines.size) {
            val fence = Regex("""^```(kotlin|ridl)$""").find(lines[i])
            if (fence != null) {
                // The nearest line above that is not blank: the formatter puts a blank line after the comment.
                val above = (i - 1 downTo 0).firstOrNull { lines[it].isNotBlank() }?.let { lines[it] } ?: ""
                val source = marker.find(above)?.groupValues?.get(1)
                    ?: error("guide line ${i + 1}: a ${fence.groupValues[1]} block with no excerpt line before it")
                val end = (i + 1 until lines.size).first { lines[it] == "```" }
                found += Excerpt(source, lines.subList(i + 1, end), i + 1)
                i = end
            }
            i++
        }
        return found
    }

    /** The runs of [lines] between `// ...` lines. */
    private fun chunks(lines: List<String>): List<String> {
        val runs = mutableListOf(mutableListOf<String>())
        for (line in lines) {
            if (line.trim() == "// ...") runs += mutableListOf<String>() else runs.last() += line.trim()
        }
        return runs.filter { it.isNotEmpty() }.map { it.joinToString("\n") }
    }

    @Test
    fun `every excerpt of the guide is in its source, in order`() {
        val excerpts = excerpts()
        assertTrue(excerpts.size >= 10, "the guide holds ${excerpts.size} excerpts")
        for (excerpt in excerpts) {
            val file = Path.of(excerpt.source)
            assertTrue(Files.isRegularFile(file), "guide line ${excerpt.at}: no file ${excerpt.source}")
            // Framed in newlines, so a chunk matches whole lines only.
            val text = Files.readAllLines(file).joinToString("\n", "\n", "\n") { it.trim() }
            var from = 0
            for (chunk in chunks(excerpt.lines)) {
                val at = text.indexOf("\n$chunk\n", from)
                assertTrue(at >= 0, "guide line ${excerpt.at}: not in ${excerpt.source}, in order:\n$chunk")
                from = at + chunk.length + 1
            }
        }
    }

    @Test
    fun `the guide names the versions its excerpts come from`() {
        val text = Files.readString(guide)
        val release = Files.readString(Path.of("../../modules/conformance/ridl-release")).trim()
        assertEquals(true, "`$release`" in text, "the guide names ridl $release")
    }
}
