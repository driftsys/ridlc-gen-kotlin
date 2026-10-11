package ridl.codegen.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.codegen.kotlin.types.FacesEmitter
import com.google.protobuf.ByteString
import ridl.codegen.v1.ModelOuterClass.Catalog
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
import ridl.codegen.v1.ModelOuterClass.Timing
import ridl.codegen.v1.ModelOuterClass.TimingMode
import ridl.codegen.v1.ModelOuterClass.Spellings
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.Plugin

/** What `Faces.kt` skips, and how: an interface the face cannot carry is a warning, not a failed package. */
class FacesEmitterTest {
    private val options = Options("kt.demo", WireEncoding.FlatBuffers)

    /** A catalog hash no placeholder spells: bytes 1 to 32. */
    private val hash = ByteArray(32) { (it + 1).toByte() }

    /** Package `kt.demo`, with its catalog. */
    private fun demo() = Model.newBuilder().setName(DottedName.newBuilder().setDotted("kt.demo"))
        .setCatalog(Catalog.newBuilder().setPackage("kt.demo").setHash(ByteString.copyFrom(hash)))

    private fun spelled(name: String) = Spellings.newBuilder().setDeclared(name).setCamel(name.replaceFirstChar(Char::uppercaseChar)).build()

    private fun model(command: CommandShape): Model {
        val interaction = Interaction.newBuilder().setName(spelled("go")).setCommand(command)
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(InteractionSlot.newBuilder().setOrdinal(1).setInteraction(interaction))
        return demo().addInterfaces(iface).build()
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
    fun `the descriptor's catalog is the model's, hash included`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1).addSlots(command(1, "set", "Set"))
        val text = checkNotNull(faces("Level", iface = iface).text)
        assertTrue("CatalogRef(\"kt.demo\", CatalogHash(byteArrayOf(${hash.joinToString()})))" in text, text)
    }

    @Test
    fun `the descriptor's catalog is named after the unit, not the source package`() {
        // ridl 0.7.0: one catalog per unit, so a subpackage's `Catalog.package` is the unit's name.
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(3).addSlots(command(1, "set", "Set"))
        val model = demo().setName(DottedName.newBuilder().setDotted("kt.demo.cluster"))
            .addDeclarations(scalar("Level")).addInterfaces(iface).build()
        val text = checkNotNull(FacesEmitter(model, options).emit().text)
        assertTrue("CatalogRef(\"kt.demo\", CatalogHash(" in text, text)
        assertTrue("\"kt.demo.cluster\"" !in text, text)
    }

    @Test
    fun `an empty catalog name is written as it is, as the Rust backend writes it`() {
        // #65: no fallback to `Model.name`, which names the source package, not the unit.
        // The Rust backend's `catalog_name` reads `Catalog.package` with `unwrap_or_default`.
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1).addSlots(command(1, "set", "Set"))
        val model = demo().setCatalog(Catalog.newBuilder().setHash(ByteString.copyFrom(hash)))
            .addDeclarations(scalar("Level")).addInterfaces(iface).build()
        val text = checkNotNull(FacesEmitter(model, options).emit().text)
        assertTrue("CatalogRef(\"\", CatalogHash(" in text, text)
    }

    @Test
    fun `a catalog hash that is not 32 bytes refuses the package`() {
        for (size in listOf(0, 31, 33)) {
            val model = demo().setCatalog(Catalog.newBuilder().setPackage("kt.demo").setHash(ByteString.copyFrom(ByteArray(size))))
            val request = Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA).setModel(model).build()
            val response = Generator.generate(request)
            assertEquals(0, response.filesCount, "no file for a $size-byte hash")
            assertEquals(
                listOf("$PLUGIN: malformed codegen model: `Catalog.hash` is $size bytes, not 32"),
                response.diagnosticsList.map { it.message },
            )
        }
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
        demo()
            .addAllDeclarations(declarations.map { scalar(it).build() }).addInterfaces(iface).build(),
        options,
    ).emit()

    /** The error diagnostics of the whole plugin over [declarations] and [iface]. */
    private fun refusals(vararg declarations: String, iface: Interface.Builder): List<String> = Generator.generate(
        Plugin.CodegenRequest.newBuilder().setSchema(SCHEMA).setModel(
            demo()
                .addAllDeclarations(declarations.map { scalar(it).build() }).addInterfaces(iface),
        ).build(),
    ).diagnosticsList.filter { it.severity == Plugin.DiagnosticSeverity.DIAGNOSTIC_SEVERITY_ERROR }.map { it.message }

    @Test
    fun `two generated types of one name refuse the package, naming both sources`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(command(1, "set", "Set")).addSlots(command(2, "set_call", "SetCall"))
        assertEquals(emptyList<String>(), faces("Level", iface = iface).warnings, "the face is not skipped")
        assertEquals(
            listOf(
                "$PLUGIN: `DriveSetCall` is generated twice in package `kt.demo`: for the descriptor of member `set_call` " +
                    "of interface `Drive`, and for the call of member `set` of interface `Drive`; rename one of them",
            ),
            refusals("Level", iface = iface),
        )
    }

    @Test
    fun `a generated type named like a declared one refuses the package`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1).addSlots(command(1, "set", "Set"))
        val refusal = refusals("Level", "DriveSet", iface = iface).single()
        assertTrue("for declaration `DriveSet`, and for the descriptor of member `set` of interface `Drive`" in refusal, refusal)
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

    /** The `Faces.kt` of a signal-only interface `Gauge` whose one signal `level` carries [timing]. */
    private fun gauge(timing: Timing?): String {
        val signal = slot(1, "level", "Level", signal = true).apply {
            if (timing != null) interactionBuilder.setTiming(timing)
        }
        val iface = Interface.newBuilder().setDeclared(spelled("Gauge")).setNumber(1).addSlots(signal)
        val level = scalar("Level").setInit(Init.newBuilder().setDerivable(true).setValue("0").setOneLevel(true)).build()
        return checkNotNull(FacesEmitter(demo().addDeclarations(level).addInterfaces(iface).build(), options).emit().text)
    }

    private fun timing(mode: TimingMode, min: String?, max: String?) = Timing.newBuilder().setMode(mode)
        .apply { if (min != null) setMinUs(min) }.apply { if (max != null) setMaxUs(max) }.build()

    @Test
    fun `each signal is a state of the async client, read through one shared decode`() {
        // #76: the timing travels on the descriptor; the runtime turns it into the polling period.
        val text = gauge(timing(TimingMode.TIMING_MODE_RANGE, "20000", "500000"))
        assertTrue("public val <P : SignalReader> GaugeAsyncClient<P>.level: SignalState<Level>" in text, text)
        assertTrue("get() = _signalStates.of(GaugeLevel, null, LevelCodec.maxSize) { raw, buf -> sampled(LevelCodec, GaugeLevel, raw, buf) }" in text, text)
        assertTrue("return sampled(LevelCodec, GaugeLevel, port.read(Gauge.number, Ordinal(1u), buf), buf)" in text, "the blocking read decodes the same way")
        assertFalse("levelFlow" in text, "the cold flow is gone")
    }

    @Test
    fun `a signal-only interface gets a suspending client of signal states, polled in a scope it owns by default`() {
        val text = gauge(null)
        assertTrue("public class GaugeAsyncClient<P : SignalReader>(" in text, text)
        assertTrue("scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)," in text, text)
        assertTrue("grid: PollGrid = PollGrid.Default," in text, text)
        assertTrue("internal val _signalStates: SignalStates = SignalStates(port, scope, grid)" in text, text)
        assertTrue("internal val _port: P = port" in text, "member names start with _, which no ridl identifier does")
        assertFalse("public fun level(): Sample<Level> = reads.level()" in text, "the async client has no read of its own")
        assertTrue("public fun level(): Sample<Level>" in text, "the blocking client keeps its read")
    }

    @Test
    fun `a member named like a fixed or derived operation keeps its interface and shadows the extension`() {
        val iface = Interface.newBuilder().setDeclared(spelled("Drive")).setNumber(1)
            .addSlots(slot(1, "next_event", "NextEvent", signal = true))
            .addSlots(slot(2, "subscribe_warning", "SubscribeWarning", signal = true))
            .addSlots(slot(3, "warning", "Warning", signal = false))
            .addSlots(command(4, "set_timeout", "SetTimeout"))
        val level = scalar("Level").setInit(Init.newBuilder().setDerivable(true).setValue("0").setOneLevel(true)).build()
        val emitted = FacesEmitter(
            demo().addDeclarations(level).addInterfaces(iface).build(),
            options,
        ).emit()
        assertEquals(emptyList<String>(), emitted.warnings)
        val text = checkNotNull(emitted.text)
        assertTrue("public fun nextEvent(): Sample<Level>" in text, "the signal keeps its member")
        assertTrue("public val <P> DriveAsyncClient<P>.nextEvent:\n    SignalState<Level>" in text, "the async client's signal is a state")
        assertTrue("\npublic suspend fun <P> DriveAsyncClient<P>.nextEvent(): Drive.Event" in text, "no member to shadow it on the async client")
        val shadowed = "@Suppress(\"EXTENSION_SHADOWED_BY_MEMBER\")\npublic fun <P> DriveClient<P>.nextEvent(): Drive.Event?"
        assertTrue(shadowed in text, "the fixed operation is an extension the member shadows")
        assertTrue("@Suppress(\"EXTENSION_SHADOWED_BY_MEMBER\")\npublic fun <P> DriveClient<P>.subscribeWarning()" in text)
        assertTrue("\npublic fun <P> DriveClient<P>.unsubscribeWarning()" in text, "an extension no member shadows is not suppressed")
        assertTrue("public fun setTimeout(level: Level)" in text, "a command keeps its member")
        assertTrue("public var <P> DriveClient<P>.timeout:" in text, "the timeout is an extension property")
    }
}
