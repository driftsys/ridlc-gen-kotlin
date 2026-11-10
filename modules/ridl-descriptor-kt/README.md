# ridl-descriptor-kt

## Responsibility

This module reads the two descriptors a RIDL runtime is given: the catalog
descriptor (`<unit>.catalog.binfb`, file identifier `RDLC`) and the deployment
descriptor (`<package>.<Deployment>.deployment.binfb`, identifier `RDLD`), which
embeds the catalog descriptor of every region. It is the Kotlin spelling of the
reading half of `crates/ridl-descriptor` at the pinned release: `verify` in
`ridl.descriptor` and in `ridl.descriptor.deployment`, with the refusals of the
Rust `VerifyError`s, and the tables of `schema/catalog.fbs` and
`schema/deployment.fbs` as read-only values. It has no writer, no `lower` and no
`describe`. It is a JVM library depending on the Kotlin standard library alone
(docs/design.md §6). The repository is licensed under the root
[MIT License](../../LICENSE).

## Status

driftsys/ridlc-gen-kotlin#142, for the loopback's `Node` (#139), which reads its
machine from a deployment descriptor as `ridl-loopback`'s does.

A buffer is refused here exactly when `ridl-descriptor` refuses it, for the same
reason. `DescriptorTest`, in the conformance module, builds two deployments with
the pinned `ridl`. One is driftsys/ridl's identity fixture, byte for byte the
descriptor `ridl-loopback`'s tests read. The other is that fixture with
attributes on every table that has them, one nested to the depth limit, and an
external component, which puts a link on the system boundary. It reads both,
then puts 55,824 buffers through `verify`: every truncation of both deployment
descriptors and of a catalog descriptor, and each of their bytes set to 0x00,
0xFF and one more. Every verdict, down to planus's error kind (`InvalidOffset`,
`InvalidLength`, `InvalidVtableLength`, `InvalidUtf8`, `UnknownEnumTag`,
`MissingRequired`), is the one the Rust `verify` gives. `just rust-verdicts`
makes the Rust verdicts again from the pinned release and checks them against
the committed `resources/descriptor/descriptor-rust-verdicts.txt`. The corpus
reaches every refusal except `TooDeep` and `TooManyAttributeValues`, which no
change of one byte can produce. `HeaderTest`, here, covers the header over
buffers written by hand.

## How it reads

`ridl-descriptor` reads both descriptors with planus 1.3.0, without the
`extra-validation` feature, and its `verify` is a walk over every field, so
which buffers it refuses is planus's reading rules. `Flat.kt` spells those
rules, from `table_reader.rs`, `slice_helpers.rs`, `impls/` and the vector
iterator.

- **An offset is read in a window.** A window runs from a start to the end of
  the buffer. The root is read in the window at 0, a table's fields in the
  window at the table, and a vector's element in the window at that element,
  since planus's iterator advances the vector one element at a time. A table's
  vtable, a signed offset back from the table, must lie inside the window the
  table was read from, and a vtable before it is refused as it is in Rust.
- **A string must be UTF-8**, and needs no terminating zero.
- **An unknown enum tag** is refused only when the field is read, and an absent
  scalar reads as its default.
- **The walk reads the fields in the order the Rust walk does**, so a buffer
  with several faults is refused for the fault Rust meets first.

## Where the code departs from docs/design.md

- **`verify` returns the whole descriptor, read.** The Rust `verify` returns a
  borrowed view, `DeploymentRef` or `CatalogRef`, whose accessors read the
  buffer again and cannot fail once it has passed. The walk here reads every
  field anyway, so it keeps what it reads: `verify` returns `Deployment` or
  `Catalog`, plain values holding copies of their strings and bytes. Kotlin has
  no borrow to make a view cheaper, and a value cannot be read from a buffer
  that changed after `verify`.
- **Errors are exceptions**, as in `ridl-rt-kt`: each `VerifyError` is a sealed
  class over `RuntimeException`. `Invalid` carries the name of planus's
  `ErrorKind`, not the error itself.
- **No reader of `ridl-rt-kt`.** `ridl.rt.flatbuffers.Reader` reads the payload
  encoding the Rust codec reads, whose offsets are not windowed. The descriptors
  follow planus, so this module has a reader of its own.
