# TASK 24 — ASM observations to coverage-map schema v1

> Historical TASK 24 snapshot. This document records the intermediate schema-v1 projection as it
> existed before TASK 25. Schema v2 and `docs/task25-method-identity-and-setup-contract.md` are the
> authoritative current contract; statements below about current schema/setup limitations are not
> current-state guidance.

## Current runtime identity model

`stp-runtime` owns physical `TestIdentity` values keyed by `(runId, jvmId, JUnit Platform uniqueId)`.
They retain display name, engine ID, binary test class, declared method, and JUnit `MethodSource`
parameter types. Each physical parameterized, repeated, template, or dynamic leaf has its own unique
ID and bucket. A bucket retains descriptor-preserving `MethodIdentity(binaryClassName, methodName,
jvmDescriptor)`, execution status, bounded late events, and other unattributed diagnostics.

`stp-junit-adapter` obtains class/method/signature from `MethodSource`; it never slices display names.
For dynamic leaves without a direct source it uses the nearest previously observed ancestor
`MethodSource`. Nested classes retain JVM binary `$` names. Containers with `ClassSource` establish
thread-local lifecycle scopes. `stp-agent` registers deterministic FNV-1a IDs against full method
keys, records collisions and transformation errors, and supplies observed method entries to runtime.

## Current schema-v1 identity model

Schema v1 is authoritative. Logical tests are `fully.qualified.BinaryClass#declaredMethod`; its
method-name validation supports legal JVM/Kotlin-style names rather than Java-source identifiers.
Inline covered methods are currently `binary.Class#methodName`, without descriptors. A fragment owns
one externally supplied revision and shard ID plus mapped tests, unmapped tests, setup scopes, and
shard-local `collection.completed`.

## Differences

Runtime identities are physical and invocation-specific; schema identities collapse invocations.
Runtime methods preserve descriptors; schema-v1 inline methods do not. Runtime can diagnose an event
whose logical test ownership is missing, while `UnmappedTest` itself requires a valid logical
`TestIdentity`. Runtime also observes late/no-active-test facts that are diagnostics rather than
automatic fragment-integrity failures.

## Required projection rules and implemented projection

`AsmCoverageFragmentProjector` is a framework/build-tool-neutral API in `stp-runtime`. It groups
physical tests by source class and declared method, unions classes and methods, sorts all identities,
and emits through `CoverageFragmentCodec`. PASS requires every relevant invocation to succeed; any
FAILED invocation makes the logical outcome FAIL without dropping coverage. ABORTED invocations are
unmapped as `SKIPPED`. A successful zero-hit group is `COLLECTED_EMPTY`.

Parameterized, repeated, and test-template leaves collapse by `MethodSource`. Dynamic leaves in the
tested JUnit Jupiter 5.9.3 runtime recover the factory `MethodSource` and collapse under the factory
method. If neither a leaf nor an observed ancestor provides it, no identity is invented: projection
records an `unsupported-identity` diagnostic, marks collection incomplete, and cannot add an
`UnmappedTest` because schema v1 requires the missing identity. A dedicated unmapped representation
for unknown logical ownership is a schema-v1 extension candidate.

ASM class names already use binary names. Constructors, static initializers, bridge/synthetic/record/
enum members, nested classes, and visible lambda methods are projected according to actual method
entry observations; no JaCoCo filters apply. A unique observed `(class, method name)` projects to the
schema contract. If more than one descriptor maps to the same schema method string, the affected test
becomes `COLLECTION_FAILED`, the fragment is incomplete, and a `schema-method-overload-conflict`
diagnostic is emitted. Overloaded declared tests likewise conflict because schema logical identity has
no signature component. This is explicit loss prevention, not overload merging.

## Setup attribution support

JUnit `ClassSource` container intervals safely produce one `CONTAINER` or `NESTED_CONTAINER` scope
for the actual binary container. `@BeforeAll` observations before a leaf are supported. Before/after
each execute inside the leaf interval and remain test coverage. Static initialization outside a known
container, inherited setup, shared framework contexts, and framework setup are not emitted. AfterAll
events currently remain bounded late-event diagnostics because the last finished leaf is deliberately
quarantined. No run-wide set or Cartesian product exists.

## Collection status, completion, and integrity

Known agent errors, required-scope transformation failures, method-ID collisions, runtime
initialization failure, and fragment serialization failure are collector-integrity failures. During
projection, known failures make identifiable tests `UnmappedReason.COLLECTION_FAILED` and
`collection.completed=false`; serialization failure prevents fragment output and is recorded in the
agent diagnostic shell. Ordinary no-active-test and late-event accounting does not automatically
invalidate collection.

`collection.completed=true` means only that this shard's local collection and projection completed
without a known critical collector/projection failure. It never asserts that all expected tests or
shards ran. Revision, shard ID, and fragment path are explicit agent inputs (`revision`, `shardId`,
`fragmentOutput`) and must be supplied together. No Git operation, test-derived shard inference,
expected inventory, or expected-shard set exists in the collector.

## Validation and determinism

The existing fragment codec is the sole JSON writer. Fragment validation now checks exact schema
version, revision, shard ID, mapped/unmapped overlap, duplicate unmapped identities and setup IDs, and
schema method syntax. Model constructors enforce test identity, collection status, and setup
references. Reprojection of identical semantic observations is byte-identical under the codec.

## Unsupported or ambiguous cases / remaining limitations

1. Schema-v1 inline method strings cannot distinguish overload descriptors; conflicts are rejected.
2. Unknown logical ownership cannot be expressed as `UnmappedTest` without inventing an identity.
3. Safe setup scopes are limited to same-thread JUnit class containers and `@BeforeAll`; inherited,
   shared/framework, out-of-interval static initialization, parallel container setup, and AfterAll
   setup attribution remain unsupported.
4. Agent startup failure before runtime installation cannot emit a fragment; the JVM startup error is
   the evidence. Global completeness remains exclusively backlog 5d.

Acceptance is correct schema-v1 projection of ASM reality, not equality with historical JaCoCo maps.
No Gradle/Maven/CLI/Jenkins wiring, publication, selector semantics, or JaCoCo production path changed.
