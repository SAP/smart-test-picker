# Current architecture

STP separates build execution from coverage data and selection:

1. A build plugin discovers target-qualified test identities under the build's effective selection.
2. An authoritative assignment partitions that inventory into shards.
3. The selected collector observes assigned tests. The plugin validates each target and aggregates
   accepted target fragments into a shard result.
4. The common joiner validates the plan, ownership and completeness; publication aligns expected
   inventory. The CLI exposes a single-target join with atomic verified output.
5. Explicit PR selection loads the map and head inventory, checks revision provenance, computes
   impact, and includes mandatory new, changed or unmapped identities.

## Modules

| Module | Responsibility |
| --- | --- |
| smart-test-picker-common | Identity/model, codecs, validation, joining, publication, storage and selection |
| smart-test-picker | Gradle inventory, execution routing, collectors and finalization |
| smart-test-picker-maven | Maven inventory, prepared provider execution, collectors and evidence/finalization |
| smart-test-picker-core | Per-session JaCoCo listener and execution-data access |
| stp-agent | ASM transformation, agent diagnostics and fragment projection |
| stp-runtime | Attribution context, per-owner method collection and setup scopes |
| stp-junit-adapter | JUnit Platform lifecycle integration for the ASM runtime |
| smart-test-picker-cli | Standalone join, selection and legacy/report/storage commands |

The shaded agent embeds runtime and adapter; consumers resolve it at the plugin's version rather
than assembling those modules. The shaded CLI is directly executable. See [artifact versioning](artifact-versioning.md).

Collection policy and physical encoding are separate. See the [semantic model](coverage-map-model.md),
[v3 format](v3-map-format.md), [collector boundaries](asm-coverage-mapping-current-state.md) and
[configuration reference](configuration-reference.md). The [architecture chronology](history/current-architecture.md)
is historical evidence, not the current contract.
