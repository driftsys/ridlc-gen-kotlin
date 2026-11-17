package ridl.rt.loopback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import ridl.descriptor.deployment.Deployment
import ridl.descriptor.deployment.Instance
import ridl.descriptor.deployment.Machine
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.DebugIdentity
import ridl.rt.contract.InstanceNo
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.port.RawSample
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Provenance
import ridl.rt.sample.Timestamp
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The tests of `crates/ridl-loopback/src/node.rs` that reach inside a node,
 * and `missing_embedded_catalog_is_refused` of `tests/node.rs`, whose
 * descriptor no `ridl build` writes: the Rust test writes it, and here the
 * node is built from the value `verify` would return. Everything else of
 * `tests/node.rs` is the conformance module's `NodeTest`, over the identity
 * fixture's descriptor.
 */
class NodeInternalTest {
    private val catalog = CatalogRef("test", CatalogHash(ByteArray(32)))
    private val key = Node.CatalogKey(catalog)

    private fun node(vararg numbers: Int, debug: Boolean = false, store: Loopback = Loopback(catalog)): Node =
        Node(mapOf(key to store), numbers.associateWith { setOf(key) }, debug)

    private fun id(n: Int): InstanceNo = InstanceNo.new(n)!!

    private fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

    @Test
    fun `missing embedded catalog is refused`() {
        val deployment = Deployment(
            version = 1, system = "test.System", name = "Test", `package` = "test", catalogs = emptyList(),
            interfaces = emptyList(), machines = listOf(Machine("Local", false, emptyList(), emptyList())),
            components = emptyList(),
            instances = listOf(
                Instance("test.Component", "test.Component/Unit", "Local", false, emptyList(), emptyList(), listOf("missing"), 1, false),
            ),
            links = emptyList(), routes = emptyList(), surface = emptyList(), systemAttributes = emptyList(), attributes = emptyList(),
        )
        assertThrows<NodeError.MissingCatalog> { Node.of(deployment, "Local", debug = false) }
    }

    @Test
    fun `attachment reads fixed bytes and coherent sample metadata`() {
        val iface = InterfaceNo(1u)
        val store = Loopback(catalog)
        store.provisionFixed(iface, Ordinal(5u), bytes(31, 32, 33))
        store.advance(Duration(17))
        val n = node(1, 3, store = store)
        val publisher = n.attach(id(1), catalog)
        val reader = n.attach(id(3), catalog)
        publisher.set(iface, Ordinal(1u), bytes(41, 42))
        publisher.set(iface, Ordinal(6u), bytes(51))
        publisher.commit()
        val fixed = ByteBuffer.allocate(8)
        assertEquals(3, reader.readFixed(iface, Ordinal(5u), fixed))
        assertArrayEquals(byteArrayOf(31, 32, 33), fixed.array().copyOf(3))
        val out = ByteBuffer.allocate(8)
        val samples = arrayOfNulls<RawSample>(2)
        assertEquals(3, reader.readCoherent(iface, listOf(Ordinal(6u), Ordinal(1u)), out, samples))
        assertArrayEquals(byteArrayOf(51, 41, 42), out.array().copyOf(3))
        val envelope = Envelope(Timestamp(17), 1uL)
        assertEquals(
            listOf(
                RawSample(Provenance.Live, Freshness.Unbounded, envelope, 1),
                RawSample(Provenance.Live, Freshness.Unbounded, envelope, 2),
            ),
            samples.toList(),
        )
    }

    /**
     * `simultaneous_opposites_have_one_winner`: the first attach pauses after
     * its conflict check; the reservation lock must still be held, so the
     * opposite attach waits for the first to count, and is refused.
     */
    @OptIn(DebugIdentity::class)
    @Test
    fun `simultaneous opposites have one winner`() {
        val positive = id(1)
        val twin = positive.twin()!!
        val pool = Executors.newSingleThreadExecutor()
        try {
            for ((first, second) in listOf(positive to twin, twin to positive)) {
                val n = node(1, debug = true)
                val paused = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val firstResult = pool.submit<Result<Attachment>> {
                    Reservations.afterConflictCheck.set {
                        paused.countDown()
                        resume.await()
                    }
                    runCatching { n.attach(first, catalog) }
                }
                assertTrue(paused.await(10, TimeUnit.SECONDS))
                // Probe from this thread while the first attach is paused.
                val locked = n.reservations.lock.isLocked && !n.reservations.lock.isHeldByCurrentThread
                resume.countDown()
                val secondResult = runCatching { n.attach(second, catalog) }
                val results = listOf(firstResult.get(10, TimeUnit.SECONDS), secondResult)
                assertEquals(1, results.count { it.isSuccess })
                assertTrue(results.any { it.exceptionOrNull() is AttachError.IdentityConflict })
                assertTrue(locked, "the conflict check must keep the reservation lock until the count")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
