package ridl.descriptor.deployment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.descriptor.Catalog
import ridl.descriptor.encode

/**
 * `verify` returns a deployment or throws a [VerifyError] whatever byte of a
 * valid descriptor is overwritten. The Rust test is
 * `a_verified_buffer_reads_in_full_whatever_byte_was_damaged` in
 * `crates/ridl-descriptor/tests/deployment_verify.rs`, over the deployment of
 * `valid()` in `deployment_common.rs`.
 */
class TotalityTest {
    private val catalogBytes: ByteArray =
        encode(Catalog(ridl.descriptor.SCHEMA_VERSION, "veh", ByteArray(32) { 7 }, "0.0.0", emptyList(), emptyList(), null))

    private fun endpoint(component: String) = Endpoint(component, "${component}0", "hpc")

    /** A flag, a scalar and a list that nests a list: two levels deep. */
    private fun attributes(): List<Attribute> {
        fun scalar(text: String) = AttributeValue(text, null)
        return listOf(
            Attribute("rust", "flag", null),
            Attribute("rust", "id", scalar("42")),
            Attribute("rust", "nested", AttributeValue(null, listOf(scalar("a"), AttributeValue(null, listOf(scalar("deep")))))),
        )
    }

    /** A deployment with one of every table. */
    private fun valid(): Deployment = Deployment(
        SCHEMA_VERSION, "veh.Vehicle", "Prod", "veh",
        listOf(EmbeddedCatalog("veh", ByteArray(32) { 7 }, catalogBytes)),
        listOf(DeployedInterface("veh", 3, "Cabin", true, true, "cabin")),
        listOf(Machine("hpc", true, listOf("gen3"), attributes())),
        listOf(Component("Climate", true, true, listOf("Climate0"), listOf("safety"), attributes())),
        listOf(
            Instance("Climate", "Climate0", "hpc", true, attributes(), listOf(InterfaceKey("veh", 3)), listOf("veh"), 7, true),
        ),
        listOf(Link("veh", 3, "cabin", endpoint("Ui"), endpoint("Climate"), Crossing.OffBoard, attributes())),
        listOf(Route("veh", 3, 2, "Cabin", "temperature", "cabin", listOf(endpoint("Climate")))),
        listOf(Surface(0, SurfaceDirection.ExternalOffers)),
        attributes(),
        attributes(),
    )

    @Test
    fun `the encoder round-trips a deployment with one of every table`() {
        val read = verify(encode(valid()))
        assertEquals(7, read.instances.single().number)
        assertEquals(Crossing.OffBoard, read.links.single().crossing)
        assertEquals("deep", read.attributes[2].value!!.list!![1].list!!.single().scalar)
    }

    @Test
    fun `a verified buffer reads in full whatever byte was damaged`() {
        val bytes = encode(valid())
        for (value in listOf(0x00, 0x01, 0x7f, 0xff)) {
            for (at in 8 until bytes.size) {
                val damaged = bytes.clone()
                damaged[at] = value.toByte()
                try {
                    verify(damaged)
                } catch (_: VerifyError) {
                    // A refusal is one of the two allowed outcomes.
                }
            }
        }
    }
}
