# MM-DECISION-1: Executable test identity in multimodule builds

## Context

STP schema v2 defines `TestIdentity` as a logical JUnit test: JVM binary class name, declared method name, and optional parameter-type discriminator. Its canonical text is `className#methodName(parameterTypes)`; for ordinary no-argument tests this is `className#methodName`. It contains no Maven module, Gradle project, task, source set, or other execution location.

MM-FIX-2 established that the current Maven inventory must fail closed when the same logical identity is discovered in more than one module test-output root. This record decides the long-term model only. It does not implement that model, change a schema, or resume MM-2.

Baseline captured on 2026-09-12:

| Repository | HEAD | Branch | Status |
| --- | --- | --- | --- |
| Main STP | `91fcbb9478984ebf5f0291a1b7486c6755d8d4f2` | `research/asm-codex` | clean |
| Jenkins STP | `9086795d22678d15ada657a6c4b3f3814195f930` | `main` | untracked `.DS_Store`, `examples/.DS_Store`, and `verification/maven-multimodule/mm2-sonar-java.json`; untouched |
| SonarJava | `9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c` | `master` | clean |

Existing `verification/maven-multimodule/mm-fix-2-duplicate-test-ownership.json` is the evidence source. The full SonarJava suite was not rerun.

## Observed SonarJava case

`org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage` is independently compiled and executed by both `java-checks-common` and `java-checks`. This is not merely dependency-classpath visibility. MM-FIX-2 records 16 duplicate logical identities, 32 physical occurrences, and successful ordinary Surefire reports in both modules. The sources differ in imports and the class bytes differ in line metadata, while executable instructions and annotations are equivalent. That equivalence does not remove the two execution owners.

The present assignment contains only the logical identity. Both modules can accept the same include, produce module-local coverage/evidence, and collide at reactor aggregation. Rejecting the duplicate is therefore the correct current behavior.

## Current model

The code uses one name for two different models: `coverage.model.TestIdentity` is the public logical identity, while `runtime.model.TestIdentity` identifies a physical event inside a run by `runId + jvmId + JUnit platformUniqueId`. The runtime projector deliberately groups physical events by the public logical identity before producing schema-v2 fragments. Neither type represents a stable build execution target.

| Area | Current key | Assumes global uniqueness? | Could support execution location separately? |
| --- | --- | --- | --- |
| `TestIdentity` | binary class + method + parameter types | Yes, wherever used in sets/maps | Yes; retain it as logical identity |
| `HeadTestInventory` | `Set<TestIdentity>` | Yes; duplicate provider input is rejected | Yes; inventory can instead contain executable identities |
| JUnit inventory discovery | logical `TestIdentity` per runnable leaf | Yes within an inventory | Yes; adapter can attach target after discovery |
| Runtime listener | run/JVM/platform unique ID, then class/method metadata | No for physical events; later projection does | Yes; collector context can supply target |
| Runtime projector | `Map<TestIdentity, Accumulator>` | Yes; unions physical observations by logical key | Yes; group by executable identity |
| Maven fragment generator | mapped/unmapped maps keyed by `TestIdentity` | Yes per module | Yes; bind module target at projection |
| `CoverageFragment` | `Map<TestIdentity, TestCoverage>` | Yes | Yes, with a new fragment representation |
| Reactor fragment aggregation | `TestIdentity`; duplicate fragment/evidence owner is fatal | Yes across modules | Yes; executable keys make both owners distinct |
| `CoverageMap` | `Map<TestIdentity, TestCoverage>` | Yes globally | Yes, but requires a new schema version |
| Execution evidence v1 | executed/non-executed logical strings plus one broad `testTarget` | Yes; exactly one evidence owner | Yes; each occurrence must carry its target |
| Mapping completeness | expected/reported/missing/duplicate `TestIdentity` sets | Yes | Yes; count executable occurrences |
| Selector analysis | logical map keys compared with logical head inventory | Yes | Yes; select executable identities and optionally derive logical views |
| Selection output | list of `className#method` strings | Yes | Yes; add execution-scoped routing output |
| Maven selection | same Surefire includes written to modules; module routing is inferred separately | Yes for a selected logical test | Yes; group includes by Maven target and use `-pl`/module configuration |
| Gradle selection | one configured `smartTest` task, patterns widened to `class.*` | Effectively yes for that task | Yes; group filters by project/task target |
| Shard assignment | one logical identity string per line | Yes across all shards | Yes; assignments must carry target + logical identity |
| Jenkins manifest/join | `expectedTests` and `shardAssignments` are identity strings; exact set equality | Yes | Yes; Jenkins can transport opaque structured executable keys after contract update |
| Published/storage artifacts | schema-v2 logical-key map | Yes | A new version can coexist; v2 remains readable under its old semantics |
| Legacy mapper/CLI/reporting | mostly `Class#method` strings | Yes | Logical display can remain; execution target is additional routing/provenance |

