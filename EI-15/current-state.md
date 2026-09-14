# Smart Test Picker current supported state

Status date: 2026-09-14. This is the concise authority for the reviewed STP and Jenkins prototype. Historical task reports remain evidence for the revisions they name; they are not universal compatibility claims.

## Production workflows

The normal mapping API is `stpPrepareCoverageMapping` → one `stpCoverageMap` call per manifest shard → `stpPublishCoverageMap`. Preparation freezes the checked-out revision and authoritative inventory, collection binds every fragment and evidence artifact to revision/shard/target, and publication restores every expected stash, validates before joining, then writes an immutable revision object before advancing the branch pointer.

The normal inventory-producing PR API is `stpLookupCoverageMap` → `stpPrSelect` → `smartTestPicker`. For both Gradle and Maven, `stpPrSelect` runs authoritative revision-bound inventory discovery before invoking the production selector; Maven uses `generate-reactor-head-test-inventory` with schema 3 and the frozen head revision. GitHub Branch Source `SOURCE_HEAD` is the supported automatic provider path. The explicit `stpHeadInventory`/`stpExplicitPrSelect` path remains a low-level revision-supplied alternative.

Direct `smartTestPicker` selector invocation with only a map/revisions has no authoritative head inventory and conservatively produces `RUN_ALL`; that is not selective success. `smartTestPicker(plan: ...)` instead executes an explicitly supplied plan without selecting or generating inventory, and the caller owns that plan's provenance.

`SELECTED` runs the enforceable selected set; `NONE` creates an empty `SELECT` plan and must run no tests; `FULL_SUITE` becomes `RUN_ALL` and removes restrictive STP filters. Unknown selector failures cannot yield a partial safe plan. Integrity, revision, configuration, security, and mapping-completeness failures fail the build. Availability failures may run the full suite only through the central policy and only where full-suite execution is known safe.

## Contract table

| Format | Producer | Consumer | Supported version | Validation and compatibility boundary |
|---|---|---|---:|---|
| legacy unversioned map/indexed map | legacy CLI/plugin generators | legacy `CoverageMapReader` tools | unversioned only | Structural legacy reader. A declared `schemaVersion` is rejected; no guessed conversion. |
| logical coverage fragment/map | Gradle ASM/JaCoCo and Maven JaCoCo logical paths; joiner | schema-v2 codecs, selectors, storage | 2 | Exact equality, checksum, lifecycle and completeness validation. Schema 1/3 is rejected. |
| executable inventory | Gradle/Maven discovery | Jenkins assignment planner and adapters | 1, containing map schema 3 identity | Exact version/revision/target validation. |
| executable assignment | Jenkins planner | Gradle/Maven mapping adapters | 1, containing map schema 3 identity | Exact version/revision/shard/target and inventory-membership validation before mapping body. |
| executable fragment/map | target-qualified collectors/joiner | executable codecs, Jenkins mapping/storage, explicit selector bridge | 3 | Exact schema equality and executable completeness. The explicit selector deliberately projects target-qualified observations to a union of logical test coverage; this is the only supported v3→logical selection projection, not a general downgrade or reserialization. |
| execution evidence | packaged JUnit listener/adapters | mapping worker and join | 1 | Exact revision/shard/target/tool/assignment reconciliation. Executed wins over non-executed invocations of one declaration. |
| mapping manifest | Jenkins preparation | collection and publication steps | 1–3 | Manifest version selects its exact fields; v3 requires mapping schema 3 and inventory/assignment version 1. |
| execution plan | selector bridge/Jenkins sharder | packaged Gradle/Maven execution adapters | 1 | Adapter contract 1 plus exact plan version. Unsupported versions fail before the body. |
| latest pointer | storage runtime | FILE/RAW_HTTP lookup | 1 | Exact fields plus project/branch/revision/map SHA-256. Missing pointer is distinct from missing/corrupt target. |

`CoverageMapContract.SCHEMA_VERSION` is the historical public alias for logical schema 2; `SCHEMA_V2`, `SCHEMA_V3`, and `LATEST_SCHEMA` make the two current wire families explicit. It is not an artifact version. Independent plugin/adapter/manifest versions are allowed to differ.

