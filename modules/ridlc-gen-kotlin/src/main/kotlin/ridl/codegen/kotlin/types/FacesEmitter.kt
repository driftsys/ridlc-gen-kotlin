package ridl.codegen.kotlin.types

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.NOTHING
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.joinToCode
import ridl.codegen.kotlin.Options
import ridl.codegen.kotlin.PLUGIN
import ridl.codegen.v1.ModelOuterClass.Clause
import ridl.codegen.v1.ModelOuterClass.ComparisonOp
import ridl.codegen.v1.ModelOuterClass.ContractKind
import ridl.codegen.v1.ModelOuterClass.Interaction
import ridl.codegen.v1.ModelOuterClass.Interface
import ridl.codegen.v1.ModelOuterClass.Model
import ridl.codegen.v1.ModelOuterClass.Param
import ridl.codegen.v1.ModelOuterClass.Payload
import ridl.codegen.v1.ModelOuterClass.ScalarClass
import ridl.codegen.v1.ModelOuterClass.Timing
import ridl.codegen.v1.ModelOuterClass.TimingMode
import ridl.codegen.v1.ModelOuterClass.TypeRef
import ridl.codegen.v1.ModelOuterClass.Visibility

private const val RT = "ridl.rt"

/** The size of a catalog hash, `CatalogHash.SIZE` (ADR-0014 decision 15). */
private const val CATALOG_HASH_SIZE = 32

private val INTERFACE = ClassName("$RT.contract", "Interface")
private val CATALOG_REF = ClassName("$RT.contract", "CatalogRef")
private val CATALOG_HASH = ClassName("$RT.contract", "CatalogHash")
private val INTERFACE_NO = ClassName("$RT.contract", "InterfaceNo")
private val ORDINAL = ClassName("$RT.contract", "Ordinal")
private val MEMBER = ClassName("$RT.contract", "Member")
private val KIND = ClassName("$RT.contract", "Kind")
private val TIMING = ClassName("$RT.contract", "Timing")
private val TIMING_MODE = ClassName("$RT.contract", "TimingMode")
private val PAYLOAD_INFO = ClassName("$RT.contract", "PayloadInfo")
private val ENCODED_SIZES = ClassName("$RT.contract", "EncodedSizes")
private val SIGNAL = ClassName("$RT.contract", "Signal")
private val EVENT = ClassName("$RT.contract", "Event")
private val COMMAND = ClassName("$RT.contract", "Command")
private val QUERY = ClassName("$RT.contract", "Query")
private val FIXED = ClassName("$RT.contract", "Fixed")
private val DURATION = ClassName("$RT.sample", "Duration")
private val SAMPLE = ClassName("$RT.sample", "Sample")
private val OCCURRENCE = ClassName("$RT.sample", "Occurrence")
private val PROVENANCE = ClassName("$RT.sample", "Provenance")
private val CAUSE = ClassName("$RT.sample", "Cause")
private val DETECTION = ClassName("$RT.sample", "Detection")
private val CONTRACT = ClassName("$RT.error", "Contract")
private val TRANSPORT = ClassName("$RT.error", "Transport")
private val VERIFY_ERROR = ClassName("$RT.payload", "VerifyError")
private val PAYLOAD = ClassName("$RT.payload", "Payload")
private val SIGNAL_READER = ClassName("$RT.port", "SignalReader")
private val SIGNAL_WRITER = ClassName("$RT.port", "SignalWriter")
private val EVENT_SOURCE = ClassName("$RT.port", "EventSource")
private val EVENT_SINK = ClassName("$RT.port", "EventSink")
private val CALLER = ClassName("$RT.port", "Caller")
private val HANDLER = ClassName("$RT.port", "Handler")
private val CORRELATION = ClassName("$RT.port", "Correlation")
private val CLAIM_ID = ClassName("$RT.port", "ClaimId")
private val READ_ERROR = ClassName("$RT.port", "ReadError")
private val SEND_ERROR = ClassName("$RT.port", "SendError")
private val SETTLE_ERROR = ClassName("$RT.port", "SettleError")
private val SERVE_ERROR = ClassName("$RT.port", "ServeError")
private val PROVIDER_ERROR = ClassName("$RT.error", "ProviderError")
private val BYTE_BUFFER = ClassName("java.nio", "ByteBuffer")
private val RESULT = ClassName("kotlin", "Result")
private val CLOCK = ClassName("$RT.port", "Clock")
private val WAKEABLE = ClassName("$RT.port", "Wakeable")
private val INTEREST = ClassName("$RT.port", "Interest")
private val CALL_ERROR = ClassName("$RT.error", "CallError")
private val CLIENT_ERROR = ClassName("$RT.error", "ClientError")
private val TIMESTAMP = ClassName("$RT.sample", "Timestamp")
private val WAKER = ClassName("$RT.task", "Waker")
private val BLOCK_ON = MemberName("$RT.task", "blockOn")
private val AWAIT_POLL = MemberName("$RT.coroutines", "awaitPoll")
private val MUTEX = ClassName("kotlinx.coroutines.sync", "Mutex")
private val WITH_LOCK = MemberName("kotlinx.coroutines.sync", "withLock")
private val TIME_DURATION = ClassName("kotlin.time", "Duration")
private val TIME_SOURCE = ClassName("kotlin.time", "TimeSource")
private val VALUE_TIME_MARK = TIME_SOURCE.nestedClass("Monotonic").nestedClass("ValueTimeMark")

/**
 * What [FacesEmitter.emit] produced: the file, or nothing; the interfaces it
 * skipped, as warnings; and the package-level names the faced ones take.
 */
class EmittedFaces(
    val path: String,
    val text: String?,
    val errors: List<String>,
    val warnings: List<String>,
    val claims: List<Claim> = emptyList(),
)

/**
 * `Faces.kt`: the interaction face of every declared interface of the
 * package (docs/design.md §5, ADR-0023), the Rust face of the pinned release
 * spelled in Kotlin. Per interface: the descriptor object and one descriptor
 * per interaction; `<Iface>Publisher<W>`; `<Iface>Provider`; and the clients,
 * bound to exactly the ports their kinds need (RA-19). An interface that waits
 * — an event, a command or a query — gets the blocking `<Iface>Client`, the
 * suspending `<Iface>AsyncClient`, and, with a call, `serve` and `serveAsync`
 * on its descriptor, all over the internal poll face `<Iface>PollClient`, one
 * internal `<Iface><Member>Call` per call and the descriptor's internal
 * `dispatch`, with the Rust settlement table; a signal-only interface gets
 * one plain `<Iface>Client`. An interface the face cannot carry is skipped
 * with a warning naming why, as the Rust pipeline skips it; the names a faced
 * interface takes are its claims, which the generator checks with every other
 * file's.
 */
class FacesEmitter(private val model: Model, private val options: Options) {
    private val pkg = options.kotlinPackage
    private val file = FileSpec.builder(pkg, "Faces").jvmName("Faces")
    private val wires = Wires(model, pkg)
    private val inits = Inits(model, pkg, wires)
    private val warnings = mutableListOf<String>()

    /** Whether any interface faced has an event, a command or a query: the file then needs the wait helpers. */
    private var waits = false

    /** Whether any interface faced has a command or a query: the file then needs the call helpers. */
    private var calls = false

    /** The top-level types of the interfaces faced so far, and what each is generated for. */
    private val claims = mutableListOf<Claim>()

    fun emit(): EmittedFaces {
        val path = pkg.replace('.', '/') + "/Faces.kt"
        // A malformed catalog hash refuses the whole model, as the Rust backend
        // refuses it (driftsys/ridl#378): inside the per-interface walk below it
        // would only skip each interface. The hash is read, never computed.
        val hash = model.catalog.hash.size()
        if (hash != CATALOG_HASH_SIZE) {
            return EmittedFaces(path, null, listOf("$PLUGIN: malformed codegen model: `Catalog.hash` is $hash bytes, not $CATALOG_HASH_SIZE"), emptyList())
        }
        var faced = 0
        for (iface in model.interfacesList) {
            // A service's inline shape has no declared name to spell, as in the Rust face.
            if (!iface.hasDeclared()) continue
            val types = mutableListOf<TypeSpec>()
            val own = mutableListOf<Claim>()
            val extensions = Extensions()
            try {
                Face(iface, types, own, extensions).emit()
            } catch (refusal: Refusal) {
                warnings += "$PLUGIN: interface `${model.name.dotted}.${iface.declared.declared}` has no generated " +
                    "face: ${refusal.message}"
                continue
            }
            types.forEach(file::addType)
            claims += own
            extensions.functions.forEach(file::addFunction)
            extensions.properties.forEach(file::addProperty)
            faced += 1
        }
        if (faced == 0) return EmittedFaces(path, null, emptyList(), warnings, claims)
        addHelpers()
        if (waits) addWaitHelpers()
        if (calls) addCallHelpers()
        file.addFileComment("Generated by %L from `%L`. Do not edit.", PLUGIN, model.name.dotted)
        return EmittedFaces(path, file.build().toString(), emptyList(), warnings, claims)
    }

