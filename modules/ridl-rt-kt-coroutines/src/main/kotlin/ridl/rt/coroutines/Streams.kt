// The streams of a call as `Flow`s (ridl §12): what a generated async client
// and `serveAsync` are built over. The stream ports stay the polling ones; this
// file is their suspending side, as `await` is for a plain call. Generated code
// names a call and its chunk form, and everything that decides what a stream
// does lives here, once.
package ridl.rt.coroutines

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Ordinal
import ridl.rt.contract.StreamNo
import ridl.rt.error.CallError
import ridl.rt.error.ClientError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.payload.EncodeError
import ridl.rt.payload.Payload
import ridl.rt.payload.Rule
import ridl.rt.payload.VerifyError
import ridl.rt.payload.Violation
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.Correlation
import ridl.rt.port.Interest
import ridl.rt.port.Pull
import ridl.rt.port.PushError
import ridl.rt.port.REPLY_STREAM
import ridl.rt.port.ReadError
import ridl.rt.port.SendError
import ridl.rt.port.SettleError
import ridl.rt.port.StreamCaller
import ridl.rt.port.StreamHandler
import ridl.rt.port.Wakeable
import ridl.rt.sample.Duration
import ridl.rt.sample.Timestamp
import ridl.rt.task.Waker
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicReference

/** The one stream parameter the face carries (driftsys/ridl#1224): several stay outside it. */
private val PARAMETER_STREAM = StreamNo(1u)

/** The bytes of a chunk or a text split no wider than this holds any UTF-8 character whole. */
private const val LONGEST_UTF8_CHARACTER = 4

/**
 * How the values of one stream map to its chunks: a stream of named elements,
 * of bytes or of text. A port knows only chunks.
 */
public interface StreamChunks<T> {
    /** The longest chunk of this stream, the size of a buffer that pulls one. */
    public val maxChunk: Int

    /**
     * The chunks of [value], each read from its position to its limit. A value
     * longer than a chunk is split; an empty one has no chunk.
     *
     * @throws CallError when [value] cannot be written.
     */
    public fun encode(value: T): List<ByteBuffer>

    /**
     * The value of one received [chunk].
     *
     * @throws CallError the refusal of a chunk that fails its check.
     */
    public fun decode(chunk: ByteBuffer): T
}

/** A stream of named elements: one element per chunk, verified and decoded by [codec]. */
public fun <T, V> elementChunks(codec: Payload<T, V>): StreamChunks<T> = object : StreamChunks<T> {
    override val maxChunk: Int = codec.maxSize

    override fun encode(value: T): List<ByteBuffer> {
        val out = ByteBuffer.allocate(codec.maxSize)
        try {
            codec.encode(value, out)
        } catch (e: EncodeError.Contract) {
            throw Contract.InvalidValue(e.violation)
        }
        out.flip()
        return listOf(out)
    }

    override fun decode(chunk: ByteBuffer): T = try {
        codec.decode(codec.verify(chunk))
    } catch (e: VerifyError.Contract) {
        throw Contract.InvalidValue(e.violation)
    } catch (_: VerifyError) {
        throw Transport.Corrupt
    }
}

/** A stream of bytes: a value is split into chunks of at most [limit] bytes, the member's limit. */
public fun byteChunks(limit: Int): StreamChunks<ByteArray> = object : StreamChunks<ByteArray> {
    override val maxChunk: Int = limit

    override fun encode(value: ByteArray): List<ByteBuffer> =
        (value.indices step limit).map { ByteBuffer.wrap(value, it, minOf(limit, value.size - it)) }

    override fun decode(chunk: ByteBuffer): ByteArray = ByteArray(chunk.remaining()).also { chunk.get(it) }
}

/**
 * A stream of text: a value is split into chunks of at most [limit] bytes, the
 * member's limit, each of them whole UTF-8 characters, because a receiver
 * checks each chunk (ADR-0032 decision 12).
 */
public fun textChunks(limit: Int): StreamChunks<String> {
    require(limit >= LONGEST_UTF8_CHARACTER) { "a text stream's chunk holds a whole character: its limit is at least 4, not $limit" }
    return object : StreamChunks<String> {
        override val maxChunk: Int = limit

        override fun encode(value: String): List<ByteBuffer> {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val chunks = mutableListOf<ByteBuffer>()
            var from = 0
            while (from < bytes.size) {
                var to = minOf(from + limit, bytes.size)
                // Back off a continuation byte, so the cut falls between two characters.
                while (to < bytes.size && bytes[to].toInt() and 0xC0 == 0x80) to -= 1
                chunks += ByteBuffer.wrap(bytes, from, to - from)
                from = to
            }
            return chunks
        }

        override fun decode(chunk: ByteBuffer): String = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(chunk).toString()
        } catch (_: CharacterCodingException) {
            throw Contract.InvalidValue(Violation("string", Rule.Utf8))
        }
    }
}

