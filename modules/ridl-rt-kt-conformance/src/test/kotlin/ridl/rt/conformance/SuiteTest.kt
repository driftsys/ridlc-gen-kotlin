package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.rt.contract.CatalogRef
import ridl.rt.loopback.Loopback
import ridl.rt.port.Caller
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.sample.Duration
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions

/**
 * The suite itself: `the_suite_macro_names_every_test_function_in_its_own_arm`
 * of `crates/ridl-rt-conformance/src/lib.rs`. A test method no `tests` list
 * names is never run by any runtime, and nothing else would report it.
 */
class SuiteTest {
    /** A factory only to construct the contracts; no test here runs one. */
    private object Unused : Factory<Loopback> {
        override val slots: Int = Loopback.SLOTS

        override fun runtime(catalog: CatalogRef): Loopback = Loopback(catalog)

        override fun source(runtime: Loopback): EventSource = runtime.source()

        override fun caller(runtime: Loopback): Caller = runtime.caller()

        override fun handler(runtime: Loopback): Handler = runtime.handler()

        override fun advance(runtime: Loopback, by: Duration): Unit = runtime.advance(by)

        override fun failNextSettle(runtime: Loopback): Unit = runtime.failNextSettle()
    }

    private val contracts = listOf(
        AttachedContract(Unused),
        ClockContract(Unused),
        SignalsContract(Unused),
        EventsContract(Unused),
        CallsContract(Unused),
        ScannableContract(Unused),
        CoherentContract(Unused),
        WakeableContract(Unused),
    )

    @Test
    fun `every public test method is listed once in its contract`() {
        for (contract in contracts) {
            val declared = contract::class.declaredMemberFunctions
                .filter { it.visibility == KVisibility.PUBLIC }
                .map { it.name }
                .sorted()
            val listed = contract.tests.map { it.name }
            assertEquals(listed.distinct(), listed, "${contract::class.simpleName} lists a test twice")
            assertEquals(declared, listed.sorted(), "${contract::class.simpleName} lists every public test method")
        }
    }

    @Test
    fun `the four suites hold the 51 tests of the Rust suite`() {
        assertEquals(40, suite(Unused).size, "the base arm")
        assertEquals(4, scannableSuite(Unused).size, "the scannable arm")
        assertEquals(2, coherentSuite(Unused).size, "the coherent arm")
        assertEquals(8, wakeableSuite(Unused).size, "the wakeable arm")
        assertTrue(contracts.sumOf { it.tests.size } == 54)
    }
}
