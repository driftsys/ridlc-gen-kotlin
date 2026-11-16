package ridl.codegen.kotlin

import com.google.protobuf.ByteString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.PodbufCodecEmitter
import ridl.codegen.kotlin.types.PodbufRoot
import ridl.codegen.kotlin.types.podbufMaxSize
import ridl.codegen.v1.ModelOuterClass.Catalog
import ridl.codegen.v1.ModelOuterClass.CommandShape
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.ForeignDeclaration
import ridl.codegen.v1.ModelOuterClass.InducedName
import ridl.codegen.v1.ModelOuterClass.InducedTuple
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.InteractionSlot
import ridl.codegen.v1.ModelOuterClass.Interface
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PayloadSizes
import ridl.codegen.v1.ModelOuterClass.PodbufBound
import ridl.codegen.v1.ModelOuterClass.PodbufBox
import ridl.codegen.v1.ModelOuterClass.PodbufDescriptor
import ridl.codegen.v1.ModelOuterClass.PodbufField
import ridl.codegen.v1.ModelOuterClass.PodbufForm
import ridl.codegen.v1.ModelOuterClass.PodbufHead
import ridl.codegen.v1.ModelOuterClass.PodbufInt
import ridl.codegen.v1.ModelOuterClass.PodbufNamedField
import ridl.codegen.v1.ModelOuterClass.PodbufPad
import ridl.codegen.v1.ModelOuterClass.PodbufProjection
import ridl.codegen.v1.ModelOuterClass.PodbufRefused
import ridl.codegen.v1.ModelOuterClass.PodbufSource
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Scope
import ridl.codegen.v1.ModelOuterClass.SizeState
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.TupleRef
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.Plugin

/**
 * `PodbufCodec.kt`'s refusals and names, over hand-built models: what the
 * corpus does not reach. `PodbufEmitterRunTest` in `modules/conformance`
 * compiles and runs the generated code.
 */
class PodbufCodecEmitterTest {
    private val options = Options("kt.demo", listOf(WireEncoding.Podbuf))

    private fun spell(name: String) = Spellings.newBuilder().setDeclared(name).setCamel(name.replaceFirstChar(Char::uppercaseChar))

