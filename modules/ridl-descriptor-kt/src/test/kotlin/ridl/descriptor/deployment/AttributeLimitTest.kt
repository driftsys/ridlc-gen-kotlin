package ridl.descriptor.deployment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.descriptor.Node
import ridl.descriptor.Str
import ridl.descriptor.Tab
import ridl.descriptor.Vec
import ridl.descriptor.write as writeNode

/**
 * The depth limit and the value budget of attribute values, over deployment
 * descriptors written by hand. The Rust tests are `a_list_nested_at_the_limit_is_accepted`,
 * `a_list_nested_one_level_past_the_limit_is_refused` and
 * `lists_that_share_one_child_many_times_are_refused` in
 * `crates/ridl-descriptor/tests/deployment_verify.rs`.
 */
class AttributeLimitTest {
    private fun write(root: Node, size: Int = 0): ByteArray = writeNode(root, FILE_IDENTIFIER, size)

    private fun leaf(): Node = Tab(listOf(Str("leaf")))

    private fun list(items: List<Node>): Node = Tab(listOf(null, Vec(items)))

    /** A deployment with no catalog and one attribute in `attributes`, whose value is [value]. */
    private fun deployment(value: Node): Node = Tab(
        listOf(SCHEMA_VERSION.toInt(), Str("veh.Vehicle"), Str("Prod"), Str("veh")) +
            List(9) { Vec(emptyList()) } +
            listOf(Vec(listOf(Tab(listOf(Str("rust"), Str("k"), value))))),
    )

    /** A scalar under [levels] - 1 lists of one value each: [levels] levels of values. */
    private fun nested(levels: Int): Node = (1 until levels).fold(leaf()) { value, _ -> list(listOf(value)) }

    private fun depth(value: AttributeValue): Int = 1 + (value.list?.maxOfOrNull(::depth) ?: 0)

    @Test
    fun `a list nested at the limit is accepted`() {
        val read = verify(write(deployment(nested(MAX_ATTRIBUTE_DEPTH))))
        assertEquals(MAX_ATTRIBUTE_DEPTH, depth(read.attributes.single().value!!))
    }

    @Test
    fun `a list nested one level past the limit is refused`() {
        val bytes = write(deployment(nested(MAX_ATTRIBUTE_DEPTH + 1)))
        assertEquals(VerifyError.TooDeep, assertThrows(VerifyError::class.java) { verify(bytes) })
    }

    /** A list of [fan] references to one list of [fan] references to one scalar: `1 + fan + fan * fan` values. */
    private fun shared(fan: Int): Node {
        val leaf = leaf()
        val middle = list(List(fan) { leaf })
        return list(List(fan) { middle })
    }

    @Test
    fun `lists that share one child many times are refused`() {
        val bytes = write(deployment(shared(2_000)))
        assertTrue(bytes.size < 100_000, "the buffer stays small: ${bytes.size}")
        assertEquals(VerifyError.TooManyAttributeValues, assertThrows(VerifyError::class.java) { verify(bytes) })
    }

    @Test
    fun `the budget is one value per four bytes of the buffer, exactly`() {
        val fan = 20
        val values = 1 + fan + fan * fan
        val unpadded = write(deployment(shared(fan)))
        assertTrue(unpadded.size < 4 * values - 4, "the buffer is padded up to the budget: ${unpadded.size}")
        // A budget of exactly `values` reads every value.
        val read = verify(write(deployment(shared(fan)), size = 4 * values))
        assertEquals(fan * fan, read.attributes.single().value!!.list!!.sumOf { it.list!!.size })
        // A budget of one value fewer is refused.
        val short = write(deployment(shared(fan)), size = 4 * values - 4)
        assertEquals(VerifyError.TooManyAttributeValues, assertThrows(VerifyError::class.java) { verify(short) })
    }
}
