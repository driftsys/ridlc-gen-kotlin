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
 * go through `verify`; the verdicts, down to planus's error kind and every
 * payload, must be those the Rust `verify` gives, committed, one code per
 * verdict, as `resources/descriptor/descriptor-rust-verdicts.txt` by
 * `just rust-verdicts`.
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
            setOf("alpha.bench.deployment.binfb", "alpha.catalog.binfb", "beta.catalog.binfb", "mapped.catalog.binfb", "remote.catalog.binfb"),
            files.keys,
        )
        val deployment = verifyDeployment(files.getValue("alpha.bench.deployment.binfb"))
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
        val deployment = verifyDeployment(built("attributes").getValue("alpha.bench.deployment.binfb"))
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
        val deployment = files.getValue("alpha.bench.deployment.binfb")
        val catalog = files.getValue("alpha.catalog.binfb")
        assertEquals("err:TooShort(7)", deploymentVerdict(deployment.copyOf(7)))
        // `the_catalog_verifier_refuses_a_deployment_descriptor` and its converse.
        assertEquals("err:WrongIdentifier([82, 68, 76, 68])", catalogVerdict(deployment))
        assertEquals("err:WrongIdentifier([82, 68, 76, 67])", deploymentVerdict(catalog))
        // `the_foreign_identifier_message_names_rdld`.
        val message = runCatching { verifyDeployment(catalog) }.exceptionOrNull()!!.message!!
        assertTrue("RDLD" in message && "RDLC" in message, message)
    }

    @Test
    fun `every mutant is refused or read exactly as the Rust verify does`() {
        val files = built()
        val bases = listOf(
            "deployment" to files.getValue("alpha.bench.deployment.binfb"),
            "catalog" to files.getValue("alpha.catalog.binfb"),
            "deployment" to built("attributes").getValue("alpha.bench.deployment.binfb"),
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
                    kotlin += verdict(kind, bytes)
                }
            }
        }
        val committed = javaClass.getResource("/descriptor/descriptor-rust-verdicts.txt")?.readText()
            ?: error("no resources/descriptor/descriptor-rust-verdicts.txt: `just rust-verdicts --write` writes it from $out")
        // A legend, `<code> <verdict>`, a blank line, then one code per buffer. The codes have one width:
        // one letter, or two when the verdicts outnumber the letters.
        val (legend, body) = committed.split("\n\n", limit = 2)
        val verdicts = legend.lines().associate { it.substringBefore(' ') to it.substringAfter(' ') }
        val width = verdicts.keys.first().length
        val rust = body.filterNot { it == '\n' }.chunked(width).map { verdicts.getValue(it) }
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
        // are reached by no mutant. `AttributeLimitTest` in `ridl-descriptor-kt` reaches both, over
        // buffers written by hand.
        val reached = kotlin.map { it.split('(', ' ').first() }.toSet()
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

        fun verdict(kind: String, bytes: ByteArray): String =
            if (kind == "deployment") deploymentVerdict(bytes) else catalogVerdict(bytes)

        /**
         * The verdict line the Rust program prints: `ok`, or `err:` and the error as Rust's `{:?}` shows it,
         * with planus's error kind alone for `Invalid`.
         */
        fun catalogVerdict(bytes: ByteArray): String = try {
            verifyCatalog(bytes)
            "ok"
        } catch (e: CatalogError) {
            "err:" + catalogError(e)
        }

        /** `{:?}` of a catalog `VerifyError`, which derives `Debug`. */
        fun catalogError(e: CatalogError): String = when (e) {
            is CatalogError.TooShort -> "TooShort(${e.len})"
            is CatalogError.WrongIdentifier -> "WrongIdentifier(${e.identifier.map { it.toInt() and 0xFF }})"
            is CatalogError.WrongVersion -> "WrongVersion(${e.version})"
            is CatalogError.Invalid -> "Invalid(${e.kind})"
        }

        fun deploymentVerdict(bytes: ByteArray): String = try {
            verifyDeployment(bytes)
            "ok"
        } catch (e: DeploymentError) {
            "err:" + deploymentError(e)
        }

        /** `{:?}` of a deployment `VerifyError`, which derives `Debug`, with the source of `EmbeddedCatalog` as [catalogError]. */
        fun deploymentError(e: DeploymentError): String = when (e) {
            is DeploymentError.TooShort -> "TooShort(${e.len})"
            is DeploymentError.WrongIdentifier -> "WrongIdentifier(${e.identifier.map { it.toInt() and 0xFF }})"
            is DeploymentError.WrongVersion -> "WrongVersion(${e.version})"
            is DeploymentError.Invalid -> "Invalid(${e.kind})"
            is DeploymentError.EmbeddedCatalog ->
                "EmbeddedCatalog { catalog: ${debug(e.catalog)}, source: ${catalogError(e.source)} }"
            is DeploymentError.CatalogHashMismatch -> "CatalogHashMismatch { catalog: ${debug(e.catalog)} }"
            DeploymentError.TooDeep -> "TooDeep"
            DeploymentError.TooManyAttributeValues -> "TooManyAttributeValues"
            DeploymentError.MalformedAttributeValue -> "MalformedAttributeValue"
            is DeploymentError.InstanceNumber ->
                "InstanceNumber { component: ${debug(e.component)}, instance: ${debug(e.instance)}, number: ${e.number} }"
            is DeploymentError.DuplicateInstanceNumber ->
                "DuplicateInstanceNumber { number: ${e.number}, first_component: ${debug(e.firstComponent)}, " +
                    "first_instance: ${debug(e.firstInstance)}, second_component: ${debug(e.secondComponent)}, " +
                    "second_instance: ${debug(e.secondInstance)} }"
            is DeploymentError.SurfaceLink -> "SurfaceLink { index: ${e.index}, link: ${e.link}, links: ${e.links} }"
        }

        /**
         * `{:?}` of a name in an error, with the escapes of `char::escape_debug_ext`. Every name the corpus's
         * refusals carry is ASCII; past ASCII this fails rather than guess at Rust's Unicode tables.
         */
        fun debug(name: String): String = buildString {
            append('"')
            for (c in name) {
                when (c) {
                    '\u0000' -> append("\\0")
                    '\t' -> append("\\t")
                    '\r' -> append("\\r")
                    '\n' -> append("\\n")
                    '"', '\\' -> append('\\').append(c)
                    in ' '..'~' -> append(c)
                    in '\u0000'..'\u007F' -> append("\\u{").append(Integer.toHexString(c.code)).append('}')
                    else -> error("the name ${name.map { it.code }} is not ASCII")
                }
            }
            append('"')
        }
    }
}
