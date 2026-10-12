// Identity, and the interaction descriptors generated code writes: the
// spelling of `ridl_rt::contract` (docs/design.md §3).
package ridl.rt.contract

import ridl.rt.encoding.Encoding
import ridl.rt.sample.Duration

/**
 * A member's ordinal: its position in the interface body, counted from 1
 * (ridl §11). An ordinal is never 0. `ridl_rt::contract::Ordinal`.
 */
@JvmInline
public value class Ordinal(public val value: UInt) : Comparable<Ordinal> {
    override fun compareTo(other: Ordinal): Int = value.compareTo(other.value)
}

/**
 * An interface's number in its catalog: the number the lock file froze, or a
 * provisional number. An interface number is never 0.
 * `ridl_rt::contract::InterfaceNo`.
 */
@JvmInline
public value class InterfaceNo(public val value: UInt) : Comparable<InterfaceNo> {
    override fun compareTo(other: InterfaceNo): Int = value.compareTo(other.value)
}

/**
 * The catalog hash: SHA-256 over the catalog's interfaces, their numbers, and
 * the types they reach. `ridl_rt::contract::CatalogHash`.
 *
 * Equality is structural over the 32 bytes, as the Rust `[u8; 32]` is. The
 * bytes are copied in and out, so a hash cannot change after it is built.
 */
public class CatalogHash(bytes: ByteArray) {
    private val bytes: ByteArray = bytes.copyOf()

    init {
        require(this.bytes.size == SIZE) { "a catalog hash is $SIZE bytes, not ${this.bytes.size}" }
    }

    /** A copy of the 32 bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is CatalogHash && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "CatalogHash(${bytes.joinToString("") { "%02x".format(it) }})"

    public companion object {
        /** The size of a SHA-256 hash in bytes. */
        public const val SIZE: Int = 32
    }
}

/**
 * A catalog: the interfaces of one unit, one package manifest and the source
 * packages in its directory tree. `ridl_rt::contract::CatalogRef`.
 *
 * Two catalog refs are equal only when both the names and the hashes are
 * equal, because two units with the same contents can have the same hash.
 */
public data class CatalogRef(
    /** The unit name. */
    public val name: String,
    /** The catalog hash. */
    public val hash: CatalogHash,
)

/** The five interaction kinds of the language, in the language order from 1. `ridl_rt::contract::Kind`. */
public enum class Kind(public val value: Int) {
    /** A `signal`. */
    Signal(1),

    /** An `event`. */
    Event(2),

    /** A `command`. */
    Command(3),

    /** A `query`. */
    Query(4),

    /** A `fixed`. */
    Fixed(5),
    ;

    public companion object {
        /** The kind whose value is [value], or `null` for a value no kind has. */
        public fun fromValue(value: Int): Kind? = entries.firstOrNull { it.value == value }
    }
}

/** An interface's descriptor. `ridl_rt::contract::Interface`. */
public interface Interface {
    /** The catalog the interface belongs to. */
    public val catalog: CatalogRef

    /** The interface number in that catalog. */
    public val number: InterfaceNo

    /** `true` when the number is provisional, not yet frozen in the lock file. */
    public val provisional: Boolean

    /** The interface name. */
    public val name: String

    /**
     * The members, in ordinal order. A reserved ordinal has no row, so the
     * index of a row is not its ordinal minus one.
     */
    public val members: List<Member>

    /**
     * The interface's size table in [encoding]. `ridl_rt::contract::Sizes<E>`
     * for the interface.
     *
     * @throws IllegalArgumentException when the package carries no codec for
     *   [encoding], which Rust refuses at compile time (`Cabin: Sizes<Proto3>`
     *   is not satisfied).
     */
    public fun sizes(encoding: Encoding): Sizes
}

/**
 * The size table of an interface in one [encoding]: what a face and a runtime
 * need to hold one table of calls in flight, or one payload whose type is not
 * known before it is read, in that encoding. `ridl_rt::contract::Sizes<E>`.
 *
 * Generated code builds one per interface per codec the package carries, from
 * each codec's [ridl.rt.payload.Payload.maxSize], the one source of a
 * payload's bound, so a table cannot drift from its codec. Where Rust has the
 * `ClaimBuffer` and `EventBuffer` array types, a Kotlin face allocates a
 * buffer of [maxBufferSize] or [eventSourceBufferSize] bytes.
 */
public class Sizes(
    /** The encoding the table sizes. */
    public val encoding: Encoding,
    /**
     * Row `i` is the bytes one in-flight instance of `members[i]` reserves:
     * the sum of `maxSize` over the member's payloads — one payload for most
     * kinds, the request and then the reply for a `query`, none for a
     * `fixed`, whose row is `0` because a fixed value is never in flight.
     * The rows are parallel to [Interface.members].
     * `Sizes::RESERVATIONS`.
     */
    public val reservations: List<ULong>,
    /**
     * The largest `maxSize` over the argument and reply payloads of the
     * interface's commands and queries; `0` when it has none: a server's claim
     * buffer, which receives a call before its member is known, is at least
     * this large. `Sizes::MAX_BUFFER_SIZE`.
     */
    public val maxBufferSize: Int,
    /**
     * The largest `maxSize` over the interface's event payloads; `0` when it
     * has none: the buffer `EventSource.next` fills before the event is known.
     * `Sizes::EVENT_SOURCE_BUFFER_SIZE`.
     */
    public val eventSourceBufferSize: Int,
)

/** An interaction's descriptor: the part every kind has. `ridl_rt::contract::Interaction`. */
public interface Interaction {
    /** The interface that declares the interaction. */
    public val iface: Interface

    /** The interaction's row in [Interface.members]. */
    public val member: Member

