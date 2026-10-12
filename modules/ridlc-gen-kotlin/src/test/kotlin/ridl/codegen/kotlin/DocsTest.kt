package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.DocParts
import ridl.codegen.kotlin.types.Links
import ridl.codegen.v1.ModelOuterClass.Arm
import ridl.codegen.v1.ModelOuterClass.CommandShape
import ridl.codegen.v1.ModelOuterClass.Constant
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.DocLink
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Enum
import ridl.codegen.v1.ModelOuterClass.EnumValue
import ridl.codegen.v1.ModelOuterClass.EventShape
import ridl.codegen.v1.ModelOuterClass.Field
import ridl.codegen.v1.ModelOuterClass.ForeignDeclaration
import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.InteractionSlot
import ridl.codegen.v1.ModelOuterClass.Interface
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.PrimitiveType
import ridl.codegen.v1.ModelOuterClass.Rule
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Scope
import ridl.codegen.v1.ModelOuterClass.SignalShape
import ridl.codegen.v1.ModelOuterClass.Slot
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Struct
import ridl.codegen.v1.ModelOuterClass.Union
import ridl.codegen.v1.ModelOuterClass.Visibility

/** #86, ADR-0028 decision 6: doc links resolved to Kotlin paths, and the KDoc order. */
class DocsTest {
    private fun spelled(declared: String, camel: String = declared.replaceFirstChar(Char::uppercaseChar)) =
        Spellings.newBuilder().setDeclared(declared).setCamel(camel).build()

    private fun declaration(name: String, camel: String = name) = Declaration.newBuilder().setName(spelled(name, camel))