/**
 * One call of a member with a stream, for a generated async client. Nothing is
 * sent until a method here runs: [args] is read then, so a failed `require`
 * throws where the call is collected or awaited.
 *
 * A send waits for a free slot under [calls], the client's one lock, as a plain
 * call does; the stream that follows does not hold it. Each method forgets the
 * call when it leaves, on a cancellation included, which interrupts every stream
 * that was not ended. A failed call throws [ClientError].
 *
 * The member's `max` bounds a wait for a slot, for a stream's first chunk and,
 * as a call's deadline does, for an outcome. Nothing else bounds a stream:
 * wrap it in `withTimeout`.
 */
public class StreamCall<P>(
    private val port: P,
    private val calls: Mutex,
    private val iface: InterfaceNo,
    private val ord: Ordinal,
    private val query: Boolean,
    private val max: Duration?,
    private val args: () -> ByteBuffer,
) where P : StreamCaller, P : Clock, P : Wakeable {
    /** One call this object sent: a flow it returned is cold, so each collection is a call of its own, with its own deadline. */
    private class Sent(val correlation: Correlation, deadline: Timestamp?) {
        @Volatile
        var deadline: Timestamp? = deadline
    }

    /** [max] from now, saturating at the largest timestamp. */
    private fun deadline(): Timestamp? = max?.let {
        val now = port.now()
        Timestamp(if (Long.MAX_VALUE - it.micros < now.micros) Long.MAX_VALUE else now.micros + it.micros)
    }

    private fun expired(deadline: Timestamp?): Boolean = deadline?.let { port.now() > it } ?: false

    /** The transport failure of a call past its deadline. */
    private fun lapsed(): ClientError = ClientError.Call(if (query) Transport.Timeout else Transport.Undelivered)

    private suspend fun send(): Sent {
        val bytes = args()
        val deadline = deadline()
        val c = calls.withLock {
            awaitPoll({}) { waker ->
                port.wakeOn(Interest.Slot, waker)
                try {
                    if (query) port.query(iface, ord, bytes, null) else port.command(iface, ord, bytes, null)
                } catch (e: SendError) {
                    if (e != SendError.Busy) throw ClientError.Send(e)
                    if (expired(deadline)) throw ClientError.Send(SendError.Busy)
                    null
                }
            }
        }
        return Sent(c, deadline)
    }

    /**
     * The reply stream, sent when it is collected. It ends when the provider
     * ends it, and throws [ClientError.Call] with the outcome when the stream
     * stopped. A chunk that fails its check refuses the stream with that
     * outcome. A collector that stops early forgets the call, which interrupts
     * the stream. The next chunk is pulled once the collector took the last one.
     */
    public fun <T> reply(chunks: StreamChunks<T>): Flow<T> = flow {
        val sent = send()
        try {
            pullAll(sent, chunks) { emit(it) }
        } finally {
            port.forget(sent.correlation)
        }
    }

    /**
     * Sends a command, pushes [data] to its stream parameter, ends it, and
     * suspends for the delivery acknowledgment. The acknowledgment may come
     * first: the provider's refusal of a chunk reaches the next push.
     */
    public suspend fun <T> upload(chunks: StreamChunks<T>, data: Flow<T>) {
        val sent = send()
        val c = sent.correlation
        try {
            pushAll(c, chunks, data)
            await(port, Interest.Outcome(c)) {
                port.ack(c)?.getOrElse { throw ClientError.Call(it as CallError) } ?: if (expired(sent.deadline)) throw lapsed() else null
            }
        } catch (e: PushError.Failed) {
            // A refusal of the whole call stops the stream, and the outcome names it.
            throw ClientError.Call(port.ack(c)?.exceptionOrNull() as? CallError ?: e.error)
        } finally {
            port.forget(c)
        }
    }

    /**
     * Sends a query, pushes [data] to its stream parameter, ends it, and
     * suspends for the reply, of at most [replySize] bytes, which [decode]
     * reads. The reply's deadline starts at the end of the upload. A query
     * settled before the upload ended stops the upload, whatever its source is
     * waiting for, and answers with its outcome.
     */
    public suspend fun <T, R : Any> uploadQuery(chunks: StreamChunks<T>, data: Flow<T>, replySize: Int, decode: (ByteBuffer) -> R): R {
        val sent = send()
        val c = sent.correlation
        try {
            return coroutineScope {
                val refused = AtomicReference<PushError.Failed?>()
                val upload = launch {
                    try {
                        pushAll(c, chunks, data)
                        sent.deadline = deadline()
                    } catch (e: PushError.Failed) {
                        refused.set(e)
                    }
                }
                var watching = false
                val reply = awaitPoll({}) { waker ->
                    port.wakeOn(Interest.Outcome(c), waker)
                    if (!watching) {
                        watching = true
                        upload.invokeOnCompletion { waker.wake() }
                    }
                    reply(c, replySize, decode)
                        ?: refused.get()?.let { throw ClientError.Call(it.error) }
                        ?: if (upload.isCompleted && expired(sent.deadline)) throw lapsed() else null
                }
                upload.cancel()
                reply
            }
        } finally {
            port.forget(c)
        }
    }

    /** The reply of [c] once it is known; a failed one throws. */
    private fun <R : Any> reply(c: Correlation, size: Int, decode: (ByteBuffer) -> R): R? {
        val buf = ByteBuffer.allocate(size)
        val outcome = try {
            port.reply(c, buf)
        } catch (e: ReadError) {
            throw ClientError.Read(e)
        } ?: return null
        outcome.onFailure { throw ClientError.Call(it as CallError) }
        buf.flip()
        return try {
            decode(buf)
        } catch (e: CallError) {
            throw ClientError.Call(e)
        }
    }

    /**
     * Sends a query and carries both of its streams at once: [data] goes up
     * while the reply comes down, sent when it is collected. A reply that ends
     * first stops what is left of the upload. A refusal of the upload surfaces
     * through the reply stream, which it stops.
     */
    public fun <T, U> duplex(up: StreamChunks<T>, data: Flow<T>, down: StreamChunks<U>): Flow<U> = flow {
        val sent = send()
        val c = sent.correlation
        try {
            coroutineScope {
                val upload = launch {
                    try {
                        pushAll(c, up, data)
                    } catch (_: PushError.Failed) {
                        // The reply stream reports how the call ended.
                    }
                }
                pullAll(sent, down) { emit(it) }
                upload.cancel()
            }
        } finally {
            port.forget(c)
        }
    }

    /** Emits the reply stream of [sent] to its end. An empty chunk carries no value in any form: a producer can push one to look at its stream. */
    private suspend fun <T> pullAll(sent: Sent, chunks: StreamChunks<T>, emit: suspend (T) -> Unit) {
        val c = sent.correlation
        val buf = ByteBuffer.allocate(chunks.maxChunk)
        var started = false
        while (true) {
            buf.clear()
            val pull = await(port, Interest.Stream(c, REPLY_STREAM)) {
                val pull = try {
                    port.pull(c, REPLY_STREAM, buf)
                } catch (_: ReadError.Short) {
                    // A chunk longer than any this stream carries is refused unread.
                    Pull.Failed(refuse(c, Transport.Corrupt))
                } catch (e: ReadError) {
                    throw ClientError.Read(e)
                }
                when {
                    pull != Pull.Pending -> pull
                    !started && expired(sent.deadline) -> throw lapsed()
                    else -> null
                }
            }
            if (pull is Pull.Chunk && pull.len == 0) continue
            started = true
            when (pull) {
                is Pull.Chunk -> {
                    buf.flip()
                    val value = try {
                        chunks.decode(buf)
                    } catch (e: CallError) {
                        throw ClientError.Call(refuse(c, e))
                    }
                    emit(value)
                }
                Pull.End -> return
                is Pull.Failed -> throw ClientError.Call(pull.error)
                Pull.Pending -> error("the wait returns a pull that is not pending")
            }
        }
    }

    /** Refuses the reply stream with [error], and returns it. */
    private fun refuse(c: Correlation, error: CallError): CallError {
        try {
            port.abort(c, REPLY_STREAM, error)
        } catch (_: PushError) {
            // The stream had already stopped: the refusal still stands here.
        }
        return error
    }

    /**
     * Pushes every chunk of [data] and ends the stream. Anything that stops it
     * short, a failed source and a cancellation included, interrupts the stream
     * and goes on. [PushError.Failed] goes on as it is, for the caller to read
     * the call's outcome.
     */
    private suspend fun <T> pushAll(c: Correlation, chunks: StreamChunks<T>, data: Flow<T>) {
        try {
            data.collect { value ->
                for (chunk in chunks.encode(value)) {
                    await(port, Interest.Stream(c, PARAMETER_STREAM)) {
                        val length = chunk.remaining()
                        val taken = port.push(c, PARAMETER_STREAM, chunk)
                        Unit.takeIf { taken == length }
                    }
                }
            }
            port.end(c, PARAMETER_STREAM)
        } catch (e: PushError.Failed) {
            throw e
        } catch (e: PushError.TooLarge) {
            interrupt(c)
            throw ClientError.Send(SendError.TooLarge(e.cap))
        } catch (e: PushError) {
            throw ClientError.Call(Transport.Interrupted).also { it.addSuppressed(e) }
        } catch (e: CallError) {
            abortParameter(c, e)
            throw ClientError.Call(e)
        } catch (e: Throwable) {
            interrupt(c)
            throw e
        }
    }

    private fun interrupt(c: Correlation) = abortParameter(c, Transport.Interrupted)

    private fun abortParameter(c: Correlation, error: CallError) {
        try {
            port.abort(c, PARAMETER_STREAM, error)
        } catch (_: PushError) {
            // The stream had already stopped, and there is nothing left to stop.
        }
    }
}

