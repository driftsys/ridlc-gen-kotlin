package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * driftsys/ridlc-gen-kotlin#16: a name the plugin chose never refuses a
 * package. Each package here declares names that meet a name the plugin
 * writes, and its output must compile with warnings as errors and behave.
 */
class NamesTest {
    @TempDir
    lateinit var work: Path

    /** The files the plugin writes for [source], a package named `probe.<name>`, with no diagnostic. */
    private fun generate(name: String, source: String): Map<String, String> {
        val pkg = work.resolve("src-$name").createDirectories()
        pkg.resolve("ridl.toml").writeText("[package]\nname = \"probe.$name\"\nversion = \"1.0.0\"\n")
        pkg.resolve("$name.ridl").writeText("package probe.$name\n\n$source")
        val requests = Harness.capturedRequests(pkg, name, work, "--emit", "codegen-model")
        return requests.values.map(Wire::readRequest).flatMap { request ->
            val response = Generator.generate(request)
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
    }

    private fun compiles(sources: Map<String, String>, name: String): ClassLoader {
        val compiled = Compiler.compile(sources, work.resolve("compile-$name"))
        assertTrue(compiled.ok, compiled.messages)
        return compiled.classLoader!!
    }

    /**
     * Declarations named like every package-level name the plugin writes:
     * its constants object, its call base, the JVM classes of its three
     * files, and the Kotlin classes it names in expressions. The package
     * reaches each of those expressions: a `Long.MIN_VALUE` constant, a float
     * step, an array and a bytes init, a deadline and a serve bound.
     */
    @Test
    fun `declarations named like the plugin's own package-level names compile`() {
        val names = listOf(
            "Constants", "InteractionCall", "TypesKt", "CodecKt", "FacesKt", "Long", "Int", "List", "ArrayList",
            "ByteArray", "Math", "Unit", "Nothing", "String", "Boolean", "Double", "Any", "Map", "Regex", "Result",
        )
        val sources = generate(
            "chosen",
            names.joinToString("\n") { "type $it : integer [0..10]" } + """

            const LOWEST : integer = -9223372036854775808
            const TOP : Constants = 3

            type Even   : integer [-10..10]
            type Offset : float [-5.0..5.0 step 0.25]
            type Key    : bytes [0..4]

            struct Load {
              items : [Even; 2..3]
              key   : Key
              top   : Constants
            }

            interface Cabin {
              signal  load   : Load @10ms
              signal  offset : Offset @10ms
              event   moved  : Even @[100ms..1s]
              command set(level: Even) @[..50ms] [
                require level > 0
              ]
              query   half(of: Even): Even @[..200ms] [
                ensure result < 8
              ]
            }
            """.trimIndent(),
        )
        val faces = sources.getValue("probe/chosen/Faces.kt")
        assertTrue("CabinSetCall" in faces, "Cabin keeps its face")
        val loader = compiles(sources, "chosen")
        val constants = loader.loadClass("probe.chosen.Constants_")
        assertEquals(Long.MIN_VALUE, constants.getField("LOWEST").get(null))
        loader.loadClass("probe.chosen.FacesKt_")
        loader.loadClass("probe.chosen.CodecKt_")
    }

    /**
     * Names inside one declaration's scope that meet a member Kotlin or the
     * plugin gives it: a field named like `equals`' parameter or
     * `hashCode`'s local, fields and entries named like hard keywords, enum
     * entries named like an enum class's own members, and enum set bits
     * named like the companion's constants.
     */
    @Test
    fun `names that meet a declaration's own members compile and behave`() {
        val sources = generate(
            "members",
            """
            type Level : integer [0..100]

            enum Mode {
              name      = 0
              ordinal   = 1
              entries   = 2
              value     = 3
              Companion = 4
              null      = 5
              in        = 6
            }

            enumset Flags {
              EMPTY         = 0
              EMPTY_        = 1
              DECLARED_MASK = 2
              in            = 3
            }

            struct Record {
              other  : Level
              result : Level
              class  : Level
              fun    : Level
              this   : Level
              mode   : Mode
              flags  : Flags
              pair   : (other : Level, in : Level)
            }

            interface Panel {
              signal  mode : Mode @10ms
              signal  in   : Level @10ms
              command set(this: Level) @[..50ms] [
                require this > 0
              ]
              query   get(object: Level): Record @[..200ms]
            }
            """.trimIndent(),
        )
        val probe = """
            package probe.members.check

            import probe.members.*

            object MembersProbe {
                private fun record(other: Long) = Record(
                    other = Level.of(other), result = Level.of(2), `class` = Level.of(3), `fun` = Level.of(4),
                    `this` = Level.of(5), mode = Mode.name_, flags = Flags.EMPTY_ + Flags.`in`,
                    pair = RecordPair(other = Level.of(6), `in` = Level.of(7)),
                )

                @JvmStatic
                fun probe(): List<String> {
                    val failures = mutableListOf<String>()
                    if (record(1) == record(9)) failures += "two records whose `other` differs are equal"
                    if (record(1) != record(1)) failures += "two equal records differ"
                    if (record(1).hashCode() == record(9).hashCode()) failures += "hashCode ignores `other`"
                    if (Mode.fromValue(3) != Mode.value_ || Mode.fromValue(4) != Mode.Companion_) failures += "an escaped entry is not its value"
                    if (Mode.value_.value != 3L) failures += "the escaped entry lost its discriminant"
                    if (Flags.EMPTY.bits != 0L || Flags.EMPTY_.bits != 1L || Flags.EMPTY__.bits != 2L) failures += "the escape is not injective"
                    if ("class=" !in record(1).toString()) failures += "toString names the field"
                    val buffer = java.nio.ByteBuffer.allocate(RecordCodec.maxSize)
                    RecordCodec.encode(record(1), buffer)
                    val decoded = RecordCodec.decode(RecordCodec.verify(buffer.flip()))
                    if (decoded != record(1)) failures += "the codec does not round-trip: ${'$'}decoded"
                    return failures
                }
            }
        """.trimIndent()
        val loader = compiles(sources + ("probe/members/check/Probe.kt" to probe), "members")
        @Suppress("UNCHECKED_CAST")
        val failures = loader.loadClass("probe.members.check.MembersProbe").getMethod("probe").invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }
}
