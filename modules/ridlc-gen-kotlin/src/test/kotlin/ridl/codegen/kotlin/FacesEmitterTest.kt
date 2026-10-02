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
import ridl.codegen.v1.ModelOuterClass.EventShape
import ridl.codegen.v1.ModelOuterClass.Init
import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.InteractionSlot
import ridl.codegen.v1.ModelOuterClass.Interface
import ridl.codegen.v1.ModelOuterClass.IntWidth
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Param
import ridl.codegen.v1.ModelOuterClass.Payload
import ridl.codegen.v1.ModelOuterClass.Scalar
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.SignalShape
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
        val emitted = faces("Level", "DriveSet", iface = iface)
        assertNull(emitted.text)
        assertTrue("`DriveSet` collides with a declaration" in emitted.warnings.single(), emitted.warnings.single())
    }

    @Test
    fun `a declaration named like the call base keeps its interface`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1).addSlots(command(1, "set", "Set"))
        val emitted = faces("Level", "InteractionCall", iface = iface)
        assertEquals(emptyList<String>(), emitted.warnings)
        assertTrue("internal abstract class InteractionCall_<" in checkNotNull(emitted.text))
    }

    /** A signal, or an event, named [declared] and [camel], over one `Level`. */
    private fun slot(ordinal: Int, declared: String, camel: String, signal: Boolean) = InteractionSlot.newBuilder().setOrdinal(ordinal)
        .setInteraction(
            Interaction.newBuilder().setName(Spellings.newBuilder().setDeclared(declared).setCamel(camel)).apply {
                val payload = Payload.newBuilder().setType(level).setFlatbuffersMaxSize(8)
                if (signal) setSignal(SignalShape.newBuilder().setPayload(payload)) else setEvent(EventShape.newBuilder().setPayload(payload))
            },
        )

    @Test
    fun `a member named like a fixed or derived operation keeps its interface and shadows the extension`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(slot(1, "next_event", "NextEvent", signal = true))
            .addSlots(slot(2, "subscribe_warning", "SubscribeWarning", signal = true))
            .addSlots(slot(3, "warning", "Warning", signal = false))
            .addSlots(command(4, "set_timeout", "SetTimeout"))
        val level = scalar("Level").setInit(Init.newBuilder().setDerivable(true).setValue("0").setOneLevel(true)).build()
        val emitted = FacesEmitter(
            Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo")).addDeclarations(level).addInterfaces(iface).build(),
            options,
        ).emit()
        assertEquals(emptyList<String>(), emitted.warnings)
        val text = checkNotNull(emitted.text)
        assertTrue("public fun nextEvent(): Sample<Level>" in text, "the signal keeps its member")
        val shadowed = "@Suppress(\"EXTENSION_SHADOWED_BY_MEMBER\")\npublic fun <P> DriveClient<P>.nextEvent(): Drive.Event?"
        assertTrue(shadowed in text, "the fixed operation is an extension the member shadows")
        assertTrue("@Suppress(\"EXTENSION_SHADOWED_BY_MEMBER\")\npublic fun <P> DriveClient<P>.subscribeWarning()" in text)
        assertTrue("\npublic fun <P> DriveClient<P>.unsubscribeWarning()" in text, "an extension no member shadows is not suppressed")
        assertTrue("public fun setTimeout(level: Level)" in text, "a command keeps its member")
        assertTrue("public var <P> DriveClient<P>.timeout:" in text, "the timeout is an extension property")
    }
}