/**
 * The stream parameter of [claim], for a provider to collect once: its elements
 * as they arrive, ending with the stream. It throws the outcome, a [CallError],
 * when the stream stopped. A chunk that fails its check refuses the stream and
 * throws that refusal.
 */
public fun <H, T> claimStream(handler: H, claim: ClaimId, chunks: StreamChunks<T>): Flow<T> where H : StreamHandler, H : Wakeable = flow {
    val buf = ByteBuffer.allocate(chunks.maxChunk)
    while (true) {
        buf.clear()
        val pull = await(handler, Interest.ClaimStream(claim, PARAMETER_STREAM)) {
            try {
                handler.pullClaim(claim, PARAMETER_STREAM, buf).takeUnless { it == Pull.Pending }
            } catch (_: ReadError.Short) {
                Pull.Failed(refuseClaim(handler, claim, Transport.Corrupt))
            }
        }
        when (pull) {
            is Pull.Chunk -> {
                buf.flip()
                val value = try {
                    chunks.decode(buf)
                } catch (e: CallError) {
                    throw refuseClaim(handler, claim, e)
                }
                emit(value)
            }
            Pull.End -> return@flow
            is Pull.Failed -> throw pull.error
            Pull.Pending -> error("the wait returns a pull that is not pending")
        }
    }
}

