# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Executable coverage-map schema 3 with target-qualified identities, inventories, assignments, fragments and maps.
- Gradle ASM mapping with target-aware routing and retained JaCoCo fallback.
- Maven reactor inventory, executable mapping aggregation, target resolution and managed selected-test evidence verification.
- Central failure policy and revision-bound explicit/automatic selection contracts used by the Jenkins integration.

### Changed
- Unified all STP-owned module versions on `0.3.0-SNAPSHOT`.
- Coverage-map schema 2 remains the logical wire family; schema 3 is the executable target-aware family. These schema numbers are independent from artifact versions.
- Test identity, map and execution contracts now fail closed on incompatible or incomplete evidence.
- BREAKING: Maven groupId migrated from `io.github.ljubisap` to `com.sap.oss.smart-test-picker`
- BREAKING: Gradle plugin ID migrated from `io.github.ljubisap.smart-test-picker` to `com.sap.oss.smart-test-picker`
- BREAKING: Java package root migrated from `io.github.ljubisap.smarttestpicker` to `com.sap.oss.smarttestpicker`. Consumers extending public APIs must update their imports.

### Fixed
- JUnit Vintage `ClassSource` executions and implicit Surefire providers now produce canonical, invocation-bound execution evidence.

### Migration notes
- Schema 2 maps cannot be treated as schema 3 executable maps; regenerate or retain the matching reader/workflow rather than changing the declared schema version.
- Consumers of the previous Maven coordinates, Gradle plugin ID, or Java package root must update to the `com.sap.oss.smart-test-picker` / `com.sap.oss.smarttestpicker` namespaces.