Thus `TestIdentity` currently serves as logical identity, inventory key, coverage ownership key, mapping key, selection key, shard key, and evidence key. It is not a true execution identity. `ShardId` and broad `testTarget` metadata locate a collection, not the owner of an individual test occurrence.

## Terminology

**Logical test identity** is the declared test independent of where it runs: `org.example.MyTest#shouldWork`. It answers “which test declaration?” Parameter types remain part of the existing disambiguation model.

**Execution target** is a deterministic build-owned route that can execute tests. Maven uses a reactor-relative module identity plus a test execution name; Gradle uses project path plus `Test` task path. It answers “where will the build execute it?” Source set and component may be diagnostic attributes, but the canonical target must correspond to an invocable build target.

**Executable test identity** is `(executionTarget, logicalTest)`. It represents one expected executable occurrence. Two executable identities may share one logical identity.

**Coverage identity** must be executable identity. Coverage for repeated physical invocations inside the same executable target may be safely unioned under that executable identity, as schema v2 already does for parameterized/dynamic observations that project to one declared test. Coverage from different execution targets must not be unioned as the authoritative representation. A derived logical union is allowed only for reporting or conservative analysis and must retain the underlying executable entries.

**Selection identity** must be executable identity. Selector output should contain execution-scoped tests, represented as logical identity plus routing metadata. A logical-only summary may remain for human display and compatibility diagnostics, but it is not sufficient execution input.

## Model A: global `TestIdentity` only

Rule: every logical identity has exactly one execution owner; multiple owners fail closed.

Advantages are no schema change, a simple selector/map, build-tool-neutral strings, full compatibility, and no disturbance to the existing single-target Gradle path. Safety is clear because unsupported topologies cannot silently lose coverage.

Disadvantages are fundamental: SonarJava and similar reactors remain unsupported, legitimate ordinary build behavior must be changed for adoption, and STP cannot distinguish coverage or route selection when multiple owners exist. The limitation also exists in Gradle multi-project, reused-test, and multi-`Test`-task builds.

This is acceptable only as the current temporary product limitation, not as the long-term contract. A selective-testing product should model ordinary executable occurrences instead of requiring users to eliminate them.

For selection, logical `T` can be routed only if adapters prove one owner. Maven uses that module and a Surefire include; Gradle uses that task and `--tests`/filter. Ambiguous ownership fails to full suite or mapping fails closed. Sharding stays logical and assigns `T` once. Completeness stays logical. Schema classification: `NO_SCHEMA_CHANGE`.

## Model B: logical identity plus ownership metadata

Public identity remains logical and metadata records `T -> {targetA, targetB}`. An adapter could route one selected `T` to every owner. Inventory could store logical tests plus an ownership relation; shard assignments could retain `T` only if every assigned shard executes all owners, or add target routing metadata.

This supports ordinary execution only if coverage is unioned across owners. In the example, the map becomes `T -> {ClassA, ClassB, ClassC}`. A `ClassC` change selects `T`, and safe routing must execute both owners because the map no longer proves which owner supplied the edge. Executing only module B requires execution-scoped coverage, which turns B into Model C in substance.

