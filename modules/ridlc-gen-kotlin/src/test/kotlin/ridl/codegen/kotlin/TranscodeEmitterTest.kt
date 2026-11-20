package ridl.codegen.kotlin

import com.google.protobuf.ByteString
import com.squareup.kotlinpoet.ClassName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.PayloadOwner
import ridl.codegen.kotlin.types.TranscodeEmitter
import ridl.codegen.v1.ModelOuterClass.Catalog
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.FbRoot
import ridl.codegen.v1.ModelOuterClass.FbTable
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.FlatBuffersProjection
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PodbufField
import ridl.codegen.v1.ModelOuterClass.PodbufForm
import ridl.codegen.v1.ModelOuterClass.PodbufHead
import ridl.codegen.v1.ModelOuterClass.PodbufInt
import ridl.codegen.v1.ModelOuterClass.PodbufNamedField
import ridl.codegen.v1.ModelOuterClass.PodbufPad
import ridl.codegen.v1.ModelOuterClass.PodbufProjection
import ridl.codegen.v1.ModelOuterClass.PodbufSource
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.SizeState
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.Visibility
import ridl.codegen.v1.Plugin

/**
 * `Transcode.kt` (#136): which types get a transcode object, which functions
 * it holds and in which order, as `transcode.rs`'s tests pin them, and the
 * option that turns the file on. `TranscodeTest` in `modules/conformance`
 * runs the generated functions against the Rust ones.
 */
class TranscodeEmitterTest {
    private fun owners(encoding: WireEncoding, vararg names: String, internal: Set<String> = emptySet()) =
        encoding to names.map { name ->
            val suffix = when (encoding) {
                WireEncoding.FlatBuffers -> "Codec"
                WireEncoding.Proto3 -> "Proto3Codec"
                WireEncoding.Podbuf -> "PodbufCodec"
            }
            val visibility = if (name in internal) Visibility.VISIBILITY_INTERNAL else Visibility.VISIBILITY_PUBLIC
            PayloadOwner(name, visibility, ClassName("kt.demo", "$name$suffix"))
        }

    private fun emit(vararg owners: Pair<WireEncoding, List<PayloadOwner>>) =
        TranscodeEmitter("kt.demo", "kt.demo", owners.toList()).emit()

    /** The transcode objects of [text], in output order. */
    private fun objects(text: String): List<String> =
        Regex("""object (\w+)Transcode""").findAll(text).map { it.groupValues[1] }.toList()

    /** The function names of each object of [text], in output order. */
    private fun functions(text: String): List<List<String>> =
        text.split(Regex("""object \w+Transcode""")).drop(1).map { body -> Regex("""fun (\w+)\(""").findAll(body).map { it.groupValues[1] }.toList() }

    /** FlatBuffers lists the types in alphabetical order and podbuf in the reverse order. */
    private val flatbuffers = owners(WireEncoding.FlatBuffers, "Alpha", "Beta", "Gamma")
    private val podbuf = owners(WireEncoding.Podbuf, "Gamma", "Beta", "Alpha")

    @Test
    fun `the output follows the order of the first listed encoding`() {
        val first = emit(flatbuffers, podbuf).text!!
        assertEquals(listOf("Alpha", "Beta", "Gamma"), objects(first))
        assertEquals(List(3) { listOf("flatbuffersToPodbuf", "podbufToFlatbuffers") }, functions(first))

        val second = emit(podbuf, flatbuffers).text!!
        assertEquals(listOf("Gamma", "Beta", "Alpha"), objects(second))
        assertEquals(List(3) { listOf("podbufToFlatbuffers", "flatbuffersToPodbuf") }, functions(second))
    }

    @Test
    fun `three encodings give the six ordered pairs, proto3 included, in the listed order`() {
        val proto3 = owners(WireEncoding.Proto3, "Beta", "Alpha", "Gamma")
        val text = emit(podbuf, flatbuffers, proto3).text!!
        assertEquals(listOf("Gamma", "Beta", "Alpha"), objects(text))
        val pairs = listOf(
            "podbufToFlatbuffers", "podbufToProto3", "flatbuffersToPodbuf", "flatbuffersToProto3", "proto3ToPodbuf", "proto3ToFlatbuffers",
        )
        assertEquals(List(3) { pairs }, functions(text))
        assertTrue("transcode(AlphaProto3Codec, AlphaCodec, bytes, out)" in text, text)
    }

    @Test
    fun `a type owned by one encoding gets no transcode object`() {
        val emitted = emit(owners(WireEncoding.FlatBuffers, "Alpha", "Beta"), owners(WireEncoding.Podbuf, "Beta", "Gamma"))
        assertEquals(listOf("Beta"), objects(emitted.text!!))
        assertEquals(listOf("BetaTranscode"), emitted.claims.map { it.name })
    }

    @Test
    fun `fewer than two encodings, or no shared type, give no file`() {
        assertNull(emit().text)
        assertNull(emit(flatbuffers).text)
        assertNull(emit(owners(WireEncoding.FlatBuffers, "Alpha"), owners(WireEncoding.Podbuf, "Beta")).text)
    }

