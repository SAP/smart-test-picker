# Coverage map schema v1

Schema v1 extends STP's existing indexed JSON format. It is the first frozen coverage-map
contract; legacy maps without `schemaVersion` remain readable only through the legacy reader
and cannot be converted because their hashed simple-name test identities do not contain the
original fully-qualified class name.

`CoverageMapContract.SCHEMA_VERSION` in STP Core is the single authority for map and fragment
validation. Compatibility requires exact equality. A higher or lower integer is rejected;
integer ordering does not imply compatibility. `ExecutionPlanContract.VERSION` similarly owns
execution-plan version 1 without changing the execution-plan wire format.

## Indexed physical form

The first implementation writes class and method coverage in one artifact:

```json
{
  "schemaVersion": 1,
  "revision": "abc123",
  "generatedAt": "2026-08-30T10:00:00Z",
  "generator": {
    "stpVersion": "0.2.0",
    "coverageRuntimeVersion": "0.1.0",
    "jacocoVersion": "0.8.15",
    "jdkVersion": "17"
  },
  "classIndex": ["com.foo.ConfigService", "com.foo.OrderService"],
  "methodIndex": ["com.foo.OrderService#placeOrder"],
  "containerIndex": ["com.foo.BarTest", "com.foo.FooTest"],
  "testIndex": ["com.foo.BarTest#slow", "com.foo.FooTest#a"],
  "tests": [
    {"test": 1, "classes": [1], "methods": [0], "outcome": "FAIL", "collectionStatus": "COLLECTED_WITH_COVERAGE"}
  ],
  "unmapped": [
    {"test": 0, "reason": "TIMEOUT"}
  ],
  "setupScopes": [
    {"id": "scope-1", "type": "CONTAINER", "coveredClasses": [0], "affectedContainers": [1]}
  ],
  "completeness": {
    "expectedTests": [0, 1],
    "reportedTests": [0, 1],
    "expectedShards": ["01"],
    "completedShards": ["01"],
    "missingTests": [],
    "unexpectedTests": [],
    "missingShards": [],
    "duplicateShards": [],
    "duplicateTests": []
  },
  "statistics": {
    "expectedTests": 2,
    "mappedTests": 1,
    "unmappedTests": 1,
    "setupScopes": 1,
    "classEdges": 1,
    "methodEdges": 1
  },
  "lifecycleState": "PUBLISHED",
  "checksum": "sha256:<64-lowercase-hex-digits>"
}
```

Every interning table and every reference collection is sorted. Test identities are interned
because their FQNs otherwise recur in mappings, unmapped entries, and both completeness sets.
`classMetrics` is intentionally excluded: it is reporting data and is not read by selection.

## Test identity

The logical identity is `fully.qualified.BinaryClass#declaredMethod`. Nested classes use JVM
binary naming, for example `com.foo.OuterTest$Inner#nestedTest`, matching the names accepted by
build-tool filters. All invocations of one declared method collapse into one identity:

* `@ParameterizedTest` and `@RepeatedTest` invocations are combined;
* `@TestTemplate` invocations are combined under the template method;
* `@TestFactory` and its dynamic tests are combined under the factory method.

Whether a runtime can always recover the declaring factory method when a dynamic test lacks a
usable `MethodSource` remains an open 5c question. Schema v1 does not introduce invocation IDs.

## Setup scopes

A setup scope independently relates covered production classes to one or more affected test
containers. This represents nested, inherited, framework, and shared-context setup without
assigning coverage to whichever test happened to initialize it first. Supported enum values are
`CONTAINER`, `NESTED_CONTAINER`, `INHERITED_SETUP`, `SHARED_CONTEXT`, and `FRAMEWORK_SETUP`.
Only `CONTAINER` is currently expected to be producible. The other values are schema placeholders,
not claims of collector capability. Covered classes and containers reference shared tables.

## Test outcome, unmapped tests, and collection status

`TestOutcome` (`PASS` or `FAIL`) is independent of `CollectionStatus`. Both passing and failing tests
may have `COLLECTED_WITH_COVERAGE` or `COLLECTED_EMPTY`; either is a mapped, reported test for
completeness. An unmapped test has no `tests` entry because no valid coverage mapping exists. A
collection failure remains `UnmappedReason.COLLECTION_FAILED` and is never synthesized as successful
empty coverage. Other unmapped reasons diagnose why a valid mapping is unavailable; `FAILED` does
not imply that every failed test is unmapped. Selection semantics remain a 5b concern.

## Completeness and shards

Completeness compares global expected and reported identity sets, never counts. Reported means
mapped plus unmapped. `CollectionExpectation` independently owns expected shard identities;
`CollectionSummary` contains only completed shard reports and other collected facts. Sharding is
dynamic and there is no per-shard expected-test assignment. Missing,
unexpected and duplicate identities and missing or duplicate shards prevent publication.

Generator versions and JDK/JaCoCo values are diagnostic and do not reject a map. Statistics are
also descriptive; completeness is never inferred from their counts.

A `CANDIDATE` validation fixture may use JSON `null` for `completeness` when its source has no
independent inventory. Such a map can be codec-round-tripped, but validation still reports
`LIFECYCLE_NOT_PUBLISHED`, and it is not selector input. An absent inventory must never be replaced
with `expectedTests = collectedTests`.

## Determinism and integrity

The checksum is SHA-256 over compact canonical JSON with the `checksum` member omitted. The final
wire document adds that checksum after hashing. All identities, dictionaries, references, scope
members, shards, and entries are sorted before serialization.

> Coverage map SHA-256 provides integrity and corruption detection. It is not an authenticity or trust mechanism.

Trust in a map must come from its publication/distribution boundary; the checksum only identifies
and verifies the exact bytes inside that boundary.

## Reserved separable method coverage

Schema v1 reserves an optional checksum-covered descriptor:

```json
"methodCoverage": {
  "artifact": "coverage-methods.json.gz",
  "sha256": "sha256:<64-lowercase-hex-digits>"
}
```

The first implementation does not use it and writes methods inline. A later implementation may
publish an immutable side artifact for lazy loading without changing the base schema. Resolution,
publication, and fallback behavior belong to 5b/5d. A declared but missing or corrupt side artifact
is not equivalent to a map that deliberately contains no method descriptor.

## Provisional fragment contract

```json
{
  "schemaVersion": 1,
  "revision": "abc123",
  "shardId": "07",
  "tests": {
    "com.foo.FooTest#a": {
      "classes": ["com.foo.OrderService"],
      "methods": ["com.foo.OrderService#placeOrder"],
      "outcome": "PASS",
      "collectionStatus": "COLLECTED_WITH_COVERAGE"
    }
  },
  "unmapped": [{"test": "com.foo.BarTest#b", "reason": "COLLECTION_FAILED"}],
  "setupScopes": [],
  "collection": {"completed": false}
}
```

Fragments from different revisions are not combinable. A fragment declares its shard but never
the expected shard set. `completed: false` differs from an absent fragment. This contract is
**PROVISIONAL** until 5c confirms how shard IDs enter the test JVM and whether the runtime can
reliably distinguish `COLLECTED_EMPTY` from `COLLECTION_FAILED`.
