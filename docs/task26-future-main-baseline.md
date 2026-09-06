# TASK 26 future-main baseline

## Repository

- branch: `research/asm-codex`
- starting HEAD: `5bf0f43c26dd3a6d16ca21036b63565e720aeddd`
- final HEAD: the commit tagged `asm-future-main-baseline` (exact object ID is also printed in the TASK 26 final report)
- baseline tag: `asm-future-main-baseline` (annotated, local only)

At start, the modified tracked files were the 27 files listed in
`task26-uncommitted-classification.md`; untracked content comprised three task documents, the new
schema/runtime/agent sources and tests listed there, nine compact TASK 24 JSON fixtures, and the
TASK 22 Spring Core evidence tree. Research-only content was the untracked
`stp-spring-core-spike/` tree. Relevant ignored content was `.gradle/`, root/module `build/` output,
and generated TASK 19/20 research output. Nothing was staged during capture/classification.

## Commits created

1. `48355cb` Add descriptor-aware coverage schema v2.
2. `9cd9998` Add ASM runtime-to-fragment projection foundation.
3. `f3aab73` Finalize JUnit setup ownership and collector integrity semantics.
4. `ea0a97e` Retain compact historical Task 24 projection fixtures.
5. `0149437` Consolidate coverage-map and future-main architecture documentation.
6. Record future-main baseline (this document and classification; exact ID is the tag target).

## Schema

- current schema version: `CoverageMapContract.SCHEMA_VERSION = 2`
- method identity: semantic `(binaryClassName, methodName, jvmDescriptor)`, canonical as
  `binary.Class#methodName(descriptor)returnType`, including `Service#doIt()V`,
  `Service#doIt(Ljava/lang/String;)V`, `Type#<init>(Ljava/lang/String;)V`, and `Type#<clinit>()V`
- test identity: `Class#method` for no parameters and `Class#method(java.lang.String)` for a
  declared parameter signature; invocation variants collapse, declared overloads remain distinct,
  and dynamic tests use authoritative `MethodSource` factory ancestry when available
- unsupported identity: missing/invalid authoritative class, method, or source ancestry is never
  inferred; it is diagnosed, locally incomplete, and cannot be encoded as an invented unmapped test
- legacy compatibility: schema v1 is not schema v2; v1 automatic conversion is NO; JaCoCo descriptor
  guessing is NO; legacy/current JaCoCo production is unchanged for now

Distinct overloaded production identities survive the model, fragment codec, map codec,
`methodIndex`, checksum, round trip, and ASM projector. No JaCoCo descriptor is guessed.

## ASM collector

- agent: ASM method-entry javaagent with exact catalog identities and explicit fragment inputs
- runtime: thread-bounded test/container context, aggregation, projection, and diagnostics
- JUnit listener: internal lifecycle bridge using authoritative Platform sources
- fragment output: deterministic schema-v2 JSON bound to explicit revision and shard
- JaCoCo required: NO for ASM-to-fragment collection
- collector integrity: local critical failures make the fragment incomplete; this is not global
  expected-test/shard completeness

Local incomplete conditions are runtime initialization failure, transformation failure, method-ID
collision, agent error, unfinished test, missing test result, unsupported logical identity,
ERROR-severity setup uncertainty, and fragment serialization/write failure. Non-critical diagnostics
include ordinary startup/shutdown noise, generic no-active-test observations, bounded late events
that are safely quarantined, and INFO/WARNING setup diagnostics.

## Setup

| Setup type | Supported | Attribution |
| --- | --- | --- |
| BeforeAll | YES | container |
| AfterAll | YES | container |
| BeforeEach | YES | active test |
| AfterEach | YES | active test |
| Nested container | YES | nested container |
| Static init in active test | YES | active test |
| Static init in container | YES | container |
| Static init outside ownership | NO | unattributed |
| Inherited setup | NO | explicit unsupported state |
| Shared framework setup | NO | explicit unsupported state |
| Async setup without propagation | NO | explicit unsupported state |
| Parallel containers | YES within bounded thread-local lifecycle | isolated ownership |

Run-wide Cartesian attribution is absent. `AfterAll` to last-leaf leakage is absent. Normalized setup
scopes are equivalent for the tested single-collector and multi-collector arrangements.

## Build tools and JaCoCo

- Gradle ASM integration: **NOT STARTED**
- Maven ASM integration: **NOT STARTED**
- CLI schema-v2 integration: **NOT STARTED**; existing user-facing/legacy CLI paths remain
- existing JaCoCo production path retained: **YES**
- removed: research-only tracing only (during the preceding future-main cleanup)

## Modules

The authoritative module and dependency snapshot is `current-architecture.md`. In summary, common
owns schema/model/codecs/validation, core retains JaCoCo selection support, agent/runtime/listener
provide the ASM collection foundation, and Gradle/Maven/CLI retain existing production paths.

## Release artifact target

- user-facing: Gradle plugin, Maven plugin, STP CLI, STP agent
- internal/bundled: `stp-runtime`, JUnit listener, relocated ASM
- shared internal dependency: common/core schema and selector libraries

## Backlog

- DONE — 1 Selector integracija
- DONE — 2 Konfiguracioni model
- DONE — 3 Adapter versioning + integritet
- IN PROGRESS — 4 Artifact lifecycle
- 5a DONE
- 5b NOT STARTED
- 5c IN PROGRESS (TASK 24 + TASK 25 proven progress; not DONE)
- 5d NOT STARTED — POC proven
- 5e NOT STARTED / independent

Dependency: `5a -> {5b, 5c} -> 5d`.

## Known limitations

1. Framework-specific inherited, shared/framework, and unpropagated async setup detection is not implemented.
2. Global inventory, merge, completeness, publication, and orchestration remain 5d boundaries.
3. Selector fallback remains 5b; Gradle/Maven/CLI have not adopted schema-v2 ASM fragments.
4. An agent failure before runtime installation may prevent fragment emission; process failure is then the evidence.

## Next recommended task

1. Gradle ASM wiring.
2. Gradle end-to-end validation.
3. Maven ASM wiring.
4. Maven end-to-end validation.
5. Decide CLI changes required for schema-v2 fragments/maps.
6. Retire replaced JaCoCo collection paths incrementally.
7. Complete remaining 5c production concerns.
8. Proceed into 5d production orchestration/publication.

The immediate next task is **Gradle ASM wiring**. Branch promotion is not part of this baseline.
