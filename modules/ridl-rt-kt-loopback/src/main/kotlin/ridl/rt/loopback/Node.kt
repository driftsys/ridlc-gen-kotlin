// Descriptor-based attachments to one machine's catalog stores: the spelling
// of `ridl-loopback`'s `node` module, `crates/ridl-loopback/src/node.rs` at the
// pinned release.
package ridl.rt.loopback

import ridl.descriptor.deployment.Deployment
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.DebugIdentity
import ridl.rt.contract.InstanceNo
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import ridl.descriptor.deployment.VerifyError as DescriptorError
import ridl.descriptor.deployment.verify as verifyDeployment

// `RidlError`'s constructor is internal to `ridl-rt-kt`, so the two errors of
// this module extend `RuntimeException` as `RidlError` does: no stack trace.

/** A failure to construct a machine's node. `ridl_loopback::NodeError`. */
public sealed class NodeError(message: String, cause: Throwable? = null) : RuntimeException(message, cause, false, false) {
    /** The descriptor or an embedded catalog failed verification; [error] says how. */
    public class InvalidDescriptor(public val error: DescriptorError) :
        NodeError("invalid deployment descriptor: ${error.message}", error)

    /** The requested machine is absent from the descriptor. */
    public data object UnknownMachine : NodeError("unknown machine")

    /** An offer or map names a catalog not embedded in the descriptor. */
    public data object MissingCatalog : NodeError("an offer or map names a missing catalog")
}

/** A refusal to attach an identity to a catalog. `ridl_loopback::AttachError`. */
public sealed class AttachError(message: String) : RuntimeException(message, null, false, false) {
    /** The positive instance is not placed on this machine, or the identity needs a debug node. */
    public data object UnknownInstance : AttachError("unknown local instance")

    /** No local store has this catalog name and hash. */
    public data object UnknownCatalog : AttachError("unknown local catalog")

    /** The instance neither offers nor maps this catalog. */
    public data object CatalogNotGranted : AttachError("catalog not granted to instance")

    /** An attachment of the opposite identity is still alive. */
    public data object IdentityConflict : AttachError("opposite identity is attached")
}

/**
 * The catalog stores and attachment grants of one machine.
 * `ridl_loopback::Node`.
 *
 * A node is built from a deployment descriptor and the name of one of its
 * machines. It holds one [Loopback] store for each catalog that an instance
 * of the machine offers into or maps, and the grant of each instance placed
 * on it. [attach] hands an instance an [Attachment] to one granted catalog
 * and reserves its identity across all of them until the attachment is
 * closed.
 *
 * Rust's `debug` feature of `ridl-loopback` is the choice of factory here:
 * a node from [fromDescriptor] refuses a debug twin and the listener with
 * [AttachError.UnknownInstance], as a Rust node without the feature does,
 * whether or not the caller could name one; a node from [debugFromDescriptor],
 * which needs the [DebugIdentity] opt-in, takes both. Stores and reservations
 * outlive the node while an attachment lives.
 */