    /** `level : integer [0..255]`, as an inline `u8`. */
    private val level: Field = Field.newBuilder()
        .setName(spell("level"))
        .setType(
            Type.newBuilder().setInline(
                Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).setIntWidth(IntWidth.INT_WIDTH_U8).setVacuous(true),
            ),
        )
        .build()

    private fun feed(bound: Int?): Declaration.Builder {
        val declaration = Declaration.newBuilder()
            .setName(spell("Feed"))
            .setStruct(Struct.newBuilder().addSlots(Slot.newBuilder().setOrdinal(1).setField(level)))
        if (bound != null) declaration.setPodbuf(SizeState.newBuilder().setBounded(bound))
        return declaration
    }

    /** `Feed`'s head: `level` at 0 as a `u8`, pad 1..8. */
    private fun head(form: PodbufForm = u8()): PodbufHead = PodbufHead.newBuilder()
        .setDeclaration(0).setSize(8).setAlign(8)
        .addFields(
            PodbufField.newBuilder()
                .setSource(PodbufSource.newBuilder().setField(PodbufNamedField.newBuilder().setName("level").setOrdinal(1)))
                .setOffset(0).setSize(1).setAlign(1)
                .setForm(form),
        )
        .addPads(PodbufPad.newBuilder().setOffset(1).setSize(7))
        .build()

    private fun u8(): PodbufForm = PodbufForm.newBuilder().setInt(PodbufInt.newBuilder().setWidth(IntWidth.INT_WIDTH_U8)).build()

    private fun model(projection: PodbufProjection?, bound: Int? = 16): Model {
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).addDeclarations(feed(bound))
        if (projection != null) model.setPodbuf(projection)
        return model.build()
    }

    @Test
    fun `a refused declaration fails the package with Rust's words`() {
        val projection = PodbufProjection.newBuilder()
            .addRefused(PodbufRefused.newBuilder().setDeclaration(0).setCause("its head is larger than a u32 offset can address"))
            .build()
        val emitted = PodbufCodecEmitter(model(projection), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf(
                "$PLUGIN: `kt.demo.Feed` cannot be laid out as a podbuf head, so the package gets no podbuf codec: " +
                    "its head is larger than a u32 offset can address",
            ),
            emitted.errors,
        )
    }

    @Test
    fun `a refused tuple is named as a tuple`() {
        val tuple = InducedTuple.newBuilder().setName(InducedName.newBuilder().setRust("FeedSetArgs")).addFields(level)
        val projection = PodbufProjection.newBuilder()
            .addRefused(PodbufRefused.newBuilder().setTuple(0).setCause("it needs `kt.demo.Huge`, which is refused"))
            .build()
        val model = model(projection).toBuilder().addTuples(tuple).build()
        val emitted = PodbufCodecEmitter(model, options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf(
                "$PLUGIN: the tuple `FeedSetArgs` cannot be laid out as a podbuf head, so the package gets no podbuf codec: " +
                    "it needs `kt.demo.Huge`, which is refused",
            ),
            emitted.errors,
        )
    }

    @Test
    fun `a model with no podbuf projection is refused`() {
        val emitted = PodbufCodecEmitter(model(null), options).emit()
        assertNull(emitted.text)
        assertEquals(listOf("$PLUGIN: the model carries no podbuf projection"), emitted.errors)
    }

    @Test
    fun `a bound at 2^31 is refused, naming the type`() {
        val projection = PodbufProjection.newBuilder().addHeads(head()).build()
        val emitted = PodbufCodecEmitter(model(projection, bound = 0x8000_0000.toInt()), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf("$PLUGIN: `kt.demo.Feed` has a podbuf bound of 2147483648 bytes, above the largest a Kotlin `Payload.maxSize` holds"),
            emitted.errors,
        )
    }

    @Test
    fun `a bound just below 2^31 is kept`() {
        val projection = PodbufProjection.newBuilder().addHeads(head()).build()
        val emitted = PodbufCodecEmitter(model(projection, bound = Int.MAX_VALUE), options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        assertTrue("maxSize: Int = 2_147_483_647" in emitted.text!!, emitted.text)
    }

    @Test
    fun `a root with no finite podbuf bound is refused, as Rust refuses it`() {
        val projection = PodbufProjection.newBuilder().addHeads(head()).build()
        val emitted = PodbufCodecEmitter(model(projection, bound = null), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf(
                "$PLUGIN: `kt.demo.Feed` has no finite podbuf bound: its largest buffer does not fit a u32 offset, " +
                    "or an array or map of it passes a descriptor limit",
            ),
            emitted.errors,
        )
    }

    @Test
    fun `the file and object names are PodbufCodec_kt and Type PodbufCodec`() {
        val projection = PodbufProjection.newBuilder().addHeads(head()).build()
        val emitted = PodbufCodecEmitter(model(projection), options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        assertEquals("kt/demo/PodbufCodec.kt", emitted.path)
        val text = emitted.text!!
        assertTrue("object FeedPodbufCodec : Payload<Feed, FeedPodbufView>" in text, text)
        assertTrue("class FeedPodbufView" in text, text)
        assertTrue("Encoding.Podbuf" in text, text)
        assertTrue("maxSize: Int = 16" in text, text)
        assertTrue("@file:JvmName(\"PodbufCodecKt_\")" in text, text)
        assertTrue("import ridl.rt.flatbuffers" !in text, text)
        assertEquals(listOf("FeedPodbufCodec", "FeedPodbufView"), emitted.claims.map { it.name }.sorted())
    }

    @Test
    fun `a text field is written to the tail and read through its descriptor`() {
        val text = PodbufForm.newBuilder()
            .setTailDescriptor(PodbufDescriptor.newBuilder().setText(PodbufBound.newBuilder().setMax(16)))
            .build()
        val label = Field.newBuilder().setName(spell("level"))
            .setType(Type.newBuilder().setInline(Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_STRING)))
            .build()
        val model = model(PodbufProjection.newBuilder().addHeads(head(text)).build()).toBuilder()
            .setDeclarations(0, feed(16).setStruct(Struct.newBuilder().addSlots(Slot.newBuilder().setOrdinal(1).setField(label))))
            .build()
        val emitted = PodbufCodecEmitter(model, options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        val code = emitted.text!!
        assertTrue("w.tailData(" in code, code)
        assertTrue("Desc.read(region, 0)" in code, code)
        // Decision 8 rule 4: the length bound, four bytes per scalar value, before the bytes are placed or read.
        assertTrue(code.indexOf("> 64L") in 0 until code.indexOf("tailRange("), code)
    }

    @Test
    fun `a box root at 2^31 is refused, naming the type`() {
        val emitted = PodbufCodecEmitter(boxModel(0x8000_0000.toInt()), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf("$PLUGIN: `kt.demo.Percent` has a podbuf bound of 2147483648 bytes, above the largest a Kotlin `Payload.maxSize` holds"),
            emitted.errors,
        )
    }

    @Test
    fun `a box root with no finite bound gets Rust's short words, before its head is read`() {
        // Rust checks a box's bound first (codec_podbuf.rs `boxed`): a box with no field is not reached.
        val emitted = PodbufCodecEmitter(boxModel(null, fields = false), options).emit()
        assertNull(emitted.text)
        assertEquals(listOf("$PLUGIN: `kt.demo.Percent` has no finite podbuf bound"), emitted.errors)
    }

    @Test
    fun `a tuple a call sends at 2^31 is refused, naming the tuple`() {
        val emitted = PodbufCodecEmitter(tupleModel(0x8000_0000.toInt()), options).emit()
        assertNull(emitted.text)
        assertEquals(
            listOf("$PLUGIN: `kt.demo.FeedSetArgs` has a podbuf bound of 2147483648 bytes, above the largest a Kotlin `Payload.maxSize` holds"),
            emitted.errors,
        )
    }

    @Test
    fun `podbufMaxSize reads a declaration's and a sent tuple's bound, and has none at 2^31`() {
        assertEquals(24, podbufMaxSize(boxModel(24), PodbufRoot.Declared(0)))
        assertEquals(Int.MAX_VALUE, podbufMaxSize(boxModel(Int.MAX_VALUE), PodbufRoot.Declared(0)))
        assertNull(podbufMaxSize(boxModel(0x8000_0000.toInt()), PodbufRoot.Declared(0)))
        assertNull(podbufMaxSize(boxModel(null), PodbufRoot.Declared(0)))
        assertEquals(32, podbufMaxSize(tupleModel(32), PodbufRoot.Tuple(0)))
        assertNull(podbufMaxSize(tupleModel(0x8000_0000.toInt()), PodbufRoot.Tuple(0)))
        // A tuple no call sends has no codec, so no bound.
        assertNull(podbufMaxSize(tupleModel(32), PodbufRoot.Tuple(1)))
    }

    @Test
    fun `a helper that reads no field binds no region, whatever the package is named`() {
        // A package with a `region` segment: the helpers' qualified names hold `region.`, which a scan of the text took for a read.
        val options = Options("kt.region", listOf(WireEncoding.Podbuf))
        val part = TypeRef.newBuilder().setReference("Part").setResolved(true).setPackage("kt.region").setIndex(1)
        val holder = Declaration.newBuilder().setName(spell("Holder"))
            .setStruct(
                Struct.newBuilder().addSlots(
                    Slot.newBuilder().setOrdinal(1).setField(Field.newBuilder().setName(spell("part")).setType(Type.newBuilder().setNamed(part))),
                ),
            )
            .setPodbuf(SizeState.newBuilder().setBounded(16))
        val inline = PodbufField.newBuilder()
            .setSource(PodbufSource.newBuilder().setField(PodbufNamedField.newBuilder().setName("part").setOrdinal(1)))
            .setOffset(0).setSize(8).setAlign(8)
            .setForm(PodbufForm.newBuilder().setInlineHead(1))
        val projection = PodbufProjection.newBuilder()
            .addHeads(PodbufHead.newBuilder().setDeclaration(0).setSize(8).setAlign(8).addFields(inline))
            .addHeads(head().toBuilder().setDeclaration(1))
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.region"))
            .setScope(Scope.newBuilder().setPackage("kt.region"))
            .addDeclarations(holder).addDeclarations(feed(16).setName(spell("Part")))
            .setPodbuf(projection).build()
        val emitted = PodbufCodecEmitter(model, options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        val text = emitted.text!!
        for (helper in listOf("podbufVerifyHolder", "podbufDecodeHolder")) {
            val body = text.substringAfter("fun $helper(").substringBefore("\n\n")
            assertTrue("region" !in body, body)
        }
        // Part reads its field, so it binds its region.
        assertTrue("val region = buffer.region(at, size)" in text.substringAfter("fun podbufVerifyPart("), text)
    }

    /** `type Percent : integer [0..100]`, a box root with [bound]; its head holds the value at 0 unless [fields] is false. */
    private fun boxModel(bound: Int?, fields: Boolean = true): Model {
        val percent = Declaration.newBuilder().setName(spell("Percent"))
            .setScalar(Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).setIntWidth(IntWidth.INT_WIDTH_U8).setVacuous(true))
        if (bound != null) percent.setPodbuf(SizeState.newBuilder().setBounded(bound))
        val boxHead = PodbufHead.newBuilder().setSize(8).setAlign(8)
        if (fields) {
            boxHead.addFields(
                PodbufField.newBuilder().setSource(PodbufSource.newBuilder().setArm(true)).setOffset(0).setSize(1).setAlign(1).setForm(u8()),
            )
        }
        return Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
            .setScope(Scope.newBuilder().setPackage("kt.demo"))
            .addDeclarations(percent)
            .setPodbuf(PodbufProjection.newBuilder().addBoxes(PodbufBox.newBuilder().setDeclaration(0).setHead(boxHead)))
            .build()
    }

    /** The tuple `FeedSetArgs` (`level`), which the command `Feed.set` sends with the podbuf bound [bound]. */
    private fun tupleModel(bound: Int): Model {
        val tuple = InducedTuple.newBuilder().setName(InducedName.newBuilder().setRust("FeedSetArgs")).addFields(level)
        val tupleHead = PodbufHead.newBuilder().setTuple(0).setSize(8).setAlign(8)
            .addFields(
                PodbufField.newBuilder().setSource(PodbufSource.newBuilder().setPosition(1)).setOffset(0).setSize(1).setAlign(1).setForm(u8()),
            )
        val command = CommandShape.newBuilder().setRequestTuple(TupleRef.newBuilder().setIndex(0))
            .setRequestSizes(PayloadSizes.newBuilder().setPodbuf(SizeState.newBuilder().setBounded(bound)))
        val face = Interface.newBuilder().setDeclared(spell("Feed"))
            .addSlots(InteractionSlot.newBuilder().setInteraction(Interaction.newBuilder().setName(spell("set")).setCommand(command)))
        return Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
            .setScope(Scope.newBuilder().setPackage("kt.demo"))
            .addTuples(tuple).addInterfaces(face)
            .setPodbuf(PodbufProjection.newBuilder().addHeads(tupleHead))
            .build()
    }

    @Test
    fun `a podbuf-only package names no FlatBuffers codec, and a FlatBuffers-only one no podbuf codec`() {
        val projection = PodbufProjection.newBuilder().addHeads(head()).build()
        val catalog = Catalog.newBuilder().setHash(ByteString.copyFrom(ByteArray(32)))
        val request = Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA).setModel(model(projection).toBuilder().setCatalog(catalog))
        fun files(encodings: String) = Generator.generate(
            request.clone().addOptions(Plugin.BackendOption.newBuilder().setKey("encodings").setValue(encodings)).build(),
        )
        val podbuf = files("podbuf")
        assertEquals(emptyList<String>(), podbuf.diagnosticsList.map { it.message })
        assertEquals(listOf("kt/demo/Types.kt", "kt/demo/PodbufCodec.kt"), podbuf.filesList.map { it.path })
        val both = files("flatbuffers,podbuf")
        assertEquals(emptyList<String>(), both.diagnosticsList.map { it.message })
        assertEquals(listOf("kt/demo/Types.kt", "kt/demo/Codec.kt", "kt/demo/PodbufCodec.kt"), both.filesList.map { it.path })
    }

    @Test
    fun `a foreign inline head is written through the declaring package's helpers`() {
        val part = TypeRef.newBuilder().setReference("kt.parts.Part").setResolved(true).setPackage("kt.parts").setForeign(true).setIndex(0)
        val field = Field.newBuilder().setName(spell("part")).setType(Type.newBuilder().setNamed(part)).build()
        val holder = Declaration.newBuilder().setName(spell("Holder"))
            .setStruct(Struct.newBuilder().addSlots(Slot.newBuilder().setOrdinal(1).setField(field)))
            .setPodbuf(SizeState.newBuilder().setBounded(24))
        val foreign = ForeignDeclaration.newBuilder().setPackage("kt.parts")
            .setDeclaration(Declaration.newBuilder().setName(spell("Part")).setStruct(Struct.getDefaultInstance()))
        val inline = PodbufField.newBuilder()
            .setSource(PodbufSource.newBuilder().setField(PodbufNamedField.newBuilder().setName("part").setOrdinal(1)))
            .setOffset(0).setSize(16).setAlign(8)
            .setForm(PodbufForm.newBuilder().setInlineHead(1))
        val projection = PodbufProjection.newBuilder()
            .addHeads(PodbufHead.newBuilder().setDeclaration(0).setSize(16).setAlign(8).addFields(inline))
            .addHeads(PodbufHead.newBuilder().setForeign(0).setSize(16).setAlign(8))
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
            .setScope(Scope.newBuilder().setPackage("kt.demo"))
            .addDeclarations(holder).addForeign(foreign).setPodbuf(projection).build()
        val emitted = PodbufCodecEmitter(model, options).emit()
        assertEquals(emptyList<String>(), emitted.errors)
        val text = emitted.text!!
        for (helper in listOf("podbufEncodePart", "podbufVerifyPart", "podbufDecodePart")) {
            assertTrue("import kt.parts.$helper" in text, text)
        }
        assertTrue("podbufEncodePart(value.part, w, at)" in text, text)
        assertTrue("podbufVerifyPart(buffer, at, size, tail)" in text, text)
        assertTrue("part = podbufDecodePart(buffer, at, size)" in text, text)
        assertEquals(listOf("HolderPodbufCodec", "HolderPodbufView"), emitted.claims.map { it.name }.sorted())
    }
}
