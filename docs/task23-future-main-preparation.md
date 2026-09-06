# TASK 23 future-main preparation

> `research/asm-codex` is being prepared as the future canonical main branch. The current `main` is not the integration destination.

## Baseline and synchronization

- `origin/main`: `c88c2ca380a2bfa380d7504887f5caa2ee4134b7`
- `research/asm-codex` before synchronization: `79482880cdfde1de4b7d5d76fb729606f5dd860d`
- merge commit: `5a008fd809cc85ca4782c59126eca98c0db248b3`
- merge strategy: non-rewriting merge of `origin/main` into `research/asm-codex`
- conflicts: none textual; the future ASM-observation-to-schema-v1 and identity projection remains an explicit follow-up design task

The merge retains coverage-map schema v1 and its fragment, codec, and validation contracts. It also retains legal JVM method-name support in the schema `TestIdentity` model.

## Removed modules and research material

The following production module references and tracked module trees were removed:

- `stp-spring-data-adapter`
- `stp-spring-data-e2e-fixture`
- `stp-spring-data-observability-spike`
- the root `validateSpringDataObservation` task and experimental README section

Bulk PetClinic/Spring Core reproduction trees, the reflection-order reproducer, detailed round reports, Spring Data spike documents, TASK 19/20/21 tooling, and generated evidence were removed from the branch tip without rewriting history. Compact canonical ASM conclusions remain in `docs/`, including the ASM research summary, ASM-versus-JaCoCo conclusions, current mapping state, context propagation, research evidence policy, and final TASK 19/20/21 conclusion documents.

## Retained production foundation

`stp-agent` retains method-entry collection, production filtering, deterministic descriptor-preserving method keys and FNV-1a IDs, collision detection, metrics/output, executor/context propagation, late-event integration, and ordinary tests. Its shaded artifact is now named `stp-agent.jar`, its role is documented as the STP ASM mapping agent, and method-entry instrumentation is the default. Explicit no-op mode remains only for bounded tests/diagnostics.

`stp-runtime` retains framework-neutral test contexts, registry/service/hooks, method-hit aggregation, ownership, context capture/attach/restore, late-event quarantine, unattributed-event accounting, identity/result models, and deterministic internal serialization. Spring-specific repository, bean, endpoint, entity, and table event models were removed.

`stp-junit-adapter` remains temporarily as an internal listener-only module. `StpRuntimeTestExecutionListener`, its service-loader entry, and lifecycle tests remain and are bundled into the agent. Round 11/12 ordering, state, and timeline diagnostics were removed.

Round 10 and Round 13-15 agent recorders/transformers, Round 13-15 runtime sink APIs, and all Round 10-15 production property handling were removed. Generic integrity diagnostics and metrics remain.

## JaCoCo and schema coexistence

The existing JaCoCo production paths in core, Gradle, Maven, CLI, common XML models, `CoverageMapperJaxb`, `ExecToXmlEngine`, and source reporting remain unchanged. The TASK 19-only `stp.jacoco.dump.events` dump/reset boundary trace was removed, restoring `JacocoPerTestListener` to current-main behavior.

No Gradle/Maven/CLI ASM migration, selector change, exec-to-XML removal, or new schema was introduced. Schema v1 remains authoritative while the ASM agent continues to emit its internal collection representation.

Two identity families remain intentionally duplicated pending migration:

- schema v1 `com.sap.oss.smarttestpicker.coverage.model.TestIdentity` versus runtime JUnit unique-ID ownership
- schema `MethodCoverageReference`/legacy mapper identities versus runtime descriptor-preserving `MethodIdentity`

Their projection and consolidation require explicit handling for parameterized, dynamic, overloaded, and JVM-special method names.

## Verification

- `./gradlew clean test`: PASS
- `./gradlew :stp-agent:test`: PASS
- `./gradlew :stp-runtime:test`: PASS
- `./gradlew :stp-junit-adapter:test`: PASS
- explicit `CoverageMapContractTest` and `JGraphTCoverageMapV1IntegrationTest` rerun: PASS
- `git diff --check`: PASS
- `scripts/check-large-files.sh`: PASS
- intended-module/settings scan: PASS
- Spring Data production-reference scan: PASS
- Round 10-15 production hook/property scan: PASS
- JaCoCo research dump/reset hook scan and diff against `origin/main`: PASS

## Follow-up work

1. Define and validate the ASM observation-to-schema-v1 projection, including test and method identity consolidation.
2. Incrementally attach and collect the ASM agent in Gradle, Maven, and CLI while retaining compatibility and unchanged selector semantics.
3. Retire JaCoCo collection/XML paths only after each replacement has end-to-end parity and migration coverage; decide final internal placement/publication boundaries for runtime and listener code.

The cleaned branch is a suitable main-line base for those incremental tasks and for eventual reviewed branch promotion. TASK 23 does not rename branches or perform that promotion.