    @Test
    fun `each function calls the runtime's transcode over the two codecs`() {
        val text = emit(flatbuffers, podbuf).text!!
        assertTrue("import ridl.rt.payload.transcode" in text, text)
        assertTrue(
            "public fun flatbuffersToPodbuf(bytes: ByteBuffer, `out`: ByteBuffer): Int = transcode(AlphaCodec, AlphaPodbufCodec, bytes, out)" in text,
            text,
        )
        assertTrue(
            "public fun podbufToFlatbuffers(bytes: ByteBuffer, `out`: ByteBuffer): Int = transcode(AlphaPodbufCodec, AlphaCodec, bytes, out)" in text,
            text,
        )
        assertTrue("@throws ridl.rt.payload.TranscodeError" in text, text)
    }

    @Test
    fun `an internal type's transcode object is internal`() {
        val text = emit(
            owners(WireEncoding.FlatBuffers, "Alpha", "Beta", internal = setOf("Beta")),
            owners(WireEncoding.Podbuf, "Alpha", "Beta", internal = setOf("Beta")),
        ).text!!
        assertTrue("public object AlphaTranscode" in text, text)
        assertTrue("internal object BetaTranscode" in text, text)
    }

    // -- through the generator --------------------------------------------------

    private val level: Field = Field.newBuilder()
        .setName(Spellings.newBuilder().setDeclared("level").setCamel("level"))
        .setType(
            Type.newBuilder().setInline(
                Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).setIntWidth(IntWidth.INT_WIDTH_U8).setVacuous(true),
            ),
        )
        .build()

    /** `Feed { level: u8 }`, with a FlatBuffers root and table and a podbuf head. */
    private fun request(vararg options: Pair<String, String>): Plugin.CodegenRequest {
        val feed = Declaration.newBuilder()
            .setName(Spellings.newBuilder().setDeclared("Feed").setCamel("Feed"))
            .setStruct(Struct.newBuilder().addSlots(Slot.newBuilder().setOrdinal(1).setField(level)))
            .setPodbuf(SizeState.newBuilder().setBounded(16))
        val head = PodbufHead.newBuilder().setDeclaration(0).setSize(8).setAlign(8)
            .addFields(
                PodbufField.newBuilder()
                    .setSource(PodbufSource.newBuilder().setField(PodbufNamedField.newBuilder().setName("level").setOrdinal(1)))
                    .setOffset(0).setSize(1).setAlign(1)
                    .setForm(PodbufForm.newBuilder().setInt(PodbufInt.newBuilder().setWidth(IntWidth.INT_WIDTH_U8))),
            )
            .addPads(PodbufPad.newBuilder().setOffset(1).setSize(7))
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
            .setCatalog(Catalog.newBuilder().setHash(ByteString.copyFrom(ByteArray(32))))
            .addDeclarations(feed)
            .setFlatbuffers(
                FlatBuffersProjection.newBuilder()
                    .addRoots(FbRoot.newBuilder().setDeclaration(0).setMaxSize(32))
                    .addTables(FbTable.newBuilder().setName("Feed").setStructDeclaration(0).setVtableSlots(1)),
            )
            .setPodbuf(PodbufProjection.newBuilder().addHeads(head))
        return Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA).setModel(model)
            .addAllOptions(options.map { (k, v) -> Plugin.BackendOption.newBuilder().setKey(k).setValue(v).build() })
            .build()
    }

    private fun paths(vararg options: Pair<String, String>): List<String> {
        val response = Generator.generate(request(*options))
        assertEquals(emptyList<String>(), response.diagnosticsList.map { it.message })
        return response.filesList.map { it.path }
    }

    @Test
    fun `transcode = true with two encodings adds Transcode kt after the codecs`() {
        assertEquals(
            listOf("kt/demo/Types.kt", "kt/demo/Codec.kt", "kt/demo/PodbufCodec.kt", "kt/demo/Transcode.kt"),
            paths("encodings" to "flatbuffers,podbuf", "transcode" to "true"),
        )
        val text = Generator.generate(request("encodings" to "podbuf,flatbuffers", "transcode" to "true"))
            .filesList.single { it.path == "kt/demo/Transcode.kt" }.text
        assertEquals(listOf(listOf("podbufToFlatbuffers", "flatbuffersToPodbuf")), functions(text))
    }

    @Test
    fun `no transcode file when the option is absent or false, or names one encoding`() {
        val codecs = listOf("kt/demo/Types.kt", "kt/demo/Codec.kt", "kt/demo/PodbufCodec.kt")
        assertEquals(codecs, paths("encodings" to "flatbuffers,podbuf"))
        assertEquals(codecs, paths("encodings" to "flatbuffers,podbuf", "transcode" to "false"))
        assertEquals(listOf("kt/demo/Types.kt", "kt/demo/Codec.kt"), paths("encodings" to "flatbuffers", "transcode" to "true"))
        assertEquals(listOf("kt/demo/Types.kt"), paths("transcode" to "true"))
    }

    @Test
    fun `a declaration named like a transcode object is refused, naming both`() {
        val clash = request("encodings" to "flatbuffers,podbuf", "transcode" to "true").toBuilder()
        clash.modelBuilder.addDeclarations(
            Declaration.newBuilder().setName(Spellings.newBuilder().setDeclared("FeedTranscode").setCamel("FeedTranscode"))
                .setStruct(Struct.getDefaultInstance()),
        )
        val messages = Generator.generate(clash.build()).diagnosticsList.map { it.message }
        assertTrue(messages.any { "`FeedTranscode` is generated twice" in it && "the transcode functions of `Feed`" in it }, messages.toString())
    }
}