    /**
     * The index of [member] in [Interface.members], which is also the
     * interaction's row in every size table of the interface
     * ([Sizes.reservations]), so a row is reached without a scan.
     * `Interaction::ROW`.
     */
    public val row: Int
}

/** A `signal` (ridl §4). `ridl_rt::contract::Signal`. */
public interface Signal<T> : Interaction {
    /** The channel's init value (ridl §4.4). */
    public fun init(): T
}

/** An `event` (ridl §5). `ridl_rt::contract::Event`. */
public interface Event<T> : Interaction

/** A `fixed` (ridl §8). `ridl_rt::contract::Fixed`. */
public interface Fixed<T> : Interaction

/** A `command` (ridl §6). `ridl_rt::contract::Command`. */
public interface Command<A> : Interaction {
    /**
     * Evaluates the command's `require` clauses: `true` when every clause is
     * true or the command declares none. `false` is reported as
     * [ridl.rt.error.Contract.PreconditionFailed].
     */
    public fun require(args: A): Boolean
}

/** A `query` (ridl §7). `ridl_rt::contract::Query`. */
public interface Query<A, R> : Interaction {
    /**
     * Evaluates the query's `require` clauses: `true` when every clause is
     * true or the query declares none. `false` is reported as
     * [ridl.rt.error.Contract.PreconditionFailed].
     */
    public fun require(args: A): Boolean

    /**
     * Evaluates the query's `ensure` clauses: `true` when every clause is true
     * or the query declares none. `false` is reported as
     * [ridl.rt.error.Contract.ContractBroken].
     */
    public fun ensure(args: A, reply: R): Boolean
}

/** One member of an interface: a row of [Interface.members]. `ridl_rt::contract::Member`. */
public data class Member(
    /** The member's ordinal. */
    public val ordinal: Ordinal,
    /** The member's kind. */
    public val kind: Kind,
    /** The member's name. */
    public val name: String,
    /**
     * The member's timing, as the IR resolved it. `null` when the IR carries
     * no timing: a `fixed`, or a `command` or `query` in a catalog built
     * before commands and queries took a default response bound.
     */
    public val timing: Timing?,
    /**
     * One entry per payload: two for a `query` (the request, then the reply),
     * one for every other kind. A `fixed` has its one entry here although its
     * row of [Sizes.reservations] is `0`.
     */
    public val payloads: List<PayloadInfo>,
) {
    /**
     * The member's `timing.max`: on a `command` or a `query` the response
     * bound, and so the call's deadline (ridl §9.3; frame specification §8).
     * `null` when the member has no timing or its timing has no `max`.
     * `ridl_rt::contract::Member::call_deadline`.
     *
     * A `command` or a `query` in a catalog built by ridl 0.6.0 or later always
     * has a `max`: an untimed member takes the package's default response
     * bound (ridl §9.1). `null` remains for a catalog built before that
     * default existed.
     *
     * The `max` is returned whatever the kind; on a `signal` it is the
     * staleness bound and on an `event` the time to live (ridl §9), neither
     * of which is a call deadline, so call it on a `command` or a `query`.
     */
    public fun callDeadline(): Duration? = timing?.max
}

/**
 * The bytes one in-flight instance of [interaction] reserves in [encoding]:
 * its row ([Interaction.row]) of the interface's size table
 * ([Sizes.reservations]). `ridl_rt::contract::reservation`.
 *
 * It cannot fail on a package that carries a codec for [encoding], because a
 * row exists for every member.
 *
 * @throws IllegalArgumentException when the package carries no codec for
 *   [encoding], which Rust refuses at compile time.
 */
public fun reservation(interaction: Interaction, encoding: Encoding): ULong =
    interaction.iface.sizes(encoding).reservations[interaction.row]

/**
 * The in-flight byte budget of [iface]'s table in [encoding]: the sum of its
 * rows ([Sizes.reservations]), saturating. A table serving several
 * interfaces adds the budget of each. `ridl_rt::contract::table_budget`.
 *
 * @throws IllegalArgumentException when the package carries no codec for
 *   [encoding], which Rust refuses at compile time.
 */
public fun tableBudget(iface: Interface, encoding: Encoding): ULong =
    iface.sizes(encoding).reservations.fold(0uL, ::saturatingAdd)

/** `a + b`, clamped at the largest `ULong`, as Rust's `u64::saturating_add`. */
private fun saturatingAdd(a: ULong, b: ULong): ULong = if (ULong.MAX_VALUE - a < b) ULong.MAX_VALUE else a + b

/** The form of a timing annotation (ridl §9). `ridl_rt::contract::TimingMode`. */
public enum class TimingMode {
    /** `@Xms`: a strict period, on a signal only (ridl §9.2). */
    StrictPeriodic,

    /** `@[min..max]`, where either side may be absent. */
    Range,
}

/**
 * A member's timing (ridl §9). `ridl_rt::contract::Timing`.
 *
 * `max` is the staleness bound of a signal, the time to live of an event, and
 * the response bound of a call. Under [TimingMode.StrictPeriodic], `min` and
 * `max` both hold the period.
 */
public data class Timing(
    /** The form of the annotation. */
    public val mode: TimingMode,
    /** The lower bound. `null` when the IR leaves it unset. */
    public val min: Duration?,
    /** The upper bound. `null` when the IR leaves it unset. */
    public val max: Duration?,
)

/**
 * One payload of a member. `ridl_rt::contract::PayloadInfo`.
 *
 * A payload's size in an encoding is not here: it is the member's row of the
 * interface's size table ([Sizes.reservations]), which derives from the
 * payload type's own codec for that encoding.
 */
public data class PayloadInfo(
    /** The payload type's name. */
    public val typeName: String,
)