/** Stops the stream parameter of [claim] with [error], and returns it. */
private fun <H : StreamHandler> refuseClaim(handler: H, claim: ClaimId, error: CallError): CallError {
    try {
        handler.abortClaim(claim, PARAMETER_STREAM, error)
    } catch (_: PushError) {
        // The stream had already stopped, or the claim is gone.
    }
    return error
}

/**
 * Whether the reply stream of a claim stopped, for a source that has nothing to
 * push: only a push reads that. The port stops a stream without a chunk to show
 * for it, so the driver watches for a wake, and a wake may be spurious. A
 * registration woken at once, on the registering thread, is the sign of a
 * stream that will not change again, and only that sign is worth a push: an
 * empty chunk, which an open stream takes and a stopped one refuses. One object
 * per stream, because a waker of another object would displace and wake it.
 */
private class ReplyWatch<H>(private val handler: H, private val claim: ClaimId, private val changed: Waker) : Waker where H : StreamHandler, H : Wakeable {
    private val interest = Interest.ClaimStream(claim, REPLY_STREAM)

    @Volatile
    private var registering: Thread? = null

    @Volatile
    private var atOnce = false

    override fun wake() {
        if (registering === Thread.currentThread()) atOnce = true else changed.wake()
    }

    /** Registers again, and throws what a push throws when the stream stopped. */
    fun check() {
        atOnce = false
        registering = Thread.currentThread()
        handler.wakeOn(interest, this)
        registering = null
        if (atOnce) {
            handler.pushClaim(claim, REPLY_STREAM, ByteBuffer.allocate(0))
            handler.wakeOn(interest, this)
        }
    }
}

/**
 * Pushes [source] to the reply stream of [claim] and ends it, which settles the
 * claim. A collector's refusal or a forget stops [source], even one suspended
 * between two elements, and the claim settles with it. An element that cannot
 * be written stops the stream with that outcome. A failed [source] stops the
 * stream with `Transport.Interrupted`, the outcome a producer stops a stream
 * with, and ends here: one stream does not end `serveAsync`. A cancellation
 * stops it the same way and goes on, so a claim is always settled.
 */
