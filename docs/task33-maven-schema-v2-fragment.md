# TASK 33 — Maven schema-v2 fragment adapter

Baseline: branch `research/asm-codex`, starting HEAD
`58f84a7bb1f93161282240d268a1080beccb6bda`.

## Current and selected artifact flow

The retained legacy flow is `JacocoPerTestListener -> session_*.exec -> generate-reports ->
session_*.xml -> generate-coverage-map -> legacy CoverageMap`. Reporting, selection, smart-test, and
merge goals retain that behavior.

`MAVEN_SCHEMA_V2_STRATEGY` is an additive JaCoCo projection:

- source artifact: existing per-test exec/XML plus new identity and report-status sidecars
- test identity source: JUnit Platform `MethodSource` class name, declared method name, and declared
  parameter types; display names are never parsed
- class coverage source: covered JaCoCo XML methods, filtered by the existing test-class filter
- method coverage source: the same XML method elements and their `desc` attributes
- descriptor availability: YES; missing descriptors reject that observation as unsafe
- setup ownership availability: NO; `setupScopes` is always empty
- collector integrity source: one-to-one local exec/identity/report-status/XML consistency and parsing
- fragment output boundary: `generate-coverage-fragment`, after `generate-reports`

The listener includes declared parameter types in the session hash. Repeated and parameterized
invocations append into one declared-test session, while overloaded declared methods remain distinct.
Tests without usable `MethodSource` do not receive invented identities; their unmatched exec artifact
makes collection incomplete.

## Capability matrix

| Capability | Gradle ASM | Maven JaCoCo schema v2 |
|---|---:|---:|
| Class coverage | YES | YES |
| Exact descriptor-aware methods | YES | YES |
| Setup scopes | YES | NO |
| Direct schema-v2 fragment | YES | YES |
| Unmapped reporting | YES | YES, for known identities |
| Local integrity gating | YES | YES |

`collection.completed` means only that all locally observed Maven per-test sessions have valid
identity, exec, status, and required XML artifacts. It does not mean all expected tests or shards ran.

## Configuration

`smartTestPicker.revision` and `smartTestPicker.shardId` are required and have no inferred defaults.
`smartTestPicker.fragmentOutput` defaults to
`${project.build.directory}/coverage-fragment-v2.json`.

```bash
mvn verify \
  -DsmartTestPicker.revision="$GIT_COMMIT" \
  -DsmartTestPicker.shardId="maven-1" \
  -DsmartTestPicker.fragmentOutput=target/coverage-fragment-v2.json
```

## Real fixture evidence

`smart-test-picker-maven/src/test/fixtures/schema-v2` ran the actual Maven 3.9.16 lifecycle on JDK
21.0.11 with Surefire 3.5.3, JaCoCo 0.8.13, `generate-reports`, and
`generate-coverage-fragment`. Seven physical invocations produced six logical identities: ordinary,
nested, two parameterized invocations collapsed to one declared identity, two declared overloads kept
distinct, and a separate valid zero-production-coverage test. The fragment contained six mapped tests,
zero unmapped tests, five class-bearing mappings, one `COLLECTED_EMPTY`, five class edges, ten exact
method edges, zero setup scopes, exact revision `fixture-revision`, exact shard `fixture-shard`, and
`collection.completed=true`.

Two clean runs were byte-identical. Both SHA-256 values were
`cf1d2e3bfed4ab2bde8834a5d0a36fa24ae228a02e433032030c6e84667ef626`.
Focused tests also prove a missing report status produces a completed=false fragment with a known
`COLLECTION_FAILED` unmapped test. Codec round-trip validation passes. No display-name identity,
method descriptor, or setup ownership is guessed.

## Closure

Maven and Gradle use the same schema version, revision/shard meaning, `TestIdentity`, `TestCoverage`,
`UnmappedTest`, codec determinism, and shard-local completion semantics. Their setup capability differs
honestly. Task 5c is DONE. Task 5b stays NOT STARTED; Task 5d stays NOT STARTED — POC proven; Task 5e is
independent. The next dependent backlog item after 5c is Task 5b, then Task 5d after both prerequisites.
