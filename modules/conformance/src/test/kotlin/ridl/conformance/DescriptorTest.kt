package ridl.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.bufferedWriter
import ridl.descriptor.VerifyError as CatalogError
import ridl.descriptor.deployment.AttributeValue
import ridl.descriptor.deployment.MAX_ATTRIBUTE_DEPTH
import ridl.descriptor.deployment.VerifyError as DeploymentError
import ridl.descriptor.deployment.verify as verifyDeployment
import ridl.descriptor.verify as verifyCatalog

/**
 * #142: `ridl-descriptor-kt` reads and refuses deployment and catalog
 * descriptors exactly as `ridl-descriptor` of the pinned release does.
 *
 * The pinned `ridl` builds driftsys/ridl's identity fixture
 * (`crates/ridl-rt-conformance/fixtures/identity`, whose sources are under
 * `src/test/descriptor/identity`) into its deployment descriptor, which is
 * byte for byte the fixture's committed `identity.deployment.binfb`, and its
 * four catalog descriptors. The reader must read the facts the fixture's
 * README states. `src/test/descriptor/attributes` is the same fixture with
 * attributes on every table that has them, one nested to the depth limit, and
 * an external component, which puts a link on the system boundary.
 *
 * Then every truncation of both deployment descriptors and of `alpha`'s
 * catalog descriptor, and each of their bytes set to 0x00, 0xFF and one more,
 * go through `verify`; the verdicts, down to planus's error kind, must be
 * those the Rust `verify` gives, committed, one letter per verdict, as
 * `resources/descriptor/descriptor-rust-verdicts.txt` by `just rust-verdicts`.
 */
class DescriptorTest {
    @TempDir
    lateinit var work: Path

    private val fixtures: Path = Path.of(System.getProperty("descriptor.dir"))

    /** The deployment descriptor and the catalog descriptors the pinned `ridl` builds from [fixture]. */
    private fun built(fixture: String = "identity"): Map<String, ByteArray> {
        val sources = fixtures.resolve(fixture)
        val pkg = work.resolve("src-$fixture")
        Files.walk(sources).use { paths ->
            paths.forEach { path ->
                val dest = pkg.resolve(path.relativeTo(sources).toString())
                if (Files.isDirectory(path)) dest.createDirectories() else path.copyTo(dest)
            }
        }
        val out = Harness.build(pkg, work.resolve("out-$fixture"), "--emit", "deployment", "--emit", "catalog")
        return Harness.files(out).filterKeys { it.endsWith(".binfb") }
    }

    @Test
    fun `the identity descriptor reads as the fixture states`() {
        val files = built()
        assertEquals(
            setOf("alpha.Bench.deployment.binfb", "alpha.catalog.binfb", "beta.catalog.binfb", "mapped.catalog.binfb", "remote.catalog.binfb"),
            files.keys,
        )
        val deployment = verifyDeployment(files.getValue("alpha.Bench.deployment.binfb"))
        assertEquals("Bench", deployment.name)
        assertEquals(listOf("Local", "Other"), deployment.machines.map { it.name })
        val placed = deployment.instances.associate {
            "${it.component}/${it.instance}" to Triple(it.machine, it.number, it.offers.map { key -> key.catalog } to it.maps)
        }
        assertEquals(
            mapOf(
                "alpha.First/Unit" to Triple("Local", 1, listOf("alpha") to listOf("mapped")),
                "alpha.Second/Unit" to Triple("Local", 3, listOf("beta") to listOf("alpha")),
                "alpha.Foreign/Unit" to Triple("Other", 2, listOf("mapped", "remote") to emptyList()),
            ),
            placed,
        )
        // Each region's catalog descriptor is embedded byte for byte, and its stored hash is the catalog's.
        assertEquals(listOf("alpha", "beta", "mapped", "remote"), deployment.catalogs.map { it.name })
        for (embedded in deployment.catalogs) {
            val standalone = files.getValue("${embedded.name}.catalog.binfb")
            assertArrayEquals(standalone, embedded.bytes, embedded.name)
            val catalog = verifyCatalog(standalone)
            assertEquals(embedded.name, catalog.name)
            assertArrayEquals(embedded.hash, catalog.hash)
            assertEquals(1, catalog.interfaces.size, embedded.name)
        }
    }

    @Test
    fun `attribute values nested to the limit, flags and a surface read in full`() {
        val deployment = verifyDeployment(built("attributes").getValue("alpha.Bench.deployment.binfb"))
        fun depth(value: AttributeValue): Int = 1 + (value.list?.maxOf(::depth) ?: 0)
        val layers = deployment.attributes.single { it.key == "layers" }
        assertEquals(MAX_ATTRIBUTE_DEPTH, depth(layers.value!!))
        val local = deployment.machines.single { it.name == "Local" }
        assertEquals(listOf("ASIL_B"), local.labels)
        assertEquals(listOf("2", "3"), local.attributes.single { it.key == "cpus" }.value!!.list!!.map { it.scalar })
        val second = deployment.instances.single { it.component == "alpha.Second" }
        assertEquals(listOf("cpuset", "reliable"), second.attributes.map { it.key })
        assertEquals(null, second.attributes.single { it.key == "reliable" }.value, "a flag has no value")
        assertEquals(listOf("owner"), deployment.systemAttributes.map { it.key })
        assertTrue(deployment.surface.isNotEmpty(), "the external component is on the system boundary")
        for (surface in deployment.surface) assertTrue(surface.link < deployment.links.size)
    }

