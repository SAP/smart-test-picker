# EI-2 — Executable artifact contracts

## Why schema v3 exists

EI-1 separated a logical `TestIdentity` from the executable occurrence identified by
`ExecutionTarget + TestIdentity`. Existing schema-v2 artifacts cannot express two owners of the same logical test.
Schema v3 therefore introduces separate semantic and wire types whose ownership keys are always
`ExecutableTestIdentity`.

**Schema v2 remains logical-identity based. Schema v3 is execution-identity based.**

## V2 audit and preserved meaning

The pre-change contract audit found execution-ownership-shaped uses of `TestIdentity` in:

- `TestInventory.expectedTests` and `HeadTestInventory.runnableTests`;
- `CoverageFragment.tests` and `UnmappedTest.test`;
- `CoverageMap.tests` and its unmapped list;
- every test set in `Completeness`;
- `CollectionExpectation.intentionallyNonExecutableTests` and its inventory;
- `CollectionSummary.reportedTests` and `duplicateTests`;
- indexed keys in `CoverageMapCodec`, object keys in `CoverageFragmentCodec`, validator comparisons,
  `PublishedTestInventory`, and `CoverageMapPublication`.

These remain schema-v2 logical ownership. `CoverageMapContract.SCHEMA_VERSION` remains `2`, and the v2
models and codecs are unchanged. The additive constants `SCHEMA_V2`, `SCHEMA_V3`, and `LATEST_SCHEMA`
make version selection explicit without changing existing readers.

## Executable inventory

`ExecutableTestInventory` is revision-bound, immutable, deterministically ordered, and rejects null or
duplicate executable identities at collection boundaries. Equal logical identities on different execution
targets remain distinct and valid. `ExecutableHeadTestInventoryCodec` emits only an explicit version-1 object
containing `version`, `revision`, and sorted executable identity strings; it does not accept the legacy raw array.

## Executable fragment and map

`ExecutableCoverageFragment` and `ExecutableCoverageMap` are distinct schema-v3 models. Their mapped keys and
unmapped entries use `ExecutableTestIdentity`. Their strict codecs reuse `ExecutableTestIdentity.parse()` and
`toString()`, sort identities deterministically, reject the other schema generation, and reject duplicate wire keys.
The map checksum remains SHA-256 over canonical JSON without the checksum member.

The v3 map wire format carries an explicit schema version, revision, lifecycle, completeness, statistics,
mapped executable-key object, executable unmapped list, setup scopes, and optional method-coverage reference.
`MapStatistics` is identity-neutral and is reused; in v3 its test counts mean executable occurrences.

## Completeness and publication

`ExecutableCompleteness` compares exact executable occurrence sets. Reported occurrences consist of collector facts
plus orchestrator-proven positive non-execution. Missing, unexpected, duplicate, and shard sets are never projected
to logical identities. `ExecutableCoverageMapPublication` additionally requires matching revisions and exact
accounting for every expected executable owner.

Expectation remains orchestration-owned through `ExecutableCollectionExpectation`; collection facts remain
collector-owned through `ExecutableCollectionSummary`. The collector does not infer its expectation.

## Validation

`ExecutableCoverageMapValidator` requires schema v3, revision and shard provenance, non-null executable keys,
mapped/unmapped disjointness, exact completeness-derived sets, valid lifecycle, valid shard accounting, unique
executable identities, and the existing setup-scope invariants. Same logical test plus different target is valid;
only the same target plus the same logical identity is a duplicate.

## Compatibility rule

Schema-v2 artifacts contain logical ownership only. Schema-v3 artifacts contain executable ownership. Neither
reader accepts the other generation, and EI-2 provides no implicit v2-to-v3 conversion. Such conversion requires
externally proven single-owner topology and belongs behind a later compatibility gate.

`ExecutablePublishedTestInventory.logicalView` is reporting-only. It must not be used for completeness,
publication safety, joins, or routing.

## Reused independent contracts

`SetupScope` remains container-oriented and does not encode a test owner, so v3 reuses it. Target-aware setup
behavior is deferred until collector and selector integration. `MethodCoverageReference` is also independent of
test identity and remains unchanged; EI-2 does not redesign storage sidecars.

## Non-goals

EI-2 does not integrate Maven, Gradle, the runtime collector, JUnit listener, selector execution, Jenkins,
publication storage, or MM-2. It does not alter routing or merge fragment production.

## Next dependency

EI-3 moves mapping completeness, sharding, and join semantics to executable identities.
