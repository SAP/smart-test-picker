# Current development state

The current development version is `0.3.0-SNAPSHOT`; artifact and wire-format versions are independent.
Use the [root workflow](../README.md), [configuration reference](configuration-reference.md) and
[compatibility table](compatibility.md) for setup, not historical task reports.

## Mapping and selection

Gradle and Maven support executable schema-v3 inventory, target assignments, collection and shard
aggregation. Inventory follows the build's effective JUnit Platform selection. Mapping checks that
assignments only narrow that selection and rejects a partial set of overloads sharing a method name.
The CLI joins complete shards for one execution target and loads the published map for explicit PR
selection. It does not discover tests or produce shard plans.

ASM is the Gradle default; Maven's collector default is JaCoCo. Both prepared ASM paths derive their
production scope, enforce code-source boundaries, and reject zero instrumentation. An otherwise
valid completed ASM fragment can absorb unreported assigned identities as `COLLECTION_FAILED`
with an audit. Those tests are mandatory while present at head. Missing whole shards still prevent
a new map; absorption is not degraded joining.

The JaCoCo collector shares the project's agent and resets its probes per session. Build-tool-managed
attachment writes to STP-owned data during mapping and project reports/checks are skipped.
Literal agent arguments are not rewritten. ASM coexistence preserves project JaCoCo results in the
qualified configurations. Both collectors retain cross-module production coverage.

## Remaining boundaries

- Effective inventory selection is qualified on the versions in [compatibility](compatibility.md).
- Maven preparation repeats early lifecycle phases when followed by test/verify; side effects must be
  repeatable. Custom provider classpaths and testClassesDirectory remain outside qualification.
- Gradle mapping does not support command-line `--tests`, non-Platform Test tasks or Kotlin
  Multiplatform production scope.
- Async attribution, parallel execution and shared cached contexts retain the limitations in
  [collector boundaries](asm-coverage-mapping-current-state.md). Complete accounting is not a proof
  of causal coverage.
- External orchestration owns checkout, plans and storage. This repository does not establish the
  current state of the companion Jenkins integration repository.

[Historical development snapshots](history/current-development-state.md) retain their original
source and package provenance. They are not release or current integration qualification.
