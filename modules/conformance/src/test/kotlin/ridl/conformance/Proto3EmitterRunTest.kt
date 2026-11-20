package ridl.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import ridl.codegen.kotlin.Generator
import ridl.codegen.kotlin.Wire
import ridl.codegen.v1.ModelOuterClass.Proto3Enum
import ridl.codegen.v1.ModelOuterClass.Type
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.Plugin
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Compiled regressions for #133, including values that bypass the value constructors. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Proto3EmitterRunTest {
    private val work: Path = createTempDirectory("proto3-regressions-")

    @AfterAll
    fun cleanup() {
        work.toFile().deleteRecursively()
    }

    private fun request(name: String, source: String): Plugin.CodegenRequest {
        val dir = work.resolve(name).createDirectories()
        dir.resolve("ridl.toml").writeText("[package]\nname = \"probe.proto3\"\nversion = \"1.0.0\"\n[backend.kotlin]\nencodings = [\"proto3\"]\n")
        dir.resolve("source.ridl").writeText("package probe.proto3\n\n$source\n")
        return Wire.readRequest(Harness.capturedRequests(dir, name, work).getValue("probe.proto3"))
    }

    private val compiled by lazy {
        val request = request(
            "values",
            """
            /// A child with no legal empty message.
            struct Child { level : integer [1..10] }
            /// A required child.
            struct Parent { child : Child }
            /// A message map.
            struct Children { items : [integer [0..10] : Child; 0..2] }
            /// A union with no legal default for its first arm.
            union Choice { child : Child other : Child }
            /// Optional defaults.
            struct Defaults { maybe : integer? spot : float [0.0..1.0 step 0.125]? odd : float? note : string [0..8]? }
            /// An open enum whose backing holds values above int32.
            open enum Huge { ZERO = 0 BIG = 2147483647 }
            /// A constrained scalar with an unchecked constructor.
            type Level : integer [1..10]
            /// A named string.
            type Name : string [0..8]
            /// An inline string.
            struct Text { text : string [0..2] }
            /// A numeric array.
            struct Numbers { items : [integer [0..10]; 0..2] }
            /// A map with a two-entry bound.
            struct Entries { items : [integer [0..10] : integer [0..10]; 0..2] }
            /// An enum key supplied through the model.
            enum Gear { ZERO = 0 ONE = 1 }
            /// An enum set key supplied through the model.
            enumset Lights { HEAD = 0 TAIL = 1 }
            /// The source uses integer keys because TYPL-209 rejects enum keys.
            struct Keys { gears : [integer [0..10] : integer [0..10]; 0..2] lights : [integer [0..10] : integer [0..10]; 0..2] }
            """.trimIndent(),
        )
        // The projection can carry enum and enum-set keys even though source syntax rejects them.
        val model = request.model.toBuilder()
        val index = model.declarationsList.indexOfFirst { it.name.declared == "Keys" }
        val declaration = model.getDeclarationsBuilder(index)
        val message = model.proto3Builder.messagesBuilderList.single { it.hasStructDeclaration() && it.structDeclaration == index }
        for ((position, name) in listOf("Gear", "Lights").withIndex()) {
            val target = model.declarationsList.indexOfFirst { it.name.declared == name }
            val ref = TypeRef.newBuilder().setReference(name).setResolved(true).setPackage("probe.proto3").setIndex(target).build()
            declaration.structBuilder.getSlotsBuilder(position).fieldBuilder.typeBuilder.mapBuilder.setKey(Type.newBuilder().setNamed(ref))
            if (position == 0) message.getFieldsBuilder(position).wireBuilder.mapBuilder.keyBuilder.setEnumeration(Proto3Enum.newBuilder().setType(ref))
        }
        // A reader must accept a packed numeric run even when the canonical writer is unpacked.
        val numbers = model.declarationsList.indexOfFirst { it.name.declared == "Numbers" }
        model.proto3Builder.messagesBuilderList.single { it.hasStructDeclaration() && it.structDeclaration == numbers }
            .getFieldsBuilder(0).wireBuilder.arrayBuilder.setPacked(false)
        val response = Generator.generate(request.toBuilder().setModel(model).build())
        assertEquals(emptyList<Plugin.Diagnostic>(), response.diagnosticsList)
        val sources = response.filesList.associate { it.path to it.text }
        val result = Compiler.compile(sources + ("probe/proto3/Run.kt" to runner), work.resolve("compiled"))
        assertTrue(result.ok, result.messages)
        result.classLoader!!.loadClass("probe.proto3.RunKt")
    }

    private fun run(name: String, expected: String) {
        assertEquals(expected, compiled.getMethod(name).invoke(null))
    }

    @Test fun `required child default is lazy`() = run("child", "0a020801")
    @Test fun `map message default is lazy`() = run("children", "0a06080112020801")
    @Test fun `union arm default is lazy`() = run("choice", "12020801")
    @Test fun `present optional defaults retain their fields`() = run("defaults", "080015000000001900000000000000002200")
    @Test fun `map count is checked before a repeated last key`() = run("mapCount", "Entries:Length")
    @Test fun `open enum outside int32 is an encode range refusal`() = run("enumRange", "Huge:Range")
    @Test fun `unpaired surrogate is an encode utf8 refusal`() = run("surrogate", "Name:Utf8")
    @Test fun `inline string length is an encode length refusal`() = run("textLength", "Text:Length")
    @Test fun `array count is an encode length refusal`() = run("arrayCount", "Numbers:Length")
    @Test fun `writer writes a default that is not legal`() = run("illegalDefault", "0800")
    @Test fun `enum map keys preserve values and reject duplicates`() = run("enumKeys", "0a04080110010a021002|Keys:Unique")
    @Test fun `enum set map keys preserve values and reject duplicates`() = run("setKeys", "120408011001120408021002|Keys:Unique")
    @Test fun `numeric array reads packed runs regardless of writer packing`() = run("packed", "08010802")

    @Test
    fun `a dependency before its refusal reports the original message exactly`() {
        val request = request(
            "refused",
            """
            /// A dependency declared first.
            struct B { a : A }
            /// The original refusal.
            struct A { xs : [Level; 0..3]? }
            /// A level.
            type Level : integer [1..10]
            """.trimIndent(),
        )
        val response = Generator.generate(request)
        assertTrue(response.filesList.isEmpty())
        assertEquals(listOf("`A.xs` is an optional array, and proto3 cannot mark a repeated field absent: an absent array is indistinguishable from an empty one, and no value is spare to carry the fact in-band, so this backend refuses rather than choosing silently (ADR-0013 decision 7). Remove the `?`, or wrap the array in a struct, whose absence proto3 can track."), response.diagnosticsList.map { it.message })
    }

    private val runner = """
        package probe.proto3

        import java.nio.ByteBuffer
        import ridl.rt.payload.Payload
        import ridl.rt.payload.EncodeError
        import ridl.rt.payload.VerifyError

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun bytes(hex: String) = ByteBuffer.wrap(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        private fun <T, V> encode(codec: Payload<T, V>, value: T): String = try {
            val out = ByteBuffer.allocate(1024)
            val n = codec.encode(value, out)
            hex(out.array().copyOf(n))
        } catch (e: EncodeError.Contract) { "${'$'}{e.violation.typeName}:${'$'}{e.violation.rule}" }
        private fun <T, V> read(codec: Payload<T, V>, hex: String): String = try {
            encode(codec, codec.decode(codec.verify(bytes(hex))))
        } catch (e: VerifyError.Contract) { "${'$'}{e.violation.typeName}:${'$'}{e.violation.rule}" }

        // Structs have no unchecked constructor. Replace a field after construction to exercise the writer's own checks.
        private fun <T : Any> corrupt(value: T, field: String, replacement: Any): T {
            value.javaClass.getDeclaredField(field).also { it.isAccessible = true }.set(value, replacement)
            return value
        }
        fun child() = read(ParentProto3Codec, "0a020801")
        fun children() = read(ChildrenProto3Codec, "0a06080112020801")
        fun choice() = read(ChoiceProto3Codec, "12020801")
        fun defaults() = read(DefaultsProto3Codec, "080015000000001900000000000000002200")
        fun mapCount() = read(EntriesProto3Codec, "0a04080110010a04080210020a0408011001")
        fun enumRange() = encode(HugeProto3Codec, Huge.fromValue(2147483648L))
        fun surrogate() = encode(NameProto3Codec, Name.unchecked("\uD800"))
        fun textLength() = encode(TextProto3Codec, corrupt(Text(""), "text", "abc"))
        fun arrayCount() = encode(NumbersProto3Codec, corrupt(Numbers(emptyList()), "items", listOf(1L, 2L, 3L)))
        fun illegalDefault() = encode(LevelProto3Codec, Level.unchecked(0L))
        fun enumKeys(): String {
            val canonical = encode(KeysProto3Codec, Keys(mapOf(Gear.ONE to 1L, Gear.ZERO to 2L), emptyMap()))
            check(read(KeysProto3Codec, canonical) == canonical)
            return canonical + "|" + read(KeysProto3Codec, "0a04080110010a0408011002")
        }
        fun setKeys(): String {
            val canonical = encode(KeysProto3Codec, Keys(emptyMap(), mapOf(Lights.HEAD to 1L, Lights.TAIL to 2L)))
            check(read(KeysProto3Codec, canonical) == canonical)
            return canonical + "|" + read(KeysProto3Codec, "120408011001120408011002")
        }
        fun packed() = read(NumbersProto3Codec, "0a020102")
    """.trimIndent()
}
