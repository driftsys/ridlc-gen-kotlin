// `ridl-rt-kt-conformance`, the port contract tests generic over a runtime:
// the spelling of `crates/ridl-rt-conformance/src/lib.rs` (story E11.20, at
// ridl `main` 44e59fa).
//
// Each public test method of the contract classes here is one test of what
// `ridl.rt.port` states a runtime does behind a port. They are generic over a
// [Factory], the one thing a runtime writes to run them. A runtime runs the
// whole suite from its own tests with [suite], and the tests of the
// extensions it implements with [scannableSuite], [coherentSuite] and
// [wakeableSuite], and, when it carries the trace context, [traceSuite]:
//
//     @TestFactory fun ports() = suite(MyFactory)
//     @TestFactory fun scannable() = scannableSuite(MyFactory)
//     @TestFactory fun trace() = traceSuite(MyFactory)
//
// What the suite leaves out is the crate documentation's list, unchanged: what
// the port contract leaves to a runtime (where the clock starts, what `advance`
// does with a negative duration, which error the injected fault reports beyond
// not being `UnknownClaim`); a handler that has served nothing; two event sinks
// on one event channel; `FixedReader`; anything a runtime reports from a
// catalog descriptor; the threading model; a runtime's own API beyond the
// factory; a wake the contract allows but does not require; which serving
// handlers a call wakes; what a forget does to a call no handler has claimed,
// beyond giving its slot back once withdrawn or settled; and the close of a
// handle.
package ridl.rt.conformance

import org.junit.jupiter.api.DynamicTest
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.Correlation
import ridl.rt.port.CoherentSignals
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.ScannableSignals
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.Wakeable
import ridl.rt.sample.Duration
import ridl.rt.sample.Timestamp
import java.nio.ByteBuffer
import kotlin.reflect.KFunction0

/**
 * What the suite needs from a runtime beyond its port interfaces:
 * `ridl_rt_conformance::Factory`.
 *
 * [R] is the runtime the suite drives: one value implementing every port the
 * suite calls. Under ADR-0021 decision 12 that is an aggregate handle, one the
 * runtime offers or one its tests write over the role handles. The two signal
 * extensions are not in this bound, because a runtime may omit them; their
 * suites ask for them.
 *
 * Every runtime a factory builds is independent of every other.
 */
public interface Factory<R>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    /**
     * A new runtime attached to [catalog], with nothing published, raised or
     * sent. Its clock moves only when [advance] moves it: it never reads
     * wall-clock time. Where it starts is the runtime's choice.
     */
    public fun runtime(catalog: CatalogRef): R

    /**
     * A new event source on [runtime], attached to the same catalog, with its
     * own subscriptions and its own queue. An occurrence raised through
     * [runtime] reaches it once it subscribes.
     */
    public fun source(runtime: R): EventSource

    /**
     * A new caller on [runtime], attached to the same catalog, with its own
     * sequence counter. Its calls are presented to the handlers of [runtime].
     */
    public fun caller(runtime: R): Caller

    /**
     * A new handler on [runtime], attached to the same catalog, with its own
     * served set and its own claims. It is presented the calls to the members
     * it serves.
     */
    public fun handler(runtime: R): Handler

    /**
     * Advances the clock of [runtime] by [by], which the suite never passes
     * negative. After it, `Clock.now` reads exactly [by] later than before.
     */
    public fun advance(runtime: R, by: Duration)

    /**
     * Makes the next `Handler.settle` through [runtime] of a claim [runtime]
     * holds fail, with an error other than `SettleError.UnknownClaim`, and
     * record no outcome. A `settle` of a claim [runtime] does not hold still
     * fails with `UnknownClaim`, and does not spend the fault. The fault is
     * injected once: the settlement after the failed one succeeds.
     */
    public fun failNextSettle(runtime: R)

    /**
     * The number of calls a runtime holds at once: the size of its call
     * table. It counts the calls sent through the runtime and through every
     * caller [caller] makes on it, because the table is the runtime's and not
     * a caller's. A call holds its slot from its send until it is reclaimed by
     * `Caller.forget`, at once for a settled call and at the settlement for a
     * call in flight (ADR-0021 decision 15), and a send with every slot held
     * is refused with `SendError.Busy`. At least 1. `Factory::SLOTS`.
     *
     * The tests fill the table by sending this many calls, so a runtime whose
     * bound is large runs them more slowly, and one with no bound cannot run
     * them.
     */
    public val slots: Int
}