Failure semantics are awkward. If A executes and B does not, logical evidence says `T` executed but cannot prove that all expected occurrences executed. Correctness therefore requires owner-scoped evidence and completeness even if coverage is logically unioned. A routing-only extension is unsafe; a complete B accumulates most of C while retaining a lossy primary key.

Maven can group a selected logical test across all recorded modules and write module-local Surefire includes; Gradle can do the same across tasks. It preserves builds but over-executes. Jenkins must transport ownership and validate every owner; shards must avoid splitting owners or represent owner assignments. Storage compatibility is possible as an additive schema extension only for routing metadata, but authoritative owner-scoped evidence and exactness alter semantics enough that existing v2 artifacts cannot claim the new guarantee. Classification: `NEW_SCHEMA_VERSION_REQUIRED` for a correct implementation; a merely `BACKWARD_COMPATIBLE_SCHEMA_EXTENSION` implementation is insufficient.

## Model C: module/build-target-qualified executable identity

Introduce a separate `ExecutionTarget` and `ExecutableTestIdentity`; do not serialize Maven coordinates into `TestIdentity`. Logical identity remains stable and build-tool neutral. Inventory, coverage, selection, sharding, evidence, and completeness use executable identity wherever they concern physical ownership. Logical views remain available for source-change matching, display, and compatibility.

This is the cleanest semantic boundary. Maven module A and module B entries cannot overwrite or duplicate each other. A changed `ClassC` selects only `(moduleB, T)`. If both entries cover a changed class, both are selected. Failure of either expected occurrence is visible as a missing executable identity. Ordinary Maven and Gradle semantics can be reproduced exactly because routing is explicit.

Impacts are substantial: schema v3 (or another explicitly new version), executable inventories/fragments/maps, target-aware collector context and evidence, target-aware shard files, selector output and CLI, adapter routing, join validation, storage negotiation, Jenkins manifest/API updates, fixtures, and documentation. Existing schema-v2 maps remain valid v2 logical maps but cannot be silently upgraded when ownership is unknown. Classification: `NEW_SCHEMA_VERSION_REQUIRED`.

## Coverage implications

Suppose A's execution of `T` covers `ClassA, ClassB`, and B's covers `ClassA, ClassC`.

The logical union model stores `T -> {A,B,C}`. A `ClassC` change must execute every owner of `T` to remain safe, because provenance was erased. Selecting logical `T` “once” has no well-defined physical meaning; an unqualified include may run zero, one, or both occurrences depending on build invocation. Executing both is safe but loses optimization.

The execution-scoped model stores `A::T -> {A,B}` and `B::T -> {A,C}`. A `ClassC` change selects only `B::T`; the adapter invokes target B with the logical Surefire/Gradle filter. This is both safe and more precise. A change to `ClassA` selects both. Reports may group both under logical `T` while exposing two execution rows.

Union remains useful as a derived conservative index: `logical T -> union(coverage of all executable T)`. It must never replace executable entries or authorize owner-specific routing.

## Selection implications

Under A, selecting `T` executes its one proven owner. Maven can use `mvn -pl owner ...` plus a Surefire include; Gradle can invoke the one task with `--tests`/filter. More than one owner is unsupported.

Under B, selecting `T` must route to all owners unless the coverage relation also becomes owner-scoped. Maven uses `-pl` for all owning modules and writes the include in each; Gradle invokes every owning task. An owner that is skipped or not invoked makes the selection incomplete.

Under C, selection returns executable identities. The Maven adapter groups by reactor module/test execution, invokes only required modules (`-pl`, adding dependency-building flags only according to configured ordinary build policy), and provides module-local Surefire/Failsafe includes. The Gradle adapter groups by fully qualified task path and applies each task's filter. Adapters cannot safely recover this precision from logical selector output alone; owner metadata must survive the selector boundary.