    @Test
    fun `the header refusals are Rust's`() {
        val files = built()
        val deployment = files.getValue("alpha.Bench.deployment.binfb")
        val catalog = files.getValue("alpha.catalog.binfb")
        assertEquals("err:TooShort", deploymentVerdict(deployment.copyOf(7)))
        // `the_catalog_verifier_refuses_a_deployment_descriptor` and its converse.
        assertEquals("err:WrongIdentifier", catalogVerdict(deployment))
        assertEquals("err:WrongIdentifier", deploymentVerdict(catalog))
        // `the_foreign_identifier_message_names_rdld`.
        val message = runCatching { verifyDeployment(catalog) }.exceptionOrNull()!!.message!!
        assertTrue("RDLD" in message && "RDLC" in message, message)
    }

    @Test
    fun `every mutant is refused or read exactly as the Rust verify does`() {
        val files = built()
        val bases = listOf(
            "deployment" to files.getValue("alpha.Bench.deployment.binfb"),
            "catalog" to files.getValue("alpha.catalog.binfb"),
            "deployment" to built("attributes").getValue("alpha.Bench.deployment.binfb"),
        )
        // Streamed: the corpus is some 55,000 buffers of up to 7 kB.
        val out = Path.of(System.getProperty("spike.dir", work.toString())).resolve("descriptor-corpus.txt")
        out.parent.createDirectories()
        val kinds = mutableListOf<String>()
        val kotlin = mutableListOf<String>()
        out.bufferedWriter().use { writer ->
            for ((kind, base) in bases) {
                for (bytes in mutants(base)) {
                    writer.write(kind)
                    writer.write(" ")
                    writer.write(bytes.toHex())
                    writer.write("\n")
                    kinds += kind
                    kotlin += if (kind == "deployment") deploymentVerdict(bytes) else catalogVerdict(bytes)
                }
            }
        }
        val committed = javaClass.getResource("/descriptor/descriptor-rust-verdicts.txt")?.readText()
            ?: error("no resources/descriptor/descriptor-rust-verdicts.txt: `just rust-verdicts --write` writes it from $out")
        // A legend, `<letter> <verdict>`, a blank line, then one letter per buffer.
        val (legend, body) = committed.split("\n\n", limit = 2)
        val verdicts = legend.lines().associate { it[0] to it.substring(2) }
        val rust = body.filterNot { it == '\n' }.map { verdicts.getValue(it) }
        assertEquals(rust.size, kotlin.size, "the committed verdicts are for another corpus")
        val differ = kotlin.indices.filter { kotlin[it] != rust[it] }
        assertTrue(differ.isEmpty()) {
            val first = differ.first()
            "${differ.size} of ${kotlin.size} verdicts differ; the first, mutant $first (${kinds[first]}), " +
                "is `${kotlin[first]}` in Kotlin and `${rust[first]}` in Rust; all: " +
                differ.take(10).joinToString { "$it ${kotlin[it]}/${rust[it]}" }
        }
        // The corpus reaches every refusal but two: no change of one byte nests a value past the limit
        // or adds a quarter of the buffer's length in values, so `TooDeep` and `TooManyAttributeValues`
        // are reached by no mutant.
        val reached = kotlin.map { it.substringBefore('(') }.toSet()
        val expected = listOf(
            "ok", "err:TooShort", "err:WrongIdentifier", "err:WrongVersion", "err:Invalid", "err:EmbeddedCatalog",
            "err:CatalogHashMismatch", "err:InstanceNumber", "err:DuplicateInstanceNumber",
            "err:MalformedAttributeValue", "err:SurfaceLink",
        )
        assertEquals(emptyList<String>(), expected.filter { it !in reached }, "refusals the corpus never reaches")
    }

    private companion object {
        /** Every truncation of [bytes], then each byte set to 0x00, 0xFF and one more: CodecTest's scheme. */
        fun mutants(bytes: ByteArray): Sequence<ByteArray> =
            (0 until bytes.size).asSequence().map { bytes.copyOf(it) } + bytes.indices.asSequence().flatMap { i ->
                sequenceOf(0x00, 0xFF, (bytes[i] + 1) and 0xFF).map { value -> bytes.copyOf().also { it[i] = value.toByte() } }
            }

        private val HEX = "0123456789abcdef".toCharArray()

        fun ByteArray.toHex(): String {
            val chars = CharArray(size * 2)
            for ((i, b) in withIndex()) {
                chars[2 * i] = HEX[(b.toInt() shr 4) and 0xF]
                chars[2 * i + 1] = HEX[b.toInt() and 0xF]
            }
            return String(chars)
        }

        /** The verdict line the Rust program prints: `ok`, or `err:` and the variant, with planus's error kind for `Invalid`. */
        fun catalogVerdict(bytes: ByteArray): String = try {
            verifyCatalog(bytes)
            "ok"
        } catch (e: CatalogError) {
            "err:" + catalogVariant(e)
        }

        fun catalogVariant(e: CatalogError): String = when (e) {
            is CatalogError.Invalid -> "Invalid(${e.kind})"
            else -> e::class.simpleName!!
        }

        fun deploymentVerdict(bytes: ByteArray): String = try {
            verifyDeployment(bytes)
            "ok"
        } catch (e: DeploymentError) {
            "err:" + when (e) {
                is DeploymentError.Invalid -> "Invalid(${e.kind})"
                is DeploymentError.EmbeddedCatalog -> "EmbeddedCatalog(${catalogVariant(e.source)})"
                else -> e::class.simpleName!!
            }
        }
    }
}
