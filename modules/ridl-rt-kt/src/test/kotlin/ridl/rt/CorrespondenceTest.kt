package ridl.rt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import ridl.rt.contract.CatalogHash
import ridl.rt.contract.CatalogRef
import ridl.rt.contract.Command
import ridl.rt.contract.EncodedSizes
import ridl.rt.contract.Event
import ridl.rt.contract.Fixed
import ridl.rt.contract.Interaction
import ridl.rt.contract.Interface
import ridl.rt.contract.InterfaceNo
import ridl.rt.contract.Kind
import ridl.rt.contract.Member
import ridl.rt.contract.Ordinal
import ridl.rt.contract.PayloadInfo
import ridl.rt.contract.Query
import ridl.rt.contract.Signal
import ridl.rt.contract.Timing
import ridl.rt.contract.TimingMode
import ridl.rt.encoding.Encoding
import ridl.rt.error.CallError
import ridl.rt.error.Contract
import ridl.rt.error.Transport
import ridl.rt.payload.EncodeError
import ridl.rt.payload.Malformed
import ridl.rt.payload.Payload
import ridl.rt.payload.Rule
import ridl.rt.payload.VerifyError
import ridl.rt.payload.Violation
import ridl.rt.port.Attached
import ridl.rt.port.Caller
import ridl.rt.port.Changed
import ridl.rt.port.Claim
import ridl.rt.port.ClaimId
import ridl.rt.port.Clock
import ridl.rt.port.CoherentSignals
import ridl.rt.port.Correlation
import ridl.rt.port.EventSink
import ridl.rt.port.EventSource
import ridl.rt.port.FixedReader
import ridl.rt.port.Handler
import ridl.rt.port.Interest
import ridl.rt.port.RaiseError
import ridl.rt.port.RawOccurrence
import ridl.rt.port.RawSample
import ridl.rt.port.ReadError
import ridl.rt.port.ScannableSignals
import ridl.rt.port.SendError
import ridl.rt.port.ServeError
import ridl.rt.port.SettleError
import ridl.rt.port.SignalReader
import ridl.rt.port.SignalWriter
import ridl.rt.port.SubscribeError
import ridl.rt.port.Wakeable
import ridl.rt.port.Watermark
import ridl.rt.port.WriteError
import ridl.rt.sample.Cause
import ridl.rt.sample.Detection
import ridl.rt.sample.Duration
import ridl.rt.sample.Envelope
import ridl.rt.sample.Freshness
import ridl.rt.sample.Occurrence
import ridl.rt.sample.Provenance
import ridl.rt.sample.Sample
import ridl.rt.sample.Timestamp
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * The correspondence table of docs/design.md §3 as a test: one row per
 * `ridl-rt` item, each pinning that the Kotlin item exists, lives in the
 * package that spells the Rust module, has the Rust name, and has the Rust
 * variants, fields and methods under their Kotlin spellings.
 *
 * The rows are transcribed from `crates/ridl-rt/src` of the pinned release,
 * except the items ridl `main` added ahead of the release that carries them:
 * `port::Wakeable`, `port::Interest` and `error::Transport::Busy` at c2543c2
 * (story E11.16), and `sample::Continuity`, `sample::TrackerFull`,
 * `sample::EventSeqTracker`, `contract::Unsized`, `Member::call_deadline`,
 * `Member::reservation` and `Encoding::max_size` at 87de8c6 (story E11.19),
 * and `correlate` with `error::ClientError` and `error::ProviderError` at
 * eb41a7a (story E11.18).
 * Two Rust items have no row: `payload::Ref` and `payload::Encoded`, the
 * borrow-checked proof that a value is decoded only from checked bytes, whose
 * guarantee [Payload] gives by taking the view `verify` returned.
 */
class CorrespondenceTest {
    private class Row(
        val rust: String,
        val kotlin: KClass<*>,
        /** Variant names, for an enum or a sealed type. */
        val variants: List<String>? = null,
        /** Rust field and method names, snake case. */
        val members: List<String> = emptyList(),
        /** Kotlin supertypes the Rust supertraits spell. */
        val extends: List<KClass<*>> = emptyList(),
        /** The Kotlin name, when it is not the Rust name. */
        val kotlinName: String? = null,
    )