Selection of changed/new test source needs target-aware head inventory. If one source path contributes the same logical test to two targets, both executable head entries are new/changed and both are selected.

## Sharding and completeness implications

Under A, `T` is assigned once and exactly one owner must execute it. Current line-based assignments and logical completeness remain valid.

Under B, assigning `T` once is safe only when that shard is responsible for all its owners and adapters deterministically execute them all. Splitting owners across agents requires `(target,T)` assignments and owner-level evidence, effectively C. Logical exactness can hide a missing owner.

Under C, the sharding universe is the executable inventory. Each `(target,T)` occurs in exactly one shard assignment. Assignment files need a canonical structured representation containing target and logical identity. Different occurrences of logical `T` may be placed on different shards. Union of shard assignments must equal the executable inventory exactly, and fragments/evidence must equal each shard's executable assignment exactly.

The completeness invariant becomes:

`expected executable occurrences = mapped occurrences ∪ executable-unmapped occurrences ∪ positively proven non-executed occurrences`

with pairwise-disjoint outcome categories and exact shard ownership. Logical completeness is a derived report, not the publication gate.

Example: inventory `{A::T, B::T}`. If A maps and B never reports, logical counting incorrectly says `{T}` is complete. Executable counting reports missing `{B::T}`. If A maps and B is positively reported skipped/non-executed, both occurrences are accounted for, while publication policy can separately decide whether that non-execution reason is admissible.

## Gradle implications

Gradle can reproduce the same ambiguity: two subprojects can compile the same binary test name, one compiled test fixture can be consumed and executed by multiple projects, `test` and `integrationTest` can include the same class, and custom JVM test suites create additional `Test` tasks/source sets. A Maven-only module qualifier would therefore create inconsistent semantics.

The canonical Gradle target should be the fully qualified task path, including project path, for example `gradle:::java-checks:test` or a structured `{buildTool:"gradle", targetId:":java-checks:test"}`. Included/composite builds need a deterministic build identity prefix rather than relying only on a local task path. Source set/suite name is useful metadata but task path is the executable route.

The current plugin wires one `smartTest` task and uses class-widened patterns, so supporting multiple tasks is a meaningful adapter change. The common model must nevertheless be target-neutral from the outset.

## Compatibility

| Model | Stored/published maps and Jenkins artifacts | CLI/selection | Maven/Gradle mapping | Classification |
| --- | --- | --- | --- | --- |
| A | unchanged v2 | unchanged logical output | unchanged; duplicates rejected | `NO_SCHEMA_CHANGE` |
| B | additive ownership alone can be read compatibly, but cannot express safe owner coverage/evidence | routing metadata required | all-owner routing; exactness needs owner keys | `NEW_SCHEMA_VERSION_REQUIRED` for correct B |
| C | v2 remains readable as legacy logical data; new artifacts use explicit new version | version-aware executable output; optional logical display | target-aware inventory, collection, join, and routing | `NEW_SCHEMA_VERSION_REQUIRED` |

Schema-v2 data must never be guessed into executable ownership. It may be used where an adapter proves a single execution target for every logical entry at the exact revision; otherwise selection falls back to full suite and remapping is required. New publishers write only the new schema once the executable pipeline is end-to-end. Storage keys may remain stable if artifact metadata/version negotiation prevents a v2 consumer from reading the new payload. Jenkins should treat identities as opaque structured values and enforce version-specific exactness.

Product effects of C: adapters should derive targets automatically from the effective Maven reactor and Gradle task graph; manual configuration is reserved for ambiguous custom executions. Reports can group by logical test but expose target-qualified rows. Maps grow roughly with repeated occurrences and their distinct edges, usually modestly and necessarily. Selection becomes more precise; diagnostics become clearer because missing/duplicate data names a target. Ordinary build semantics are preserved by routing to the same targets rather than changing projects to satisfy STP.

## Decision

Choose `MODEL_C_EXECUTABLE_IDENTITY`.

`TestIdentity` does **not** change. Introduce separate types:

