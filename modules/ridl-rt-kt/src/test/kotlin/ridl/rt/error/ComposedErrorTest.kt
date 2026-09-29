package ridl.rt.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.ServeError

/**
 * `ClientError` and `ProviderError` (ADR-0021 decision 16): the tests of
 * `crates/ridl-rt/src/error.rs` on ridl `main` (eb41a7a). Rust converts each
 * inner error into its own variant with `?` through a `From` impl; Kotlin has
 * no `?`, so the variant is built directly, and the test pins that each
 * variant carries the inner error it is built from and that a `when` with no
 * `else` names every variant.
 */
class ComposedErrorTest {
    private fun client(e: ClientError): Any = when (e) {
        is ClientError.Send -> e.error
        is ClientError.Call -> e.error
        is ClientError.Read -> e.error
    }

    private fun provider(e: ProviderError): Any = when (e) {
        is ProviderError.Serve -> e.error
        is ProviderError.Claim -> e.error
    }

    @Test
    fun `each inner error lands in its own client variant`() {
        assertEquals(SendError.Busy, client(ClientError.Send(SendError.Busy)))
        assertEquals(Transport.Timeout, client(ClientError.Call(Transport.Timeout)))
        assertEquals(ReadError.Detached, client(ClientError.Read(ReadError.Detached)))
    }

    @Test
    fun `each inner error lands in its own provider variant`() {
        assertEquals(ServeError.NotOwner, provider(ProviderError.Serve(ServeError.NotOwner)))
        assertEquals(ReadError.Detached, provider(ProviderError.Claim(ReadError.Detached)))
    }
}