/**
 * The tests of one module of the suite, over one factory. Each public test
 * method is one contract case; [tests] lists every one of them, and a test of
 * this module checks that it misses none.
 */
public abstract class Contract<R>(protected val factory: Factory<R>)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    /** Every test method of this contract, in the order the Rust module declares them. */
    public abstract val tests: List<KFunction0<Unit>>

    /** A new runtime attached to [catalog]. */
    protected fun runtime(): R = factory.runtime(catalog)

    /**
     * Takes every slot of [rt]'s call table: sends [Factory.slots] commands
     * through [rt] and has [rt] claim and settle each as it is sent, so every
     * slot holds a settled call nobody has forgotten. Returns the
     * correlations in send order. [rt] must serve `IFACE`/`ORD` already.
     */
    protected fun fill(rt: R): List<Correlation> = List(factory.slots) {
        val c = rt.command(IFACE, ORD, bytes(1), null)
        val claim = checkNotNull(rt.nextClaim(out(8))) { "the call just sent" }
        rt.settle(claim.id, Result.success(bytes()))
        c
    }

    /** One dynamic test per entry of [tests], named after the method it runs. */
    internal fun dynamicTests(): List<DynamicTest> = tests.map { test -> DynamicTest.dynamicTest(test.name) { test() } }

    protected companion object {
        public val IFACE: InterfaceNo = InterfaceNo(1u)
        public val ORD: Ordinal = Ordinal(1u)
        public val OTHER: Ordinal = Ordinal(2u)

        /**
         * The catalog every test attaches to. The hash is all zeros, which
         * is enough here: the runtime carries the catalog without examining
         * it. The generated clients, publisher and `serve` compare the
         * catalogs (ADR-0023 decision 8), but these tests do not run
         * generated code.
         */
        public val catalog: CatalogRef = CatalogRef("face.demo", CatalogHash(ByteArray(32)))

        /** [start] moved forward by [by] microseconds. */
        public fun later(start: Timestamp, by: Long): Timestamp = Timestamp(start.micros + by)

        public fun bytes(vararg values: Int): ByteBuffer = ByteBuffer.wrap(ByteArray(values.size) { values[it].toByte() })

        public fun out(size: Int): ByteBuffer = ByteBuffer.allocate(size)

        /** The bytes a port wrote into this buffer: from 0 to its position. */
        public fun ByteBuffer.written(): ByteArray = ByteArray(position()).also { duplicate().flip().get(it) }

        public fun array(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
    }
}

/**
 * The tests of the ports every runtime presents, over [factory]: the base arm
 * of `ridl_rt_conformance::suite!`.
 */
public fun <R> suite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler =
    listOf(
        AttachedContract(factory),
        ClockContract(factory),
        SignalsContract(factory),
        EventsContract(factory),
        CallsContract(factory),
    ).flatMap { it.dynamicTests() }

/** The tests of the `ScannableSignals` extension: `suite!(F; scannable)`. */
public fun <R> scannableSuite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : ScannableSignals =
    ScannableContract(factory).dynamicTests()

/** The tests of the `CoherentSignals` extension: `suite!(F; coherent)`. */
public fun <R> coherentSuite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : CoherentSignals =
    CoherentContract(factory).dynamicTests()

/**
 * The tests of the `Wakeable` extension: `suite!(F; wakeable)`. They need
 * `Wakeable` on the runtime and on the source, caller and handler [factory]
 * makes; a handle that is not `Wakeable` fails the test that needs it, naming
 * the handle, where Rust refuses to compile.
 */
public fun <R> wakeableSuite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler, R : Wakeable =
    WakeableContract(factory).dynamicTests()

/**
 * The tests of a runtime that carries the trace context: `suite!(F; trace)`.
 * `trace` is not an interface: it asks for nothing beyond the base arm. A
 * runtime that does not carry the context delivers `null` (ADR-0021 decision
 * 21, rule 3) and does not run it.
 */
public fun <R> traceSuite(factory: Factory<R>): List<DynamicTest>
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler =
    TraceContract(factory).dynamicTests()