```text
TestIdentity {
  className
  methodName
  parameterTypes
}

ExecutionTarget {
  buildTool
  targetId
}

ExecutableTestIdentity {
  logicalTest
  executionTarget
}
```

Recommended invariants:

1. `TestIdentity` identifies the declared logical test only and never contains a build target.
2. `ExecutionTarget` is deterministic and unique within the frozen build/revision; equality uses normalized `buildTool + targetId`.
3. Every expected runnable occurrence has exactly one `ExecutableTestIdentity`; several may share a logical test.
4. Authoritative inventory, shard assignment, coverage, evidence, and completeness are keyed by executable identity.
5. Coverage never unions across targets in authoritative storage; logical union is derived only.
6. Selection returns executable identities and adapters must execute exactly their named targets, subject to conservative build dependency expansion.
7. Every executable occurrence is accounted for exactly once by mapped, executable-unmapped, or positively proven non-executed evidence, and by exactly one shard.
8. Unknown, unstable, missing, or conflicting target ownership fails closed during mapping/publication and falls back to the full suite during selection.

For Maven, prefer a reactor-relative module path plus normalized test execution ID as canonical `targetId`; retain `groupId:artifactId` as diagnostic coordinates. Paths avoid version churn and artifact-coordinate collisions, while the execution ID distinguishes Surefire/Failsafe/custom executions. The root module uses `.`. Canonical paths must be normalized, relative, non-escaping, case-preserving, and derived from the frozen effective reactor.

For Gradle, use the fully qualified `Test` task path including project path. Composite builds add a stable included-build identity derived from Gradle's build path. Never use absolute workspace paths, ephemeral JVM IDs, display names, or CI agent names. Target stability is required for the exact revision and effective build configuration; when target topology changes, head-inventory comparison treats occurrences as added/deleted and selection remains conservative.

## SonarJava walkthrough

Inventory discovers two entries:

```text
maven:java-checks-common#surefire:test :: org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage
maven:java-checks#surefire:test        :: org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage
```

The sharder assigns each whole executable key exactly once; they may share or occupy different shards. A shard file carries target plus logical identity, so no module independently “rediscovers” ownership from a logical line.

The Maven mapping adapter groups assignments by target, invokes the corresponding module/test execution, and writes only that target's Surefire include. Collector context supplies the canonical target to the listener/projector. Each physical observation projects to its assigned executable identity.

`java-checks-common` emits a module fragment/evidence entry keyed by its executable identity; `java-checks` emits the other. Reactor aggregation sees two distinct keys, verifies each against its assignment and target binding, and retains both coverage sets. The published map contains both entries, not an unqualified union.

On a future PR, a production change intersects each executable coverage entry independently. If only the `java-checks` occurrence covered the changed class, selection returns only that executable identity. The Maven adapter invokes `java-checks` with the logical method filter. If both covered it, both targets are returned and executed. Ambiguity disappears because inventory, assignment, observation, fragment, evidence, aggregation, map, and selection all preserve the same target-qualified occurrence.

## Migration

1. Add common semantic types and canonical target rules without changing v2 readers.
2. Define a new schema and executable inventory/fragment/evidence/selection representations; retain explicit v2 codecs.
3. Make collectors receive a trusted adapter-supplied execution target and validate it against assignments.
4. Convert mapping and joins to executable exactness, then add Maven and Gradle routing.
5. Update Jenkins/CLI transport and reporting with version negotiation and logical grouping views.
6. Initially read v2 only through a single-owner compatibility gate; otherwise run full suite and request remapping. Publish the new format only after every producer and consumer in the path supports it.
7. Validate unique-owner fixtures, SonarJava dual ownership, Gradle multi-task/multi-project duplicates, missing-owner evidence, and mixed-version rejection before resuming MM-2.

## Impacted components

