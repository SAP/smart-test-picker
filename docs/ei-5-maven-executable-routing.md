# EI-5 Maven executable routing

## Purpose

EI-5 makes Maven schema-v3 inventory and mapping use `ExecutableTestIdentity`, defined as a canonical Maven `ExecutionTarget` plus the unchanged logical `TestIdentity`. The same logical TestIdentity may execute in multiple Maven modules.

## Canonical Maven execution target

`MavenExecutionTargetResolver` is the single derivation boundary used by inventory, assignment preparation, and aggregation. It takes the reactor execution root and a `MavenProject`; it never consults the process working directory, artifact version, or Maven coordinates.

## Reactor-relative target derivation

Canonicalized and normalized module base directories must be within the canonical reactor root. The root is `maven:.`; children and nested modules use `/`-separated reactor-relative paths such as `maven:java-checks` and `maven:parent/child`. Escapes, missing base directories, malformed targets, and duplicate target-to-project mappings fail closed. Every result is round-tripped through `ExecutionTarget.parse`.

## Executable reactor inventory

`generate-reactor-head-test-inventory` retains schema 2 by default. Explicit `-DsmartTestPicker.schemaVersion=3` discovers each module with its isolated JUnit runtime, qualifies every logical test with that module target, builds `ExecutableHeadTestInventory`, and writes it with `ExecutableHeadTestInventoryCodec`. Revision verification remains exact. Cross-module logical duplicates are valid; duplicate executable production is rejected. The v2 logical duplicate safety path remains intact.

## Assignment partitioning

`prepare-reactor-executable-mapping` strictly decodes `ExecutableShardAssignment`, validates its version, revision, and shard ID, rejects non-Maven targets, validates every target against exactly one effective-reactor project, and partitions tests by target without rediscovery.

## Module-local Surefire routing

Each reactor module receives its own deterministic Surefire includes file containing only the logical identities assigned to that exact target. Unassigned modules receive a no-match include. Maven routing removes target information only after selecting the exact owning module.

## Runtime target configuration

Assignment preparation explicitly sets schema 3 and the canonical `smartTestPicker.executionTarget` on every Maven module. The EI-4 runtime consumes this trusted value and does not derive it.

## Executable fragment generation

`GenerateCoverageFragmentMojo` preserves its v2 branch. Under schema 3 it requires a Maven target, qualifies mapped and unmapped facts at the EI-4 boundary, verifies exact target ownership, and serializes `ExecutableCoverageFragment` with `ExecutableCoverageFragmentCodec`.

## Execution evidence v2

Module evidence v2 contains revision, shard, module execution target, and deterministically ordered executable `EXECUTED` and positively proven `NON_EXECUTED` identities. All identities must match the declared module target and the two sets must be disjoint. Evidence v1 remains unchanged.

## Reactor aggregation

Schema-v3 aggregation decodes the executable assignment and module executable fragments/evidence. It validates binding and target ownership, permits the same logical identity under different targets, rejects duplicate executable ownership and mapped/unmapped conflicts, and requires exact assignment ownership. Its final `ExecutableCoverageFragment` is directly consumable by the EI-3 joiner.

## Strict subset guarantee

The schema-v3 reactor fixture assigns `module-a::A1` and `module-b::B2` (plus the shared dual-owner proof) while deliberately making `A2` and `B1` fail if invoked. The build passes, Surefire reports only assigned methods, and fragments/evidence contain no outside-assignment identity.

## Schema-v2 compatibility

Schema selection is explicit and defaults to 2. Existing logical inventory, raw line assignment, fragment, evidence-v1, duplicate rejection, and reactor aggregation tests remain unchanged and green. Parallel v3 fixture coverage was added rather than replacing v2 fixtures.

## SonarJava case

At revision `9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c`, the JDK 26 schema-v3 inventory-only run found 4,161 executable occurrences in 12 test-bearing modules. Sixteen logical identities have multiple Maven targets and zero executable identities collide. `ReassignmentFinderTest#parameter_with_usage` is present under both `maven:java-checks-common` and `maven:java-checks`. No SonarJava tests or mapping were run.

## Non-goals

EI-5 does not change `TestIdentity`, Gradle routing, selectors, Jenkins, storage, or SonarJava sources. It does not implement selector-v3 behavior or resume MM-2.

## Next dependency

Before MM-2 resumes, the remaining dependency is wiring the upstream schema-v3 selection/orchestration boundary to emit and pass `ExecutableShardAssignment` to the new Maven preparation goal in the real mapping workflow.
