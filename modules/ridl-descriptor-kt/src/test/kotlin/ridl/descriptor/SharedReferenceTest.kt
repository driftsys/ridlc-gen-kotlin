package ridl.descriptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.descriptor.deployment.FILE_IDENTIFIER as DEPLOYMENT_IDENTIFIER
import ridl.descriptor.deployment.verify as verifyDeployment

/**
 * #148: a buffer may refer to one string or byte vector from many places,
 * and `verify` copies it once, not once per reference. Without the sharing, a
 * buffer of a few megabytes whose references all name one large string asks
 * for its size times the number of references, and fails with
 * `OutOfMemoryError` where Rust, which copies nothing, accepts it.
 */
class SharedReferenceTest {
    private val fan = 1_000
    private val large = "x".repeat(64 * 1024)

    @Test
    fun `labels that all name one string share one copy`() {
        val label = Str(large)
        val machine = Tab(listOf(Str("Local"), null, Vec(List(fan) { label }), Vec(emptyList())))
        val deployment = Tab(
            listOf(1, Str("veh.Vehicle"), Str("Prod"), Str("veh")) +
                listOf(Vec(emptyList()), Vec(emptyList()), Vec(listOf(machine))) +
                List(7) { Vec(emptyList()) },
        )
        val bytes = write(deployment, DEPLOYMENT_IDENTIFIER)
        assertTrue(bytes.size < large.length + 8 * fan, "the buffer holds the string once: ${bytes.size}")
        val labels = verifyDeployment(bytes).machines.single().labels
        assertEquals(fan, labels.size)
        assertEquals(large, labels.first())
        assertTrue(labels.all { it === labels.first() }, "every label is the one copy of the string")
    }

    @Test
    fun `hashes that all name one byte vector share one copy`() {
        // The writer's offsets all point forward, so the entries are one table,
        // which names the vector, and the catalog's own hash is another vector.
        val earlier = Tab(listOf(Str(large)))
        val catalog = Tab(
            listOf(1, Str("alpha"), Str("own"), Str("ridl"), Vec(emptyList()), Vec(emptyList()), Vec(List(fan) { earlier })),
        )
        val bytes = write(catalog, FILE_IDENTIFIER)
        assertTrue(bytes.size < large.length + 8 * fan, "the buffer holds the bytes once: ${bytes.size}")
        val hashes = verify(bytes).compatible!!.map { it.hash }
        assertEquals(fan, hashes.size)
        // The vector's length leaves out the zero the writer ends a string with.
        assertTrue(large.toByteArray().contentEquals(hashes.first()))
        assertTrue(hashes.all { it === hashes.first() }, "every hash is the one copy of the bytes")
    }
}
