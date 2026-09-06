# TASK 26 uncommitted-path classification

Classification is based on the starting tree at `5bf0f43c26dd3a6d16ca21036b63565e720aeddd`,
the full diffs, repository history, and TASK 22–25 documents. Each listed path or explicit glob is
disjoint, so every starting modified/untracked file has exactly one category.

| Path | Category | Why it exists | Commit? | Target commit |
| ---- | -------- | ------------- | ------- | ------------- |
| `docs/coverage-map-follow-ups.md` | TASK25_DOCUMENTATION | Consolidates 5c after schema/setup decisions | YES | Documentation consolidation |
| `docs/coverage-map-model.md` | TASK25_DOCUMENTATION | Describes schema-v2 semantic identities | YES | Schema v2 |
| `docs/coverage-map-schema.md` | CANONICAL_ARCHITECTURE_DOC | Authoritative current wire/setup contract | YES | Schema v2 |
| `docs/task22-main-vs-asm-codex-audit.md` | HISTORICAL_RESEARCH_ONLY | Compact canonical integration conclusions retained by TASK 23 policy | YES (`KEEP_CANONICAL`) | Documentation consolidation |
| `docs/task24-asm-to-schema-v1-projection.md` | TASK24_DOCUMENTATION | Historical projection decision record | YES | Projection foundation |
| `docs/task25-method-identity-and-setup-contract.md` | TASK25_DOCUMENTATION | Authoritative TASK 25 decision record | YES | Setup/integrity semantics |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/CoverageMapContract.java` | TASK25_PRODUCTION | Makes schema v2 authoritative | YES | Schema v2 |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/model/MethodIdentity.java` | TASK25_PRODUCTION | Exact descriptor-aware semantic type | YES | Schema v2 |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/model/TestCoverage.java` | TASK25_PRODUCTION | Stores semantic method identities | YES | Schema v2 |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/model/TestIdentity.java` | TASK25_PRODUCTION | Distinguishes overloaded declared tests | YES | Schema v2 |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/serialization/CoverageFragmentCodec.java` | TASK25_PRODUCTION | Reads/writes exact v2 fragment identities | YES | Schema v2 |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/serialization/CoverageMapCodec.java` | TASK25_PRODUCTION | Interns/checksums exact v2 map identities | YES | Schema v2 |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/validation/CoverageMapValidator.java` | TASK25_PRODUCTION | Validates schema v2 method semantics | YES | Schema v2 |
| `smart-test-picker-common/src/main/java/com/sap/oss/smarttestpicker/coverage/validation/ValidationCode.java` | TASK25_PRODUCTION | Adds exact-version/identity diagnostics | YES | Schema v2 |
| `smart-test-picker-common/src/test/java/com/sap/oss/smarttestpicker/coverage/CoverageMapContractTest.java` | TASK25_FIXTURE | Schema, overload, codec, checksum, and round-trip regression | YES | Schema v2 |
| `smart-test-picker-common/src/test/java/com/sap/oss/smarttestpicker/coverage/JGraphTCoverageMapV1IntegrationTest.java` | TASK25_FIXTURE | Keeps historical v1 input boundary while validating current output | YES | Schema v2 |
| `stp-runtime/build.gradle` | TASK24_PRODUCTION | Connects framework-neutral projection to common schema | YES | Projection foundation |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/AsmCoverageFragmentProjector.java` | TASK24_PRODUCTION | Projects ASM observations into fragments | YES | Projection foundation |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/CollectorIntegrity.java` | TASK24_PRODUCTION | Defines local collector health gate | YES | Projection foundation |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/FragmentProjectionConfig.java` | TASK24_PRODUCTION | Explicit revision/shard binding | YES | Projection foundation |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/FragmentProjectionResult.java` | TASK24_PRODUCTION | Returns fragment plus diagnostics | YES | Projection foundation |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/RuntimeObservation.java` | TASK24_PRODUCTION | Stable projection input snapshot | YES | Projection foundation |
| `stp-runtime/src/test/java/com/sap/oss/smarttestpicker/runtime/AsmCoverageFragmentProjectorTest.java` | TASK25_FIXTURE | TASK 24 projection plus TASK 25 overload/setup regressions | YES | Setup/integrity semantics |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/SetupDiagnostic.java` | TASK25_PRODUCTION | Typed setup uncertainty and severity | YES | Setup/integrity semantics |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/RuntimeContextService.java` | TASK25_PRODUCTION | Bounded container/thread ownership and AfterAll handling | YES | Setup/integrity semantics |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/RuntimeEventAggregator.java` | TASK25_PRODUCTION | Aggregates setup scopes/diagnostics for projection | YES | Setup/integrity semantics |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/RuntimeJsonSerializer.java` | TASK25_PRODUCTION | Preserves JUnit parameter identity in diagnostics | YES | Setup/integrity semantics |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/model/TestIdentity.java` | TASK25_PRODUCTION | Carries MethodSource parameter types | YES | Setup/integrity semantics |
| `stp-junit-adapter/src/main/java/com/sap/oss/smarttestpicker/junit/StpRuntimeTestExecutionListener.java` | TASK25_PRODUCTION | Supplies exact test/container lifecycle ownership | YES | Setup/integrity semantics |
| `stp-junit-adapter/src/test/java/com/sap/oss/smarttestpicker/junit/AdapterFixtures.java` | TASK25_FIXTURE | Overload, lifecycle, and parallel fixtures | YES | Setup/integrity semantics |
| `stp-junit-adapter/src/test/java/com/sap/oss/smarttestpicker/junit/StpRuntimeTestExecutionListenerTest.java` | TASK25_FIXTURE | Test identity/setup/parallel regression | YES | Setup/integrity semantics |
| `stp-junit-adapter/src/test/resources/golden/two-parameterized-invocations.json` | TASK25_FIXTURE | Expected parameter-signature observation | YES | Setup/integrity semantics |
| `stp-agent/build.gradle` | TASK24_PRODUCTION | Bundles fragment/schema dependencies for agent output | YES | Projection foundation |
| `stp-agent/src/main/java/com/sap/oss/smarttestpicker/agent/AgentConfiguration.java` | TASK24_PRODUCTION | Adds explicit fragment/revision/shard inputs | YES | Projection foundation |
| `stp-agent/src/main/java/com/sap/oss/smarttestpicker/agent/AgentOutputWriter.java` | TASK24_PRODUCTION | Reports fragment write failure | YES | Projection foundation |
| `stp-agent/src/main/java/com/sap/oss/smarttestpicker/agent/AgentRuntime.java` | TASK24_PRODUCTION | Projects and emits schema fragment at shutdown | YES | Projection foundation |
| `stp-agent/src/main/java/com/sap/oss/smarttestpicker/agent/StpAgent.java` | TASK24_PRODUCTION | Installs configured fragment production | YES | Projection foundation |
| `stp-agent/src/test/java/com/sap/oss/smarttestpicker/agent/AgentShellTest.java` | TASK25_FIXTURE | Real-agent schema-v2 fragment/integrity regression | YES | Setup/integrity semantics |
| `stp-agent/src/test/java/example/fixture/AgentCoverageFragmentFixtureMain.java` | TASK24_FIXTURE | Bounded real-javaagent fragment fixture | YES | Projection foundation |
| `stp-schema-v1-fixtures/task24/*.json` | TASK24_FIXTURE | Compact intermediate schema-v1 evidence | YES (`KEEP_CANONICAL`, historical label added) | Regression fixtures |
| `stp-spring-core-spike/task22/evidence/*.json` | HISTORICAL_RESEARCH_ONLY | Bulk/reproducible TASK 22 audit evidence superseded by compact docs/history | NO (`KEEP_OUT_OF_FUTURE_MAIN`) | none |

Ignored paths relevant to recent work are `.gradle/`, root/module `build/` directories, and
`stp-spring-core-spike/task19/` plus `task20/` generated build output. They are
`GENERATED_OR_TEMPORARY`, are not committed, and do not define the baseline.
