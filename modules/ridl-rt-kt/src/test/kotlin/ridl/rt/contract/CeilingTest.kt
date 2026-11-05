package ridl.rt.contract

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `each_kind_has_its_default_ceiling` of `crates/ridl-rt/src/contract.rs`. */
class CeilingTest {
    @Test
    fun `each kind has its default ceiling`() {
        assertEquals(4_096 - 256, SIGNAL_EVENT_CEILING)
        assertEquals(4 * 4_096 - 256, CALL_CEILING)
        assertEquals(16 * 4_096 - 256, FIXED_CEILING)
        assertEquals(3_840, defaultCeiling(Kind.Signal))
        assertEquals(3_840, defaultCeiling(Kind.Event))
        assertEquals(16_128, defaultCeiling(Kind.Command))
        assertEquals(16_128, defaultCeiling(Kind.Query))
        assertEquals(65_280, defaultCeiling(Kind.Fixed))
    }
}