    private val rows = listOf(
        // contract
        Row("ridl_rt::contract::Ordinal", Ordinal::class),
        Row("ridl_rt::contract::InterfaceNo", InterfaceNo::class),
        Row("ridl_rt::contract::CatalogHash", CatalogHash::class),
        Row("ridl_rt::contract::CatalogRef", CatalogRef::class, members = listOf("name", "hash")),
        Row("ridl_rt::contract::Kind", Kind::class, variants = listOf("Signal", "Event", "Command", "Query", "Fixed")),
        Row(
            "ridl_rt::contract::Interface", Interface::class,
            members = listOf("catalog", "number", "provisional", "name", "members"),
        ),
        Row("ridl_rt::contract::Interaction", Interaction::class, members = listOf("iface", "member")),
        Row("ridl_rt::contract::Signal", Signal::class, members = listOf("init"), extends = listOf(Interaction::class)),
        Row("ridl_rt::contract::Event", Event::class, extends = listOf(Interaction::class)),
        Row("ridl_rt::contract::Fixed", Fixed::class, extends = listOf(Interaction::class)),
        Row(
            "ridl_rt::contract::Command", Command::class,
            members = listOf("require"), extends = listOf(Interaction::class),
        ),
        Row(
            "ridl_rt::contract::Query", Query::class,
            members = listOf("require", "ensure"), extends = listOf(Interaction::class),
        ),
        Row(
            "ridl_rt::contract::Member", Member::class,
            members = listOf("ordinal", "kind", "name", "timing", "payloads", "call_deadline", "reservation"),
        ),
        Row("ridl_rt::contract::Unsized", ridl.rt.contract.Unsized::class, members = listOf("ordinal", "member", "type_name")),
        Row("ridl_rt::contract::TimingMode", TimingMode::class, variants = listOf("StrictPeriodic", "Range")),
        Row("ridl_rt::contract::Timing", Timing::class, members = listOf("mode", "min", "max")),
        Row("ridl_rt::contract::PayloadInfo", PayloadInfo::class, members = listOf("type_name", "max_size")),
        Row(
            "ridl_rt::contract::EncodedSizes", EncodedSizes::class,
            members = listOf("proto3", "flatbuffers", "repr_c"),
        ),
        // encoding: the trait and its three marker types are one closed enum.
        Row(
            "ridl_rt::encoding::Encoding", Encoding::class,
            variants = listOf("FlatBuffers", "Proto3", "ReprC"), members = listOf("max_size"),
        ),
        // error
        Row(
            "ridl_rt::error::Contract", Contract::class,
            variants = listOf("InvalidValue", "PreconditionFailed", "ContractBroken", "UnknownInteraction"),
            extends = listOf(CallError::class),
        ),
        Row(
            "ridl_rt::error::Transport", Transport::class,
            variants = listOf("Timeout", "Undelivered", "Down", "Corrupt", "Busy"),
            extends = listOf(CallError::class),
        ),
        Row("ridl_rt::error::CallError", CallError::class, variants = listOf("Contract", "Transport")),
        Row("ridl_rt::error::ClientError", ridl.rt.error.ClientError::class, variants = listOf("Send", "Call", "Read")),
        Row("ridl_rt::error::ProviderError", ridl.rt.error.ProviderError::class, variants = listOf("Serve", "Claim")),
        // correlate
        Row(
            "ridl_rt::correlate::Table", ridl.rt.correlate.Table::class,
            members = listOf("insert", "settle", "outcome", "forget", "wake_on"),
        ),
        Row("ridl_rt::correlate::Settled", ridl.rt.correlate.Settled::class, variants = listOf("Recorded", "Reclaimed", "Unknown")),
        Row("ridl_rt::correlate::Forgotten", ridl.rt.correlate.Forgotten::class, variants = listOf("Reclaimed", "Marked", "Unknown")),
        Row("ridl_rt::correlate::Waiters", ridl.rt.correlate.Waiters::class, members = listOf("register", "take", "take_all")),
        // sample
        Row("ridl_rt::sample::Timestamp", Timestamp::class),
        Row("ridl_rt::sample::Duration", Duration::class),
        Row("ridl_rt::sample::Envelope", Envelope::class, members = listOf("stamp", "seq")),
        Row("ridl_rt::sample::Provenance", Provenance::class, variants = listOf("Init", "Live", "Invalid")),
        Row("ridl_rt::sample::Cause", Cause::class, variants = listOf("Declared", "Detected")),
        Row("ridl_rt::sample::Detection", Detection::class, variants = listOf("InvalidValue", "Corrupt")),
        Row("ridl_rt::sample::Freshness", Freshness::class, variants = listOf("Fresh", "Stale", "Unbounded")),
        Row(
            "ridl_rt::sample::Sample", Sample::class,
            members = listOf("value", "provenance", "freshness", "envelope", "usable"),
        ),
        Row("ridl_rt::sample::Occurrence", Occurrence::class, members = listOf("payload", "envelope")),
        Row("ridl_rt::sample::Continuity", ridl.rt.sample.Continuity::class, variants = listOf("First", "Next", "Lost", "NotNewer")),
        Row("ridl_rt::sample::TrackerFull", ridl.rt.sample.TrackerFull::class),
        Row("ridl_rt::sample::EventSeqTracker", ridl.rt.sample.EventSeqTracker::class, members = listOf("observe", "forget")),
        // payload
        Row("ridl_rt::payload::Payload", Payload::class, members = listOf("max_size", "encode", "verify", "decode")),
        Row("ridl_rt::payload::EncodeError", EncodeError::class, variants = listOf("Capacity")),
        Row("ridl_rt::payload::VerifyError", VerifyError::class, variants = listOf("Structure", "Contract")),
        Row(
            "ridl_rt::payload::Malformed", Malformed::class,
            variants = listOf(
                "OutOfBounds", "Unaligned", "MissingRequired", "Utf8",
                "Union", "TooDeep", "TooManyTables", "TooLarge",
            ),
        ),
        Row("ridl_rt::payload::Violation", Violation::class, members = listOf("type_name", "rule")),
        Row("ridl_rt::payload::Rule", Rule::class, variants = listOf("Range", "Step", "Length", "Pattern", "Variant")),
        // flatbuffers: the free reading functions are `Reader`'s methods, Kotlin's own.
        Row(
            "ridl_rt::flatbuffers::Builder", ridl.rt.flatbuffers.Builder::class,
            members = listOf(
                "used", "push_u8", "push_i8", "push_u16", "push_i16", "push_u32", "push_i32", "push_u64",
                "push_i64", "push_f32", "push_f64", "push_offset", "push_string", "push_vector",
                "push_offset_vector", "push_table", "finish",
            ),
        ),
        Row("ridl_rt::flatbuffers::Pos", ridl.rt.flatbuffers.Pos::class),
        Row(
            "ridl_rt::flatbuffers::Field", ridl.rt.flatbuffers.Field::class,
            variants = listOf("Bool", "U8", "I8", "U16", "I16", "U32", "I32", "U64", "I64", "F32", "F64", "Offset"),
            members = listOf("size"),
        ),
        Row("ridl_rt::flatbuffers::TableField", ridl.rt.flatbuffers.TableField::class, members = listOf("slot", "offset", "value")),
        Row("ridl_rt::flatbuffers::Vector", ridl.rt.flatbuffers.Vector::class, members = listOf("len", "first", "element")),
        // port
        Row("ridl_rt::port::Attached", Attached::class, members = listOf("catalog")),
        Row("ridl_rt::port::Clock", Clock::class, members = listOf("now")),
        Row("ridl_rt::port::SignalReader", SignalReader::class, members = listOf("read"), extends = listOf(Attached::class)),
        Row(
            "ridl_rt::port::RawSample", RawSample::class,
            members = listOf("provenance", "freshness", "envelope", "len"),
        ),
        Row(
            "ridl_rt::port::SignalWriter", SignalWriter::class,
            members = listOf("set", "invalidate", "touch", "commit"), extends = listOf(Attached::class),
        ),
        Row(
            "ridl_rt::port::EventSource", EventSource::class,
            members = listOf("subscribe", "unsubscribe", "next"), extends = listOf(Attached::class),
        ),
        Row("ridl_rt::port::RawOccurrence", RawOccurrence::class, members = listOf("iface", "ord", "envelope", "len")),
        Row("ridl_rt::port::EventSink", EventSink::class, members = listOf("raise"), extends = listOf(Attached::class)),
        Row(
            "ridl_rt::port::Caller", Caller::class,
            members = listOf("command", "query", "ack", "reply", "forget"), extends = listOf(Attached::class),
        ),
        Row("ridl_rt::port::Correlation", Correlation::class),
        Row(
            "ridl_rt::port::Handler", Handler::class,
            members = listOf("serve", "next_claim", "settle"), extends = listOf(Attached::class),
        ),
        Row(
            "ridl_rt::port::Claim", Claim::class,
            members = listOf("id", "iface", "ord", "envelope", "remaining", "len"),
        ),
        Row("ridl_rt::port::ClaimId", ClaimId::class),
        Row("ridl_rt::port::FixedReader", FixedReader::class, members = listOf("read_fixed"), extends = listOf(Attached::class)),
        Row(
            "ridl_rt::port::ScannableSignals", ScannableSignals::class,
            members = listOf("generation", "scan"), extends = listOf(SignalReader::class),
        ),
        Row("ridl_rt::port::Watermark", Watermark::class, members = listOf("iface", "generation", "seq")),
        Row("ridl_rt::port::Changed", Changed::class, members = listOf("iface", "ord", "seq")),
        Row(
            "ridl_rt::port::CoherentSignals", CoherentSignals::class,
            members = listOf("read_coherent"), extends = listOf(SignalReader::class),
        ),
        Row("ridl_rt::port::Wakeable", Wakeable::class, members = listOf("wake_on")),
        Row("ridl_rt::port::Interest", Interest::class, variants = listOf("Outcome", "Slot", "Event", "Claim")),
        Row("ridl_rt::port::ReadError", ReadError::class, variants = listOf("Short", "TooFewSamples", "Contract", "Detached")),
        Row("ridl_rt::port::WriteError", WriteError::class, variants = listOf("TooLarge", "NotOwner", "Contract", "Detached")),
        Row(
            "ridl_rt::port::RaiseError", RaiseError::class,
            variants = listOf("Busy", "TooLarge", "NotOwner", "Contract", "Detached"),
        ),
        Row("ridl_rt::port::SendError", SendError::class, variants = listOf("Busy", "TooLarge", "Contract", "Detached")),
        Row("ridl_rt::port::SubscribeError", SubscribeError::class, variants = listOf("Contract", "Detached")),
        Row("ridl_rt::port::ServeError", ServeError::class, variants = listOf("Contract", "NotOwner", "Detached")),
        Row("ridl_rt::port::SettleError", SettleError::class, variants = listOf("UnknownClaim", "TooLarge", "Detached")),
    )

