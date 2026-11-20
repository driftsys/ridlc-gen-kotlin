package ridl.conformance

import com.google.protobuf.ListValue
import com.google.protobuf.util.JsonFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Constraint
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.FbSlot
import ridl.codegen.v1.ModelOuterClass.FbTable
import ridl.codegen.v1.ModelOuterClass.FbWire
import ridl.codegen.v1.ModelOuterClass.FbRoot
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.FlatBuffersProjection
import ridl.codegen.v1.ModelOuterClass.Match
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PodbufBound
import ridl.codegen.v1.ModelOuterClass.PodbufDescriptor
import ridl.codegen.v1.ModelOuterClass.PodbufField
import ridl.codegen.v1.ModelOuterClass.PodbufForm
import ridl.codegen.v1.ModelOuterClass.PodbufHead
import ridl.codegen.v1.ModelOuterClass.PodbufNamedField
import ridl.codegen.v1.ModelOuterClass.PodbufProjection
import ridl.codegen.v1.ModelOuterClass.PodbufSource
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.SizeState
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.Plugin.CodegenResponse
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * A typl `match` pattern is an I-Regexp (RFC 9485) that matches the whole
 * value, and a string may carry several clauses, each of which it must match
 * (driftsys/ridl#597, driftsys/ridlc-gen-kotlin#186). ridl sends each clause's
 * normalised pattern, and the generated code compiles it with
 * `java.util.regex` unchanged and calls `matches`.
 */
class PatternsTest {
    @TempDir
    lateinit var work: Path

    /** The plugin's response for package `probe.<name>`; `ridl build` accepting [source] is checked on the way. */
    private fun response(name: String, source: String): CodegenResponse {
        val pkg = work.resolve("src-$name").createDirectories()
        pkg.resolve("ridl.toml").writeText(Harness.manifest("probe.$name"))
        pkg.resolve("$name.ridl").writeText("package probe.$name\n\n$source")
        val requests = Harness.capturedRequests(pkg, name, work, "--emit", "codegen-model")
        return Generator.generate(Wire.readRequest(requests.getValue("probe.$name")))
    }

    /** The generated sources of [source], compiled; `accepts(type, value)` asks the type's `ofOrNull`. */
    private fun compiled(name: String, source: String): (String, String) -> Boolean {
        val response = response(name, source)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile-$name"))
        assertTrue(compiled.ok, compiled.messages)
        return { type, value ->
            val owner = Class.forName("probe.$name.$type", true, compiled.classLoader)
            val companion = owner.getField("Companion").get(null)
            val ofOrNull = companion.javaClass.methods.single { it.name.startsWith("ofOrNull") }
            ofOrNull.invoke(companion, value) != null
        }
    }

    private fun shown(value: String): String = value.map { "U+%04X".format(it.code) }.joinToString(" ")

    /**
     * ridl's agreement table: the pinned release's
     * `crates/ridl-ir/tests/pattern_agreement.json`, which the Rust and the
     * TypeScript outputs are held to, copied under `resources/patterns/`.
     * Every literal becomes one named string type, and every value gets the
     * table's verdict from the generated `ofOrNull`.
     */
    @Test
    fun `every pair of ridl's agreement table gets the table's verdict`() {
        val table = ListValue.newBuilder()
        val text = checkNotNull(javaClass.getResource("/patterns/pattern-agreement.json")).readText()
        JsonFormat.parser().merge(text, table)
        val rows = table.valuesList.map { it.structValue.fieldsMap }
        val literals = rows.map { it.getValue("literal").stringValue }.distinct()
        val source = literals.withIndex().joinToString("\n") { (i, literal) -> "type P$i : string [0..256 match $literal]" }
        val accepts = compiled("agreement", source)
        val differ = rows.mapNotNull { row ->
            val literal = row.getValue("literal").stringValue
            val value = row.getValue("value").stringValue
            val expected = row.getValue("accepted").boolValue
            val actual = accepts("P${literals.indexOf(literal)}", value)
            "$literal (${shown(value)}): expected $expected, got $actual".takeIf { actual != expected }
        }
        assertEquals(emptyList<String>(), differ)
        assertEquals(125, rows.size, "the table changed size: check it against the pinned release")
    }

    /** A local `Day` type uses the same pattern text as `ridl.std.Date`; a search would accept it inside longer text. */
    @Test
    fun `a pattern matches the whole value, not a part of it`() {
        val accepts = compiled("whole", "type Day : string [0..16 match /[0-9]{4}-[0-9]{2}-[0-9]{2}/]")
        val verdicts = listOf("2026-11-18", "x2026-11-18", "2026-11-18\n", "١٢٣٤-١٢-١٢", "2026-11-18x")
            .map { "${shown(it)} ${accepts("Day", it)}" }
        assertEquals(
            listOf("true", "false", "false", "false", "false"),
            verdicts.map { it.substringAfterLast(' ') },
            verdicts.toString(),
        )
    }

    @Test
    fun `dot accepts additional control characters`() {
        // These cases extend ridl's copied agreement table.
        val accepts = compiled("dotcontrols", "type Dot : string [1..1 match /./]")
        for (value in listOf("\u0085", "\u000B", "\u000C")) assertTrue(accepts("Dot", value), shown(value))
    }

    @Test
    fun `both codecs refuse a buffer that breaks only the second inline clause`() {
        val request = Wire.readRequest(Harness.capturedRequests("kt-values", work.resolve("capture")).getValue("kt.values"))
        val response = Generator.generate(request)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val probe = """
            package kt.values

            import java.nio.ByteBuffer
            import ridl.rt.payload.Payload
            import ridl.rt.payload.Rule
            import ridl.rt.payload.VerifyError

            object ClauseProbe {
                private fun <V> checkCodec(codec: Payload<ClauseToken, V>) {
                    val buffer = ByteBuffer.allocate(codec.maxSize)
                    codec.encode(ClauseToken("ABC"), buffer)
                    buffer.flip()
                    check(codec.decode(codec.verify(buffer)).text == "ABC")
                    val offset = (0 until buffer.limit() - 2).single {
                        buffer.get(it) == 65.toByte() && buffer.get(it + 1) == 66.toByte() && buffer.get(it + 2) == 67.toByte()
                    }
                    // BBC matches [A-Z]+ and fails A[A-Z][A-Z].
                    buffer.put(offset, 66.toByte())
                    try {
                        codec.decode(codec.verify(buffer))
                        error("accepted a value that breaks clause 2")
                    } catch (e: VerifyError.Contract) {
                        check(e.violation.rule == Rule.Pattern)
                    }
                }

                @JvmStatic
                fun flatbuffers() = checkCodec(ClauseTokenCodec)

                @JvmStatic
                fun podbuf() = checkCodec(ClauseTokenPodbufCodec)
            }
        """.trimIndent()
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text } + ("kt/values/ClauseProbe.kt" to probe), work.resolve("compile"))
        assertTrue(compiled.ok, compiled.messages)
        val owner = compiled.classLoader!!.loadClass("kt.values.ClauseProbe")
        for (encoding in listOf("flatbuffers", "podbuf")) owner.getMethod(encoding).invoke(null)
    }

    @Test
    fun `each codec emitter refuses a second inline clause Java cannot compile`() {
        val scalar = Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_STRING).setConstraint(
            Constraint.newBuilder().setLenMax(3)
                .addMatches(Match.newBuilder().setPattern("[A-Z]+"))
                .addMatches(Match.newBuilder().setPattern("a{99999999999}")),
        )
        val field = Field.newBuilder().setName(Spellings.newBuilder().setDeclared("text").setCamel("Text"))
            .setType(Type.newBuilder().setInline(scalar))
        val declaration = Declaration.newBuilder()
            .setName(Spellings.newBuilder().setDeclared("Broken").setCamel("Broken"))
            .setStruct(Struct.newBuilder().addSlots(Slot.newBuilder().setOrdinal(1).setField(field)))
            .setPodbuf(SizeState.newBuilder().setBounded(40))
        val head = PodbufHead.newBuilder().setDeclaration(0).setSize(16).setAlign(8).addFields(
            PodbufField.newBuilder().setOffset(0).setSize(16).setAlign(4)
                .setSource(PodbufSource.newBuilder().setField(PodbufNamedField.newBuilder().setName("text").setOrdinal(1)))
                .setForm(PodbufForm.newBuilder().setTailDescriptor(PodbufDescriptor.newBuilder().setText(PodbufBound.newBuilder().setMax(3)))),
        )
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.values"))
            .addDeclarations(declaration)
            .setFlatbuffers(
                FlatBuffersProjection.newBuilder().addRoots(FbRoot.newBuilder().setDeclaration(0).setMaxSize(64))
                    .addTables(
                        FbTable.newBuilder().setName("Broken").setStructDeclaration(0).setVtableSlots(1)
                            .addSlots(FbSlot.newBuilder().setId(0).setField(0).setWire(FbWire.newBuilder().setText(true))),
                    ),
            )
            .setPodbuf(PodbufProjection.newBuilder().addHeads(head))
        val options = ridl.codegen.kotlin.Options("kt.values", listOf(ridl.codegen.kotlin.WireEncoding.FlatBuffers, ridl.codegen.kotlin.WireEncoding.Podbuf))
        val emissions = listOf(
            ridl.codegen.kotlin.types.CodecEmitter(model.build(), options).emit(),
            ridl.codegen.kotlin.types.PodbufCodecEmitter(model.build(), options).emit(),
        )
        for (emitted in emissions) {
            org.junit.jupiter.api.Assertions.assertNull(emitted.text)
            assertTrue(emitted.errors.any { "a{99999999999}` does not compile under java.util.regex" in it }, emitted.errors.toString())
        }
    }

    /**
     * A value must match every clause, the first that refuses it is the
     * violation, and an inline field checks its clauses as a named type does.
     */
    @Test
    fun `a value must match every clause of its type`() {
        val source = """
            type Token : string [1..16 match /.*[0-9].*/ match /.*[A-Z].*/]

            struct Login {
              token : string [1..16 match /.*[0-9].*/ match /.*[A-Z].*/]
            }
        """.trimIndent()
        val response = response("clauses", source)
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        val compiled = Compiler.compile(response.filesList.associate { it.path to it.text }, work.resolve("compile-clauses"))
        assertTrue(compiled.ok, compiled.messages)
        val token = Class.forName("probe.clauses.Token", true, compiled.classLoader)
        val companion = token.getField("Companion").get(null)
        val violation = companion.javaClass.methods.single { it.name.startsWith("violation") }
        val login = Class.forName("probe.clauses.Login", true, compiled.classLoader)
        fun refusal(value: String): String = try {
            login.constructors.single().newInstance(value)
            "accepted"
        } catch (e: InvocationTargetException) {
            e.targetException.message ?: e.targetException.javaClass.name
        }
        assertEquals(
            listOf("null", "Pattern", "Pattern", "Pattern"),
            listOf("A1", "a1", "AB", "ab").map { "${violation.invoke(companion, it)}" },
        )
        assertEquals("accepted", refusal("A1"))
        for (value in listOf("a1", "AB")) assertTrue("the pattern constraint of Login" in refusal(value), refusal(value))
    }
}
