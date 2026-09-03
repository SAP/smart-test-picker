# Coverage map semantic model

The semantic model lives under `com.sap.oss.smarttestpicker.coverage.model`; JSON construction and
parsing live separately under `coverage.serialization`. Model records defensively copy maps, lists,
and sets, expose no setters, and use value types for revisions, test identities, containers, and
shards. This separation lets selection and future builders depend on meaning rather than dictionary
indexes or JSON layout.

The main types are `CoverageMap`, `CoverageMapRevision`, `TestIdentity`, `TestCoverage`,
`TestContainer`, `SetupScope`, `UnmappedTest`, `TestInventory`, `CollectionExpectation`,
`CollectionSummary`, `Completeness`, `TestOutcome`,
`GeneratorProvenance`, `MapStatistics`, `CoverageFragment`, and `MethodCoverageReference`.

## Setup is first-class

Static initialization, `@BeforeAll`, inherited setup, nested setup, and cached framework contexts do
not necessarily belong to one test class. `SetupScope` therefore maps covered classes to a set of
affected containers. This lets backlog 5b conservatively select every logical test in every affected
container without schema changes.

## Failed and unmapped tests

Test outcome and coverage collection status are independent. A failed test with a valid coverage
collection remains a mapped test with `outcome = FAIL`; its coverage is not discarded. A test is an
`UnmappedTest` only when no valid mapping exists, including a collection failure. Backlog 5b owns
the future selection policy for these states.

`COLLECTED_EMPTY` is different: the test and collector completed successfully and observed no
production coverage. `COLLECTION_FAILED` belongs in `unmapped`, never in a successful test mapping.

## Independent inventory and completeness

`Completeness.from` requires both an independent `CollectionExpectation` and a collector-side
`CollectionSummary`. The expectation combines `TestInventory` with expected shard identities;
neither expected tests nor expected shards can be inferred from collector output. Inventory discovery belongs to 5c/5d. Completeness uses identity
set differences and separately records missing, unexpected and duplicate tests and missing or
duplicate shards. Equal counts with different identities are incomplete.

Dynamic sharding has a global expected test set and an expected shard set; there is deliberately no
shard-to-test assignment in the model.

## Lifecycle

`CoverageMapLifecycleState` represents `FRAGMENT`, `CANDIDATE`, and `PUBLISHED`. Only a validated
`PUBLISHED` map may be selector input. This task defines the distinction; atomic publication,
retention, and storage belong to backlog 5d.

## Deliberate boundaries

This contract does not implement fragment merging or a map builder (5c/5d), runtime coverage and
test discovery (5c), setup-scope or unmapped-test selection policy (5b), storage/publication (5d),
Jenkins orchestration (5d), or arbitrary base/head revision handling (5e). It also does not alter
Gradle or Maven adapters.

## Version impact

Schema v1 is not compatible with legacy maps. Legacy test keys use `SimpleClass#method_7hexHash`,
from which the FQN cannot be reconstructed, so maps must be regenerated. The semantic selector API
need not change in Task 5a and the existing CLI output schema remains unchanged; future v1 reader
adoption will be an explicit integration step. Because Core and CLI artifact content changes, the
next release must not reuse `0.1.0`; the recommended next artifact version is `0.2.0`.

The RAD 1 evaluation repository is frozen at commit `a53e5c7` and is not modified or regenerated.