    @TestFactory
    fun `every ridl-rt item has a Kotlin item that spells it`(): List<DynamicTest> = rows.map { row ->
        DynamicTest.dynamicTest(row.rust) {
            val segments = row.rust.split("::")
            assertEquals("ridl_rt", segments[0])
            assertEquals("ridl.rt.${segments[1]}", row.kotlin.java.packageName, "the package spells the Rust module")
            assertEquals(row.kotlinName ?: segments[2], row.kotlin.simpleName, "the name spells the Rust name")

            row.variants?.let { expected ->
                assertEquals(expected.toSet(), variantsOf(row.kotlin), "the variants are the Rust variants")
            }

            val declared = row.kotlin.members.map { it.name }.toSet()
            for (member in row.members) {
                val kotlinName = camel(member)
                assertTrue(kotlinName in declared, "`$member` is spelled `$kotlinName` on ${row.kotlin.simpleName}")
            }

            for (supertype in row.extends) {
                assertTrue(row.kotlin.isSubclassOf(supertype), "${row.kotlin.simpleName} extends ${supertype.simpleName}")
            }
        }
    }

    @TestFactory
    fun `every public item of ridl-rt-kt has a row`(): List<DynamicTest> {
        val rowed = rows.map { it.kotlin }.toSet()
        val nested = rows.flatMap { it.kotlin.sealedSubclasses }.toSet()
        val extras = setOf(
            RidlError::class,
            ridl.rt.payload.ConstraintViolation::class,
            ridl.rt.task.Waker::class,
            ridl.rt.flatbuffers.Reader::class,
            ridl.rt.flatbuffers.TableView::class,
        )
        return publicTopLevelClasses().map { kclass ->
            DynamicTest.dynamicTest(kclass.qualifiedName!!) {
                assertTrue(
                    kclass in rowed || kclass in nested || kclass in extras,
                    "${kclass.qualifiedName} is in the correspondence table or named as Kotlin's own",
                )
            }
        }
    }

    private fun variantsOf(kclass: KClass<*>): Set<String> =
        if (kclass.java.isEnum) {
            kclass.java.enumConstants.map { (it as Enum<*>).name }.toSet()
        } else {
            kclass.sealedSubclasses.map { it.simpleName!! }.toSet()
        }

    private fun camel(snake: String): String =
        snake.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }
            .joinToString("")

    private fun publicTopLevelClasses(): List<KClass<*>> {
        val root = java.io.File(RidlError::class.java.protectionDomain.codeSource.location.toURI())
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") && '$' !in it.name && !it.name.endsWith("Kt.class") }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(java.io.File.separatorChar, '.') }
            .map { Class.forName(it).kotlin }
            .filter { it.visibility == kotlin.reflect.KVisibility.PUBLIC }
            .sortedBy { it.qualifiedName }
            .toList()
    }
}
