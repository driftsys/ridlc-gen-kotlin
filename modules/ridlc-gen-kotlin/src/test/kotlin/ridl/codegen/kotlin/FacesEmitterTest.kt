package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.FacesEmitter
import ridl.codegen.v1.ModelOuterClass.Clause
import ridl.codegen.v1.ModelOuterClass.Constraint
import ridl.codegen.v1.ModelOuterClass.DeclKind
import ridl.codegen.v1.ModelOuterClass.Declaration
import ridl.codegen.v1.ModelOuterClass.CommandShape
import ridl.codegen.v1.ModelOuterClass.ContractKind
import ridl.codegen.v1.ModelOuterClass.DottedName
import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.InteractionSlot
import ridl.codegen.v1.ModelOuterClass.Interface
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Param
import ridl.codegen.v1.ModelOuterClass.Payload
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.Plugin

/** What `Faces.kt` skips, and how: an interface the face cannot carry is a warning, not a failed package. */
class FacesEmitterTest {
    private val options = Options("kt.demo", WireEncoding.FlatBuffers)

    private fun spelled(name: String) = Spellings.newBuilder().setDeclared(name).setCamel(name.replaceFirstChar(Char::uppercaseChar)).build()

    private fun model(command: CommandShape): Model {
        val interaction = Interaction.newBuilder().setName(spelled("go")).setCommand(command)
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(InteractionSlot.newBuilder().setOrdinal(1).setInteraction(interaction))
        return Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).addInterfaces(iface).build()
    }

    @Test
    fun `a call with two parameters skips its interface with a warning`() {
        val command = CommandShape.newBuilder()
            .addParams(Param.newBuilder().setName(spelled("a")))
            .addParams(Param.newBuilder().setName(spelled("b")))
            .build()
        val emitted = FacesEmitter(model(command), options).emit()
        assertNull(emitted.text, "no interface is left to face")
        assertEquals(emptyList<String>(), emitted.errors)
        assertTrue(emitted.warnings.single().contains("`kt.demo.Drive`") && "exactly one parameter" in emitted.warnings[0], emitted.warnings[0])
    }

    @Test
    fun `a clause the translator refuses names the clause and the reason`() {
        val level = TypeRef.newBuilder().setReference("Level").setResolved(true).setIndex(0).setKind(DeclKind.DECL_KIND_SCALAR)
        val scalar = Declaration.newBuilder().setName(spelled("Level")).setScalar(
            Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).setIntWidth(IntWidth.INT_WIDTH_U8)
                .setConstraint(Constraint.newBuilder().setMin("0").setMax("100")),
        )
        val refused = Clause.newBuilder().setKind(ContractKind.CONTRACT_KIND_REQUIRE)
            .setSource("level * 2 > 3").setRefused("an arithmetic subject").build()
        val command = CommandShape.newBuilder()
            .addParams(Param.newBuilder().setName(spelled("level")).setType(Type.newBuilder().setNamed(level)))
            .setRequest(Payload.newBuilder().setType(level).setFlatbuffersMaxSize(43))
            .addClauses(refused).build()
        val model = model(command).toBuilder().addDeclarations(scalar).build()
        val warning = FacesEmitter(model, options).emit().warnings.single()
        assertTrue("`level * 2 > 3`" in warning && "an arithmetic subject" in warning, warning)
    }

    @Test
    fun `a skipped interface leaves the package generated, with the warning in the response`() {
        val command = CommandShape.newBuilder().addParams(Param.newBuilder().setName(spelled("a"))).addParams(Param.newBuilder().setName(spelled("b"))).build()
        val request = Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA).setModel(model(command)).build()
        val response = Generator.generate(request)
        assertEquals(listOf("kt/demo/Types.kt", "kt/demo/Codec.kt"), response.filesList.map { it.path })
        assertEquals(Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_WARNING, response.diagnosticsList.single().severity)
    }

    @Test
    fun `a member named like a generated client method skips its interface`() {
        val event = Interaction.newBuilder().setName(Spellings.newBuilder().setDeclared("next_event").setCamel("NextEvent"))
            .setEvent(
                ridl.codegen.v1.ModelOuterClass.EventShape.newBuilder()
                    .setPayload(Payload.newBuilder().setType(TypeRef.newBuilder().setReference("Level")).setFlatbuffersMaxSize(8)),
            )
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(InteractionSlot.newBuilder().setOrdinal(1).setInteraction(event))
        val model = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).addInterfaces(iface).build()
        val emitted = FacesEmitter(model, options).emit()
        assertNull(emitted.text)
        assertTrue("collides with the generated `nextEvent`" in emitted.warnings.single(), emitted.warnings.single())
    }

    private val level = TypeRef.newBuilder().setReference("Level").setResolved(true).setIndex(0).setKind(DeclKind.DECL_KIND_SCALAR)

    private fun scalar(name: String) = Declaration.newBuilder().setName(spelled(name)).setScalar(
        Scalar.newBuilder().setClass_(ScalarClass.SCALAR_CLASS_INTEGER).setIntWidth(IntWidth.INT_WIDTH_U8),
    )

    /** A command taking one `Level`, spelled [declared] and [camel]. */
    private fun command(ordinal: Int, declared: String, camel: String) = InteractionSlot.newBuilder().setOrdinal(ordinal).setInteraction(
        Interaction.newBuilder().setName(Spellings.newBuilder().setDeclared(declared).setCamel(camel)).setCommand(
            CommandShape.newBuilder()
                .addParams(Param.newBuilder().setName(spelled("level")).setType(Type.newBuilder().setNamed(level)))
                .setRequest(Payload.newBuilder().setType(level).setFlatbuffersMaxSize(43)),
        ),
    )

    private fun faces(vararg declarations: String, iface: Interface.Builder) = FacesEmitter(
        Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
            .addAllDeclarations(declarations.map { scalar(it).build() }).addInterfaces(iface).build(),
        options,
    ).emit()

    @Test
    fun `two generated types of one name skip their interface`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(command(1, "set", "Set")).addSlots(command(2, "set_call", "SetCall"))
        val emitted = faces("Level", iface = iface)
        assertNull(emitted.text)
        assertTrue("`DriveSetCall` is generated twice" in emitted.warnings.single(), emitted.warnings.single())
    }

    @Test
    fun `a generated type named like a declared one skips its interface`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1).addSlots(command(1, "set", "Set"))
        val emitted = faces("Level", "InteractionCall", iface = iface)
        assertNull(emitted.text)
        assertTrue("`InteractionCall` collides with a declaration" in emitted.warnings.single(), emitted.warnings.single())
    }
}
