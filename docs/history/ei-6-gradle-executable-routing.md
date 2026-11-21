# EI-6 Gradle executable routing

## Canonical execution target

Schema v3 defines one Gradle executable owner as one enabled concrete `Test` task. Its `targetId` is the fully-qualified Gradle task path and the existing EI-1 rendering remains authoritative:

- root task `:test` -> `gradle::test`
- subproject task `:module-a:test` -> `gradle::module-a:test`
- custom task `:module-a:integrationTest` -> `gradle::module-a:integrationTest`
- nested project task `:parent:child:functionalTest` -> `gradle::parent:child:functionalTest`

Project-only ownership is invalid. `TestIdentity` stays logical and unchanged. Consequently, the same logical test under two tasks or projects produces two valid `ExecutableTestIdentity` values. Only an exact executable duplicate is invalid.

Composite/included builds are unsupported for schema-v3 routing. The adapter detects included builds and fails closed rather than using an ambiguous plain task path, an absolute filesystem path, or Gradle internal identity. `buildSrc` and external test-suite plugins were not validated as executable targets.

## Inventory and mapping scope

`generateGradleExecutableHeadTestInventory` independently invokes JUnit discovery with each participating task's `testClassesDirs` and runtime `classpath`, then qualifies results with that task path. Disabled tasks are excluded. `mappingTestTasks` can explicitly bound the authoritative invocation scope; otherwise all enabled concrete `Test` tasks in the build participate. Custom `Test` task names require no special handling.

Schema v2 retains its existing `generateHeadTestInventory` path and standard-`test` synthetic mapping task. Schema v3 is selected explicitly and requires `executableAssignmentFile` (or `-Dstp.executableAssignment`). Missing, malformed, v2, or mixed-version input fails closed.

## Assignment and filtering

The adapter decodes the shared `ExecutableShardAssignment` and validates exact revision, shard ID, Gradle build tool, and known task target. It partitions identities by exact task. It does not calculate shards.

Each task receives method-level Gradle `TestFilter` patterns in `binary.class.Name.methodName` form. Canonical parameter types remain in assignment, inventory, runtime facts, and fragments; they are deliberately not passed as Gradle runner syntax. JUnit parameterized invocations are selected through their declared template method. Nested binary names, including `$`, are preserved. A task with no assignment receives a no-match sentinel and is not instrumented, so Gradle executes zero tests and no full-suite fallback is possible.

## Runtime and collectors

The adapter supplies `smartTestPicker.executionTarget` and the collector configuration for every non-empty task partition. Runtime listeners never infer it. Each task JVM has exactly one target. ASM forces the existing `maxParallelForks = 1` guardrail. JaCoCo uses a task-and-shard-local exec/report directory and converts its positive identity artifacts into a target-qualified schema-v3 fragment.

ASM and JaCoCo write collision-free per-task fragments. `aggregateGradleExecutableCoverage` combines these into `build/stp/executable-fragment.json`, rejects binding/ownership collisions, and invokes `ExecutableFragmentAssignmentValidator` for exact assignment accounting. Positive skipped evidence is retained as target-qualified `SKIPPED`; absence is never converted to non-execution.

Task dependencies are explicit: mapping depends on inventory, participating Test tasks, collector conversion where applicable, and final aggregation. Parallel task output paths do not collide, but general parallel Test-task execution was not stress-tested. Schema-v3 configuration cache support is not claimed because topology is currently captured from configured Test task objects and conversion tasks are materialized after project evaluation. Existing schema-v2 ASM configuration-cache behavior is unchanged.

## Validation

Focused TestKit fixtures prove a root project with `test`, `integrationTest`, and a zero-assignment `componentTest`; the same logical test in two tasks; strict method subsets; parameterized and nested tests; explicit skipped evidence; multiproject ownership under `:module-a:test` and `:module-b:test`; task-local ASM fragments and their final aggregate; and a task-local JaCoCo schema-v3 fragment. Router tests cover revision, shard, build-tool, unknown-target, and empty-partition safety.

Spring Framework revision `f70cb60282dfd5a64a7b57cd9f12e3459f1edf1a` was validated with the explicit scope `:spring-core:test`. Inventory found 3,643 executable identities. A one-identity assignment ran only `AccessControlTests#forMemberWhenPublicConstructor`; one test report and one target-qualified mapped fragment entry were produced, with zero outside-assignment execution. No Spring source file was modified.

Kotlin/backtick identity behavior has no Gradle-specific transformation in EI-6; the hardened shared `TestIdentity` rules remain authoritative. No new Kotlin fixture was added because routing operates only on already-discovered semantic identities.
