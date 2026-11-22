# Coverage wire schemas

Artifact version and schema version are independent. Readers require the version their codec
supports; a newer or older number is not silently accepted.

| Artifact | Version | Codec / representation |
| --- | --- | --- |
| Logical map | schemaVersion 2 | CoverageMapCodec, indexed JSON with checksum |
| Logical fragment | schemaVersion 2 | CoverageFragmentCodec, provisional collection result |
| Executable map | schemaVersion 3 | ExecutableCoverageMapCodec, streaming gzip indexed-delta-1 |
| Executable fragment | schemaVersion 3 | ExecutableCoverageFragmentCodec, ordinary JSON |
| Executable head inventory | version 1 | ExecutableHeadTestInventoryCodec |
| Executable shard assignment | version 1 | ExecutableShardAssignmentCodec |
| Execution evidence | version 1 / 2 | Logical / target-qualified observation accounting |

## Executable inputs

A head inventory contains `version`, `revision`, and `tests` (target-qualified identity strings).
An assignment adds `shardId` to the same fields and restricts the inventory to that shard.
Neither contains coverage.

A v3 fragment contains `schemaVersion`, `revision`, `shardId`, `tests`, `unmapped`,
`setupScopes`, and `collection.completed`. Execution targets are carried by identities and
setup owners, not by shard ID. The generic codec validates structure and identities; the assignment
validator also checks exact ownership and accounting against the prepared target/plan.

Agent and build finalization may retain additional diagnostic fields. The
`assignmentAbsorption` extension is validated by the shared ASM finalization policy and preserved
in rewritten target fragments. Generic codec round trips do not preserve arbitrary extra fields.

## Maps

The [v3 format specification](v3-map-format.md) defines dictionaries, delta indices, canonical
ordering, gzip integrity and the checksum over the uncompressed canonical stream. The v3 fragment
wire format did not change when the map encoding changed. A fragment file is not a map.

The logical schema-2 indexed JSON format remains implemented by `CoverageMapCodec`; it is not a
route to recover executable target ownership. Legacy unversioned JaCoCo maps also remain separate.
No migration reader exists for the earlier experimental uncompressed v3 map encoding.

The [semantic model](coverage-map-model.md) defines completeness, outcomes, setup and unmapped
selection. [Historical schema notes](history/coverage-map-schema.md) retain earlier design details;
use the current codecs and v3 specification for new consumers.