    /** The file-private helpers every face of the file shares. */
    private fun addHelpers() {
        val t = TypeVariableName("T")
        val v = TypeVariableName("V")
        val codec = PAYLOAD.parameterizedBy(t, v)
        file.addFunction(
            FunSpec.builder("encoded").addModifiers(KModifier.PRIVATE).addTypeVariable(t).addTypeVariable(v)
                .addParameter("codec", codec).addParameter("value", t).returns(BYTE_BUFFER)
                .addStatement("val out = %T.allocate(codec.maxSize)", BYTE_BUFFER)
                .addStatement("codec.encode(value, out)")
                .addStatement("out.flip()")
                .addStatement("return out")
                .build(),
        )
        file.addFunction(
            FunSpec.builder("checked").addModifiers(KModifier.PRIVATE).addTypeVariable(t).addTypeVariable(v)
                .addKdoc("[buf] verified and decoded, or the [%T] the binding detected.", DETECTION)
                .addParameter("codec", codec).addParameter("buf", BYTE_BUFFER).returns(RESULT.parameterizedBy(t))
                .beginControlFlow("return try")
                .addStatement("%T.success(codec.decode(codec.verify(buf)))", RESULT)
                .nextControlFlow("catch (e: %T)", VERIFY_ERROR)
                .addStatement("%T.failure(detection(e))", RESULT)
                .endControlFlow()
                .build(),
        )
        file.addFunction(
            FunSpec.builder("detection").addModifiers(KModifier.PRIVATE)
                .addParameter("error", VERIFY_ERROR).returns(DETECTION)
                .addStatement(
                    "return if (error is %T.Contract) %T.InvalidValue(error.violation) else %T.Corrupt",
                    VERIFY_ERROR, DETECTION, DETECTION,
                )
                .build(),
        )
        file.addFunction(
            FunSpec.builder("callOutcome").addModifiers(KModifier.PRIVATE).addTypeVariable(t).addTypeVariable(v)
                .addKdoc("[buf] verified and decoded, or the call error it settles or is read as.")
                .addParameter("codec", codec).addParameter("buf", BYTE_BUFFER).returns(RESULT.parameterizedBy(t))
                .beginControlFlow("return try")
                .addStatement("%T.success(codec.decode(codec.verify(buf)))", RESULT)
                .nextControlFlow("catch (e: %T)", VERIFY_ERROR)
                .addStatement(
                    "%T.failure(if (e is %T.Contract) %T.InvalidValue(e.violation) else %T.Corrupt)",
                    RESULT, VERIFY_ERROR, CONTRACT, TRANSPORT,
                )
                .endControlFlow()
                .build(),
        )
        file.addFunction(
            FunSpec.builder("settle").addModifiers(KModifier.PRIVATE)
                .addKdoc("Settles [claim]: `true` when the handler accepted the settlement, `false` when it threw.")
                .addParameter("handler", HANDLER).addParameter("claim", CLAIM_ID)
                .addParameter("outcome", RESULT.parameterizedBy(BYTE_BUFFER)).returns(BOOLEAN)
                .beginControlFlow("return try")
                .addStatement("handler.settle(claim, outcome)")
                .addStatement("true")
                .nextControlFlow("catch (_: %T)", SETTLE_ERROR)
                .addStatement("false")
                .endControlFlow()
                .build(),
        )
    }

    private fun addWaitHelpers() {
        file.addFunction(
            FunSpec.builder("deadline").addModifiers(KModifier.PRIVATE)
                .addKdoc("The wall-clock point [timeout] after now, or `null` for no bound.")
                .addParameter("timeout", TIME_DURATION.copy(nullable = true)).returns(VALUE_TIME_MARK.copy(nullable = true))
                .addStatement("return timeout?.let { %T.Monotonic.markNow() + it }", TIME_SOURCE)
                .build(),
        )
    }

    private fun addCallHelpers() {
        val t = TypeVariableName("T")
        val v = TypeVariableName("V")
        file.addFunction(
            FunSpec.builder("request").addModifiers(KModifier.PRIVATE).addTypeVariable(t).addTypeVariable(v)
                .addKdoc("[value] encoded, or `ClientError.Send(SendError.Contract(Contract.PreconditionFailed))` when its `require` failed: nothing is sent.")
                .addParameter("allowed", BOOLEAN).addParameter("codec", PAYLOAD.parameterizedBy(t, v)).addParameter("value", t)
                .returns(BYTE_BUFFER)
                .beginControlFlow("if (!allowed)")
                .addStatement("throw %T.Send(%T.Contract(%T.PreconditionFailed))", CLIENT_ERROR, SEND_ERROR, CONTRACT)
                .endControlFlow()
                .addStatement("return encoded(codec, value)")
                .build(),
        )
        file.addFunction(
            FunSpec.builder("deadlineAfter").addModifiers(KModifier.PRIVATE)
                .addKdoc("[now] plus [max], saturating at the largest timestamp.")
                .addParameter("now", TIMESTAMP).addParameter("max", DURATION).returns(TIMESTAMP)
                .addStatement("return %T(if (%T.MAX_VALUE - max.micros < now.micros) %T.MAX_VALUE else now.micros + max.micros)", TIMESTAMP, LONG, LONG)
                .build(),
        )
        file.addType(interactionCall())
    }

