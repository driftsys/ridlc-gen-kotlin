package ridl.rt.loopback

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ridl.rt.conformance.Factory
import ridl.rt.conformance.coherentSuite
import ridl.rt.conformance.scannableSuite
import ridl.rt.conformance.suite
import ridl.rt.conformance.wakeableSuite
import ridl.rt.contract.CatalogRef
import ridl.rt.port.Caller
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.sample.Duration

/**
 * The port contract suite of `ridl-rt-kt-conformance`, run over this runtime:
 * `crates/ridl-loopback/tests/conformance.rs`. Every test the suite has runs
 * here, including those of the three extensions, which the loopback
 * implements. The tests of what only this runtime can express are in
 * `PortsTest`.
 */
class ConformanceTest {
    /** The loopback as the suite builds it: the aggregate, the additional role handles, and its two test hooks. */
    private object LoopbackFactory : Factory<Loopback> {
        override val slots: Int = Loopback.SLOTS

        override fun runtime(catalog: CatalogRef): Loopback = Loopback(catalog)

        override fun source(runtime: Loopback): EventSource = runtime.source()

        override fun caller(runtime: Loopback): Caller = runtime.caller()

        override fun handler(runtime: Loopback): Handler = runtime.handler()

        override fun advance(runtime: Loopback, by: Duration): Unit = runtime.advance(by)

        override fun failNextSettle(runtime: Loopback): Unit = runtime.failNextSettle()
    }

    @TestFactory
    fun `the ports every runtime presents`(): List<DynamicTest> = suite(LoopbackFactory)

    @TestFactory
    fun `the scannable signals extension`(): List<DynamicTest> = scannableSuite(LoopbackFactory)

    @TestFactory
    fun `the coherent signals extension`(): List<DynamicTest> = coherentSuite(LoopbackFactory)

    @TestFactory
    fun `the wakeable extension`(): List<DynamicTest> = wakeableSuite(LoopbackFactory)
}
