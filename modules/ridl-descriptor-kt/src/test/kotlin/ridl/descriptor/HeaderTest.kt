package ridl.descriptor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ridl.descriptor.deployment.verify as verifyDeployment
import ridl.descriptor.deployment.VerifyError as DeploymentError

/**
 * The header and the root of both descriptors, over buffers written by hand.
 * The whole-buffer parity with `ridl-descriptor` is the conformance module's
 * `DescriptorTest`, over descriptors the pinned `ridl` builds.
 */
class HeaderTest {
    /**
     * A root table holding only `version` (slot 0): the root offset, [identifier],
     * a vtable of one slot, and the table after it.
     */
    private fun rootOnly(identifier: String, version: Int): ByteArray {
        val bytes = ByteArray(24)
        fun u16(at: Int, v: Int) { bytes[at] = v.toByte(); bytes[at + 1] = (v shr 8).toByte() }
        fun u32(at: Int, v: Int) { u16(at, v and 0xFFFF); u16(at + 2, v ushr 16) }
        u32(0, 16) // the root table is at 16
        identifier.toByteArray().copyInto(bytes, 4)
        u16(8, 6) // vtable size: 4 bytes of header and one slot
        u16(10, 8) // table size
        u16(12, 4) // slot 0, `version`, at 4 in the table
        u32(16, 8) // the table's vtable is 8 bytes before it
        u32(20, version)
        return bytes
    }

    @Test
    fun `a buffer shorter than the header is refused`() {
        assertEquals(VerifyError.TooShort(7), assertThrows(VerifyError::class.java) { verify(ByteArray(7)) })
        assertEquals(DeploymentError.TooShort(0), assertThrows(DeploymentError::class.java) { verifyDeployment(ByteArray(0)) })
    }

    @Test
    fun `each verifier refuses the other's identifier, naming both`() {
        val catalog = assertThrows(VerifyError::class.java) { verify(rootOnly("RDLD", 1)) }
        assertTrue(catalog is VerifyError.WrongIdentifier)
        val deployment = assertThrows(DeploymentError::class.java) { verifyDeployment(rootOnly("RDLC", 1)) }
        assertTrue(deployment is DeploymentError.WrongIdentifier)
        assertTrue("RDLD" in deployment.message!! && "RDLC" in deployment.message!!, deployment.message)
    }

    @Test
    fun `a version other than 1 is refused before any other field is read`() {
        assertEquals(VerifyError.WrongVersion(2), assertThrows(VerifyError::class.java) { verify(rootOnly("RDLC", 2)) })
        assertEquals(DeploymentError.WrongVersion(0), assertThrows(DeploymentError::class.java) { verifyDeployment(rootOnly("RDLD", 0)) })
    }

    @Test
    fun `with the version read, a missing required field is malformed`() {
        assertEquals(VerifyError.Invalid("MissingRequired"), assertThrows(VerifyError::class.java) { verify(rootOnly("RDLC", 1)) })
        assertEquals(
            DeploymentError.Invalid("MissingRequired"),
            assertThrows(DeploymentError::class.java) { verifyDeployment(rootOnly("RDLD", 1)) },
        )
    }

    @Test
    fun `a vtable outside the buffer or of an odd size is malformed`() {
        val outside = rootOnly("RDLC", 1).also { it[16] = 100 } // the vtable 100 bytes before the table
        assertEquals(VerifyError.Invalid("InvalidOffset"), assertThrows(VerifyError::class.java) { verify(outside) })
        val odd = rootOnly("RDLC", 1).also { it[8] = 5 }
        assertEquals(VerifyError.Invalid("InvalidVtableLength"), assertThrows(VerifyError::class.java) { verify(odd) })
    }
}