| Component | Size | Reason |
| --- | --- | --- |
| `smart-test-picker-common` | LARGE | semantic types, schema codecs/validation, inventory, completeness, map, selector, output, storage compatibility |
| `smart-test-picker-core` | MEDIUM | legacy JaCoCo listener identity files and target binding |
| `smart-test-picker` (Gradle adapter) | LARGE | enumerate target-scoped inventories, mapping tasks, per-task routing/filtering |
| `smart-test-picker-maven` | LARGE | reactor target derivation, target-aware assignments/fragments/evidence/aggregation/selection |
| `stp-runtime` | MEDIUM | preserve execution target during physical-to-schema projection |
| `stp-junit-adapter` | SMALL | carry trusted runtime target context; logical extraction remains |
| Jenkins plugin | MEDIUM | manifest assignments, join/publication validation, version negotiation; identities remain opaque transport data |
| CLI | MEDIUM | versioned parsing/output and target-aware selection/report presentation |
| schemas/docs | LARGE | new schema, canonical forms, invariants, compatibility and operator documentation |
| fixtures/tests | LARGE | cross-tool duplicate, routing, exactness, migration, and E2E coverage |

## Scorecard

Scores are 1 (poor) to 5 (strong); for implementation complexity, 5 means simplest to implement.

| Criterion | A | B | C |
| --- | ---: | ---: | ---: |
| Correctness | 2 | 3 | 5 |
| Safety | 5 | 3 | 5 |
| Optimization precision | 1 | 2 | 5 |
| Build-tool neutrality | 4 | 4 | 5 |
| Implementation complexity | 5 | 3 | 1 |
| Backward compatibility | 5 | 3 | 2 |
| Operational simplicity | 3 | 2 | 4 |
| Debuggability | 4 | 2 | 5 |
| Real-world Maven support | 1 | 4 | 5 |
| Real-world Gradle support | 2 | 4 | 5 |
| **Total** | **32** | **30** | **42** |

C wins on correctness, exact evidence, precision, cross-tool consistency, and faithful build behavior. Its implementation and migration cost is justified because the alternatives either reject real builds or obscure the ownership fact that safety checks need.

## Implementation slices

1. **EI-1 — semantic model and target canon.** Add/test `ExecutionTarget` and `ExecutableTestIdentity`; specify Maven and Gradle normalization. No schema integration.
2. **EI-2 — versioned artifact contracts.** Define new inventory, fragment, map, evidence, assignment, and selection schemas/codecs plus strict mixed-version behavior. Depends on EI-1.
3. **EI-3 — executable completeness and join.** Make validation, sharding exactness, aggregation, publication, and derived logical views use executable occurrences. Depends on EI-2.
4. **EI-4 — collector propagation.** Bind trusted target context through runtime/JUnit/legacy collectors and emit target-qualified fragments/evidence. Depends on EI-2; parallel with EI-3.
5. **EI-5 — Maven adapter.** Derive reactor/test-execution targets, generate executable inventory, route assignments and PR selections, and cover SonarJava fixtures. Depends on EI-3 and EI-4.
6. **EI-6 — Gradle adapter.** Derive project/`Test` task targets, generate executable inventory, route mapping and PR selections, and cover multi-task/multi-project duplicates. Depends on EI-3 and EI-4.
7. **EI-7 — Jenkins, CLI, storage compatibility.** Transport structured assignments/results, negotiate artifact versions, provide logical report grouping, and enforce v2 single-owner fallback. Depends on EI-3; integrates EI-5/EI-6 outputs.
8. **EI-8 — real-project E2E and rollout gate.** Run SonarJava and a focused Gradle duplicate topology through mapping, publication, selection, and exact execution; document upgrade/rollback. Depends on EI-5, EI-6, and EI-7.

## MM-2 impact

MM-2 remains **PAUSED**. It is ready to resume only after EI-1 through EI-5 and the relevant Jenkins/CLI join path from EI-7 are complete and focused SonarJava mapping proves two distinct executable occurrences end to end. EI-6 is required before claiming general cross-build-tool support; EI-8 is the release/adoption gate. No SonarJava build workaround should substitute for the model.
