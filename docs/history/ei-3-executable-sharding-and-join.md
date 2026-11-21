# EI-3 executable sharding and join

## Purpose

EI-3 adds build-tool-neutral schema-v3 orchestration. It plans, assigns, validates, joins, and counts executable test occurrences without changing schema-v2 behavior or integrating build adapters.

## Authoritative executable universe

`ExecutableTestInventory.expectedTests()` is the authoritative `Set<ExecutableTestIdentity>`. Logical `TestIdentity` equality never collapses executable shard ownership. Thus `maven:java-checks-common::T` and `maven:java-checks::T` remain independent occurrences.

## Audit of v2 orchestration

The existing common-module v2 artifacts (`CoverageFragment`, `CoverageMap`, `CollectionSummary`, `Completeness`, and `CoverageMapPublication`) consistently use logical identities. Their immutable/sorted artifact conventions, completeness set arithmetic, candidate/publication lifecycle, and statistics shape are reusable concepts. Their identity-bearing models and publication alignment cannot be reused for v3 because that would collapse owners. Maven's assignment-file and aggregation behavior was inspected only as contract context; Maven and Gradle code remain unchanged. EI-3 therefore adds parallel executable models and services rather than replacing v2 types.

## Shard plan

`ExecutableShardPlan` binds an immutable, deterministically ordered `Map<ShardId, Set<ExecutableTestIdentity>>` to a `CoverageMapRevision`. It rejects nulls and cross-shard duplicate executable identities. `ExecutableTestSharder` sorts the inventory and distributes it round-robin. It supports one shard, empty shards when shard count exceeds inventory size, and an explicitly empty plan for empty inventory.

## Assignment artifact

`ExecutableShardAssignment` represents one shard. `ExecutableShardAssignmentCodec` emits deterministic JSON with assignment format `version: 1`, revision, shard ID, and sorted executable identity strings. It rejects raw line lists, absent required fields, unsupported versions, duplicate values, and malformed executable identities.

## Exact partition invariant

`ExecutableShardPlan.validateExactPartition` requires matching revisions and exact set equality between all assignments and inventory. Plan construction separately enforces pairwise disjointness. Missing, unexpected, and duplicate executable assignments fail.

## Fragment-to-assignment validation

`ExecutableFragmentAssignmentValidator` accepts schema 3 only and requires matching revision, matching shard, completed collection, disjoint mapped/unmapped facts, and exact assignment accounting. The only permitted absence is an identity supplied explicitly as orchestrator-owned positive non-execution.

## Join rules

`ExecutableCoverageFragmentJoiner` is a filesystem-free semantic join. It validates the plan against inventory, revisions, exact shard reports, each fragment against its assignment, and executable ownership before constructing a map. Different executable keys are copied independently. A repeated setup-scope ID across fragments fails closed because no established cross-fragment ownership/merge rule exists.

## Duplicate ownership

The join records every mapped and unmapped occurrence by full executable identity. The same executable identity in two fragments, or mapped in one and unmapped in another, fails closed and is never unioned. Different targets for the same logical test are not duplicates.

## Positive non-execution

Collected facts remain collector-owned. Expected inventory and proven non-execution remain orchestrator-owned. Non-execution is accepted only through the joiner's explicit `intentionallyNonExecutable` input, must belong to inventory and the relevant shard assignment, and is never inferred from absence or empty coverage.

## Completeness

The join derives `ExecutableCollectionSummary` from mapped plus unmapped fragment facts and preserves the list of completed shard reports for duplicate detection. `ExecutableCompleteness.from` then computes exact executable-keyed missing, unexpected, duplicate-test, missing-shard, and duplicate-shard sets.

## Candidate map lifecycle

Successful join produces schema-3 `ExecutableCoverageMap` in `CANDIDATE` state. Statistics count executable inventory, mapped executable keys, and executable unmapped entries. Existing validation/publication remains the boundary for `PUBLISHED`; storage publication is unchanged.

## SonarJava example

The focused integration-style test uses logical `org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage` with owners `maven:java-checks-common` and `maven:java-checks`. It verifies both a shared shard and separate shards, retains two map entries, and keeps `{ClassA, ClassB}` separate from `{ClassA, ClassC}`.

## V2 compatibility

No v2 type or behavior is modified. There is no `TestIdentity` conversion adapter and the executable join accepts only `ExecutableCoverageFragment` schema 3 objects.

## Non-goals

EI-3 does not change Maven, Gradle, runtime collection, JUnit adaptation, selectors, storage, Jenkins, SonarJava, duration balancing, or schema-v2 semantics.

## Next dependency

EI-4 propagates the trusted execution target through collector/runtime output.