    private fun interactionCall(): TypeSpec {
        val p = TypeVariableName("P", listOf(CALLER, CLOCK, WAKEABLE))
        val t = TypeVariableName("T", ANY)
        val correlationOrNull = CORRELATION.copy(nullable = true)
        return TypeSpec.classBuilder(INTERACTION_CALL).addModifiers(KModifier.INTERNAL, KModifier.ABSTRACT)
            .addKdoc(
                "One command or query in flight: the counterpart of the Rust named future. It is sent when it is " +
                    "created; `SendError.Busy` leaves it unsent, to retry on a later poll. Each [poll] registers " +
                    "its interest, reads the port once and returns: `null` while waiting, the outcome, or a thrown " +
                    "`ClientError`. Past its deadline, the port's `now()` plus the member's `max`, an unsent call " +
                    "throws `Send(Busy)` and a sent one is forgotten and throws `Call(Undelivered)` for a command or " +
                    "`Call(Timeout)` for a query. A call that finished forgets itself, and polling it again throws " +
                    "`IllegalStateException`.",
            )
            .addTypeVariable(p).addTypeVariable(t)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("port", p).addParameter("iface", INTERFACE_NO).addParameter("ord", ORDINAL)
                    .addParameter("query", BOOLEAN).addParameter("args", BYTE_BUFFER)
                    .addParameter("max", DURATION.copy(nullable = true)).build(),
            )
            .addProperty(PropertySpec.builder("port", p, KModifier.PROTECTED).initializer("port").build())
            .addProperty(PropertySpec.builder("query", BOOLEAN, KModifier.PRIVATE).initializer("query").build())
            // No trace context yet: the face passes `null`, as the Rust face
            // passes `None`, until driftsys/ridl#754 has it call the hook.
            .addProperty(
                PropertySpec.builder("send", LambdaTypeName.get(returnType = CORRELATION), KModifier.PRIVATE)
                    .initializer("if (query) { { port.query(iface, ord, args, null) } } else { { port.command(iface, ord, args, null) } }")
                    .build(),
            )
            .addProperty(
                PropertySpec.builder("deadline", TIMESTAMP.copy(nullable = true), KModifier.PRIVATE)
                    .initializer("max?.let { deadlineAfter(port.now(), it) }").build(),
            )
            .addProperty(PropertySpec.builder("correlation", correlationOrNull, KModifier.PRIVATE).mutable().initializer("null").build())
            .addProperty(PropertySpec.builder("done", BOOLEAN, KModifier.PRIVATE).mutable().initializer("false").build())
            .addInitializerBlock(CodeBlock.of("trySend()\n"))
            .addFunction(
                FunSpec.builder("read").addModifiers(KModifier.PROTECTED, KModifier.ABSTRACT)
                    .addKdoc("The outcome of the call sent under [c], or `null` while it is not known; throws `ClientError` for a failed one.")
                    .addParameter("c", CORRELATION).returns(t.copy(nullable = true)).build(),
            )
            .addFunction(FunSpec.builder("sent").returns(BOOLEAN).addStatement("return correlation != null").build())
            .addFunction(
                FunSpec.builder("poll").addParameter("waker", WAKER).returns(t.copy(nullable = true))
                    .addStatement("check(!done) { %S }", "a call that finished is polled again")
                    .beginControlFlow("if (correlation == null)")
                    .addStatement("port.wakeOn(%T.Slot, waker)", INTEREST)
                    .beginControlFlow("if (!trySend())")
                    .beginControlFlow("if (expired())")
                    .addStatement("done = true")
                    .addStatement("throw %T.Send(%T.Busy)", CLIENT_ERROR, SEND_ERROR)
                    .endControlFlow()
                    .addStatement("return null")
                    .endControlFlow()
                    .endControlFlow()
                    .addStatement("val c = correlation!!")
                    .addStatement("port.wakeOn(%T.Outcome(c), waker)", INTEREST)
                    .addStatement("val result = try { read(c) } catch (e: %T) { finish(c); throw e }", CLIENT_ERROR)
                    .beginControlFlow("if (result != null)")
                    .addStatement("finish(c)")
                    .addStatement("return result")
                    .endControlFlow()
                    .beginControlFlow("if (expired())")
                    .addStatement("finish(c)")
                    .addStatement("throw %T.Call(if (query) %T.Timeout else %T.Undelivered)", CLIENT_ERROR, TRANSPORT, TRANSPORT)
                    .endControlFlow()
                    .addStatement("return null")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("cancel")
                    .addKdoc("Forgets a sent call that has no outcome yet, once; a call that finished is left alone.")
                    .beginControlFlow("if (!done)")
                    .addStatement("done = true")
                    .addStatement("correlation?.let { port.forget(it) }")
                    .endControlFlow()
                    .build(),
            )
            .addFunction(
                FunSpec.builder("block").addParameter("timeout", TIME_DURATION.copy(nullable = true)).returns(t)
                    .addKdoc("Waits for the outcome on this thread, at most [timeout]; when it passes, cancels the call and throws what the call's state says.")
                    .addStatement("val result = %M(deadline(timeout)) { poll(it) }", BLOCK_ON)
                    .beginControlFlow("if (result != null)")
                    .addStatement("return result")
                    .endControlFlow()
                    .addStatement("val wasSent = sent()")
                    .addStatement("cancel()")
                    .addStatement(
                        "throw if (wasSent) %T.Call(if (query) %T.Timeout else %T.Undelivered) else %T.Send(%T.Busy)",
                        CLIENT_ERROR, TRANSPORT, TRANSPORT, CLIENT_ERROR, SEND_ERROR,
                    )
                    .build(),
            )
            .addFunction(
                FunSpec.builder("await").addModifiers(KModifier.SUSPEND).returns(t)
                    .addKdoc("Suspends until the outcome; a cancelled coroutine cancels the call.")
                    .addStatement("return %M(::cancel, ::poll)", AWAIT_POLL)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("trySend").addModifiers(KModifier.PRIVATE).returns(BOOLEAN)
                    .beginControlFlow("return try")
                    .addStatement("correlation = send()")
                    .addStatement("true")
                    .nextControlFlow("catch (e: %T)", SEND_ERROR)
                    .beginControlFlow("if (e != %T.Busy)", SEND_ERROR)
                    .addStatement("done = true")
                    .addStatement("throw %T.Send(e)", CLIENT_ERROR)
                    .endControlFlow()
                    .addStatement("false")
                    .endControlFlow()
                    .build(),
            )
            .addFunction(
                FunSpec.builder("expired").addModifiers(KModifier.PRIVATE).returns(BOOLEAN)
                    .addStatement("val at = deadline ?: return false")
                    .addStatement("return port.now() > at")
                    .build(),
            )
            .addFunction(
                FunSpec.builder("finish").addModifiers(KModifier.PRIVATE).addParameter("c", CORRELATION)
                    .addStatement("done = true")
                    .addStatement("port.forget(c)")
                    .build(),
            )
            .build()
    }

    /** The top-level extensions one face adds to the file: its fixed and derived operations. */
    private class Extensions {
        val functions = mutableListOf<FunSpec>()
        val properties = mutableListOf<PropertySpec>()
    }

    /** One interaction the face carries, with the names and types the emitter needs for it. */
    private class Member(
        val ordinal: Int,
        val interaction: Interaction,
        val descriptor: ClassName,
    ) {
        val declared: String get() = interaction.name.declared
        val camel: String get() = interaction.name.camel
        val method: String get() = camel.replaceFirstChar(Char::lowercaseChar)
    }

    /** A named payload: its Kotlin type, its codec object, and its FlatBuffers size bound. */
    private inner class Named(val ref: TypeRef, val maxSize: Int) {
        val type: ClassName
        val codec: ClassName

        init {
            val (owner, declaration) = wires.declarationOf(ref)
            type = ClassName(owner, declaration.name.camel)
            codec = ClassName(owner, declaration.name.camel + "Codec")
        }
    }

    private inner class Face(
        private val iface: Interface,
        private val types: MutableList<TypeSpec>,
        private val claims: MutableList<Claim>,
        private val extensions: Extensions,
    ) {
        private val name = iface.declared.camel
        private val self = ClassName(pkg, name)
        private val internal = iface.visibility == Visibility.VISIBILITY_INTERNAL
        private val members = iface.slotsList.filter { it.hasInteraction() }.map { slot ->
            Member(slot.ordinal, slot.interaction, ClassName(pkg, name + slot.interaction.name.camel))
        }
        private val signals = members.filter { it.interaction.hasSignal() }
        private val events = members.filter { it.interaction.hasEvent() }
        private val commands = members.filter { it.interaction.hasCommand() }
        private val queries = members.filter { it.interaction.hasQuery() }

        private fun named(payload: Payload?, what: String): Named {
            if (payload == null || !payload.hasType()) refuse("$what must be a single named type")
            if (!payload.hasFlatbuffersMaxSize()) refuse("$what `${payload.type.reference}` has no FlatBuffers bound")
            return Named(payload.type, payload.flatbuffersMaxSize)
        }

        private fun signalPayload(m: Member) = named(m.interaction.signal.payload, "signal `${m.declared}`'s payload")
        private fun eventPayload(m: Member) = named(m.interaction.event.payload, "event `${m.declared}`'s payload")

        private fun argument(m: Member): Pair<Param, Named> {
            val (params, request) = if (m.interaction.hasCommand()) {
                m.interaction.command.paramsList to m.interaction.command.request
            } else {
                m.interaction.query.paramsList to m.interaction.query.request
            }
            if (params.size != 1) refuse("interaction `${m.declared}` must declare exactly one parameter")
            val hasRequest = if (m.interaction.hasCommand()) m.interaction.command.hasRequest() else m.interaction.query.hasRequest()
            if (!hasRequest) refuse("interaction `${m.declared}`'s parameter must be a named type")
            return params[0] to named(request, "interaction `${m.declared}`'s parameter")
        }

        private fun reply(m: Member): Named {
            if (!m.interaction.query.hasReplyPayload()) refuse("query `${m.declared}`'s reply must be a single named type")
            return named(m.interaction.query.replyPayload, "query `${m.declared}`'s reply")
        }

        fun emit() {
            val waits = events.isNotEmpty() || commands.isNotEmpty() || queries.isNotEmpty()
            val of = "interface `${iface.declared.declared}`"
            add(descriptor(), "the descriptor of $of")
            members.forEachIndexed { row, m -> add(interaction(m, row), "the descriptor of member `${m.declared}` of $of") }
            val hasCalls = commands.isNotEmpty() || queries.isNotEmpty()
            for (m in commands + queries) add(call(m), "the call of member `${m.declared}` of $of")
            if (waits) {
                add(client(ClassName(pkg, "${name}PollClient"), pollFace = true), "the poll client of $of")
                add(blockingClient(), "the client of $of")
                add(asyncClient(), "the async client of $of")
            } else if (signals.isNotEmpty()) {
                add(client(ClassName(pkg, "${name}Client"), pollFace = false), "the client of $of")
            }
            if (signals.isNotEmpty() || events.isNotEmpty()) add(publisher(), "the publisher of $of")
            if (hasCalls) add(provider(), "the provider of $of")
            // Set last: an interface refused above adds nothing to the file, helpers included.
            if (waits) this@FacesEmitter.waits = true
            if (hasCalls) this@FacesEmitter.calls = true
        }

        /** Adds [type] to the face, and claims its name for [source]. `InteractionCall_` is claimed by no one: no ridl name reaches it. */
        private fun add(type: TypeSpec, source: String) {
            types += type
            claims += Claim(type.name!!, source)
        }

        private fun TypeSpec.Builder.visibility(): TypeSpec.Builder = apply { if (internal) addModifiers(KModifier.INTERNAL) }

        // -- the descriptors ---------------------------------------------------

        private fun descriptor(): TypeSpec {
            // The model's hash, copied byte for byte: `emit` refused any other size.
            val hashCode = CodeBlock.of("%T(byteArrayOf(%L))", CATALOG_HASH, model.catalog.hash.toByteArray().joinToString())
            val callSizes = commands.map { argument(it).second.maxSize } +
                queries.flatMap { listOf(argument(it).second.maxSize, reply(it).maxSize) }
            val eventSizes = events.map { eventPayload(it).maxSize }
            val rows = members.map { row(it) }.joinToCode(",\n")
            val builder = TypeSpec.objectBuilder(self).visibility()
                .addKdoc("The descriptor of interface `%L`.", iface.declared.declared)
                .addSuperinterface(INTERFACE)
                .addProperty(
                    PropertySpec.builder("catalog", CATALOG_REF, KModifier.OVERRIDE)
                        .initializer("%T(%S, %L)", CATALOG_REF, model.catalog.`package`.ifEmpty { model.name.dotted }, hashCode).build(),
                )
                .addProperty(
                    PropertySpec.builder("number", INTERFACE_NO, KModifier.OVERRIDE)
                        .initializer("%T(%Lu)", INTERFACE_NO, iface.number).build(),
                )
                .addProperty(PropertySpec.builder("provisional", BOOLEAN, KModifier.OVERRIDE).initializer("%L", iface.provisional).build())
                .addProperty(PropertySpec.builder("name", STRING, KModifier.OVERRIDE).initializer("%S", iface.declared.declared).build())
                .addProperty(
                    PropertySpec.builder("members", LIST.parameterizedBy(MEMBER), KModifier.OVERRIDE)
                        .initializer("listOf(\n%L,\n)", rows).build(),
                )
                .addFunction(checkCatalog())
                .addProperty(
                    PropertySpec.builder("MAX_BUFFER_SIZE", INT, KModifier.CONST)
                        .addKdoc("The largest argument or reply payload of this interface: a `dispatch` buffer is at least this large. 0 with no call.")
                        .initializer("%L", callSizes.maxOrNull() ?: 0).build(),
                )
                .addProperty(
                    PropertySpec.builder("EVENT_SOURCE_BUFFER_SIZE", INT, KModifier.CONST)
                        .addKdoc("The largest event payload of this interface. 0 with no event.")
                        .initializer("%L", eventSizes.maxOrNull() ?: 0).build(),
                )
            for (m in commands + queries) {
                builder.addType(
                    TypeSpec.classBuilder(correlation(m).simpleName)
                        .addKdoc("Identifies one sent %L `%L` to its caller: returned by its send method, accepted by its own outcome method only.",
                            if (m.interaction.hasCommand()) "command" else "query", m.declared)
                        .addModifiers(KModifier.INTERNAL, KModifier.VALUE).addAnnotation(JvmInline::class)
                        .primaryConstructor(FunSpec.constructorBuilder().addParameter("correlation", CORRELATION).build())
                        .addProperty(PropertySpec.builder("correlation", CORRELATION).initializer("correlation").build())
                        .build(),
                )
            }
            if (events.isNotEmpty()) builder.addType(eventType()).addFunction(takeEvent())
            if (commands.isNotEmpty() || queries.isNotEmpty()) {
                builder.addProperty(
                    PropertySpec.builder("SERVE_BUDGET", INT, KModifier.PRIVATE, KModifier.CONST).initializer("32")
                        .addKdoc(
                            "The most claims one pass of `serve` and `serveAsync` takes (driftsys/ridl#568). A pass that " +
                                "took this many wakes its own waker, so a coroutine serving a claim stream that never " +
                                "ends lets its dispatcher run other coroutines between passes.",
                        ).build(),
                )
                builder.addFunction(dispatch()).addFunction(claimServed()).addFunction(servePass()).addFunction(serve())
                    .addFunction(serveAsync())
            }
            return builder.build()
        }

        /**
         * `checkCatalog`, the descriptor's comparison of a port's catalog with
         * its own (ADR-0023 decision 8): the poll client, the plain client, the
         * publisher, `serve` and `serveAsync` call it once per binding, before
         * they use the port; the blocking and async clients reach it through
         * the poll client they build. The whole `CatalogRef` is compared, name
         * and hash. A mismatch is a defect in how the program was assembled,
         * so it throws `IllegalStateException`, where the Rust face panics.
         */
        private fun checkCatalog(): FunSpec = FunSpec.builder("checkCatalog").addModifiers(KModifier.INTERNAL)
            .addKdoc(
                "Throws `IllegalStateException` unless [found] is the catalog the face of interface `%L` was generated " +
                    "from, [catalog] (ADR-0023 decision 8). Every client, the publisher, `serve` and `serveAsync` call it " +
                    "once per binding, before they use the port.",
                iface.declared.declared,
            )
            .addParameter("found", CATALOG_REF)
            .addStatement(
                "check(found == catalog) { %P }",
                "the face of interface `${iface.declared.declared}` was generated from catalog \$catalog, " +
                    "but the port is attached to catalog \$found",
            )
            .build()

        /** The sentence every binding's KDoc carries about [checkCatalog]: what throws, and how a program avoids it. */
        private fun catalogThrows(port: String, timing: String): String =
            "Throws `IllegalStateException` when [$port] is attached to a catalog other than the one this face was " +
                "generated from, that is, when the package name or the catalog hash differs (ADR-0023 decision 8); " +
                "$timing. A program that must not throw compares `$port.catalog == ${self.simpleName}.catalog` first."

        private fun claimServed(): FunSpec = FunSpec.builder("claimServed").addModifiers(KModifier.PRIVATE)
            .addParameter("handler", HANDLER)
            .addStatement(
                "try { handler.serve(number, listOf(%L)) } catch (e: %T) { throw %T.Serve(e) }",
                (commands + queries).map { ordinal(it) }.joinToCode(", "), SERVE_ERROR, PROVIDER_ERROR,
            )
            .build()

        /** One pass of `serve`: registers for the next claim, then settles the claims waiting, at most [SERVE_BUDGET]. */
        private fun servePass(): FunSpec {
            val h = TypeVariableName("H", listOf(HANDLER, WAKEABLE))
            return FunSpec.builder("servePass").addModifiers(KModifier.PRIVATE).addTypeVariable(h)
                .addParameter("handler", h).addParameter("provider", ClassName(pkg, "${name}Provider"))
                .addParameter("buffer", BYTE_BUFFER).addParameter("until", VALUE_TIME_MARK.copy(nullable = true))
                .addParameter("waker", WAKER)
                .returns(NOTHING.copy(nullable = true))
                .addStatement("handler.wakeOn(%T.Claim(number), waker)", INTEREST)
                .addStatement("dispatch(handler, provider, buffer, until, SERVE_BUDGET) { waker.wake() }")
                .addStatement("return null")
                .build()
        }

        private fun serve(): FunSpec {
            val h = TypeVariableName("H", listOf(HANDLER, WAKEABLE))
            return FunSpec.builder("serve").addTypeVariable(h)
                .addKdoc(
                    "Serves interface `%L`'s calls on this thread: registers its members with [handler], then settles each " +
                        "claim as it arrives. Returns when [timeout] passes; with no timeout it returns only by throwing " +
                        "`ProviderError`: `Serve` when [handler] refuses the members, `Claim` when a claim read fails other than on an oversized claim, every " +
                        "claim settled before it staying settled. An exception [provider] throws is thrown unchanged.\n\n%L",
                    iface.declared.declared,
                    catalogThrows("handler", "the comparison is made once, before the members are registered"),
                )
                .addParameter("handler", h).addParameter("provider", ClassName(pkg, "${name}Provider"))
                .addParameter(ParameterSpec.builder("timeout", TIME_DURATION.copy(nullable = true)).defaultValue("null").build())
                .addStatement("checkCatalog(handler.catalog)")
                .addStatement("claimServed(handler)")
                .addStatement("val buffer = %T.allocate(MAX_BUFFER_SIZE)", BYTE_BUFFER)
                // An explicit null: a `Unit?` lambda returning servePass's `Nothing?` fails JVM verification.
                .addStatement("val until = deadline(timeout)")
                .addStatement("%M<%T>(until) { waker -> servePass(handler, provider, buffer, until, waker); null }", BLOCK_ON, UNIT)
                .build()
        }

        private fun correlation(m: Member): ClassName = self.nestedClass("${m.camel}Correlation")

        private fun row(m: Member): CodeBlock {
            val i = m.interaction
            val (kind, payloads) = when {
                i.hasSignal() -> "Signal" to listOf(signalPayload(m))
                i.hasEvent() -> "Event" to listOf(eventPayload(m))
                i.hasCommand() -> "Command" to listOf(argument(m).second)
                i.hasQuery() -> "Query" to listOf(argument(m).second, reply(m))
                i.hasFixed() -> "Fixed" to listOf(named(i.fixed.named.takeIf { i.fixed.hasNamed() }, "fixed `${m.declared}`'s payload"))
                else -> refuse("`${m.declared}` is not an interaction this plugin reads")
            }
            val timing = if (i.hasFixed() || !i.hasTiming()) CodeBlock.of("null") else timing(i.timing)
            val infos = payloads.map {
                CodeBlock.of("%T(%S, %T(null, %Lu, null))", PAYLOAD_INFO, it.ref.reference, ENCODED_SIZES, it.maxSize)
            }.joinToCode(", ")
            return CodeBlock.of(
                "%T(%T(%Lu), %T.%L, %S, %L, listOf(%L))",
                MEMBER, ORDINAL, m.ordinal, KIND, kind, m.declared, timing, infos,
            )
        }

        private fun timing(timing: Timing): CodeBlock {
            val mode = if (timing.mode == TimingMode.TIMING_MODE_STRICT_PERIODIC) "StrictPeriodic" else "Range"
            fun micros(present: Boolean, text: String): CodeBlock =
                if (!present) CodeBlock.of("null") else CodeBlock.of("%T(%L)", DURATION, Literals.long(micros(text).toString()))
            return CodeBlock.of(
                "%T(%T.%L, %L, %L)", TIMING, TIMING_MODE, mode,
                micros(timing.hasMinUs(), timing.minUs), micros(timing.hasMaxUs(), timing.maxUs),
            )
        }

        private fun micros(text: String): Long = text.toLongOrNull()
            ?: text.toBigDecimalOrNull()?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()
            ?: refuse("the timing bound `$text` is not a number of microseconds")

        private fun interaction(m: Member, row: Int): TypeSpec {
            val i = m.interaction
            val builder = TypeSpec.objectBuilder(m.descriptor).visibility()
                .addProperty(PropertySpec.builder("iface", INTERFACE, KModifier.OVERRIDE).getter(FunSpec.getterBuilder().addStatement("return %T", self).build()).build())
                .addProperty(PropertySpec.builder("member", MEMBER, KModifier.OVERRIDE).getter(FunSpec.getterBuilder().addStatement("return %T.members[%L]", self, row).build()).build())
            when {
                i.hasSignal() -> {
                    val payload = signalPayload(m)
                    builder.addKdoc("Signal `%L` of `%L`.", m.declared, iface.declared.declared)
                        .addSuperinterface(SIGNAL.parameterizedBy(payload.type))
                        .addProperty(codecProperty(payload))
                        .addFunction(FunSpec.builder("init").addModifiers(KModifier.OVERRIDE).returns(payload.type).addStatement("return %L", channelInit(m, payload)).build())
                }
                i.hasEvent() -> {
                    val payload = eventPayload(m)
                    builder.addKdoc("Event `%L` of `%L`.", m.declared, iface.declared.declared)
                        .addSuperinterface(EVENT.parameterizedBy(payload.type)).addProperty(codecProperty(payload))
                }
                i.hasCommand() -> {
                    val (_, arg) = argument(m)
                    builder.addKdoc("Command `%L` of `%L`.", m.declared, iface.declared.declared)
                        .addSuperinterface(COMMAND.parameterizedBy(arg.type)).addProperty(codecProperty(arg))
                        .addFunction(clauseFun("require", ContractKind.CONTRACT_KIND_REQUIRE, m, arg, null))
                }
                i.hasQuery() -> {
                    val (_, arg) = argument(m)
                    val reply = reply(m)
                    builder.addKdoc("Query `%L` of `%L`.", m.declared, iface.declared.declared)
                        .addSuperinterface(QUERY.parameterizedBy(arg.type, reply.type))
                        .addProperty(codecProperty(arg))
                        .addProperty(PropertySpec.builder("replyCodec", reply.codec).initializer("%T", reply.codec).build())
                        .addFunction(clauseFun("require", ContractKind.CONTRACT_KIND_REQUIRE, m, arg, null))
                        .addFunction(clauseFun("ensure", ContractKind.CONTRACT_KIND_ENSURE, m, arg, reply))
                }
                i.hasFixed() -> {
                    val payload = named(i.fixed.named.takeIf { i.fixed.hasNamed() }, "fixed `${m.declared}`'s payload")
                    builder.addKdoc("Fixed `%L` of `%L`.", m.declared, iface.declared.declared)
                        .addSuperinterface(FIXED.parameterizedBy(payload.type)).addProperty(codecProperty(payload))
                }
            }
            return builder.build()
        }

        private fun codecProperty(payload: Named): PropertySpec =
            PropertySpec.builder("codec", payload.codec).addKdoc("The payload's FlatBuffers codec.").initializer("%T", payload.codec).build()

        /**
         * The channel's init (ridl §4.4): the signal's own `= value` when it
         * declares one over a named scalar, else the payload type's typl init.
         */
        private fun channelInit(m: Member, payload: Named): CodeBlock {
            val signal = m.interaction.signal
            val (owner, declaration) = wires.declarationOf(payload.ref)
            if (signal.hasDeclaredInit() && declaration.hasScalar() && signal.init.hasValue()) {
                return inits.named(ClassName(owner, declaration.name.camel), declaration.scalar, signal.init.value, model.isForeign(payload.ref))
            }
            return inits.ofRef(payload.ref)
        }

        // -- the clauses ---------------------------------------------------------

        /**
         * One `require` or `ensure` method: the narrow translator of ADR-0023
         * decision 1, over the lowering's accepted comparisons. The subject is
         * the single parameter or `result`, a same-package named scalar over
         * an integer or a float; any other clause refuses the interface.
         */
        private fun clauseFun(method: String, kind: ContractKind, m: Member, arg: Named, reply: Named?): FunSpec {
            val clauses = (if (m.interaction.hasCommand()) m.interaction.command.clausesList else m.interaction.query.clausesList)
                .filter { it.kind == kind }
            val predicates = clauses.map { predicate(it, arg, reply) }
            val builder = FunSpec.builder(method).addModifiers(KModifier.OVERRIDE)
                .addParameter("args", arg.type)
                .returns(BOOLEAN)
            if (reply != null) builder.addParameter("reply", reply.type)
            if (clauses.isNotEmpty()) {
                builder.addKdoc(
                    "Evaluates the `%L` clauses:\n%L",
                    method, clauses.joinToString("\n") { "`${it.source}`" },
                )
            }
            return builder.addStatement("return %L", if (predicates.isEmpty()) CodeBlock.of("true") else predicates.joinToCode(" && ")).build()
        }

        private fun predicate(clause: Clause, arg: Named, reply: Named?): CodeBlock {
            fun refuseClause(reason: String): Nothing = refuse("cannot translate contract clause `${clause.source}`: $reason")
            if (clause.hasRefused()) refuseClause(clause.refused)
            if (!clause.hasComparison()) refuseClause("no accepted comparison")
            val comparison = clause.comparison
            val (subject, named) = when {
                comparison.hasResult() -> "reply" to (reply ?: refuseClause("`result` outside a query's ensure"))
                comparison.hasParam() && comparison.param == 0 -> "args" to arg
                else -> refuseClause("no accepted comparison")
            }
            if (model.isForeign(named.ref)) refuseClause("the subject is declared in another package")
            val (_, declaration) = wires.declarationOf(named.ref)
            if (!declaration.hasScalar()) refuseClause("the subject is not a named integer or float scalar")
            val literal = when (declaration.scalar.class_) {
                ScalarClass.SCALAR_CLASS_INTEGER -> {
                    if (comparison.literal.any { it == '.' || it == 'e' || it == 'E' }) {
                        refuseClause("the literal does not match the subject's numeric type")
                    }
                    Literals.long(comparison.literal)
                }
                ScalarClass.SCALAR_CLASS_FLOAT -> Literals.double(comparison.literal)
                else -> refuseClause("the subject is not a named integer or float scalar")
            }
            val op = when (comparison.op) {
                ComparisonOp.COMPARISON_OP_LT -> "<"
                ComparisonOp.COMPARISON_OP_LE -> "<="
                ComparisonOp.COMPARISON_OP_GT -> ">"
                ComparisonOp.COMPARISON_OP_GE -> ">="
                ComparisonOp.COMPARISON_OP_EQ -> "=="
                else -> "!="
            }
            return CodeBlock.of("%L.value %L %L", subject, op, literal)
        }

        private fun callClass(m: Member): ClassName = ClassName(pkg, "$name${m.camel}Call")

        private fun call(m: Member): TypeSpec {
            val (_, arg) = argument(m)
            // Not the ridl parameter's name, which could be `port`.
            val value = "value"
            val query = m.interaction.hasQuery()
            val result = if (query) reply(m).type else UNIT
            val p = TypeVariableName("P", listOf(CALLER, CLOCK, WAKEABLE))
            val read = FunSpec.builder("read").addModifiers(KModifier.OVERRIDE).addParameter("c", CORRELATION).returns(result.copy(nullable = true))
            if (query) {
                val reply = reply(m)
                read.addStatement("val buf = %T.allocate(%T.maxSize)", BYTE_BUFFER, reply.codec)
                    .addStatement("val outcome = try { port.reply(c, buf) } catch (e: %T) { throw %T.Read(e) } ?: return null", READ_ERROR, CLIENT_ERROR)
                    .addStatement("outcome.onFailure { throw %T.Call(it as %T) }", CLIENT_ERROR, CALL_ERROR)
                    .addStatement("buf.flip()")
                    .addStatement("return callOutcome(%T, buf).getOrElse { throw %T.Call(it as %T) }", reply.codec, CLIENT_ERROR, CALL_ERROR)
            } else {
                read.addStatement("return port.ack(c)?.getOrElse { throw %T.Call(it as %T) }", CLIENT_ERROR, CALL_ERROR)
            }
            return TypeSpec.classBuilder(callClass(m)).addModifiers(KModifier.INTERNAL)
                .addKdoc("One %L `%L` in flight.", if (query) "query" else "command", m.declared)
                .addTypeVariable(p)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("port", p).addParameter(value, arg.type).build())
                .superclass(ClassName(pkg, INTERACTION_CALL).parameterizedBy(p, result))
                .addSuperclassConstructorParameter("port")
                .addSuperclassConstructorParameter("%L", number())
                .addSuperclassConstructorParameter("%L", ordinal(m))
                .addSuperclassConstructorParameter("%L", query)
                .addSuperclassConstructorParameter("request(%T.require(%L), %T, %L)", m.descriptor, value, arg.codec, value)
                .addSuperclassConstructorParameter("%T.member.callDeadline()", m.descriptor)
                .addFunction(read.build())
                .build()
        }

        // -- the client ------------------------------------------------------------

        private fun number(): CodeBlock = CodeBlock.of("%T.number", self)

        private fun ordinal(m: Member): CodeBlock = CodeBlock.of("%T(%Lu)", ORDINAL, m.ordinal)

        private fun client(className: ClassName, pollFace: Boolean): TypeSpec {
            val bounds = buildList<TypeName> {
                if (signals.isNotEmpty()) add(SIGNAL_READER)
                if (events.isNotEmpty()) add(EVENT_SOURCE)
                if (commands.isNotEmpty() || queries.isNotEmpty()) add(CALLER)
            }
            val p = TypeVariableName("P", bounds)
            val builder = TypeSpec.classBuilder(className)
                .apply { if (pollFace) addModifiers(KModifier.INTERNAL) else visibility() }
                .addKdoc(
                    if (pollFace) {
                        "The poll face of interface `%L`: a send returns a correlation, and an outcome is read without " +
                            "waiting. Internal: the clients and `serve` are built over it.\n\n%L"
                    } else {
                        "The consumer face of interface `%L`, over exactly the ports its interactions need.\n\n%L"
                    },
                    iface.declared.declared,
                    catalogThrows("port", "the constructor makes the comparison once, before the port is used"),
                )
                .addTypeVariable(p)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("port", p).build())
                .addProperty(PropertySpec.builder("port", p, KModifier.INTERNAL).initializer("port").build())
                .addInitializerBlock(CodeBlock.of("%T.checkCatalog(port.catalog)\n", self))
            for (m in signals) {
                val payload = signalPayload(m)
                builder.addFunction(
                    FunSpec.builder(m.method).returns(SAMPLE.parameterizedBy(payload.type))
                        .addKdoc(
                            "Reads signal `%L`, with the provenance, freshness and envelope the runtime resolved. A channel " +
                                "with no value under `Init` or `Invalid(Declared)` holds its init value; bytes that fail their " +
                                "check, none under any other provenance included, read as the init value under " +
                                "`Provenance.Invalid` with what was detected.",
                            m.declared,
                        )
                        .addStatement("val buf = %T.allocate(%T.maxSize)", BYTE_BUFFER, payload.codec)
                        .addStatement("val raw = port.read(%L, %L, buf)", number(), ordinal(m))
                        .beginControlFlow(
                            "if (raw.len == 0 && (raw.provenance == %T.Init || raw.provenance == %T.Invalid(%T.Declared)))",
                            PROVENANCE, PROVENANCE, CAUSE,
                        )
                        .addStatement("return %T(%T.init(), raw.provenance, raw.freshness, raw.envelope)", SAMPLE, m.descriptor)
                        .endControlFlow()
                        .addStatement("buf.flip()")
                        .addStatement("return checked(%T, buf).fold(", payload.codec)
                        .addStatement("  { %T(it, raw.provenance, raw.freshness, raw.envelope) },", SAMPLE)
                        .addStatement(
                            "  { %T(%T.init(), %T.Invalid(%T.Detected(it as %T)), raw.freshness, raw.envelope) },",
                            SAMPLE, m.descriptor, PROVENANCE, CAUSE, DETECTION,
                        )
                        .addStatement(")")
                        .build(),
                )
            }
            if (events.isNotEmpty()) {
                val receiver = Receiver(className, bounds, pollFace || internal)
                subscriptions(receiver)
                extensions.functions += receiver.extension("nextEvent").returns(self.nestedClass("Event").copy(nullable = true))
                    .addKdoc(
                        "Takes the next occurrence of any subscribed event of `%L`, or `null` when none is waiting. An " +
                            "occurrence of another interface, or of an ordinal this one does not declare, throws " +
                            "`ReadError.Contract(Contract.UnknownInteraction)`: the port has already consumed it.",
                        iface.declared.declared,
                    )
                    .addStatement("return %T.takeEvent(port)", self).build()
            }
            for (m in commands + queries) builder.addFunction(send(m))
            for (m in commands) {
                builder.addFunction(
                    FunSpec.builder("${m.method}Ack").addParameter("correlation", correlation(m))
                        .returns(RESULT.parameterizedBy(UNIT).copy(nullable = true))
                        .addKdoc("Command `%L`'s delivery acknowledgment once it is known, or `null` while it is not. It does not wait.", m.declared)
                        .addStatement("return port.ack(correlation.correlation)").build(),
                )
            }
            for (m in queries) {
                val reply = reply(m)
                builder.addFunction(
                    FunSpec.builder("${m.method}Reply").addParameter("correlation", correlation(m))
                        .returns(RESULT.parameterizedBy(reply.type).copy(nullable = true))
                        .addKdoc(
                            "Query `%L`'s reply once it is known, or `null` while it is not. It does not wait. A reply " +
                                "that fails its check is a failure: `Contract.InvalidValue` or `Transport.Corrupt`.",
                            m.declared,
                        )
                        .addStatement("val buf = %T.allocate(%T.maxSize)", BYTE_BUFFER, reply.codec)
                        .addStatement("val outcome = port.reply(correlation.correlation, buf) ?: return null")
                        .addStatement("buf.flip()")
                        .addStatement("return outcome.fold({ callOutcome(%T, buf) }, { %T.failure(it) })", reply.codec, RESULT)
                        .build(),
                )
            }
            return builder.build()
        }

        private fun serveAsync(): FunSpec {
            val h = TypeVariableName("H", listOf(HANDLER, WAKEABLE))
            return FunSpec.builder("serveAsync").addModifiers(KModifier.SUSPEND).addTypeVariable(h)
                .addKdoc(
                    "Serves interface `%L`'s calls, suspending between claims. It never returns normally: it ends by " +
                        "throwing `ProviderError`, as [serve] does, or by the cancellation of its coroutine.\n\n%L",
                    iface.declared.declared,
                    catalogThrows("handler", "the comparison is made once, before the members are registered"),
                )
                .addParameter("handler", h).addParameter("provider", ClassName(pkg, "${name}Provider"))
                .returns(NOTHING)
                .addStatement("checkCatalog(handler.catalog)")
                .addStatement("claimServed(handler)")
                .addStatement("val buffer = %T.allocate(MAX_BUFFER_SIZE)", BYTE_BUFFER)
                .addStatement("return %M<%T>({}) { waker -> servePass(handler, provider, buffer, null, waker) }", AWAIT_POLL, NOTHING)
                .build()
        }

        /** The ports a waiting client needs (RA-19): a signal reads, an event waits, a call sends and waits on the clock. */
        private fun waitingBounds(): List<TypeName> = buildList {
            if (signals.isNotEmpty()) add(SIGNAL_READER)
            if (events.isNotEmpty()) add(EVENT_SOURCE)
            if (commands.isNotEmpty() || queries.isNotEmpty()) {
                add(CALLER)
                add(CLOCK)
            }
            add(WAKEABLE)
        }

        /** The signal reads, delegated to the poll face, which both clients share. */
        private fun TypeSpec.Builder.delegatedReads(): TypeSpec.Builder = apply {
            for (m in signals) {
                addFunction(
                    FunSpec.builder(m.method).returns(SAMPLE.parameterizedBy(signalPayload(m).type))
                        .addKdoc("Reads signal `%L`, as the runtime resolved it. It does not wait.", m.declared)
                        .addStatement("return poll.%N()", m.method).build(),
                )
            }
        }

        /**
         * The class a fixed or derived operation extends (#9, driftsys/ridl#580):
         * a client or the publisher, with the bounds of its type parameter.
         * The class holds the member methods alone, and a member wins over an
         * extension of the same name, so a ridl member named like a fixed or
         * derived operation keeps the plain call; the operation stays
         * reachable through an aliased import.
         */
        private inner class Receiver(val type: ClassName, val bounds: List<TypeName>, val internalOnly: Boolean) {
            private val variable = TypeVariableName(if (type.simpleName.endsWith("Publisher")) "W" else "P", bounds)

            /** The member methods that take no argument: the signal reads of a client, none on a publisher. */
            private val zeroArgMembers: Set<String> =
                if (type.simpleName.endsWith("Publisher")) emptySet() else signals.map { it.method }.toSet()

            fun extension(name: String): FunSpec.Builder = FunSpec.builder(name).addTypeVariable(variable)
                .receiver(type.parameterizedBy(variable))
                .apply { if (internalOnly) addModifiers(KModifier.INTERNAL) }
                .apply {
                    // Kotlin 2.2 does not warn on a generic receiver, as every one here is; the annotation keeps a
                    // compiler that does from failing a build with warnings as errors.
                    if (name in zeroArgMembers) {
                        addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "EXTENSION_SHADOWED_BY_MEMBER").build())
                    }
                }

            fun extensionProperty(name: String, propertyType: TypeName): PropertySpec.Builder =
                PropertySpec.builder(name, propertyType).mutable().addTypeVariable(variable)
                    .receiver(type.parameterizedBy(variable))
                    .apply { if (internalOnly) addModifiers(KModifier.INTERNAL) }
        }

        /** `subscribe<Event>` and `unsubscribe<Event>` of every event, as extensions of [receiver]'s client. */
        private fun subscriptions(receiver: Receiver) {
            for (m in events) {
                extensions.functions += receiver.extension("subscribe${m.camel}").addKdoc("Starts delivery of event `%L`.", m.declared)
                    .addStatement("port.subscribe(%L, listOf(%L))", number(), ordinal(m)).build()
                extensions.functions += receiver.extension("unsubscribe${m.camel}").addKdoc("Stops delivery of event `%L`.", m.declared)
                    .addStatement("port.unsubscribe(%L, listOf(%L))", number(), ordinal(m)).build()
            }
        }

        private fun blockingClient(): TypeSpec {
            val p = TypeVariableName("P", waitingBounds())
            val poll = ClassName(pkg, "${name}PollClient")
            val builder = TypeSpec.classBuilder(ClassName(pkg, "${name}Client")).visibility()
                .addKdoc(
                    "The blocking client of interface `%L`: each call waits on this thread for its outcome, at most " +
                        "its `timeout` when it is set, and throws `ClientError` for a failed call. Use it from one thread at " +
                        "a time. `timeout`, `nextEvent` and the subscriptions are extensions (#9).\n\n%L",
                    iface.declared.declared,
                    catalogThrows("port", "the comparison is made once, by the poll client the constructor builds"),
                )
                .addTypeVariable(p)
                .primaryConstructor(
                    FunSpec.constructorBuilder().addParameter("port", p)
                        .addParameter(ParameterSpec.builder("timeout", TIME_DURATION.copy(nullable = true)).defaultValue("null").build())
                        .build(),
                )
                .addProperty(PropertySpec.builder("port", p, KModifier.INTERNAL).initializer("port").build())
                .addProperty(
                    PropertySpec.builder("timeoutBound", TIME_DURATION.copy(nullable = true), KModifier.INTERNAL).mutable()
                        .initializer("timeout").addKdoc("What the `timeout` extension reads and writes.").build(),
                )
                .addProperty(PropertySpec.builder("poll", poll.parameterizedBy(p), KModifier.PRIVATE).initializer("%T(port)", poll).build())
                .delegatedReads()
            val receiver = Receiver(ClassName(pkg, "${name}Client"), waitingBounds(), internal)
            extensions.properties += receiver.extensionProperty("timeout", TIME_DURATION.copy(nullable = true))
                .addKdoc("The longest a call or `nextEvent` of this client waits, or `null` for no bound.")
                .getter(FunSpec.getterBuilder().addStatement("return timeoutBound").build())
                .setter(FunSpec.setterBuilder().addParameter("value", TIME_DURATION.copy(nullable = true)).addStatement("timeoutBound = value").build())
                .build()
            if (events.isNotEmpty()) {
                subscriptions(receiver)
                extensions.functions += receiver.extension("nextEvent").returns(self.nestedClass("Event").copy(nullable = true))
                    .addKdoc("Waits for the next occurrence of any subscribed event of `%L`, or returns `null` at the client's `timeout`.", iface.declared.declared)
                    .addStatement("return %M(deadline(timeoutBound)) { waker ->", BLOCK_ON)
                    .addStatement("  port.wakeOn(%T.Event(%L), waker)", INTEREST, number())
                    .addStatement("  try { %T.takeEvent(port) } catch (e: %T) { throw %T.Read(e) }", self, READ_ERROR, CLIENT_ERROR)
                    .addStatement("}")
                    .build()
            }
            for (m in commands + queries) {
                val (param, arg) = argument(m)
                val value = param.name.property
                val f = FunSpec.builder(m.method).addParameter(value, arg.type)
                    .addKdoc("Calls %L `%L` and waits for its outcome.", if (m.interaction.hasQuery()) "query" else "command", m.declared)
                if (m.interaction.hasQuery()) {
                    f.returns(reply(m).type).addStatement("return %T(this.port, %N).block(this.timeoutBound)", callClass(m), value)
                } else {
                    f.addStatement("%T(this.port, %N).block(this.timeoutBound)", callClass(m), value)
                }
                builder.addFunction(f.build())
            }
            return builder.build()
        }

        private fun asyncClient(): TypeSpec {
            val p = TypeVariableName("P", waitingBounds())
            val poll = ClassName(pkg, "${name}PollClient")
            val builder = TypeSpec.classBuilder(ClassName(pkg, "${name}AsyncClient")).visibility()
                .addKdoc(
                    "The suspending client of interface `%L`: each call suspends until its outcome and throws `ClientError` " +
                        "for a failed one; a cancelled call is forgotten. Calls run one at a time: a second concurrent call " +
                        "waits for the first, so use a second client over a second caller handle for concurrency. " +
                        "`nextEvent` and the subscriptions are extensions (#9).\n\n%L",
                    iface.declared.declared,
                    catalogThrows("port", "the comparison is made once, by the poll client the constructor builds"),
                )
                .addTypeVariable(p)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("port", p).build())
                .addProperty(PropertySpec.builder("port", p, KModifier.INTERNAL).initializer("port").build())
                .addProperty(PropertySpec.builder("poll", poll.parameterizedBy(p), KModifier.PRIVATE).initializer("%T(port)", poll).build())
                .delegatedReads()
            if (commands.isNotEmpty() || queries.isNotEmpty()) {
                builder.addProperty(PropertySpec.builder("calls", MUTEX, KModifier.PRIVATE).initializer("%T()", MUTEX).build())
            }
            if (events.isNotEmpty()) {
                // The port keeps one event waker per handle: two waits at once would displace and wake each other forever.
                builder.addProperty(PropertySpec.builder("events", MUTEX, KModifier.INTERNAL).initializer("%T()", MUTEX).build())
                val receiver = Receiver(ClassName(pkg, "${name}AsyncClient"), waitingBounds(), internal)
                subscriptions(receiver)
                extensions.functions += receiver.extension("nextEvent").addModifiers(KModifier.SUSPEND).returns(self.nestedClass("Event"))
                    .addKdoc(
                        "Suspends until the next occurrence of any subscribed event of `%L`. Concurrent calls take " +
                            "occurrences one at a time.",
                        iface.declared.declared,
                    )
                    .addStatement("return events.%M { %M({}) { waker ->", WITH_LOCK, AWAIT_POLL)
                    .addStatement("  port.wakeOn(%T.Event(%L), waker)", INTEREST, number())
                    .addStatement("  try { %T.takeEvent(port) } catch (e: %T) { throw %T.Read(e) }", self, READ_ERROR, CLIENT_ERROR)
                    .addStatement("} }")
                    .build()
            }
            for (m in commands + queries) {
                val (param, arg) = argument(m)
                val value = param.name.property
                val f = FunSpec.builder(m.method).addModifiers(KModifier.SUSPEND).addParameter(value, arg.type)
                    .addKdoc("Calls %L `%L` and suspends until its outcome.", if (m.interaction.hasQuery()) "query" else "command", m.declared)
                if (m.interaction.hasQuery()) {
                    f.returns(reply(m).type).addStatement("return this.calls.%M { %T(this.port, %N).await() }", WITH_LOCK, callClass(m), value)
                } else {
                    f.addStatement("this.calls.%M { %T(this.port, %N).await() }", WITH_LOCK, callClass(m), value)
                }
                builder.addFunction(f.build())
            }
            return builder.build()
        }

        private fun eventType(): TypeSpec {
            val event = self.nestedClass("Event")
            val builder = TypeSpec.interfaceBuilder(event).addModifiers(KModifier.SEALED)
                .addKdoc("One occurrence of an event of interface `%L`.", iface.declared.declared)
            for (m in events) {
                val payload = eventPayload(m)
                builder.addType(
                    TypeSpec.classBuilder(m.camel).addModifiers(KModifier.VALUE).addAnnotation(JvmInline::class)
                        .addKdoc("An occurrence of event `%L`.", m.declared)
                        .addSuperinterface(event)
                        .primaryConstructor(FunSpec.constructorBuilder().addParameter("occurrence", OCCURRENCE.parameterizedBy(payload.type)).build())
                        .addProperty(PropertySpec.builder("occurrence", OCCURRENCE.parameterizedBy(payload.type)).initializer("occurrence").build())
                        .build(),
                )
            }
            return builder.build()
        }

        /** The poll of the next occurrence every client's `nextEvent` makes, on the descriptor, where no member can shadow it. */
        private fun takeEvent(): FunSpec {
            val event = self.nestedClass("Event")
            val code = CodeBlock.builder()
                .addStatement("val buf = %T.allocate(%T.EVENT_SOURCE_BUFFER_SIZE)", BYTE_BUFFER, self)
                .addStatement("val occurrence = port.next(buf) ?: return null")
                .beginControlFlow("if (occurrence.iface != %L)", number())
                .addStatement("throw %T.Contract(%T.UnknownInteraction)", READ_ERROR, CONTRACT)
                .endControlFlow()
                .addStatement("buf.flip()")
                .beginControlFlow("return when (occurrence.ord)")
            for (m in events) {
                code.addStatement(
                    "%L -> %T(%T(checked(%T, buf), occurrence.envelope))",
                    ordinal(m), event.nestedClass(m.camel), OCCURRENCE, eventPayload(m).codec,
                )
            }
            code.addStatement("else -> throw %T.Contract(%T.UnknownInteraction)", READ_ERROR, CONTRACT).endControlFlow()
            return FunSpec.builder("takeEvent").addModifiers(KModifier.INTERNAL).addParameter("port", EVENT_SOURCE)
                .returns(event.copy(nullable = true))
                .addKdoc(
                    "Takes the next occurrence of any subscribed event of `%L` from [port], or `null` when none is " +
                        "waiting. An occurrence of another interface, or of an ordinal this one does not declare, throws " +
                        "`ReadError.Contract(Contract.UnknownInteraction)`: the port has already consumed it.",
                    iface.declared.declared,
                )
                .addCode(code.build()).build()
        }

        private fun send(m: Member): FunSpec {
            val (param, arg) = argument(m)
            val argName = param.name.property
            val kind = if (m.interaction.hasCommand()) "command" else "query"
            return FunSpec.builder(m.method).addParameter(argName, arg.type).returns(correlation(m))
                .addKdoc(
                    "Sends %L `%L` and returns the correlation of its outcome. A `require` clause that fails throws " +
                        "`SendError.Contract(Contract.PreconditionFailed)`, and nothing is sent.",
                    kind, m.declared,
                )
                .beginControlFlow("if (!%T.require(%N))", m.descriptor, argName)
                .addStatement("throw %T.Contract(%T.PreconditionFailed)", SEND_ERROR, CONTRACT)
                .endControlFlow()
                .addStatement(
                    "return %T(this.port.%L(%L, %L, encoded(%T, %N), null))",
                    correlation(m), kind, number(), ordinal(m), arg.codec, argName,
                )
                .build()
        }

        // -- the publisher and the provider ------------------------------------------

        private fun publisher(): TypeSpec {
            val bounds = buildList<TypeName> {
                if (signals.isNotEmpty()) add(SIGNAL_WRITER)
                if (events.isNotEmpty()) add(EVENT_SINK)
            }
            val w = TypeVariableName("W", bounds)
            val builder = TypeSpec.classBuilder(ClassName(pkg, "${name}Publisher")).visibility()
                .addKdoc(
                    "The provider face of interface `%L`'s signals and events. `commit` and each signal's `invalidate` " +
                        "and `touch` are extensions (#9).\n\n%L",
                    iface.declared.declared,
                    catalogThrows("port", "the constructor makes the comparison once, before the port is used"),
                )
                .addTypeVariable(w)
                .primaryConstructor(FunSpec.constructorBuilder().addParameter("port", w).build())
                .addProperty(PropertySpec.builder("port", w, KModifier.INTERNAL).initializer("port").build())
                .addInitializerBlock(CodeBlock.of("%T.checkCatalog(port.catalog)\n", self))
            val receiver = Receiver(ClassName(pkg, "${name}Publisher"), bounds, internal)
            for (m in signals) {
                val payload = signalPayload(m)
                builder.addFunction(
                    FunSpec.builder(m.method).addParameter("value", payload.type)
                        .addKdoc("Stages a new value for signal `%L`, published by `commit`.", m.declared)
                        .addStatement("port.set(%L, %L, encoded(%T, value))", number(), ordinal(m), payload.codec).build(),
                )
                extensions.functions += receiver.extension("invalidate${m.camel}")
                    .addKdoc("Stages the invalid state for signal `%L`, with `Cause.Declared`, published by `commit`.", m.declared)
                    .addStatement("port.invalidate(%L, %L)", number(), ordinal(m)).build()
                extensions.functions += receiver.extension("touch${m.camel}")
                    .addKdoc("Stages a re-affirmation of signal `%L`'s current value, published by `commit`.", m.declared)
                    .addStatement("port.touch(%L, %L)", number(), ordinal(m)).build()
            }
            for (m in events) {
                builder.addFunction(
                    FunSpec.builder(m.method).addParameter("value", eventPayload(m).type)
                        .addKdoc("Raises one occurrence of event `%L`.", m.declared)
                        .addStatement("port.raise(%L, %L, encoded(%T, value), null)", number(), ordinal(m), eventPayload(m).codec).build(),
                )
            }
            if (signals.isNotEmpty()) {
                extensions.functions += receiver.extension("commit").addKdoc("Publishes every staged signal change.")
                    .addStatement("port.commit()").build()
            }
            return builder.build()
        }

        private fun provider(): TypeSpec {
            val builder = TypeSpec.interfaceBuilder(ClassName(pkg, "${name}Provider")).visibility()
                .addKdoc("What an application implements to serve interface `%L`'s calls, driven by `serve` or `serveAsync`.", iface.declared.declared)
            for (m in commands) {
                val (param, arg) = argument(m)
                builder.addFunction(
                    FunSpec.builder(m.method).addModifiers(KModifier.ABSTRACT)
                        .addParameter(param.name.property, arg.type)
                        .addKdoc(
                            "Serves command `%L`. A command has no failure the application reports (ridl §6.1); arguments " +
                                "that break their constraints or `require` never reach it.",
                            m.declared,
                        ).build(),
                )
            }
            for (m in queries) {
                val (param, arg) = argument(m)
                builder.addFunction(
                    FunSpec.builder(m.method).addModifiers(KModifier.ABSTRACT)
                        .addParameter(param.name.property, arg.type)
                        .returns(reply(m).type)
                        .addKdoc("Serves query `%L`. A reply that breaks an `ensure` clause is discarded, and `ContractBroken` settled.", m.declared)
                        .build(),
                )
            }
            return builder.build()
        }

        /**
         * The Rust `dispatch`: one pass over the claims already waiting, each
         * settled — an unknown interface or ordinal `UnknownInteraction`, bytes
         * that fail their structure `Transport.Corrupt`, a constraint
         * `InvalidValue`, a failed `require` `PreconditionFailed`, a failed
         * `ensure` `ContractBroken`. A command settles before its provider
         * method runs, a query after. It returns the settlements the handler
         * accepted.
         */
        private fun dispatch(): FunSpec {
            val provider = ClassName(pkg, "${name}Provider")
            val code = CodeBlock.builder()
                .beginControlFlow("if (buffer.capacity() < MAX_BUFFER_SIZE)")
                .addStatement("return 0")
                .endControlFlow()
                .addStatement("var settled = 0")
                .addStatement("var left = budget")
                .beginControlFlow("while (true)")
                .addStatement("if (until != null && until.hasPassedNow()) return settled")
                .beginControlFlow("if (left == 0)")
                .addStatement("onBudgetSpent()")
                .addStatement("return settled")
                .endControlFlow()
                .addStatement("buffer.clear()")
                // Only the claim read is the handler's failure: a provider's own ReadError passes unchanged.
                .beginControlFlow("val claim = try")
                .addStatement("handler.nextClaim(buffer)")
                .nextControlFlow("catch (e: %T.ShortClaim)", READ_ERROR)
                // Arguments that do not fit MAX_BUFFER_SIZE are larger than any valid encoding of this
                // interface's members, so the claim is settled Corrupt unread, whichever member it names. A
                // refused settlement ends the pass: the runtime keeps that claim the next one (driftsys/ridl#569).
                .addStatement("if (!settle(handler, e.claim, %T.failure(%T.Corrupt))) return settled", RESULT, TRANSPORT)
                .addStatement("left -= 1")
                .addStatement("settled += 1")
                .addStatement("continue")
                .nextControlFlow("catch (e: %T)", READ_ERROR)
                .addStatement("throw %T.Claim(e)", PROVIDER_ERROR)
                .endControlFlow()
                .addStatement("if (claim == null) return settled")
                .addStatement("left -= 1")
                .addStatement("val args = buffer.duplicate()")
                .addStatement("args.flip()")
                .beginControlFlow("val accepted = if (claim.iface != number)")
                .addStatement("settle(handler, claim.id, %T.failure(%T.UnknownInteraction))", RESULT, CONTRACT)
                .nextControlFlow("else when (claim.ord)")
            // The decoded argument is always `arg`: a ridl parameter's own name could shadow a local here.
            val value = "arg"
            for (m in commands) {
                val (_, arg) = argument(m)
                code.beginControlFlow("%L ->", ordinal(m))
                    .addStatement("callOutcome(%T, args).fold(", arg.codec)
                    .indent()
                    .beginControlFlow("{ %L ->", value)
                    .beginControlFlow("if (!%T.require(%L))", m.descriptor, value)
                    .addStatement("settle(handler, claim.id, %T.failure(%T.PreconditionFailed))", RESULT, CONTRACT)
                    .nextControlFlow("else")
                    .addStatement("val ok = settle(handler, claim.id, %T.success(%T.allocate(0)))", RESULT, BYTE_BUFFER)
                    .addStatement("provider.%N(%L)", m.method, value)
                    .addStatement("ok")
                    .endControlFlow()
                    .endControlFlow()
                    .addStatement(", { settle(handler, claim.id, %T.failure(it)) },", RESULT)
                    .unindent()
                    .addStatement(")")
                    .endControlFlow()
            }
            for (m in queries) {
                val (_, arg) = argument(m)
                val reply = reply(m)
                code.beginControlFlow("%L ->", ordinal(m))
                    .addStatement("callOutcome(%T, args).fold(", arg.codec)
                    .indent()
                    .beginControlFlow("{ %L ->", value)
                    .beginControlFlow("if (!%T.require(%L))", m.descriptor, value)
                    .addStatement("settle(handler, claim.id, %T.failure(%T.PreconditionFailed))", RESULT, CONTRACT)
                    .nextControlFlow("else")
                    .addStatement("val reply = provider.%N(%L)", m.method, value)
                    .beginControlFlow("if (!%T.ensure(%L, reply))", m.descriptor, value)
                    .addStatement("settle(handler, claim.id, %T.failure(%T.ContractBroken))", RESULT, CONTRACT)
                    .nextControlFlow("else")
                    .addStatement("buffer.clear()")
                    .addStatement("%T.encode(reply, buffer)", reply.codec)
                    .addStatement("buffer.flip()")
                    .addStatement("settle(handler, claim.id, %T.success(buffer))", RESULT)
                    .endControlFlow()
                    .endControlFlow()
                    .endControlFlow()
                    .addStatement(", { settle(handler, claim.id, %T.failure(it)) },", RESULT)
                    .unindent()
                    .addStatement(")")
                    .endControlFlow()
            }
            code.addStatement("else -> settle(handler, claim.id, %T.failure(%T.UnknownInteraction))", RESULT, CONTRACT)
                .endControlFlow()
                .addStatement("if (accepted) settled += 1")
                .endControlFlow()
            return FunSpec.builder("dispatch")
                .addKdoc(
                    "Settles every claim of interface `%L` that is waiting, and returns how many the handler accepted. It " +
                        "does not wait. [buffer] holds the arguments and then the reply, so it is at least [MAX_BUFFER_SIZE] " +
                        "bytes; a shorter one returns 0 and consumes nothing.\n\nEvery claim taken is settled: an unknown " +
                        "interface or ordinal `UnknownInteraction`, bytes that fail their structure `Transport.Corrupt`, a " +
                        "constraint `InvalidValue`, a failed `require` `PreconditionFailed`, a failed `ensure` " +
                        "`ContractBroken`. A command is settled before its provider method runs, because its " +
                        "acknowledgment is a delivery acknowledgment (ridl §6.1); a query after, with the reply. A claim " +
                        "whose arguments do not fit [MAX_BUFFER_SIZE], reported as `ReadError.ShortClaim`, is larger " +
                        "than any valid encoding of this interface's members: it is settled `Transport.Corrupt` by its " +
                        "id without being read, whichever interface or member it names, and when the handler refuses " +
                        "that settlement the pass ends at once, because the runtime keeps that claim the next one " +
                        "(driftsys/ridl#569). Any other read failure of the handler is thrown as `ProviderError.Claim`. " +
                        "Past [until], when it is set, it takes no further claim, so a claim stream that never ends " +
                        "cannot hold it. It takes at most [budget] claims, whether or not their settlement is accepted, " +
                        "except an oversized claim whose settlement is refused; when it stops at that bound, claims may " +
                        "still be waiting, and it calls [onBudgetSpent] (driftsys/ridl#568).",
                    iface.declared.declared,
                )
                .addModifiers(KModifier.INTERNAL)
                .addParameter("handler", HANDLER).addParameter("provider", provider).addParameter("buffer", BYTE_BUFFER)
                .addParameter(ParameterSpec.builder("until", VALUE_TIME_MARK.copy(nullable = true)).defaultValue("null").build())
                .addParameter(ParameterSpec.builder("budget", INT).defaultValue("%T.MAX_VALUE", INT).build())
                .addParameter(ParameterSpec.builder("onBudgetSpent", LambdaTypeName.get(returnType = UNIT)).defaultValue("{}").build())
                .returns(INT).addCode(code.build()).build()
        }
    }
}