    private val scalar = Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).build()

    private fun constant(name: String, primitive: PrimitiveType) =
        declaration(name).setConstant(Constant.newBuilder().setPrimitive(primitive).setValue("0")).build()

    private fun slot(declared: String, shape: Interaction.Builder.() -> Unit) = InteractionSlot.newBuilder()
        .setInteraction(Interaction.newBuilder().setName(spelled(declared)).apply(shape))

    /**
     * `kt.demo`, generated into `com.example.demo`: a scalar, an enum whose
     * value `value` the enum escapes, a struct, a union, an internal type, an
     * emitted and a skipped constant, a foreign struct and constant, an
     * interface `Drive` with a face and one `Hush` without.
     */
    private val model: Model = Model.newBuilder()
        .setName(DottedName.newBuilder().setDotted("kt.demo"))
        .setScope(Scope.newBuilder().setPackage("kt.demo"))
        .addDeclarations(declaration("Speed").setScalar(scalar))
        .addDeclarations(
            declaration("Gear").setEnum(
                Enum.newBuilder().addValues(EnumValue.newBuilder().setName(spelled("P")))
                    .addValues(EnumValue.newBuilder().setName(spelled("value"))),
            ),
        )
        .addDeclarations(
            declaration("Holder").setStruct(
                Struct.newBuilder()
                    .addSlots(Slot.newBuilder().setField(Field.newBuilder().setName(spelled("max_speed", "MaxSpeed"))))
                    .addSlots(Slot.newBuilder().setField(Field.newBuilder().setName(spelled("in", "In")))),
            ),
        )
        .addDeclarations(declaration("Shape").setUnion(Union.newBuilder().addArms(Arm.newBuilder().setName(spelled("circle")))))
        .addDeclarations(declaration("Hidden").setScalar(scalar).setVisibility(Visibility.VISIBILITY_INTERNAL))
        .addDeclarations(constant("GREETING", PrimitiveType.PRIMITIVE_TYPE_STRING))
        .addDeclarations(constant("BLOB", PrimitiveType.PRIMITIVE_TYPE_BYTES))
        .addForeign(ForeignDeclaration.newBuilder().setPackage("other.pkg").setDeclaration(declaration("Thing").setScalar(scalar)))
        .addForeign(ForeignDeclaration.newBuilder().setPackage("other.pkg").setDeclaration(constant("GREETING", PrimitiveType.PRIMITIVE_TYPE_STRING)))
        .addInterfaces(
            Interface.newBuilder().setDeclared(spelled("Drive"))
                .addSlots(slot("speed") { setSignal(SignalShape.getDefaultInstance()) })
                .addSlots(slot("alarm") { setEvent(EventShape.getDefaultInstance()) })
                .addSlots(slot("set_gear") { setName(spelled("set_gear", "SetGear")).setCommand(CommandShape.getDefaultInstance()) }),
        )
        .addInterfaces(Interface.newBuilder().setDeclared(spelled("Hush")))
        .build()

    private val links = Links(model, "com.example.demo", faced = setOf(0))

    @Test
    fun `each target resolves to the Kotlin path of what the plugin emits for it`() {
        val expected = mapOf(
            "kt.demo.Speed" to "com.example.demo.Speed",
            "kt.demo.Gear.P" to "com.example.demo.Gear.P",
            "kt.demo.Gear.value" to "com.example.demo.Gear.value_",
            "kt.demo.Holder" to "com.example.demo.Holder",
            "kt.demo.Holder.max_speed" to "com.example.demo.Holder.maxSpeed",
            "kt.demo.Shape.circle" to "com.example.demo.Shape.Circle",
            "kt.demo.GREETING" to "com.example.demo.Constants_.GREETING",
            "other.pkg.Thing" to "other.pkg.Thing",
            "kt.demo.Drive" to "com.example.demo.Drive",
            "kt.demo.Drive.speed" to "com.example.demo.DriveClient.speed",
            "kt.demo.Drive.set_gear" to "com.example.demo.DriveClient.setGear",
        )
        for ((target, path) in expected) assertEquals(path, links.path(target), target)
    }

    @Test
    fun `a target the output does not emit, or emits internal, has no path`() {
        for (
            target in listOf(
                "kt.demo.Hidden", "kt.demo.BLOB", "other.pkg.GREETING", "kt.demo.Hush", "kt.demo.Drive.alarm",
                "kt.demo.Holder.in", "kt.demo.Holder.nothing", "kt.demo.Speed.max", "kt.demo.Speedy", "elsewhere.Thing",
            )
        ) {
            assertNull(links.path(target), target)
        }
    }

    private fun link(doc: String, written: String, target: String): DocLink {
        val at = doc.indexOf(written)
        return DocLink.newBuilder().setText(written.removeSurrounding("[", "]"))
            .setOffset(doc.substring(0, at).toByteArray().size).setLen(written.toByteArray().size).setTarget(target).build()
    }

    @Test
    fun `a link after a multi-byte character lands on its bytes`() {
        val doc = "Measured in °C, see [Speed]."
        assertEquals(
            "Measured in °C, see [Speed][com.example.demo.Speed].",
            links.linked(doc, listOf(link(doc, "[Speed]", "kt.demo.Speed"))),
        )
    }

    @Test
    fun `a link with no path is a code span of its target, and links are replaced last to first`() {
        val doc = "Not [Hidden], but [Speed]."
        val replaced = links.linked(doc, listOf(link(doc, "[Hidden]", "kt.demo.Hidden"), link(doc, "[Speed]", "kt.demo.Speed")))
        assertEquals("Not `kt.demo.Hidden`, but [Speed][com.example.demo.Speed].", replaced)
    }

    @Test
    fun `a link off the text, off a character boundary, empty or overlapping leaves the text`() {
        val doc = "°C [Speed]"
        fun raw(offset: Int, len: Int) = DocLink.newBuilder().setText("x").setOffset(offset).setLen(len).setTarget("kt.demo.Speed").build()
        for (bad in listOf(raw(100, 2), raw(1, 2), raw(0, 0), raw(-1, 2), raw(0, -1))) {
            assertEquals(doc, links.linked(doc, listOf(bad)), "$bad")
        }
        val overlapping = listOf(link(doc, "[Speed]", "kt.demo.Speed"), raw(3, 3))
        assertEquals("°C [Speed][com.example.demo.Speed]", links.linked(doc, overlapping))
    }

    private fun rule(text: String) = Rule.newBuilder().setText(text).build()

    @Test
    fun `the KDoc is the doc, the sentence, the rules, then the tags`() {
        val doc = "Sets the [Gear]."
        val parts = DocParts(
            doc = doc,
            links = listOf(link(doc, "[Gear]", "kt.demo.Gear")),
            see = listOf(DocLink.newBuilder().setTarget("kt.demo.Speed").build(), DocLink.newBuilder().setTarget("kt.demo.Hidden").build()),
            since = listOf("1.2"),
            rules = listOf(rule("require gear != P")),
        )
        val param = DocParts("The gear to set.", emptyList(), emptyList(), emptyList(), listOf(rule("range 0 ..= 5")))
        val kdoc = links.kdoc(parts, "Calls command `set_gear`.", listOf("gear" to param, "silent" to DocParts.NONE))
        assertEquals(
            """
            Sets the [Gear][com.example.demo.Gear].

            Calls command `set_gear`.

            Rules:
            - require gear != P

            @param gear The gear to set.
            - range 0 ..= 5
            @see com.example.demo.Speed
            @see `kt.demo.Hidden`
            @since 1.2
            """.trimIndent(),
            kdoc,
        )
    }

    @Test
    fun `an empty carrier is the sentence alone, and nothing without one`() {
        assertEquals("The descriptor.", links.kdoc(DocParts.NONE, "The descriptor."))
        assertEquals("", links.kdoc(DocParts.NONE))
    }
}
