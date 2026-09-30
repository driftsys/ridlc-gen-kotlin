package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.Plugin.CodegenResponse
import ridl.codegen.v1.Plugin.DiagnosticSeverity
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * driftsys/ridlc-gen-kotlin#16 and #18, the rule of driftsys/ridl's
 * generated-name collision design. A name the plugin chose never refuses a
 * package: a package that declares names meeting one the plugin writes
 * compiles with warnings as errors and behaves. Two generated names spelled
 * from ridl names that one Kotlin namespace cannot hold refuse the package,
 * with one message naming both sources: the cases of the design note's
 * appendix that Kotlin also derives (X-6a, X-8, X-9, X-11 to X-14), and the
 * ones only Kotlin meets.
 */
class NamesTest {
    @TempDir
    lateinit var work: Path

    private val WARNING = DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING

    /** The plugin's response for [source], a package named `probe.<name>`, by package: it and every package it reaches. */
    private fun responses(name: String, source: String): Map<String, CodegenResponse> {
        val pkg = work.resolve("src-$name").createDirectories()
        pkg.resolve("ridl.toml").writeText("[package]\nname = \"probe.$name\"\nversion = \"1.0.0\"\n")
        pkg.resolve("$name.ridl").writeText("package probe.$name\n\n$source")
        val requests = Harness.capturedRequests(pkg, name, work, "--emit", "codegen-model")
        return requests.mapValues { (_, request) -> Generator.generate(Wire.readRequest(request)) }
    }

    /** The files the plugin writes for [source], with no diagnostic but the [warnings] it expects. */
    private fun generate(name: String, source: String, warnings: Int = 0): Map<String, String> =
        responses(name, source).values.flatMap { response ->
            assertEquals(emptyList<String>(), response.diagnosticsList.filter { it.severity != WARNING }.map { it.message })
            assertEquals(warnings, response.diagnosticsList.size, response.diagnosticsList.toString())
            response.filesList.map { it.path to it.text }
        }.toMap()

