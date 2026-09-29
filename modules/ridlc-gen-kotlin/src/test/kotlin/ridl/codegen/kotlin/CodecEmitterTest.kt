package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.CodecEmitter
import ridl.codegen.kotlin.types.Layout
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.FbRoot
import ridl.codegen.v1.ModelOuterClass.FbUnbounded
import ridl.codegen.v1.ModelOuterClass.FbUnboundedCause
import ridl.codegen.v1.ModelOuterClass.FlatBuffersProjection
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Struct

/** How `Codec.kt` treats a root with no finite bound, and the table layout: what the corpus does not reach. */
class CodecEmitterTest {
    private val options = Options("kt.demo", WireEncoding.FlatBuffers)

    private fun model(cause: FbUnboundedCause): Model {
        val declaration = Declaration.newBuilder()
            .setName(Spellings.newBuilder().setDeclared("Feed").setCamel("Feed"))
            .setStruct(Struct.getDefaultInstance())
        val root = FbRoot.newBuilder().setDeclaration(0)
            .setUnbounded(FbUnbounded.newBuilder().setCause(cause).setMember("items"))
        return Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
            .addDeclarations(declaration)
            .setFlatbuffers(FlatBuffersProjection.newBuilder().addRoots(root))
            .build()
    }

    @Test
    fun `a root with an unbounded member is refused, naming it`() {
        val emitted = CodecEmitter(model(FbUnboundedCause.FB_UNBOUNDED_CAUSE_MEMBER), options).emit()
        assertNull(emitted.text)
        assertTrue(emitted.errors.single().contains("`kt.demo.Feed.items` has no finite FlatBuffers bound"), emitted.errors[0])
    }

    @Test
    fun `a root the projection exempts gets no codec, and the package is still generated`() {
        val emitted = CodecEmitter(model(FbUnboundedCause.FB_UNBOUNDED_CAUSE_EXEMPT), options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        assertTrue(emitted.text!!.contains("No codec for `Feed`"), emitted.text)
        assertTrue("FeedCodec" !in emitted.text)
    }

    @Test
    fun `fields are placed in declaration order at their own alignment, as the Rust codec places them`() {
        // The Rust codec's own worked example: Warning's u8 then i64.
        val warning = Layout.place(listOf(1, 8))
        assertEquals(listOf(4, 8), warning.offsets)
        assertEquals(16, warning.size)
        assertEquals(8, warning.align)
        // A box of each width.
        assertEquals(listOf(5, 6, 8, 16), listOf(1, 2, 4, 8).map { Layout.place(listOf(it)).size })
        // The union wrapper.
        Layout.place(listOf(1, 4)).let { assertEquals(listOf(4, 8), it.offsets); assertEquals(12, it.size) }
    }
}