## Component compatibility matrix

| Component | Coordinates/version and provenance | Java | Wire contracts | Packaging/integrity | Evidence |
|---|---|---:|---|---|---|
| common | `com.sap.oss.smart-test-picker:smart-test-picker-common:0.2.0`, current STP revision | 17+ | logical 2, executable 3, inventory/assignment/evidence 1 | normal JAR; HPI bundles the pinned Maven dependency | STP full suite; HPI enforcer/tests |
| core/JaCoCo listener | `smart-test-picker-core:0.1.0`, current STP revision | 17+ | logical coverage/evidence | JAR; JUnit APIs compile-only | STP suite; EI-14 abort reconciliation |
| Gradle plugin | `com.sap.oss.smart-test-picker:0.1.0`, current STP revision | 17+ | logical 2 and executable 3 | Gradle module/plugin marker; functional repository bootstrap | Gradle TestKit suite; PetClinic/Spring historical evidence |
| Maven plugin | `smart-test-picker-maven:0.1.0`, current STP revision | 17+ | logical 2 and executable 3 | Maven plugin JAR; reactor functional bootstrap | Maven functional suite; EI-13/EI-14 Commons Statistics |
| CLI | `smart-test-picker-cli:0.2.0`, current STP revision | 17+ | versioned selection plus unversioned legacy commands | shadow JAR; services merged | CLI/common tests and packaged ingress check |
| ASM agent | `stp-agent:0.1.0`, current STP revision | 17+ | logical/executable collector output | shadow JAR; ASM relocated; JUnit listener/service bundled | agent/runtime/JUnit suites |
| runtime/JUnit integration | internal, current STP revision | 17+ | runtime ownership/evidence | only bundled with agent; not published separately | focused lifecycle/context tests |
| Jenkins HPI | `io.github.smarttestpicker:smart-test-picker:1.0-SNAPSHOT`, Jenkins revision | Jenkins 2.516.2; tested JDK 21; bytecode 17 | all rows above; adapter/plan contract 1; automatic Gradle/Maven inventory in `stpPrSelect` | HPI contains generated manifest/checksums and `WEB-INF/licenses.xml` | 96 Jenkins tests, HPI build, complete Maven production-workflow smoke |
| selector bundled in HPI | CLI 0.2.0, STP `94c48ef12417b3157fd64615ed71aa58bda070c6` | 17+ | logical 2, executable 3 explicit projection, plan 1 | SHA-256 in generated HPI manifest | packaged checksum/provenance tests |
| Maven execution adapter | 0.1.0, Jenkins source tree | Java 8+ in Maven host | plan 1, adapter 1 | shaded Jackson; fixed manifest metadata; reproducible SHA-256 | adapter verifier and HPI build |
| Gradle execution adapter | 0.1.0, Jenkins source tree | Gradle host JVM | plan 1, adapter 1 | init script with generated SHA-256 | HPI packaging/cache tests |

These are compatible only as the pinned producer/consumer set packaged and tested together. A matching number alone is insufficient; source provenance and checksum are part of the identity. Fresh builds must use an initially empty isolated Gradle/Maven cache, publish unpublished STP dependencies into an isolated functional repository, then build the HPI from that repository. Machine-global `mavenLocal()` is not release provenance.

## Collector, lifecycle, and execution semantics

Gradle mapping supports ASM (default) and JaCoCo; Maven mapping supports JaCoCo and rejects ASM. JUnit Platform is the production lifecycle bridge. JUnit 4 is supported only through the Vintage engine in the tested Platform configuration. The retained builds exercise JUnit Platform 1.9.3/5.9.3 and 6.1.3 libraries in their stated modules, and Maven Surefire/Failsafe 3.5.3 in focused fixtures. This is not a claim for every JUnit 5/6 or build-tool version.

Disabled/skipped declarations and aborted-only declarations are non-executed and are never published as mapped. A successful or failed invocation is executed; an aborted sibling invocation does not erase its valid coverage. Per-test snapshots are reset so coverage cannot leak forward. `BeforeEach`/`AfterEach` work falls in the leaf interval. Bounded container setup is represented by setup scopes; unattributable shared work makes a fragment incomplete instead of guessing. `AfterAll` and late work cannot be attached to the last leaf.

