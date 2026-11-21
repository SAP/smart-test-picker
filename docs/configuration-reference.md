# Configuration reference

These tables cover the shipped command and configuration surface. IDs identify the tool, goal or
command, and parameter; they are documentation keys, not additional command-line flags. Maven
properties use `-D`, parameters without a property use the goal's POM `<configuration>`, and Gradle
properties belong to `smartTestPicker { ... }`. Read-only injected Maven values are listed explicitly
rather than silently excluded. An unset value is not necessarily optional: goal validation can require it.

Schema 3 is the executable, target-qualified workflow. Schema 2 is logical identity coverage. `legacy`
means the unversioned JaCoCo map/report flow, not the compact v3 format. Inventory/assignment files
have their own version 1 even when used with schema 3. See the [workflow](../README.md),
[Gradle details](../smart-test-picker/README.md), [Maven details](../smart-test-picker-maven/README.md),
and [CLI join contract](join-fragments-cli.md).

The defaults below describe normal invocation; prepared mapping overrides target-local output paths.
No public parameters are omitted from these tables. Configuration reference coverage is checked against
the Java sources and generated Maven help descriptor by `verifyReadinessDocumentation`.

## Maven goals and parameters

| ID | Default | Meaning | Schema |
| --- | --- | --- | --- |
| `maven:aggregate-reactor-coverage-fragment` | not applicable | Validates module/target-local schema-v2/v3 results and produces one shard result. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:evidenceOutput` | unset | Execution-evidence output path, separate from the coverage fragment. User property: `smartTestPicker.evidenceOutput`. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:fragmentOutput` | unset | Accepted coverage fragment output path; not a published coverage map. User property: `smartTestPicker.fragmentOutput`. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:project` | ${project} | Read-only Maven injection: current Maven project. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:revision` | unset; required for mapping | Exact source revision to which the assignment and fragment must be bound. User property: `smartTestPicker.revision`. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:schemaVersion` | 2 | Select the logical schema 2 or executable schema 3 path supported by this goal. User property: `smartTestPicker.schemaVersion`. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:shardAssignments` | ${env.STP_MAPPING_TESTS_FILE} | Revision-bound executable shard assignment used for aggregation accounting. User property: `smartTestPicker.testsFile`. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:shardId` | unset; required for mapping | Shard identifier that must match the assignment and all collected fragments. User property: `smartTestPicker.shardId`. | 2, 3 |
| `maven:aggregate-reactor-coverage-fragment:testTarget` | test | Legacy test-target label or execution target supplied by orchestration. User property: `smartTestPicker.testTarget`. | 2, 3 |
| `maven:generate-coverage-fragment` | not applicable | Converts JaCoCo sessions or validates and finalizes ASM fragments, including audited assignment absorption. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:assignmentFile` | ${env.STP_MAPPING_TESTS_FILE} | Revision-bound executable shard assignment; preparation may only narrow each target's effective selection. User property: `smartTestPicker.testsFile`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:coverageCollector` | JACOCO | Choose ASM or JACOCO for explicitly activated collection; Maven defaults to JACOCO. User property: `smartTestPicker.coverageCollector`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:evidenceOutput` | ${project.build.directory}/execution-evidence-v1.json | Execution-evidence output path, separate from the coverage fragment. User property: `smartTestPicker.evidenceOutput`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:execDir` | ${project.build.directory}/jacoco | Directory containing per-test JaCoCo session execution data. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:executionTarget` | unset | Canonical build execution target that owns the fragment's test identities. User property: `smartTestPicker.executionTarget`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:failsafeReportsDir` | ${project.build.directory}/failsafe-reports | Failsafe XML outcome reports used when finalizing collected coverage. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:fragmentOutput` | ${project.build.directory}/coverage-fragment-v2.json | Accepted coverage fragment output path; not a published coverage map. User property: `smartTestPicker.fragmentOutput`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:moduleDiagnosticOutput` | unset | Prepared ASM agent diagnostic path for one module/target. User property: `smartTestPicker.moduleDiagnosticOutput`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:moduleEvidenceOutput` | unset | Prepared execution-evidence path for one module/target. User property: `smartTestPicker.moduleEvidenceOutput`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:moduleFragmentOutput` | unset | Prepared coverage fragment path for one module/target. User property: `smartTestPicker.moduleFragmentOutput`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:reportsDir` | ${project.build.directory}/jacoco-xml | Directory of JaCoCo XML coverage reports consumed by this goal. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:revision` | unset; required for mapping | Exact source revision to which the assignment and fragment must be bound. User property: `smartTestPicker.revision`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:schemaVersion` | 2 | Select the logical schema 2 or executable schema 3 path supported by this goal. User property: `smartTestPicker.schemaVersion`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:shardId` | unset; required for mapping | Shard identifier that must match the assignment and all collected fragments. User property: `smartTestPicker.shardId`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:surefireReportsDir` | ${project.build.directory}/surefire-reports | Surefire XML outcome reports used when finalizing collected coverage. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-fragment:testTarget` | test | Legacy test-target label or execution target supplied by orchestration. User property: `smartTestPicker.testTarget`. | 2, 3 (ASM only 3) |
| `maven:generate-coverage-map` | not applicable | Maven Mojo that generates the JSON coverage map from per-test JaCoCo XML reports. Equivalent of the Gradle generateTestCoverageJson task. Reads per-test XML reports produced by JaCoCo and writes a unified JSON coverage map with metadata. Usage: mvn smart-test-picker:generate-coverage-map | legacy |
| `maven:generate-coverage-map:baseBranch` | main | Git base branch used by the legacy change-detection and storage flow. User property: `smartTestPicker.baseBranch`. | legacy |
| `maven:generate-coverage-map:outputFile` | ${project.build.directory}/test-coverage-map.json | Destination of this goal's generated or downloaded artifact. | legacy |
| `maven:generate-coverage-map:projectDir` | ${project.basedir} | Read-only Maven injection: current module base directory. | legacy |
| `maven:generate-coverage-map:reportsDir` | ${project.build.directory}/jacoco-xml | Directory of JaCoCo XML coverage reports consumed by this goal. | legacy |
| `maven:generate-head-test-inventory` | not applicable | Generates the exact schema-v2 inventory using JUnit Platform discovery only. | 2 |
| `maven:generate-head-test-inventory:outputFile` | ${project.build.directory}/head-test-inventory.json | Destination of this goal's generated or downloaded artifact. | 2 |
| `maven:generate-head-test-inventory:prHeadRevision` | unset; inventory uses checked-out HEAD, explicit selection requires a full commit | Expected full head revision for inventory or explicit PR selection. User property: `smartTestPicker.prHeadRevision`. | 2 |
| `maven:generate-head-test-inventory:project` | ${project} | Read-only Maven injection: current Maven project. | 2 |
| `maven:generate-reactor-head-test-inventory` | not applicable | Generates a revision-bound logical schema-v2 or executable schema-v3 inventory for the effective Maven reactor. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:executionId` | unset (provider default execution) | Provider execution ID used to distinguish a qualified Maven execution target. User property: `smartTestPicker.executionId`. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:executionProfile` | unset (no profile qualifier) | Profile qualifier in the canonical Maven execution target; does not replace Maven profile activation. User property: `smartTestPicker.executionProfile`. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:executionType` | unset (unqualified Surefire target) | Provider kind, surefire or failsafe, used for target resolution and effective test selection. User property: `smartTestPicker.executionType`. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:outputFile` | ${session.executionRootDirectory}/.stp/head-test-inventory.json | Destination of this goal's generated or downloaded artifact. User property: `smartTestPicker.outputFile`. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:prHeadRevision` | unset; inventory uses checked-out HEAD, explicit selection requires a full commit | Expected full head revision for inventory or explicit PR selection. User property: `smartTestPicker.prHeadRevision`. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:project` | ${project} | Read-only Maven injection: current Maven project. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:reactorRoot` | Maven execution root | Canonical reactor root used to derive stable module-relative execution targets. User property: `smartTestPicker.reactorRoot`. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:schemaVersion` | 2 | Select the logical schema 2 or executable schema 3 path supported by this goal. User property: `smartTestPicker.schemaVersion`. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:session` | ${session} | Read-only Maven injection: current Maven session. | 2, 3 |
| `maven:generate-reactor-head-test-inventory:testFilter` | unset (no additional narrowing) | Additional narrowing filter applied after the provider's effective test selection. User property: `smartTestPicker.testFilter`. | 2, 3 |
| `maven:generate-report` | not applicable | Maven Mojo that generates an HTML dashboard report of test selection results. Equivalent of the Gradle generateTestReport task. Reads coverage map and selected tests, produces a self-contained HTML dashboard with stat cards, donut charts, coverage matrix, and source pages. Usage: mvn smart-test-picker:generate-report | legacy |
| `maven:generate-report:classLevelSelection` | false | Expand selection to whole test classes instead of individual methods. User property: `smartTestPicker.classLevelSelection`. | legacy |
| `maven:generate-report:coverageMapFile` | ${project.build.directory}/test-coverage-map.json | Coverage map input used for selection or report generation. | legacy |
| `maven:generate-report:jacocoXmlDir` | ${project.build.directory}/jacoco-xml | Directory containing JaCoCo XML data used by the report. | legacy |
| `maven:generate-report:maxCommitDistance` | 500 | Maximum accepted distance from map revision to head before conservative full-suite selection. User property: `smartTestPicker.maxCommitDistance`. | legacy |
| `maven:generate-report:projectDir` | ${project.basedir} | Read-only Maven injection: current module base directory. | legacy |
| `maven:generate-report:reportFile` | ${project.build.directory}/reports/smart-test-picker/index.html | Destination of the generated HTML report. | legacy |
| `maven:generate-report:selectedTestsFile` | ${project.build.directory}/selected-tests.json | Selected-test JSON output or input, according to the selection/report goal. | legacy |
| `maven:generate-report:sourceDir` | ${project.basedir}/src/main/java | Production source directory used to render source coverage reports. | legacy |
| `maven:generate-reports` | not applicable | Maven Mojo that converts per-test JaCoCo .exec files into XML reports. Equivalent of the Gradle generateSmartReports task. Scans for session_*.exec files produced by the per-test JaCoCo instrumentation and generates one XML report per test. Usage: mvn smart-test-picker:generate-reports | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:classesDir` | ${project.build.outputDirectory} | Compiled production classes directory. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:coverageCollector` | JACOCO | Choose ASM or JACOCO for explicitly activated collection; Maven defaults to JACOCO. User property: `smartTestPicker.coverageCollector`. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:execDir` | ${project.build.directory}/jacoco | Directory containing per-test session_*.exec files. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:reportDir` | ${project.build.directory}/jacoco-xml | Output directory for generated XML reports. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:schemaVersion` | 2 | Select the logical schema 2 or executable schema 3 path supported by this goal. User property: `smartTestPicker.schemaVersion`. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:session` | ${session} | Read-only Maven injection: current Maven session. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:sourceDir` | ${project.basedir}/src/main/java | Source directory for source file references in XML reports. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:generate-reports:threads` | 0 | Number of parallel threads for report generation. User property: `smartTestPicker.threads`. | legacy, 2, 3 (JACOCO; skips ASM) |
| `maven:help` | not applicable | Display help information on smart-test-picker-maven. Call mvn ${goalPrefix}:help -Ddetail=true -Dgoal= to display parameter details. | legacy, 2, 3 |
| `maven:help:detail` | false | If true, display all settable properties for each goal. | legacy, 2, 3 |
| `maven:help:goal` | unset | The name of the goal for which to show help. If unspecified, all goals will be displayed. | legacy, 2, 3 |
| `maven:help:indentSize` | 2 | The number of spaces per indentation level, should be positive. | legacy, 2, 3 |
| `maven:help:lineLength` | 80 | The maximum length of a display line, should be positive. | legacy, 2, 3 |
| `maven:merge-coverage-maps` | not applicable | Aggregator Mojo that merges per-module coverage maps into a single unified map. Runs once on the root project after all modules have completed their verify phase. Scans reactor modules for target/test-coverage-map.json, merges all test mappings into one map, and writes the result to the root project's target/test-coverage-map.json. The merged map uses the metadata (commitId, baseBranch, timestamp) from the first module that has one \u2014 all modules share the same commit context. | legacy |
| `maven:merge-coverage-maps:outputFile` | ${project.build.directory}/test-coverage-map.json | Output file for the merged coverage map. | legacy |
| `maven:merge-coverage-maps:project` | ${project} | Read-only Maven injection: current Maven project. | legacy |
| `maven:merge-coverage-maps:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | legacy |
| `maven:merge-executable-head-test-inventories` | not applicable | Combines revision-compatible executable inventories and rejects duplicate target-qualified identities. | 3 |
| `maven:merge-executable-head-test-inventories:inputFiles` | unset | Comma-separated executable head inventory files; incompatible revisions or duplicate owners are rejected. User property: `smartTestPicker.inputFiles`. | 3 |
| `maven:merge-executable-head-test-inventories:outputFile` | ${session.executionRootDirectory}/target/head-test-inventory.json | Destination of this goal's generated or downloaded artifact. User property: `smartTestPicker.outputFile`. | 3 |
| `maven:merge-executable-target-fragments` | not applicable | Joins independently invoked Maven test-execution targets into one exact Jenkins shard result. | 3 |
| `maven:merge-executable-target-fragments:assignmentFile` | ${env.STP_MAPPING_TESTS_FILE} | Revision-bound executable shard assignment; preparation may only narrow each target's effective selection. User property: `smartTestPicker.testsFile`. | 3 |
| `maven:merge-executable-target-fragments:evidenceFiles` | unset | Comma-separated input evidence paths for merging executable target fragments. User property: `smartTestPicker.evidenceFiles`. | 3 |
| `maven:merge-executable-target-fragments:evidenceOutput` | unset | Execution-evidence output path, separate from the coverage fragment. User property: `smartTestPicker.evidenceOutput`. | 3 |
| `maven:merge-executable-target-fragments:fragmentFiles` | unset | Comma-separated target fragment input paths to merge. User property: `smartTestPicker.fragmentFiles`. | 3 |
| `maven:merge-executable-target-fragments:fragmentOutput` | unset | Accepted coverage fragment output path; not a published coverage map. User property: `smartTestPicker.fragmentOutput`. | 3 |
| `maven:merge-executable-target-fragments:revision` | unset; required for mapping | Exact source revision to which the assignment and fragment must be bound. User property: `smartTestPicker.revision`. | 3 |
| `maven:merge-executable-target-fragments:shardId` | unset; required for mapping | Shard identifier that must match the assignment and all collected fragments. User property: `smartTestPicker.shardId`. | 3 |
| `maven:merge-executable-target-fragments:testTarget` | ${env.STP_MAPPING_TEST_TARGET} | Legacy test-target label or execution target supplied by orchestration. User property: `smartTestPicker.testTarget`. | 3 |
| `maven:merge-test-metrics` | not applicable | Aggregator Mojo that merges per-module test metrics into a single unified JSON. Runs once on the last reactor module after all modules have completed their verify phase. Collects target/smart-test-metrics.json from each module, adds run metadata (commitId, branch, environment), and writes the merged result to the root project's target/smart-test-metrics.json. | legacy |
| `maven:merge-test-metrics:baseBranch` | main | Git base branch used by the legacy change-detection and storage flow. User property: `smartTestPicker.baseBranch`. | legacy |
| `maven:merge-test-metrics:project` | ${project} | Read-only Maven injection: current Maven project. | legacy |
| `maven:merge-test-metrics:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | legacy |
| `maven:prepare-reactor-executable-mapping` | not applicable | Validates and partitions a schema-v3 assignment before Maven test execution. | 3 |
| `maven:prepare-reactor-executable-mapping:aggregateEvidenceOutput` | unset | Destination for the combined reactor execution evidence. User property: `smartTestPicker.evidenceOutput`. | 3 |
| `maven:prepare-reactor-executable-mapping:aggregateOutput` | unset | Destination for the accepted reactor shard fragment; stale output is invalidated before preparation. User property: `smartTestPicker.fragmentOutput`. | 3 |
| `maven:prepare-reactor-executable-mapping:asmExcludes` | unset (no additional exclusions) | Comma-separated package prefixes excluded from ASM instrumentation within the production boundary. User property: `smartTestPicker.asm.excludes`. | 3 |
| `maven:prepare-reactor-executable-mapping:asmIncludes` | unset (derive from compiled production sources) | Override for derived ASM package prefixes; explicit blank or malformed values are rejected. User property: `smartTestPicker.asm.includes`. | 3 |
| `maven:prepare-reactor-executable-mapping:assignmentFile` | ${env.STP_MAPPING_TESTS_FILE} | Revision-bound executable shard assignment; preparation may only narrow each target's effective selection. User property: `smartTestPicker.testsFile`. | 3 |
| `maven:prepare-reactor-executable-mapping:completeInventoryFile` | unset (no complete external inventory) | Complete revision-bound executable inventory used to validate a narrowed reactor invocation. User property: `smartTestPicker.completeInventoryFile`. | 3 |
| `maven:prepare-reactor-executable-mapping:executionId` | unset (provider default execution) | Provider execution ID used to distinguish a qualified Maven execution target. User property: `smartTestPicker.executionId`. | 3 |
| `maven:prepare-reactor-executable-mapping:executionProfile` | unset (no profile qualifier) | Profile qualifier in the canonical Maven execution target; does not replace Maven profile activation. User property: `smartTestPicker.executionProfile`. | 3 |
| `maven:prepare-reactor-executable-mapping:executionType` | unset (unqualified Surefire target) | Provider kind, surefire or failsafe, used for target resolution and effective test selection. User property: `smartTestPicker.executionType`. | 3 |
| `maven:prepare-reactor-executable-mapping:expectedCollector` | ${env.STP_MAPPING_COLLECTOR} | Read-only Maven injection: orchestrator's frozen collector; nonblank mismatch rejects preparation. | 3 |
| `maven:prepare-reactor-executable-mapping:pluginDescriptor` | ${plugin} | Read-only Maven injection: running plugin metadata. | 3 |
| `maven:prepare-reactor-executable-mapping:project` | ${project} | Read-only Maven injection: current Maven project. | 3 |
| `maven:prepare-reactor-executable-mapping:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | 3 |
| `maven:prepare-reactor-executable-mapping:reactorRoot` | Maven execution root | Canonical reactor root used to derive stable module-relative execution targets. User property: `smartTestPicker.reactorRoot`. | 3 |
| `maven:prepare-reactor-executable-mapping:revision` | unset; required for mapping | Exact source revision to which the assignment and fragment must be bound. User property: `smartTestPicker.revision`. | 3 |
| `maven:prepare-reactor-executable-mapping:session` | ${session} | Read-only Maven injection: current Maven session. | 3 |
| `maven:prepare-reactor-executable-mapping:shardId` | unset; required for mapping | Shard identifier that must match the assignment and all collected fragments. User property: `smartTestPicker.shardId`. | 3 |
| `maven:prepare-reactor-executable-mapping:testFilter` | unset (no additional narrowing) | Additional narrowing filter applied after the provider's effective test selection. User property: `smartTestPicker.testFilter`. | 3 |
| `maven:pull-map` | not applicable | Downloads map bytes from the remote HTTP store; HTTP 404 is not an error. | legacy, 2, 3 (byte transport) |
| `maven:pull-map:baseBranch` | main | Git base branch used by the legacy change-detection and storage flow. User property: `smartTestPicker.baseBranch`. | legacy, 2, 3 (byte transport) |
| `maven:pull-map:outputFile` | ${project.build.directory}/test-coverage-map.json | Destination of this goal's generated or downloaded artifact. User property: `smartTestPicker.outputFile`. | legacy, 2, 3 (byte transport) |
| `maven:pull-map:remotePassword` | unset | HTTP Basic Auth password for the remote store; supply credentials externally rather than in source. User property: `smartTestPicker.remotePassword`. | legacy, 2, 3 (byte transport) |
| `maven:pull-map:remoteUrl` | unset | Remote store base URL; the transport stores coverage-map bytes by base branch. User property: `smartTestPicker.remoteUrl`. | legacy, 2, 3 (byte transport) |
| `maven:pull-map:remoteUser` | unset | HTTP Basic Auth username for the remote store. User property: `smartTestPicker.remoteUser`. | legacy, 2, 3 (byte transport) |
| `maven:push-map` | not applicable | Uploads map bytes to the configured remote HTTP store. | legacy, 2, 3 (byte transport) |
| `maven:push-map:baseBranch` | main | Git base branch used by the legacy change-detection and storage flow. User property: `smartTestPicker.baseBranch`. | legacy, 2, 3 (byte transport) |
| `maven:push-map:inputFile` | ${project.build.directory}/test-coverage-map.json | Coverage-map bytes to upload to the configured remote store. User property: `smartTestPicker.inputFile`. | legacy, 2, 3 (byte transport) |
| `maven:push-map:remotePassword` | unset | HTTP Basic Auth password for the remote store; supply credentials externally rather than in source. User property: `smartTestPicker.remotePassword`. | legacy, 2, 3 (byte transport) |
| `maven:push-map:remoteUrl` | unset | Remote store base URL; the transport stores coverage-map bytes by base branch. User property: `smartTestPicker.remoteUrl`. | legacy, 2, 3 (byte transport) |
| `maven:push-map:remoteUser` | unset | HTTP Basic Auth username for the remote store. User property: `smartTestPicker.remoteUser`. | legacy, 2, 3 (byte transport) |
| `maven:root-generate-report` | not applicable | Aggregator Mojo that merges per-module coverage maps and generates the HTML report. Combines the work of merge-coverage-maps and the old root-generate-report into a single aggregator invocation. Scans reactor modules for per-module coverage maps, merges them, then generates the unified HTML dashboard. | legacy |
| `maven:root-generate-report:classLevelSelection` | false | Expand selection to whole test classes instead of individual methods. User property: `smartTestPicker.classLevelSelection`. | legacy |
| `maven:root-generate-report:maxCommitDistance` | 500 | Maximum accepted distance from map revision to head before conservative full-suite selection. User property: `smartTestPicker.maxCommitDistance`. | legacy |
| `maven:root-generate-report:project` | ${project} | Read-only Maven injection: current Maven project. | legacy |
| `maven:root-generate-report:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | legacy |
| `maven:root-select-tests` | not applicable | Aggregator Mojo that runs test selection on the merged coverage map at the root level and writes Surefire includes files to each reactor module. Uses the merged test-coverage-map.json from the root project's target directory (produced by a prior baseline run with smart-test-picker profile). Writes selected-tests.json to root target plus a per-module selected-tests-surefire.txt so Surefire filters tests automatically. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:classLevelSelection` | false | Expand selection to whole test classes instead of individual methods. User property: `smartTestPicker.classLevelSelection`. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:fullSuiteTriggers` | unset (no triggers) | File glob patterns whose changed paths force a full test suite. User property: `smartTestPicker.fullSuiteTriggers`. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:integrationRevision` | unset; explicit PR mode is disabled unless configured | Frozen full integration commit for explicit PR selection; supply all three explicit revisions together. User property: `smartTestPicker.integrationRevision`. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:maxCommitDistance` | 500 | Maximum accepted distance from map revision to head before conservative full-suite selection. User property: `smartTestPicker.maxCommitDistance`. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:prBaseRevision` | unset; required with explicit PR selection | Frozen full PR base commit for explicit PR selection. User property: `smartTestPicker.prBaseRevision`. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:prHeadRevision` | unset; inventory uses checked-out HEAD, explicit selection requires a full commit | Expected full head revision for inventory or explicit PR selection. User property: `smartTestPicker.prHeadRevision`. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:project` | ${project} | Read-only Maven injection: current Maven project. | 2; 3 via explicit PR flow |
| `maven:root-select-tests:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | 2; 3 via explicit PR flow |
| `maven:select-tests` | not applicable | Maven Mojo that selects tests impacted by code changes. Equivalent of the Gradle selectTests task. Reads the coverage map, detects git changes, and writes selected-tests.json plus a Surefire-compatible includes file. Usage: mvn smart-test-picker:select-tests | 2; 3 via explicit PR flow |
| `maven:select-tests:classLevelSelection` | false | Expand selection to whole test classes instead of individual methods. User property: `smartTestPicker.classLevelSelection`. | 2; 3 via explicit PR flow |
| `maven:select-tests:coverageMapFile` | ${project.build.directory}/test-coverage-map.json | Coverage map input used for selection or report generation. | 2; 3 via explicit PR flow |
| `maven:select-tests:fullSuiteTriggers` | unset (no triggers) | File glob patterns whose changed paths force a full test suite. User property: `smartTestPicker.fullSuiteTriggers`. | 2; 3 via explicit PR flow |
| `maven:select-tests:headTestInventoryFile` | ${project.build.directory}/head-test-inventory.json | Revision-bound inventory of runnable head tests used during selection. | 2; 3 via explicit PR flow |
| `maven:select-tests:integrationRevision` | unset; explicit PR mode is disabled unless configured | Frozen full integration commit for explicit PR selection; supply all three explicit revisions together. User property: `smartTestPicker.integrationRevision`. | 2; 3 via explicit PR flow |
| `maven:select-tests:mavenProject` | ${project} | Read-only Maven injection: current Maven project. | 2; 3 via explicit PR flow |
| `maven:select-tests:maxCommitDistance` | 500 | Maximum accepted distance from map revision to head before conservative full-suite selection. User property: `smartTestPicker.maxCommitDistance`. | 2; 3 via explicit PR flow |
| `maven:select-tests:prBaseRevision` | unset; required with explicit PR selection | Frozen full PR base commit for explicit PR selection. User property: `smartTestPicker.prBaseRevision`. | 2; 3 via explicit PR flow |
| `maven:select-tests:prHeadRevision` | unset; inventory uses checked-out HEAD, explicit selection requires a full commit | Expected full head revision for inventory or explicit PR selection. User property: `smartTestPicker.prHeadRevision`. | 2; 3 via explicit PR flow |
| `maven:select-tests:projectDir` | ${project.basedir} | Read-only Maven injection: current module base directory. | 2; 3 via explicit PR flow |
| `maven:select-tests:selectedTestsFile` | ${project.build.directory}/selected-tests.json | Selected-test JSON output or input, according to the selection/report goal. | 2; 3 via explicit PR flow |
| `maven:select-tests:testClassesDir` | ${project.build.testOutputDirectory} | Compiled test-class directory used by legacy inventory/selection paths. | 2; 3 via explicit PR flow |
| `maven:smart-test` | not applicable | Selects affected tests, forks verification for affected modules and generates legacy reports. | 2; 3 via explicit PR flow |
| `maven:smart-test:classLevelSelection` | false | Expand selection to whole test classes instead of individual methods. User property: `smartTestPicker.classLevelSelection`. | 2; 3 via explicit PR flow |
| `maven:smart-test:fullSuiteTriggers` | unset (no triggers) | File glob patterns whose changed paths force a full test suite. User property: `smartTestPicker.fullSuiteTriggers`. | 2; 3 via explicit PR flow |
| `maven:smart-test:integrationRevision` | unset; explicit PR mode is disabled unless configured | Frozen full integration commit for explicit PR selection; supply all three explicit revisions together. User property: `smartTestPicker.integrationRevision`. | 2; 3 via explicit PR flow |
| `maven:smart-test:maxCommitDistance` | 500 | Maximum accepted distance from map revision to head before conservative full-suite selection. User property: `smartTestPicker.maxCommitDistance`. | 2; 3 via explicit PR flow |
| `maven:smart-test:prBaseRevision` | unset; required with explicit PR selection | Frozen full PR base commit for explicit PR selection. User property: `smartTestPicker.prBaseRevision`. | 2; 3 via explicit PR flow |
| `maven:smart-test:prHeadRevision` | unset; inventory uses checked-out HEAD, explicit selection requires a full commit | Expected full head revision for inventory or explicit PR selection. User property: `smartTestPicker.prHeadRevision`. | 2; 3 via explicit PR flow |
| `maven:smart-test:project` | ${project} | Read-only Maven injection: current Maven project. | 2; 3 via explicit PR flow |
| `maven:smart-test:reactorProjects` | ${reactorProjects} | Read-only Maven injection: active reactor projects. | 2; 3 via explicit PR flow |
| `maven:smart-test:remotePassword` | unset | HTTP Basic Auth password for the remote store; supply credentials externally rather than in source. User property: `smartTestPicker.remotePassword`. | 2; 3 via explicit PR flow |
| `maven:smart-test:remoteUrl` | unset | Remote store base URL; the transport stores coverage-map bytes by base branch. User property: `smartTestPicker.remoteUrl`. | 2; 3 via explicit PR flow |
| `maven:smart-test:remoteUser` | unset | HTTP Basic Auth username for the remote store. User property: `smartTestPicker.remoteUser`. | 2; 3 via explicit PR flow |
| `maven:smart-test:springProfiles` | unset | Spring profiles forwarded to the managed test execution. User property: `spring.profiles.active`. | 2; 3 via explicit PR flow |

## Gradle properties and tasks

| ID | Default | Meaning | Schema |
| --- | --- | --- | --- |
| `gradle-task:aggregateGradleExecutableCoverage` | not applicable | Validates assigned target fragments and writes build/stp/executable-fragment.json, not a published map. | 3 |
| `gradle-task:generateGradleExecutableHeadTestInventory` | not applicable | Aggregates project inventories into build/executable-head-test-inventory.json; discovery mode must be enabled. | 3 |
| `gradle-task:generateGradleExecutableProjectInventory` | not applicable | Discovers effective JUnit Platform selection for this project's mapped Test tasks. | 3 |
| `gradle-task:generateHeadTestInventory` | not applicable | Discovers logical identities from the standard test task into build/head-test-inventory.json. | 2 |
| `gradle-task:generateSmartReports` | not applicable | Converts legacy per-test JaCoCo execution sessions to XML. | legacy |
| `gradle-task:generateSmartTestCoverage` | not applicable | Dedicated standalone mapping Test task; not used as a synthetic target in schema 3. | 2 (ASM), legacy (JACOCO) |
| `gradle-task:generateSmartTestMapping` | not applicable | Runs configured mapping targets and their collector/aggregation dependencies. | 2, 3; legacy JACOCO |
| `gradle-task:generateStpJacocoExecutableFragment<segment>` | not applicable | Per-target task, created for JACOCO: converts sessions and outcomes to an executable fragment; segment is the sanitized Test task path. | 3 |
| `gradle-task:generateTestCoverageJson` | not applicable | Converts legacy per-test XML coverage into build/test-coverage-map.json. | legacy |
| `gradle-task:generateTestReport` | not applicable | Renders the legacy coverage/selection HTML report. | legacy |
| `gradle-task:pullCoverageMap` | not applicable | Downloads map bytes to the local cache using remoteStore configuration. | legacy, 2, 3 (byte transport) |
| `gradle-task:pushCoverageMap` | not applicable | Uploads local map bytes using remoteStore configuration; not a publication-registry task. | legacy, 2, 3 (byte transport) |
| `gradle-task:resolveStpAgentArtifact` | not applicable | Resolves the version-aligned shaded ASM agent; mapping wires this dependency automatically. | 2, 3 |
| `gradle-task:selectTests` | not applicable | Selects tests using the coverage map and Git changes; explicit PR mode requires three frozen revisions. | 2; 3 via explicit PR flow |
| `gradle-task:smartTest` | not applicable | Executes the selected logical tests through a Gradle Test task. | 2 |
| `gradle-task:validateGradleExecutableSelection` | not applicable | Checks assignment narrowing against the original effective Test selections before execution. | 3 |
| `gradle:baseBranch` | main | The base branch used for change detection (e.g. "main", "develop"). The coverage map is generated against this branch and used as the diff baseline. | 2, 3; legacy where applicable |
| `gradle:classLevelSelection` | false | When enabled, the smartTest task includes entire test classes instead of individual test methods. Use this for projects where test methods within a class depend on shared state or execution order. | 2, 3; legacy where applicable |
| `gradle:coverageCollector` | ASM | Accepts ASM or JACOCO, case-insensitively. | 2, 3; legacy where applicable |
| `gradle:coverageExcludes` | empty | Additional ASM package prefixes to exclude. | 2, 3; legacy where applicable |
| `gradle:coverageIncludes` | empty; schema-v3 mapping derives prefixes from production code sources | ASM package-prefix override. | 2, 3; legacy where applicable |
| `gradle:executableAssignmentFile` | stp.executableAssignment, otherwise unset | Schema-v3 executable shard assignment. | 3 (schema selector defaults to 2) |
| `gradle:executableInventoryDiscovery` | stp.executableInventoryDiscovery, or false | Assignment-free schema-v3 inventory discovery. | 3 (schema selector defaults to 2) |
| `gradle:executionTarget` | stp.executionTarget, otherwise unset | Canonical adapter-supplied execution target. | 3 (schema selector defaults to 2) |
| `gradle:fullSuiteTriggers` | empty (disabled) | Glob patterns for files that trigger a full test suite when changed. Use this for non-Java files that affect runtime behavior but are invisible to JaCoCo coverage (e.g. XML data models, Spring configuration, data files). Patterns are matched against git diff file paths relative to the project root. Standard glob syntax is supported (e.g. **\/*-items.xml, *\/spring*.xml). When any changed file matches a trigger pattern, the plugin outputs FULL_SUITE and all tests are run. This is the safety-first approach: when configuration files change, run everything. | 2, 3; legacy where applicable |
| `gradle:integrationRevision` | unset | Frozen integration commit; setting any explicit revision requires all three. | 2, 3; legacy where applicable |
| `gradle:mappingTestTasks` | stp.mappingTestTasks, or an empty list | Fully-qualified Test-task paths in the mapping scope. | 3 (schema selector defaults to 2) |
| `gradle:maxCommitDistance` | 500 | Maximum number of commits between the coverage map's commitId and HEAD. If the distance exceeds this threshold, the mapping is considered stale and the full test suite is run instead. | 2, 3; legacy where applicable |
| `gradle:prBaseRevision` | unset | Frozen PR base commit. | 2, 3; legacy where applicable |
| `gradle:prHeadRevision` | unset | Frozen PR head commit and provenance for newly generated head inventory. | 2, 3; legacy where applicable |
| `gradle:remoteStore` | an unconfigured storage extension, with publishing disabled | Remote map storage settings. | 2, 3; legacy where applicable |
| `gradle:remoteStore.credentials` | credentials with username and password unset | HTTP Basic Auth credentials for the remote store. | 2, 3; legacy where applicable |
| `gradle:remoteStore.credentials.password` | unset. Do not store credentials in project source | HTTP Basic Auth password. | 2, 3; legacy where applicable |
| `gradle:remoteStore.credentials.username` | unset. Supply through an external credential provider | HTTP Basic Auth username. | 2, 3; legacy where applicable |
| `gradle:remoteStore.push` | false. Set to true on CI builds on the main branch | Whether to push the coverage map after generation. | 2, 3; legacy where applicable |
| `gradle:remoteStore.url` | unset; no remote store is configured | Base URL of the remote store (e.g. Nexus raw repository URL). Maps are stored at `{url}/{baseBranch}/test-coverage-map.json`. | 2, 3; legacy where applicable |
| `gradle:revision` | stp.revision, then GIT_COMMIT, then UNKNOWN | Revision persisted in fragments. | 2, 3; legacy where applicable |
| `gradle:runtimeSchemaVersion` | stp.schemaVersion, or 2 when unset | Runtime fragment schema: choose logical 2 or executable 3. | 2, 3 |
| `gradle:shardId` | stp.shardId, otherwise the mapping task path | Optional externally orchestrated shard ID; defaults to the mapping task path. | 2, 3; legacy where applicable |

## CLI commands and options

| ID | Default | Meaning | Schema |
| --- | --- | --- | --- |
| `cli:exec-to-xml` | not applicable | Converts per-test JaCoCo .exec sessions to XML; not a v3 map join. | legacy |
| `cli:exec-to-xml:--classes-dir` | new ArrayList&lt;&gt;() | Compiled classes directory (repeatable) | legacy |
| `cli:exec-to-xml:--exec-dir` | unset | Directory containing .exec files | legacy |
| `cli:exec-to-xml:--help` | false | Picocli standard help/version option. | legacy |
| `cli:exec-to-xml:--output-dir` | unset | Output directory for XML reports | legacy |
| `cli:exec-to-xml:--platform-home` | unset | Platform home (auto-discovers classes directories) | legacy |
| `cli:exec-to-xml:--source-dir` | unset | Java source directory for report references | legacy |
| `cli:exec-to-xml:--threads` | 0 | Parallel threads (default: CPU count) | legacy |
| `cli:exec-to-xml:--version` | false | Picocli standard help/version option. | legacy |
| `cli:exec-to-xml:-h` | false | Picocli standard help/version option. | legacy |
| `cli:exec-to-xml:-V` | false | Picocli standard help/version option. | legacy |
| `cli:generate-map` | not applicable | Builds a legacy map from per-test JaCoCo XML. | legacy |
| `cli:generate-map:--base-branch` | main | Base branch name for metadata (default: main) | legacy |
| `cli:generate-map:--cache` | false | Also write to local cache (~/.gradle/smart-test-picker/PROJECT_NAME/local-coverage-map.json) | legacy |
| `cli:generate-map:--gzip` | false | Compress output with gzip | legacy |
| `cli:generate-map:--help` | false | Picocli standard help/version option. | legacy |
| `cli:generate-map:--indexed` | false | Use indexed format (smaller file, integer references) | legacy |
| `cli:generate-map:--output` | unset | Output JSON file path | legacy |
| `cli:generate-map:--project-dir` | current directory | Project root directory for git metadata (default: current dir) | legacy |
| `cli:generate-map:--version` | false | Picocli standard help/version option. | legacy |
| `cli:generate-map:--xml-dir` | unset | Directory containing per-test JaCoCo XML reports | legacy |
| `cli:generate-map:-h` | false | Picocli standard help/version option. | legacy |
| `cli:generate-map:-V` | false | Picocli standard help/version option. | legacy |
| `cli:generate-report` | not applicable | Renders a legacy coverage and selection dashboard. | legacy |
| `cli:generate-report:--class-level-selection` | false | Enable class-level expansion section in report | legacy |
| `cli:generate-report:--help` | false | Picocli standard help/version option. | legacy |
| `cli:generate-report:--map` | unset | Coverage map file (JSON, indexed, or gzip) | legacy |
| `cli:generate-report:--max-commit-distance` | 500 | Max commit distance for display (default: 500) | legacy |
| `cli:generate-report:--output` | unset | Output HTML report file | legacy |
| `cli:generate-report:--platform-home` | unset | Platform home (auto-discovers source directories) | legacy |
| `cli:generate-report:--project-dir` | current directory | Project root directory for git commands (default: current dir) | legacy |
| `cli:generate-report:--selected-tests` | unset | Selected tests JSON file (output of select-tests) | legacy |
| `cli:generate-report:--source-dir` | unset | Java source directory (for source coverage pages) | legacy |
| `cli:generate-report:--version` | false | Picocli standard help/version option. | legacy |
| `cli:generate-report:--xml-dir` | unset | Per-test JaCoCo XML reports directory (for source coverage pages) | legacy |
| `cli:generate-report:-h` | false | Picocli standard help/version option. | legacy |
| `cli:generate-report:-V` | false | Picocli standard help/version option. | legacy |
| `cli:join-fragments` | not applicable | Joins one execution target's complete v3 shards, verifies and atomically publishes a gzip map. | 3 |
| `cli:join-fragments:--assignments-dir` | unset | Directory containing one executable assignment file per shard | 3 |
| `cli:join-fragments:--dir` | unset | Directory of paired fragment-NAME.json and tests-NAME.json files; NAME must match within each pair. | 3 |
| `cli:join-fragments:--expected-shards` | unset | Positive number of shard pairs required with --dir | 3 |
| `cli:join-fragments:--fragments-dir` | unset | Directory of fragment files; every direct file is decoded, without extension filtering | 3 |
| `cli:join-fragments:--head-inventory` | unset | Versioned executable head inventory file | 3 |
| `cli:join-fragments:--help` | false | Picocli standard help/version option. | 3 |
| `cli:join-fragments:--output` | unset | Published schema-v3 map; replaced only after verification | 3 |
| `cli:join-fragments:--version` | false | Picocli standard help/version option. | 3 |
| `cli:join-fragments:-h` | false | Picocli standard help/version option. | 3 |
| `cli:join-fragments:-V` | false | Picocli standard help/version option. | 3 |
| `cli:merge-maps` | not applicable | Merges legacy maps; use join-fragments for v3. | legacy |
| `cli:merge-maps:--gzip` | false | Compress output with gzip | legacy |
| `cli:merge-maps:--help` | false | Picocli standard help/version option. | legacy |
| `cli:merge-maps:--indexed` | false | Use indexed format (smaller file, integer references) | legacy |
| `cli:merge-maps:--input-dir` | unset | Directory containing coverage map JSON files | legacy |
| `cli:merge-maps:--output` | unset | Output merged coverage map file path | legacy |
| `cli:merge-maps:--version` | false | Picocli standard help/version option. | legacy |
| `cli:merge-maps:-h` | false | Picocli standard help/version option. | legacy |
| `cli:merge-maps:-V` | false | Picocli standard help/version option. | legacy |
| `cli:pull-map` | not applicable | Downloads opaque map bytes from a configured remote store. | legacy, 2, 3 |
| `cli:pull-map:--branch` | main | Base branch (default: main) | legacy, 2, 3 |
| `cli:pull-map:--help` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:pull-map:--output` | unset | Output file path (default: local cache) | legacy, 2, 3 |
| `cli:pull-map:--password` | unset | HTTP Basic Auth password | legacy, 2, 3 |
| `cli:pull-map:--project-dir` | current directory | Project root directory (default: current dir) | legacy, 2, 3 |
| `cli:pull-map:--url` | unset | Remote store base URL | legacy, 2, 3 |
| `cli:pull-map:--user` | unset | HTTP Basic Auth username | legacy, 2, 3 |
| `cli:pull-map:--version` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:pull-map:-h` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:pull-map:-V` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:push-map` | not applicable | Uploads opaque map bytes to a configured remote store. | legacy, 2, 3 |
| `cli:push-map:--branch` | main | Base branch (default: main) | legacy, 2, 3 |
| `cli:push-map:--help` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:push-map:--input` | unset | Coverage map file to upload | legacy, 2, 3 |
| `cli:push-map:--password` | unset | HTTP Basic Auth password | legacy, 2, 3 |
| `cli:push-map:--url` | unset | Remote store base URL | legacy, 2, 3 |
| `cli:push-map:--user` | unset | HTTP Basic Auth username | legacy, 2, 3 |
| `cli:push-map:--version` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:push-map:-h` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:push-map:-V` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:query` | not applicable | Queries a legacy map by test, class, method or statistics. | legacy |
| `cli:query:--class` | unset | Find all tests covering a class (FQN) | legacy |
| `cli:query:--grep` | unset | Substring search across test names, classes, and methods | legacy |
| `cli:query:--help` | false | Picocli standard help/version option. | legacy |
| `cli:query:--map` | unset | Coverage map JSON file. If not provided, resolves from local cache. | legacy |
| `cli:query:--method` | unset | Find all tests covering a method (FQN#method) | legacy |
| `cli:query:--prefer-map` | nearest | Map selection mode when using cache: nearest, remote, local (default: nearest) | legacy |
| `cli:query:--project-dir` | current directory | Project root directory (default: current dir) | legacy |
| `cli:query:--stats` | false | Show map statistics (total tests, classes, methods) | legacy |
| `cli:query:--test` | unset | Show coverage for a specific test (FQN#method) | legacy |
| `cli:query:--version` | false | Picocli standard help/version option. | legacy |
| `cli:query:-h` | false | Picocli standard help/version option. | legacy |
| `cli:query:-V` | false | Picocli standard help/version option. | legacy |
| `cli:refreshed-report` | not applicable | Legacy platform-specific select/Ant/JaCoCo/report orchestration; not the v3 join/selection workflow. | legacy |
| `cli:refreshed-report:--exec-dir` | /tmp/jacoco-exec/ | Directory where JaCoCo per-test .exec files are written (default: /tmp/jacoco-exec/) | legacy |
| `cli:refreshed-report:--help` | false | Picocli standard help/version option. | legacy |
| `cli:refreshed-report:--java-home` | unset | Java home for ant (default: JAVA_HOME env var) | legacy |
| `cli:refreshed-report:--map` | unset | Coverage map file (JSON, indexed, or gzip) | legacy |
| `cli:refreshed-report:--max-commit-distance` | 500 | Max commits before coverage map is considered stale (default: 500) | legacy |
| `cli:refreshed-report:--output` | build/reports/smart-test-picker/index.html | Output HTML report file | legacy |
| `cli:refreshed-report:--platform-home` | unset | Platform home directory (e.g. bin/platform/) | legacy |
| `cli:refreshed-report:--skip-tests` | false | Skip test execution (use existing exec files) | legacy |
| `cli:refreshed-report:--threads` | 0 | Parallel threads for exec-to-xml conversion (default: CPU count) | legacy |
| `cli:refreshed-report:--version` | false | Picocli standard help/version option. | legacy |
| `cli:refreshed-report:-h` | false | Picocli standard help/version option. | legacy |
| `cli:refreshed-report:-V` | false | Picocli standard help/version option. | legacy |
| `cli:select-tests` | not applicable | Selects against logical v2 maps, or v3 with explicit PR revisions and executable head inventory. | 2; 3 in explicit PR mode |
| `cli:select-tests:--format` | json | Output format: json, txt, ant (default: json) | 2; 3 in explicit PR mode |
| `cli:select-tests:--full-suite-trigger` | new ArrayList&lt;&gt;() | Glob pattern that forces full suite when matched (repeatable) | 2; 3 in explicit PR mode |
| `cli:select-tests:--head-inventory` | unset | Exact logical head inventory for v2, or executable head inventory for v3 explicit PR selection. | 2; 3 in explicit PR mode |
| `cli:select-tests:--help` | false | Picocli standard help/version option. | 2; 3 in explicit PR mode |
| `cli:select-tests:--integration-revision` | unset | Frozen full integration commit ID (enables explicit PR mode) | 2; 3 in explicit PR mode |
| `cli:select-tests:--map` | unset | Coverage map file (JSON, indexed, or gzip). If not provided, resolves from local cache. | 2; 3 in explicit PR mode |
| `cli:select-tests:--max-commit-distance` | 500 | Max commits before coverage map is considered stale (default: 500) | 2; 3 in explicit PR mode |
| `cli:select-tests:--output` | unset | Output file for selected tests | 2; 3 in explicit PR mode |
| `cli:select-tests:--pr-base-revision` | unset | Frozen full PR base commit ID (explicit PR mode) | 2; 3 in explicit PR mode |
| `cli:select-tests:--pr-head-revision` | unset | Frozen full PR head commit ID (explicit PR mode) | 2; 3 in explicit PR mode |
| `cli:select-tests:--prefer-map` | nearest | Map selection mode when using cache: nearest, remote, local (default: nearest) | 2; 3 in explicit PR mode |
| `cli:select-tests:--project-dir` | current directory | Project root directory for git commands (default: current dir) | 2; 3 in explicit PR mode |
| `cli:select-tests:--test-classes-dir` | unset | Deprecated legacy option; schema-v2 selection requires --head-inventory | 2; 3 in explicit PR mode |
| `cli:select-tests:--version` | false | Picocli standard help/version option. | 2; 3 in explicit PR mode |
| `cli:select-tests:-h` | false | Picocli standard help/version option. | 2; 3 in explicit PR mode |
| `cli:select-tests:-V` | false | Picocli standard help/version option. | 2; 3 in explicit PR mode |
| `cli:smart-test-picker` | not applicable | CLI entry point; choose a subcommand. | legacy, 2, 3 |
| `cli:smart-test-picker:--help` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:smart-test-picker:--version` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:smart-test-picker:-h` | false | Picocli standard help/version option. | legacy, 2, 3 |
| `cli:smart-test-picker:-V` | false | Picocli standard help/version option. | legacy, 2, 3 |
