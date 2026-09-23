# Current development state

Status date: 2026-09-23.

- Main STP repository: [SAP/smart-test-picker](https://github.com/SAP/smart-test-picker)
- Jenkins integration repository: [stp-jenkins-plugin-poc](https://github.com/ljubisap/stp-jenkins-plugin-poc)
- Unified STP-owned artifact version: `0.3.0-SNAPSHOT`
- Jenkins baseline: 2.516.2; tested runtime JDK 21; plugin/STP bytecode target Java 17
- Coverage-map families: logical schema 2 and executable target-aware schema 3
- Execution plan: version 1; bundled adapter contract: version 1

Verify the shared STP version and generated publication POMs with:

```console
./gradlew verifyStpVersionConsistency
```

The current Maven reactor path supports revision-bound schema-3 inventory, target-qualified mapping,
canonical reactor target resolution and managed selected execution. The Jenkins plugin owns HPI
packaging, adapter activation/cache, controller-side FILE storage, RAW_HTTP storage integration and
Pipeline orchestration. Application builds still own their normal Maven/Gradle command, dependency
repositories, exact-head dependency preparation and project-specific profiles.

Historical EI directories and task reports retain the exact commits, versions, checksums and build
results they validated. They are evidence snapshots, not current setup instructions. The most recent
external Automation Engine result is documented only in the Jenkins POC evidence note and remains
scoped to the exact builds and downloaded artifacts named there.

Current STP contracts and module usage are documented by the [root README](../README.md),
[artifact versioning](artifact-versioning.md), [compatibility table](compatibility.md), and module
README files. Jenkins installation, Pipeline API, controller-side FILE storage, and canonical examples
belong to the Jenkins integration repository; the two repositories intentionally do not duplicate
those operational references.

For the Jenkins-facing boundary, use the plugin repository's
[component ownership reference](https://github.com/ljubisap/stp-jenkins-plugin-poc/blob/main/docs/reference/component-ownership.md),
[support matrix](https://github.com/ljubisap/stp-jenkins-plugin-poc/blob/main/docs/reference/support-validation-matrix.md),
and [current runtime evidence](https://github.com/ljubisap/stp-jenkins-plugin-poc/blob/main/docs/validation/petclinic-docker-2026-09-23.md).

## Verification provenance

This overview was reviewed on the status date above against the current repository contracts and the
current Jenkins integration documentation. Exact source revisions, HPI checksums, Jenkins builds and
scenario results belong to the linked evidence record rather than this stable overview.