    /** The error diagnostics of package `probe.<name>`, which writes no file. */
    private fun refusals(name: String, source: String): List<String> {
        val response = responses(name, source).getValue("probe.$name")
        assertEquals(emptyList<String>(), response.filesList.map { it.path })
        return response.diagnosticsList.map { it.message }
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

    private fun refused(name: String, source: String, vararg expected: String) {
        val messages = refusals(name, source)
        for (e in expected) assertTrue(messages.any { e in it }, "no refusal says `$e`: $messages")
    }

    @Test
    fun `X-6a two tuple fields one property cannot hold are refused`() = refused(
        "xsixa",
        """
        type Speed : integer [0..250]
        struct Reading {
          bounds : (minSpeed : Speed, min_speed : Speed)
        }
        """.trimIndent(),
        "`probe.xsixa.ReadingBounds` cannot be generated: its fields `minSpeed` and `min_speed` are both the property " +
            "`minSpeed`; rename one of them",
    )

    @Test
    fun `two struct fields one property cannot hold are refused`() = refused(
        "fields",
        """
        type Level : integer [0..100]
        struct S {
          XY  : Level
          x_y : Level
        }
        """.trimIndent(),
        "`probe.fields.S` cannot be generated: its fields `XY` and `x_y` are both the property `xY`",
    )

    private val twoMembers = """
        type Level : integer [0..100]
        interface Cabin {
          signal XY  : Level @10ms
          signal x_y : Level @10ms
    """.trimIndent()

    @Test
    fun `X-8 two members equal under camel case are refused`() = refused(
        "xeight",
        "$twoMembers\n}",
        "`CabinXY` is generated twice in package `probe.xeight`: for the descriptor of member `XY` of interface " +
            "`Cabin`, and for the descriptor of member `x_y` of interface `Cabin`; rename one of them",
    )

    @Test
    fun `X-8b the same members in a skipped face claim nothing and build`() {
        val sources = generate("xeightb", "$twoMembers\n  command set(a: Level, b: Level) @[..50ms]\n}", warnings = 1)
        compiles(sources, "xeightb")
    }

    @Test
    fun `X-8c the same members in a face the plugin carries are refused`() = refused(
        "xeightc",
        "$twoMembers\n  command set(level: Level) @[..50ms]\n}",
        "`CabinXY` is generated twice",
    )

    @Test
    fun `X-9 a descriptor named like a declaration is refused`() = refused(
        "xnine",
        """
        type CabinTemperature : integer [-40..85]
        interface Cabin {
          signal temperature : CabinTemperature @10ms
        }
        """.trimIndent(),
        "`CabinTemperature` is generated twice in package `probe.xnine`: for declaration `CabinTemperature`, and for " +
            "the descriptor of member `temperature` of interface `Cabin`",
    )

    @Test
    fun `X-11 an induced tuple named like a declaration is refused`() = refused(
        "xeleven",
        """
        type Speed : integer [0..250]
        struct Reading {
          bounds : (low : Speed, high : Speed)
        }
        struct ReadingBounds {
          low : Speed
        }
        """.trimIndent(),
        "`ReadingBounds` is generated twice in package `probe.xeleven`: for declaration `ReadingBounds`, and for the " +
            "tuple induced from `Reading.bounds`",
    )

    @Test
    fun `X-12 two descriptors across interfaces are refused`() = refused(
        "xtwelve",
        """
        type Level : integer [0..100]
        interface A { signal bC : Level @10ms }
        interface AB { signal c : Level @10ms }
        """.trimIndent(),
        "`ABC` is generated twice in package `probe.xtwelve`: for the descriptor of member `bC` of interface `A`, and " +
            "for the descriptor of member `c` of interface `AB`",
    )

    @Test
    fun `X-13 a descriptor named like an interface is refused`() = refused(
        "xthirteen",
        """
        type Level : integer [0..100]
        interface Horn { signal active : Level @10ms }
        interface HornActive { signal level : Level @10ms }
        """.trimIndent(),
        "`HornActive` is generated twice in package `probe.xthirteen`: for the descriptor of member `active` of " +
            "interface `Horn`, and for the descriptor of interface `HornActive`",
    )

    @Test
    fun `X-14a interfaces equal under snake case build`() {
        val sources = generate(
            "xfourteena",
            """
            type Level : integer [0..100]
            interface HTTPServer { signal level : Level @10ms }
            interface HttpServer { signal level : Level @10ms }
            """.trimIndent(),
        )
        compiles(sources, "xfourteena")
    }

    @Test
    fun `X-14b an interface and a declaration equal under camel case are refused`() = refused(
        "xfourteenb",
        """
        type cabin : integer [0..100]
        interface Cabin { signal level : cabin @10ms }
        """.trimIndent(),
        "`Cabin` is generated twice in package `probe.xfourteenb`: for declaration `cabin`, and for the descriptor of " +
            "interface `Cabin`",
    )

    @Test
    fun `X-17 a constant named like a descriptor builds`() {
        val sources = generate(
            "xseventeen",
            """
            type Level : integer [0..100]
            const CabinLevel : Level = 5
            interface Cabin { signal level : Level @10ms }
            """.trimIndent(),
        )
        compiles(sources, "xseventeen")
    }

    @Test
    fun `two declarations equal under camel case are refused`() = refused(
        "cased",
        """
        type Level : integer [0..100]
        type level : integer [0..10]
        """.trimIndent(),
        "`Level` is generated twice in package `probe.cased`: for declaration `Level`, and for declaration `level`",
    )

    @Test
    fun `a declaration named like another's codec is refused`() = refused(
        "codecs",
        """
        type Level : integer [0..100]
        struct LevelCodec { level : Level }
        """.trimIndent(),
        "`LevelCodec` is generated twice in package `probe.codecs`: for declaration `LevelCodec`, and for the codec of " +
            "declaration `Level`",
    )

    @Test
    fun `a member named like the provider is refused`() = refused(
        "provider",
        """
        type Level : integer [0..100]
        interface Cabin {
          signal  provider : Level @10ms
          command set(level: Level) @[..50ms]
        }
        """.trimIndent(),
        "`CabinProvider` is generated twice in package `probe.provider`: for the descriptor of member `provider` of " +
            "interface `Cabin`, and for the provider of interface `Cabin`",
    )

    /**
     * X-18, driftsys/ridl#416 (#17): package `veh` declares a type named like
     * its child package `veh.common`. The JVM refuses a package holding a
     * class and a subpackage of one name (JLS §7.1), but the class is
     * `veh.Common`: `camel_case` upper-cases a class's first letter, and a
     * package segment is lower case (MANI-006), so the two never meet, even
     * on a case-insensitive file system, where `veh/Common.class` and the
     * directory `veh/common` are still two names.
     */
    @Test
    fun `X-18 a type named like its child package builds and is reachable from it`() {
        val root = work.resolve("src-veh").createDirectories()
        root.resolve("ridl.toml").writeText("[package]\nname = \"veh\"\nversion = \"1.0.0\"\n")
        root.resolve("veh.ridl").writeText("package veh\n\ntype common : integer [0..100]\n")
        root.resolve("common").createDirectories().resolve("common.ridl")
            .writeText("package veh.common\n\nimport veh.common as Common\n\nstruct Uses { c : Common }\n")
        val responses = Harness.capturedRequests(root, "veh", work, "--emit", "codegen-model")
            .mapValues { (_, request) -> Generator.generate(Wire.readRequest(request)) }
        assertEquals(listOf("veh", "veh.common"), responses.keys.toList())
        val sources = responses.values.flatMap { response ->
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        val consumer = """
            package veh.consumer

            object Consumer {
                @JvmStatic
                fun probe(): Long = veh.common.Uses(veh.Common.of(5)).c.value
            }
        """.trimIndent()
        val loader = compiles(sources + ("veh/consumer/Consumer.kt" to consumer), "veh")
        assertEquals(5L, loader.loadClass("veh.consumer.Consumer").getMethod("probe").invoke(null))
    }
}
