# The `ridl.codegen.v1` schema

`plugin.proto`, `model.proto` and `deployment.proto`, which `plugin.proto`
imports, are copied unchanged from `crates/ridl-ir/proto/ridl/codegen/v1/` of
[driftsys/ridl](https://github.com/driftsys/ridl) at the release tag the
conformance module pins (`modules/conformance/ridl-release`). The conformance
check `checkSchema` fails when they differ from that tag, so a pin bump and a
schema copy are one change.

The IR specification §6 and §7 make `ridl.codegen.v1` additive only: a later
release may add fields, never change or remove one, and the reader parses with
unknown fields ignored, so a request from a newer `ridl` still reads.

One rename was made in place, against that rule: ridl 0.16 renames
`PodbufForm.descriptor` to `tail_descriptor` (driftsys/ridl#1254), because
protoc's Java output for the old name does not compile. The field number stays;
the JSON key becomes `tailDescriptor`. The plugin does not read the field.
