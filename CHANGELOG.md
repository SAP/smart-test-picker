# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Local Central-compatible publications with real source/Javadoc artifacts, third-party notices,
  signed bundle verification and a Java 17 API baseline. No registry publication is implied.
- Source-checked configuration reference and schema-v3-first setup documentation; historical
  design and validation records now live under `docs/history/`.
- Executable coverage-map schema 3 with target-qualified identities, inventories, assignments, fragments and maps.
- Gradle ASM mapping with target-aware routing and retained JaCoCo fallback.
- Maven reactor inventory, executable mapping aggregation, target resolution and managed selected-test evidence verification.
- Central failure policy and revision-bound explicit/automatic selection contracts used by the Jenkins integration.

### Changed
- Maven and Gradle schema-v3 inventories follow effective provider/Test task selection. Assignments
  may only narrow it; partial overload assignments are rejected before test execution.
- Completed valid ASM target fragments on both tools absorb unreported assigned tests as
  `COLLECTION_FAILED`, with an audit. These tests remain mandatory while present at head.
- JaCoCo mapping retains cross-module coverage, isolates build-tool-managed agent output, and skips
  project coverage consumers. Literal project agent destinations remain outside isolation.
- Gradle inventory supports 8.0 to below 10.0. Maven schema-v3 inventory/preparation require 3.9 or
  later, qualified on 3.9.15; Maven 4 remains untested. Preparation repeats early lifecycle phases.
- Unified all STP-owned module versions on `0.3.0-SNAPSHOT`.
- Coverage-map schema 2 remains the logical wire family; schema 3 is the executable target-aware family. These schema numbers are independent from artifact versions.
- Test identity, map and execution contracts now fail closed on incompatible or incomplete evidence.
- BREAKING: Maven groupId migrated from `io.github.ljubisap` to `com.sap.oss.smart-test-picker`
- BREAKING: Gradle plugin ID migrated from `io.github.ljubisap.smart-test-picker` to `com.sap.oss.smart-test-picker`
- BREAKING: Java package root migrated from `io.github.ljubisap.smarttestpicker` to `com.sap.oss.smarttestpicker`. Consumers extending public APIs must update their imports.

### Fixed
- Maven version detection uses injected RuntimeInformation rather than the maven-core manifest.
- Invocation-owned Maven JaCoCo agent directories are cleaned when the session finishes, without
  changing the project's execution-data file.
- JUnit Vintage `ClassSource` executions and implicit Surefire providers now produce canonical, invocation-bound execution evidence.
- Gradle JaCoCo executable mapping preserves collected container setup coverage as target-owned `FRAMEWORK_SETUP` scopes, so setup-only changes select the affected container's tests. Invalid setup evidence rejects fragment generation.

### Migration notes
- The legacy unversioned Gradle JaCoCo map cannot represent setup ownership and now rejects positive setup coverage instead of publishing it under a pseudo-test. Use executable schema 3 with an authoritative task assignment; see [STP-GRADLE-SETUP-01](https://github.com/SAP/smart-test-picker/blob/2cf74c0466049cbf80fe1bf38ed794e113f9fbef/verification/stp-gradle-setup-01/REPORT.md).
- Schema 2 maps cannot be treated as schema 3 executable maps; regenerate or retain the matching reader/workflow rather than changing the declared schema version.
- Consumers of the previous Maven coordinates, Gradle plugin ID, or Java package root must update to the `com.sap.oss.smart-test-picker` / `com.sap.oss.smarttestpicker` namespaces.