Coverage identity retains declared signatures and executable target. Gradle filters at target plus class/method granularity. Maven Surefire/Failsafe cannot enforce declared overload descriptors, so selected signatures collapse to target + class + base method and reports are reconciled at that enforceable identity. Parameterized invocation display names remain report evidence, not selector identities. Identical classes/tests in different modules or tasks remain distinct executable identities.

The Gradle suite covers multiple target routing, empty tasks, custom task ownership, included-build rejection, configuration cache in retained scenarios, and ASM's `maxParallelForks=1` guardrail. EI-13 proves a specific complete Maven reactor with modules without assigned tests, prerequisite builds, profiles, Surefire routing, overload collapse, and three independent agents. Arbitrary Maven executions/profiles and arbitrary Gradle parallel/composite topologies are not inferred from those runs.

## Publication, determinism, context, and resources

FILE uses same-host locking and atomic replacement. RAW_HTTP creates immutable objects and uses one-controller JVM writer serialization; a remote PUT is not described as an atomic filesystem move. Multi-controller/external-writer compare-and-swap is unsupported. Repeated unequal publication conflicts; an equal immutable object is idempotent. Validation precedes pointer update, and pointer checksum/target corruption is an integrity failure. Agent caches are keyed by type/version/SHA-256, revalidate hits, replace corruption, and keep different versions/checksums separate.

Logical/executable inventories, assignments, fragments, evidence, maps and execution plans promise canonical semantic ordering. Versioned map codecs and the Maven execution-adapter build promise byte-identical output for identical semantic input and pinned build metadata. `generatedAt`, runtime/JDK provenance, HPI build-JDK metadata, logs, and other operational timestamps are intentionally variable; comparisons that exclude them must say so. Manifests are canonical for the same frozen inputs.

ASM context propagation supports instrumented raw/virtual Thread entry points, standard Executor/ExecutorService and scheduled overloads, executor-style ForkJoinPool overloads, and selected CompletableFuture stages. Direct ForkJoinTask operations, reflection/method-handle submission, overridden `Thread.run`, pre-instrumentation callers, reactive/request frameworks, and arbitrary work completing after its owner are unsupported. Unsupported or late events are quarantined/integrity-reported and cannot become another test's coverage.

The retained EI-14 schema-v3 map is 4,158,128 bytes with 1,905 mapped executable identities and 88 evidenced non-executed declarations (1,993 inventory identities). EI-15 resource measurements are recorded in `verification-report.md`; they are representative checks, not a broad benchmark.

## Evidence and limitations

EI-13 proves its exact Commons Statistics Maven 3.9.16/JDK 21/Surefire scenario, including independent expected/actual reconciliation and NONE. EI-14 replaces only the aborted-invocation accounting and unknown-target boundary, and retains EI-13 evidence for unchanged discovery, sharding, selection, storage, and project tests. Current unit/functional gates validate changed local artifacts; no new full Spring or SonarJava run is claimed.

Unsupported boundaries include Maven+ASM, Gradle included/composite builds for executable mapping, arbitrary parallel test forks, automatic SCM providers other than GitHub Branch Source, synthetic-merge selective inventory, multi-controller publication, remote backends other than RAW_HTTP, direct reactive/reflection context propagation, release signing/publishing, and universal JUnit/build-tool compatibility.

EI-15 completed clean initially-empty-cache builds for both repositories and the Java-21 packaged Docker smoke. Its maintained Maven multimodule scenario runs R0 mapping/publication and R1 lookup/automatic inventory/selection/execution through all six production steps; expected, selected, and raw executed are exactly `com.example.modulea.FooTest#foo`, while `FooTest#notSelected` is excluded. A controlled `SCMRevisionAction` supplies PR metadata; GitHub webhook delivery and PR discovery are outside this proof. Direct selector mode still runs the full suite without authoritative inventory, while explicitly supplied plans remain supported. Release prerequisites still unmet are signing/distribution and the Jenkins LICENSE/NOTICE disposition. EI-15 does not publish artifacts.