public suspend fun <H, T> driveReply(handler: H, claim: ClaimId, chunks: StreamChunks<T>, source: Flow<T>) where H : StreamHandler, H : Wakeable {
    try {
        coroutineScope {
            val ready = Channel<ByteBuffer>()
            launch {
                try {
                    source.collect { value -> chunks.encode(value).forEach { ready.send(it) } }
                    ready.close()
                } catch (e: Throwable) {
                    ready.close(e)
                }
            }
            val changed = Channel<Unit>(Channel.CONFLATED)
            val watch = ReplyWatch(handler, claim) { changed.trySend(Unit) }
            while (true) {
                watch.check()
                val next = select<ChannelResult<ByteBuffer>?> {
                    ready.onReceiveCatching { it }
                    changed.onReceive { null }
                } ?: continue
                if (next.isClosed) {
                    next.exceptionOrNull()?.let { throw it }
                    break
                }
                val chunk = next.getOrThrow()
                await(handler, Interest.ClaimStream(claim, REPLY_STREAM)) {
                    val length = chunk.remaining()
                    Unit.takeIf { handler.pushClaim(claim, REPLY_STREAM, chunk) == length }
                }
            }
        }
        handler.endClaim(claim, REPLY_STREAM)
    } catch (_: PushError.Failed) {
        // The caller refused the stream or forgot the call: the end settles the claim.
        stopReply { handler.endClaim(claim, REPLY_STREAM) }
    } catch (_: PushError.TooLarge) {
        stopReply { handler.abortClaim(claim, REPLY_STREAM, Transport.Interrupted) }
    } catch (e: CallError) {
        stopReply { handler.abortClaim(claim, REPLY_STREAM, e) }
    } catch (e: CancellationException) {
        stopReply { handler.abortClaim(claim, REPLY_STREAM, Transport.Interrupted) }
        throw e
    } catch (_: Exception) {
        stopReply { handler.abortClaim(claim, REPLY_STREAM, Transport.Interrupted) }
    }
}

/** Runs [stop], whose stream may already have stopped. */
private inline fun stopReply(stop: () -> Unit) {
    try {
        stop()
    } catch (_: PushError) {
        // The settlement was made all the same, or the claim is gone.
    }
}

/**
 * Runs a provider's [block] over a command's stream parameter, once the command
 * is acknowledged. A stream that failed, as [block] saw through [claimStream],
 * is the provider's to end with, and a failure of [block] ends here: the
 * command was acknowledged, and one stream does not end `serveAsync`. Whatever
 * is left unread is refused, so an unfinished stream parameter does not hold the
 * call's slot.
 */
public suspend fun <H> consumeUpload(handler: H, claim: ClaimId, block: suspend () -> Unit) where H : StreamHandler, H : Wakeable {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The caller reads the failure from the stream, which the refusal below stops.
    } finally {
        refuseClaim(handler, claim, Transport.Interrupted)
    }
}

/**
 * Runs a provider's [block] over a query's stream parameter, and settles the
 * claim with the reply it returns. A [CallError] from [block], a stream's
 * failure included, settles with that error, and another failure with
 * `Transport.Interrupted`: one stream does not end `serveAsync`. A cancellation
 * settles the same way and goes on.
 */
public suspend fun <H> settleUpload(handler: H, claim: ClaimId, block: suspend () -> ByteBuffer) where H : StreamHandler, H : Wakeable {
    val outcome = try {
        Result.success(block())
    } catch (e: CallError) {
        Result.failure(e)
    } catch (e: CancellationException) {
        settle(handler, claim, Result.failure(Transport.Interrupted))
        throw e
    } catch (_: Exception) {
        Result.failure(Transport.Interrupted)
    }
    settle(handler, claim, outcome)
}

/**
 * Settles [claim]. A reply refused as too large leaves the claim settleable, so
 * it settles again with `Transport.Corrupt`, what `ridl-transport-ws` answers,
 * rather than leave the call and its slot held (driftsys/ridl#1260, ADR-0030).
 * Any other refusal, of a reply or of a failure, is left to the handler.
 */
private fun <H : StreamHandler> settle(handler: H, claim: ClaimId, outcome: Result<ByteBuffer>) {
    try {
        handler.settle(claim, outcome)
    } catch (_: SettleError.TooLarge) {
        if (outcome.isSuccess) settle(handler, claim, Result.failure(Transport.Corrupt))
    } catch (_: SettleError) {
        // The claim is gone: its handler closed, and the call went with it.
    }
}
