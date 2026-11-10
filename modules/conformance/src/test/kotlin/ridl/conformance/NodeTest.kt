package ridl.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import ridl.descriptor.deployment.VerifyError
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.DebugIdentity
import ridl.rt.contract.InstanceNo
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.loopback.AttachError
import ridl.rt.loopback.Attachment
import ridl.rt.loopback.Loopback
import ridl.rt.loopback.Node
import ridl.rt.loopback.NodeError
import ridl.rt.port.Changed
import ridl.rt.port.Interest
import ridl.rt.port.RaiseError
import ridl.rt.port.RawSample
import ridl.rt.port.ServeError
import ridl.rt.port.SignalReader
import ridl.rt.port.Watermark
import ridl.rt.port.WriteError
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * #139: `crates/ridl-loopback/tests/node.rs` of the pinned release, case for
 * case, over the identity fixture's deployment descriptor, which the pinned
 * `ridl` builds byte for byte as the Rust tests read it
 * (`src/test/descriptor/identity`, `DescriptorTest`).
 *
 * `alpha.First/Unit` (1) and `alpha.Second/Unit` (3) run on `Local`. First
 * offers `alpha` and maps `mapped`; Second offers `beta` and maps `alpha`.
 * `alpha.Foreign/Unit` (2) runs on `Other`.
 *
 * Rust's `debug` cases run over a node from `Node.debugFromDescriptor`, and
 * `runtime_debug_does_not_enable_node_debug` is the default node refusing a
 * twin and the listener that the caller has opted in to name. Rust's
 * `missing_embedded_catalog_is_refused` writes a descriptor, which needs a
 * writer: it is `NodeInternalTest`'s in the loopback module, over the value
 * `verify` returns. `fixture_catalog_constants_match_descriptor` checks
 * `ridl-rt-conformance`'s identity constants, which `ridl-rt-kt-conformance`
 * does not have.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodeTest {
    private lateinit var bytes: ByteArray

    @BeforeAll
    fun build() {
        val work = Files.createTempDirectory("node-test")
        val sources = Path.of(System.getProperty("descriptor.dir")).resolve("identity")
        val pkg = work.resolve("src-identity")
        Files.walk(sources).use { paths ->
            paths.forEach { path ->
                val dest = pkg.resolve(path.relativeTo(sources).toString())
                if (Files.isDirectory(path)) dest.createDirectories() else path.copyTo(dest)
            }
        }
        bytes = Harness.build(pkg, work.resolve("out"), "--emit", "deployment").resolve("alpha.Bench.deployment.binfb").readBytes()
    }

    private fun node(): Node = Node.fromDescriptor(bytes, "Local")

    @OptIn(DebugIdentity::class)
    private fun debugNode(): Node = Node.debugFromDescriptor(bytes, "Local")

    private fun id(n: Int): InstanceNo = InstanceNo.new(n)!!

    private fun catalog(node: Node, name: String): CatalogRef = node.catalogs().single { it.name == name }

    private val iface = InterfaceNo(1u)
    private val ord = Ordinal(1u)

    @Test
    fun `node verifies descriptor and machine`() {
        val broken = assertThrows<NodeError.InvalidDescriptor> { Node.fromDescriptor("broken".toByteArray(), "Local") }
        assertEquals(VerifyError.TooShort(6), broken.error)
        assertThrows<NodeError.UnknownMachine> { Node.fromDescriptor(bytes, "absent") }
        val corrupt = bytes.copyOf()
        val at = (0..corrupt.size - 4).first { String(corrupt, it, 4, Charsets.ISO_8859_1) == "RDLC" }
        corrupt[at] = 0
        val error = assertThrows<NodeError.InvalidDescriptor> { Node.fromDescriptor(corrupt, "Local") }
        assertNotNull(error.cause, "the descriptor's refusal is the source")
    }

    @Test
    fun `node catalogs follow local offers and maps`() {
        assertEquals(listOf("alpha", "beta", "mapped"), node().catalogs().map { it.name })
    }

    @Test
    fun `positive grants use name and hash`() {
        val n = node()
        for (name in listOf("alpha", "mapped")) n.attach(id(1), catalog(n, name))
        assertThrows<AttachError.CatalogNotGranted> { n.attach(id(1), catalog(n, "beta")) }
        for (number in listOf(2, 999)) assertThrows<AttachError.UnknownInstance> { n.attach(id(number), catalog(n, "alpha")) }
        assertThrows<AttachError.UnknownCatalog> { n.attach(id(1), CatalogRef("alpha", CatalogHash(ByteArray(32)))) }
        assertThrows<AttachError.UnknownCatalog> { n.attach(id(1), CatalogRef("remote", CatalogHash(ByteArray(32)))) }
    }

    @Test
    fun `attachments survive node drop`() {
        var n: Node? = node()
        val c = catalog(n!!, "alpha")
        val a = n.attach(id(1), c)
        val b = n.attach(id(3), c)
        @Suppress("UNUSED_VALUE")
        n = null
        System.gc()
        a.set(iface, ord, bytes(42))
        a.commit()
        val out = out(8)
        b.read(iface, ord, out)
        assertArrayEquals(array(42), out.written())
    }

    @OptIn(DebugIdentity::class)
    @Test
    fun `runtime debug does not enable node debug`() {
        val n = node()
        val c = catalog(n, "alpha")
        val positive = id(1)
        // Establish the grant, then release it so conflict cannot hide the guard.
        n.attach(positive, c).close()
        assertThrows<AttachError.UnknownInstance> { n.attach(positive.twin()!!, c) }
        assertThrows<AttachError.UnknownInstance> { n.attach(InstanceNo.LISTENER, c) }
    }

    @Test
    fun `attachment wakes and delivers events claims and outcomes`() {
        val n = node()
        val c = catalog(n, "alpha")
        val a = n.attach(id(1), c)
        val b = n.attach(id(3), c)
        val count = AtomicInteger()
        val waker = Waker { count.incrementAndGet() }
        a.subscribe(iface, listOf(Ordinal(2u)))
        a.wakeOn(Interest.Event(iface), waker)
        var before = count.get()
        b.raise(iface, Ordinal(2u), bytes(7, 8), null)
        assertTrue(count.get() > before)
        val event = out(8)
        a.next(event)!!
        assertArrayEquals(array(7, 8), event.written())
        a.serve(iface, listOf(Ordinal(4u)))
        a.wakeOn(Interest.Claim(iface), waker)
        before = count.get()
        val call = b.query(iface, Ordinal(4u), bytes(9), null)
        assertTrue(count.get() > before)
        val args = out(8)
        val claim = a.nextClaim(args)!!
        assertArrayEquals(array(9), args.written())
        b.wakeOn(Interest.Outcome(call), waker)
        before = count.get()
        a.settle(claim.id, Result.success(bytes(10, 11)))
        assertTrue(count.get() > before)
        val reply = out(8)
        assertEquals(2, b.reply(call, reply)!!.getOrThrow())
        assertArrayEquals(array(10, 11), reply.written())
        b.forget(call)
        assertNull(b.reply(call, out(8)))
        // Every settled call must return its slot after forget.
        repeat(Loopback.SLOTS * 2) {
            val next = b.query(iface, Ordinal(4u), bytes(), null)
            a.settle(a.nextClaim(out(8))!!.id, Result.success(bytes()))
            b.forget(next)
        }
    }

    /** The cases of `node.rs` under the `debug` feature, over a debug node. */
    @Nested
    @OptIn(DebugIdentity::class)
    inner class Debug {
        private fun twin(): InstanceNo = id(1).twin()!!

        @Test
        fun `twins receive the same grants`() {
            val n = debugNode()
            for (name in listOf("alpha", "mapped")) {
                val c = catalog(n, name)
                val a = n.attach(twin(), c)
                a.set(iface, ord, bytes(7))
                a.commit()
                val observer = n.attach(InstanceNo.LISTENER, c)
                val out = out(4)
                assertEquals(1, observer.read(iface, ord, out).len)
                assertArrayEquals(array(7), out.written())
            }
            assertThrows<AttachError.CatalogNotGranted> { n.attach(twin(), catalog(n, "beta")) }
            assertThrows<AttachError.UnknownInstance> { n.attach(id(2).twin()!!, catalog(n, "alpha")) }
        }

        @Test
        fun `opposite identities exclude in both orders`() {
            for ((first, second) in listOf(id(1) to twin(), twin() to id(1))) {
                val n = debugNode()
                val a = n.attach(first, catalog(n, "alpha"))
                assertThrows<AttachError.IdentityConflict> { n.attach(second, catalog(n, "mapped")) }
                a.close()
                n.attach(second, catalog(n, "mapped")).close()
                n.attach(first, catalog(n, "alpha"))
            }
            fun signed(number: Int): InstanceNo = if (number < 0) id(-number).twin()!! else id(number)
            for ((first, second) in listOf(1 to -3, -3 to 1, 3 to -1, -1 to 3)) {
                val n = debugNode()
                val a = n.attach(signed(first), catalog(n, "alpha"))
                val b = n.attach(signed(second), catalog(n, "alpha"))
                a.close()
                b.close()
            }
        }

        @Test
        fun `listeners coexist with each other and both identities`() {
            for (who in listOf(id(1), twin())) {
                for (listenerFirst in listOf(false, true)) {
                    val n = debugNode()
                    val c = catalog(n, "alpha")
                    val (first, second) = if (listenerFirst) InstanceNo.LISTENER to who else who to InstanceNo.LISTENER
                    listOf(n.attach(first, c), n.attach(second, c), n.attach(InstanceNo.LISTENER, c)).forEach(Attachment::close)
                }
            }
        }

        @Test
        fun `last attachment releases identity`() {
            val n = debugNode()
            val c = catalog(n, "alpha")
            assertThrows<AttachError> { n.attach(id(1), catalog(n, "beta")) }
            n.attach(twin(), c).close()
            val a = n.attach(id(1), c)
            val b = n.attach(id(1), catalog(n, "mapped"))
            a.close()
            assertThrows<AttachError.IdentityConflict> { n.attach(twin(), c) }
            b.close()
            n.attach(twin(), c)
        }

        @Test
        fun `reservation outlives port cleanup`() {
            val n = debugNode()
            val c = catalog(n, "alpha")
            val provider = n.attach(id(1), c)
            val caller = n.attach(id(3), c)
            provider.serve(iface, listOf(Ordinal(4u)))
            caller.serve(iface, listOf(Ordinal(4u)))
            val call = caller.query(iface, Ordinal(4u), bytes(), null)
            assertNotNull(provider.nextClaim(out(8)))
            val wakes = AtomicInteger()
            val conflicts = AtomicInteger()
            caller.wakeOn(Interest.Claim(iface)) {
                wakes.incrementAndGet()
                try {
                    n.attach(twin(), c)
                } catch (_: AttachError.IdentityConflict) {
                    conflicts.incrementAndGet()
                }
            }
            provider.close()
            // Closing the handler returns its claim and wakes another serving handler.
            assertEquals(1, wakes.get())
            assertEquals(1, conflicts.get())
            n.attach(twin(), c)
            val claim = caller.nextClaim(out(8))!!
            caller.settle(claim.id, Result.success(bytes()))
            caller.forget(call)
        }

        /** A provider that published 42, and a listener on its catalog. */
        private fun listener(): Pair<Attachment, Attachment> {
            val n = debugNode()
            val c = catalog(n, "alpha")
            val provider = n.attach(id(1), c)
            provider.set(iface, ord, bytes(42))
            provider.commit()
            return provider to n.attach(InstanceNo.LISTENER, c)
        }

        private fun unchanged(provider: SignalReader) {
            val out = out(8)
            provider.read(iface, ord, out)
            assertArrayEquals(array(42), out.written())
        }

        @Test
        fun `listener refuses set without side effects`() {
            val (p, l) = listener()
            assertThrows<WriteError.NotOwner> { l.set(iface, ord, bytes(9)) }
            l.commit()
            unchanged(p)
        }

        @Test
        fun `listener refuses invalidate without side effects`() {
            val (p, l) = listener()
            assertThrows<WriteError.NotOwner> { l.invalidate(iface, ord) }
            l.commit()
            unchanged(p)
        }

        @Test
        fun `listener refuses touch without side effects`() {
            val (p, l) = listener()
            val before = p.generation(iface)
            assertThrows<WriteError.NotOwner> { l.touch(iface, ord) }
            l.commit()
            unchanged(p)
            assertEquals(before, p.generation(iface))
        }

        @Test
        fun `listener refuses raise without side effects`() {
            val (p, l) = listener()
            p.subscribe(iface, listOf(ord))
            assertThrows<RaiseError.NotOwner> { l.raise(iface, ord, bytes(9), null) }
            l.commit()
            assertNull(p.next(out(8)))
            unchanged(p)
        }

        @Test
        fun `listener refuses serve without side effects`() {
            val (p, l) = listener()
            assertThrows<ServeError.NotOwner> { l.serve(iface, listOf(ord)) }
            val c = l.command(iface, ord, bytes(9), null)
            assertNull(l.nextClaim(out(8)))
            assertNotNull(p.nextClaim(out(8)))
            l.forget(c)
        }

        @Test
        fun `listener reads and calls but never claims`() {
            val n = debugNode()
            for (name in listOf("alpha", "beta", "mapped")) {
                val c = catalog(n, name)
                val who = if (name == "beta") id(3) else id(1)
                val p = n.attach(who, c)
                val l = n.attach(InstanceNo.LISTENER, c)
                p.set(iface, ord, bytes(42))
                p.commit()
                unchanged(l)
                assertTrue(l.generation(iface) > 0uL)
                val marks = arrayOf(Watermark(iface, 0uL))
                val changes = arrayOf<Changed?>(Changed(iface, ord, 0uL))
                assertEquals(1, l.scan(marks, changes))
                val samples = arrayOf<RawSample?>(l.read(iface, ord, out(8)))
                assertEquals(1, l.readCoherent(iface, listOf(ord), out(8), samples))
                assertThrows<Exception> { l.readFixed(iface, ord, out(8)) }
                l.subscribe(iface, listOf(ord))
                p.raise(iface, ord, bytes(7), null)
                val event = out(8)
                assertEquals(1, l.next(event)!!.len)
                assertArrayEquals(array(7), event.written())
                l.unsubscribe(iface, listOf(ord))
                p.raise(iface, ord, bytes(8), null)
                assertNull(l.next(out(8)))
                for (query in listOf(false, true)) {
                    val call = if (query) l.query(iface, ord, bytes(3), null) else l.command(iface, ord, bytes(3), null)
                    assertNull(l.nextClaim(out(8)))
                    val args = out(8)
                    val claim = p.nextClaim(args)!!
                    assertArrayEquals(array(3), args.written())
                    p.settle(claim.id, Result.success(bytes(5)))
                    if (query) {
                        val reply = out(8)
                        assertEquals(1, l.reply(call, reply)!!.getOrThrow())
                        assertArrayEquals(array(5), reply.written())
                    } else {
                        assertEquals(Unit, l.ack(call)!!.getOrThrow())
                    }
                    l.forget(call)
                }
                p.close()
                l.close()
            }
        }
    }

    private companion object {
        fun array(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

        fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(array(*values))

        fun out(size: Int): ByteBuffer = ByteBuffer.allocate(size)

        fun ByteBuffer.written(): ByteArray = ByteArray(position()).also {
            val view = duplicate()
            view.flip()
            view.get(it)
        }
    }
}
