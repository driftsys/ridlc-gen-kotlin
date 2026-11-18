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
 * driftsys/ridlc-gen-kotlin#157: a Kotlin `String` may hold an unpaired
 * surrogate, which UTF-8 cannot carry (typl §4.4) and which
 * `String.toByteArray` and `encodeToByteArray` replace without an error. The
 * constructor of a named string scalar refuses one as `Utf8`, before its
 * length and its pattern (driftsys/ridl#1322), and the FlatBuffers and the
 * podbuf encoders refuse one in any string position, named or not, as
 * `EncodeError.Contract` with `Utf8` (driftsys/ridl#1330). A struct's own
 * checks of an inline string do not, as in the TypeScript backend, so the
 * encoder is reached with one. A Rust `String` cannot hold such a value, so
 * there are no Rust bytes to compare with.
 */
class WellFormedTest {
    @TempDir
    lateinit var work: Path

    private val source = """
        package probe.utf8

        /// A named string that checks its length and its pattern.
        type Code : string [1..3 match /^[a-z]+${'$'}/]

        /// A named string that checks its length alone.
        type Word : string [0..8]

        /// A union with a string arm.
        union Choice {
          word : Word
          code : Code
        }

        /// Every position a string is written at.
        struct Texts {
          bare   : string [0..8]
          word   : Word
          list   : [string [0..8]; 0..2]
          byWord : [Word : string [0..8]; 0..2]
          maybe  : string [0..8]?
          choice : Choice
        }
    """.trimIndent()

    private val probe = """
        @file:JvmName("WellFormedProbe")

        package probe.utf8.check

        import probe.utf8.Choice
        import probe.utf8.Code
        import probe.utf8.Texts
        import probe.utf8.TextsCodec
        import probe.utf8.TextsPodbufCodec
        import probe.utf8.Word
        import ridl.rt.payload.ConstraintViolation
        import ridl.rt.payload.EncodeError
        import ridl.rt.payload.Payload
        import ridl.rt.payload.Rule
        import java.nio.ByteBuffer

        /** An unpaired high surrogate inside, a lone low one, and a high one at the end. */
        private val illFormed = listOf("x\uD800y", "\uDC00", "a\uD800")

        /** A `Texts` holding a supplementary code point in every position but those given. */
        private fun texts(
            bare: String = "😀",
            word: Word = Word.of("😀"),
            list: List<String> = listOf("😀"),
            byWord: Map<Word, String> = mapOf(Word.of("😀") to "😀"),
            maybe: String? = "😀",
            choice: Choice = Choice.Word(Word.of("😀")),
        ) = Texts(bare = bare, word = word, list = list, byWord = byWord, maybe = maybe, choice = choice)

        /** `ok`, or `<type> <rule>` of the encoder's `Contract`; `THREW` for anything else. */
        private fun <T, V> encoded(codec: Payload<T, V>, value: T): String = try {
            codec.encode(value, ByteBuffer.allocate(codec.maxSize))
            "ok"
        } catch (e: EncodeError.Contract) {
            "${'$'}{e.violation.typeName} ${'$'}{e.violation.rule}"
        } catch (e: Throwable) {
            "THREW ${'$'}e"
        }

        /** The rule `of` refused [value] with, or `null` when it built one. */
        private fun refused(of: () -> Any): Rule? = try {
            of()
            null
        } catch (e: ConstraintViolation) {
            e.rule
        }

        fun constructors(): List<String> {
            val failures = mutableListOf<String>()
            for (bad in illFormed) {
                val shown = bad.map { "%04X".format(it.code) }
                for ((what, rule) in listOf(
                    "Code.of" to refused { Code.of(bad) },
                    "Code.of, too long and not matching" to refused { Code.of(bad + "ZZZZ") },
                    "Word.of" to refused { Word.of(bad) },
                    "Word.of, too long" to refused { Word.of(bad + "123456789") },
                )) {
                    if (rule != Rule.Utf8) failures += "${'$'}what(${'$'}shown) refused with ${'$'}rule, not Utf8"
                }
                if (Code.ofOrNull(bad) != null) failures += "Code.ofOrNull(${'$'}shown) built a value"
            }
            if (refused { Word.of("😀") } != null) failures += "Word.of(😀) was refused"
            if (refused { Code.of("ab") } != null) failures += "Code.of(ab) was refused"
            return failures
        }

        fun encoders(): List<String> {
            val failures = mutableListOf<String>()
            val codecs = listOf<Pair<String, Payload<Texts, *>>>("FlatBuffers" to TextsCodec, "podbuf" to TextsPodbufCodec)
            for ((encoding, codec) in codecs) {
                @Suppress("UNCHECKED_CAST")
                val c = codec as Payload<Texts, Any?>
                val out = ByteBuffer.allocate(c.maxSize)
                val n = c.encode(texts(), out)
                val back = c.decode(c.verify(ByteBuffer.wrap(out.array().copyOf(n))))
                if (back != texts()) failures += "${'$'}encoding: 😀 does not round-trip: ${'$'}back"
                for (bad in illFormed) {
                    val shown = bad.map { "%04X".format(it.code) }
                    val cases = listOf(
                        "a bare field" to (texts(bare = bad) to "Texts"),
                        "a named field" to (texts(word = Word.unchecked(bad)) to "Word"),
                        "an array element" to (texts(list = listOf("ok", bad)) to "Texts"),
                        "a map key" to (texts(byWord = mapOf(Word.unchecked(bad) to "v")) to "Word"),
                        "a map value" to (texts(byWord = mapOf(Word.of("k") to bad)) to "Texts"),
                        "an optional" to (texts(maybe = bad) to "Texts"),
                        "a union arm" to (texts(choice = Choice.Word(Word.unchecked(bad))) to "Word"),
                    )
                    for ((what, case) in cases) {
                        val (value, type) = case
                        val got = encoded(c, value)
                        if (got != "${'$'}type Utf8") failures += "${'$'}encoding, ${'$'}what holding ${'$'}shown: ${'$'}got"
                    }
                }
            }
            return failures
        }
    """.trimIndent()

    private val loader: ClassLoader by lazy {
        val pkg = work.resolve("src-probe.utf8").createDirectories()
        pkg.resolve("ridl.toml").writeText(
            "[package]\nname = \"probe.utf8\"\nversion = \"1.0.0\"\n\n[backend.kotlin]\nencodings = [\"flatbuffers\", \"podbuf\"]\n",
        )
        pkg.resolve("source.ridl").writeText(source + "\n")
        val sources = Harness.capturedRequests(pkg, "probe.utf8", work).values.flatMap { request ->
            val response = Generator.generate(Wire.readRequest(request))
            assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
            response.filesList.map { it.path to it.text }
        }.toMap()
        val compiled = Compiler.compile(sources + ("probe/utf8/check/Probe.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        compiled.classLoader!!
    }

    private fun run(probe: String) {
        @Suppress("UNCHECKED_CAST")
        val failures = loader.loadClass("probe.utf8.check.WellFormedProbe").getMethod(probe).invoke(null) as List<String>
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `a named string scalar refuses an unpaired surrogate as Utf8, before its length and its pattern`() = run("constructors")

    @Test
    fun `each encoder refuses an unpaired surrogate in every string position, and writes a supplementary code point`() =
        run("encoders")
}
