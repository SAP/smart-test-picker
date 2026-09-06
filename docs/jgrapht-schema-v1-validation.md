# JGraphT coverage-map schema-v1 validation

## Dataset and conversion

The validation reads, but does not modify or regenerate,
`../smart-test-picker-evaluation/jgrapht/results/test-coverage-map.json.gz`. The artifact was added at
evaluation-repository commit `df8f3785075773c96ce5e8baaa6e10d271b4b32d` and identifies JGraphT
revision `093b0c5ea006ba5b1d8b7a0212676bf8850cac6b`. Its physical format is gzip-compressed legacy JSON
with `classMetrics`, `metadata`, and 2,308 `testMappings`; mappings contain both class and method
coverage.

The validation helper removes the legacy seven-hex-character suffix and reconstructs the FQN from
JGraphT test sources. If a simple class name is ambiguous, an archived JUnit identity in the
evaluation PIT XML must disambiguate it. It never guesses an FQN. This converted 2,298 mappings
(99.57%); ten mappings were excluded and reported because their `ReversedDoublyLinkedListViewTest`
source class was unavailable. The two `IncomingOutgoingEdgesTest` mappings are safely distinguished
where archived JUnit evidence exists; one without such evidence remains excluded.

The semantic fixture is a `CANDIDATE`, has `completeness = null`, and uses an empty setup-scope list.
The null means no independent expected inventory is available. The empty scope list means setup
information is unavailable, not that JGraphT has no setup coverage. The fixture is consequently not
valid selector input and cannot be mistaken for a published map.

## Results

The complete legacy artifact has 565 unique covered classes, 3,151 unique covered methods, 60,556
test-to-class edges, and 213,049 test-to-method edges. After removing the ten unresolved mappings,
the converted source subset and the schema-v1 round trip both have exactly:

| Measure | Converted source | Round trip |
| --- | ---: | ---: |
| Tests | 2,298 | 2,298 |
| Unique classes | 561 | 561 |
| Unique methods | 3,099 | 3,099 |
| Test-to-class edges | 60,477 | 60,477 |
| Test-to-method edges | 212,656 | 212,656 |

All reconstructed `TestIdentity` values and semantic edge sets compare equal. Every test, class, and
method index resolves in range; dictionaries are sorted and contain no semantic duplicates. Nested
binary names are handled by preserving the `$` suffix after resolving the outer test class.

One representative run produced 1,920,176 raw bytes and 111,800 gzip bytes. Dictionary JSON members
occupied 412,691 bytes and the indexed `tests` member occupied 1,501,089 bytes (member sizes include
their JSON field framing and are diagnostic, not an additive file decomposition). The result is
broadly consistent with the earlier indexed/gzip expectation: dictionary indexing plus gzip reduces
this high-edge-density payload to about 6% of its raw schema-v1 size. The checksum was
`sha256:5561b8545399aff95e33dec02b05d9fb819191674019a9183e93adfe33168a2e` for that deterministic
fixture. Two serializations and serialize-deserialize-serialize were byte-identical.

Serializing the same semantic map with method sets removed measured the method-coverage contribution
at 1,246,745 raw bytes and 70,265 gzip bytes. This comparison is diagnostic and is not a proposal to
change the inline schema.

Representative in-process timings, averaged where practical, were approximately 217 ms semantic
construction, 79 ms serialization, 101 ms deserialization, and 241 ms semantic/dictionary
validation. These are diagnostics rather than thresholds. Heap delta/peak was not measured because
the Gradle test JVM does not provide a useful isolated peak without adding profiling infrastructure.

Corrupting the real-data-sized serialized document is rejected as `CHECKSUM_MISMATCH`. Changing its
schema version to 2 is rejected before v1 parsing as `INCOMPATIBLE_SCHEMA/HIGHER_SCHEMA_VERSION`.

## Running the validation

`JGraphTCoverageMapV1IntegrationTest` uses the sibling evaluation and JGraphT trees when present and
is skipped otherwise, so the normal test suite remains portable. Alternate read-only locations can
be supplied with JVM properties `stp.jgraphtCoverageMap` and `stp.jgraphtSourceRoot`. It neither
executes JGraphT tests nor invokes a coverage collector.

## Limitations

- No new coverage was collected, and the full JGraphT test suite was not run.
- Setup scopes were not validated.
- Completeness was not validated against an independent inventory.
- Shards and fragments were not validated.
- Selector semantics were not changed or validated.
- The result does not prove 45k-test scale.
- It does prove schema-v1 codec/model handling and exact converted-subset preservation on a real
  multi-thousand-test, high-edge-density dataset.