public class Node internal constructor(
    private val stores: Map<CatalogKey, Loopback>,
    private val grants: Map<Int, Set<CatalogKey>>,
    private val debug: Boolean,
) {
    /** A catalog's name and hash: a store's key, as Rust's `(&str, CatalogHash)`. */
    internal data class CatalogKey(val name: String, val hash: List<Byte>) : Comparable<CatalogKey> {
        constructor(catalog: CatalogRef) : this(catalog.name, catalog.hash.toByteArray().toList())

        // Rust orders `&str` by its UTF-8 bytes and the hash byte by byte, unsigned.
        override fun compareTo(other: CatalogKey): Int {
            val byName = compareBytes(name.toByteArray().toList(), other.name.toByteArray().toList())
            return if (byName != 0) byName else compareBytes(hash, other.hash)
        }
    }

    internal val reservations = Reservations()

    /** The machine's deduplicated catalogs, ordered by name and hash. */
    public fun catalogs(): List<CatalogRef> = stores.toSortedMap().values.map { it.catalog }

    /**
     * Attaches [who] to [catalog] with the instance's grant, and reserves its
     * identity across the node's catalogs until the attachment is closed. A
     * debug twin shares its instance's grant and excludes it; the listener
     * may attach to any catalog of the node, beside anyone.
     *
     * @throws AttachError when the identity or the catalog is refused.
     */
    @OptIn(DebugIdentity::class)
    public fun attach(who: InstanceNo, catalog: CatalogRef): Attachment {
        val number = who.get()
        val listener = debug && who.isListener()
        val grant = if (listener) {
            null
        } else {
            if (number < 0 && !debug) throw AttachError.UnknownInstance
            // Not the listener, so not `Int.MIN_VALUE`: the absolute value exists.
            grants[abs(number)] ?: throw AttachError.UnknownInstance
        }
        val key = CatalogKey(catalog)
        val store = stores[key] ?: throw AttachError.UnknownCatalog
        if (grant != null && key !in grant) throw AttachError.CatalogNotGranted
        reservations.reserve(number, conflicting = !listener)
        return Attachment(store.attach().split(), Reservation(reservations, number), listener)
    }

    public companion object {
        /**
         * Verifies [bytes] before selecting [machine] and its catalogs: a node
         * that refuses debug identities. `Node::from_descriptor` without the
         * `debug` feature.
         *
         * @throws NodeError when the descriptor, the machine or a catalog is refused.
         */
        public fun fromDescriptor(bytes: ByteArray, machine: String): Node = of(verified(bytes), machine, debug = false)

        /**
         * As [fromDescriptor], for a node that attaches debug twins and the
         * listener: `Node::from_descriptor` with the `debug` feature.
         */
        @DebugIdentity
        public fun debugFromDescriptor(bytes: ByteArray, machine: String): Node = of(verified(bytes), machine, debug = true)

        private fun verified(bytes: ByteArray): Deployment = try {
            verifyDeployment(bytes)
        } catch (e: DescriptorError) {
            throw NodeError.InvalidDescriptor(e)
        }

        /** The node of [machine] in a verified [deployment]. */
        internal fun of(deployment: Deployment, machine: String, debug: Boolean): Node {
            if (deployment.machines.none { it.name == machine }) throw NodeError.UnknownMachine
            val catalogs = deployment.catalogs.map { it.name to CatalogHash(it.hash) }
            val stores = HashMap<CatalogKey, Loopback>()
            val grants = HashMap<Int, Set<CatalogKey>>()
            for (instance in deployment.instances) {
                if (instance.machine != machine) continue
                // Rust's `BTreeSet<&str>`: each name once, in byte order.
                val names = sortedSetOf<String>({ a, b -> compareBytes(a.toByteArray().toList(), b.toByteArray().toList()) })
                names += instance.offers.map { it.catalog }
                names += instance.maps
                val granted = HashSet<CatalogKey>()
                for (name in names) {
                    var found = false
                    for ((embedded, hash) in catalogs) {
                        if (embedded != name) continue
                        found = true
                        val key = CatalogKey(CatalogRef(embedded, hash))
                        granted += key
                        stores.getOrPut(key) { Loopback(CatalogRef(embedded, hash)) }
                    }
                    if (!found) throw NodeError.MissingCatalog
                }
                grants[instance.number] = granted
            }
            return Node(stores, grants, debug)
        }
    }
}

/** Unsigned, byte by byte, then by length: Rust's order of `[u8]`. */
private fun compareBytes(a: List<Byte>, b: List<Byte>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}

/**
 * The identities attached to a node and their counts, shared with every
 * attachment so that it outlives the node. The conflict check and the count
 * are made under one lock, so of two opposite identities attaching at once
 * exactly one wins.
 */
internal class Reservations {
    internal val lock = ReentrantLock()
    private val counts = HashMap<Int, Int>()

    fun reserve(number: Int, conflicting: Boolean) = lock.withLock {
        if (conflicting && counts.containsKey(-number)) throw AttachError.IdentityConflict
        afterConflictCheck.get()?.let {
            afterConflictCheck.remove()
            it()
        }
        counts.merge(number, 1, Int::plus)
    }

    fun release(number: Int) = lock.withLock {
        val count = checkNotNull(counts[number]) { "no live reservation of $number" }
        if (count == 1) counts.remove(number) else counts[number] = count - 1
    }

    internal companion object {
        /** Only tests pause an attach after its conflict check, as Rust's `AFTER_CONFLICT_CHECK`. */
        internal val afterConflictCheck = ThreadLocal<(() -> Unit)?>()
    }
}

/** One live reservation of [number], released once. */
internal class Reservation(private val reservations: Reservations, private val number: Int) {
    private var released = false

    fun release() = synchronized(this) {
        if (!released) {
            released = true
            reservations.release(number)
        }
    }
}
