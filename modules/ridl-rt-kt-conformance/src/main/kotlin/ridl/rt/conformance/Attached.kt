// `Attached`: the catalog a port serves. `crates/ridl-rt-conformance/src/attached.rs`.
package ridl.rt.conformance

import org.junit.jupiter.api.Assertions.assertEquals
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Clock
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.Handler
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import kotlin.reflect.KFunction0

/** The tests of `Attached`. */
public class AttachedContract<R>(factory: Factory<R>) : Contract<R>(factory)
    where R : Attached, R : Clock, R : SignalReader, R : SignalWriter, R : EventSource, R : EventSink, R : Caller,
          R : Handler {
    override val tests: List<KFunction0<Unit>> = listOf(::`the catalog is the one the runtime was built with`)

    /**
     * Every port answers with the catalog its runtime was built with, carried
     * unexamined — the runtime and each handle made from it.
     */
    public fun `the catalog is the one the runtime was built with`() {
        val rt = runtime()
        assertEquals(catalog, rt.catalog)
        assertEquals(catalog, factory.source(rt).catalog)
        assertEquals(catalog, factory.caller(rt).catalog)
        assertEquals(catalog, factory.handler(rt).catalog)
    }
}
