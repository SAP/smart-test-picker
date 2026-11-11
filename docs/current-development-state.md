# Current development state

Status date: 2026-11-04. Source reviewed through STP `4f73dc3` and Jenkins `ff9c9cd`.

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

The authoritative [collector attribution and adoption boundaries](asm-coverage-mapping-current-state.md#current-maven-collector-boundaries)
include interval-based JaCoCo bleed/loss, ASM container-scope HTTP attribution,
cached shared-web-context rejection, and the limit of structural completeness.
Maven ASM is implemented in the prepared schema-v3 path; this is not general
qualification for HTTP-heavy Spring suites. The [open product decisions](asm-coverage-mapping-current-state.md#open-product-decisions)
remain decisions, not implemented guards or adapters.

## Coupled Maven ASM delivery

Following the EI-19 source-pair and exact-package provenance convention (recorded
in historical commit `26ed707`), the validated Maven ASM integration is:

- STP source: `d9cb928d5a07f6ea2cf152381b6b55513113cf09`.
- Jenkins plugin source: `ff9c9cdc1dcfff0de2ab381f4656f3a38c19b128`.
- Validated HPI SHA-256:
  `dfa8993d33d70f4ea7e6da5935f015821c644f54ed23f0f097dde24fa48e7864`.

**These two source commits are coupled through the effective-collector check.**
Jenkins freezes the manifest collector, rejects an explicit step/manifest
mismatch, and supplies `STP_MAPPING_COLLECTOR`. Maven preparation compares each
active module's effective collector against that read-only environment binding
before the fork guard and agent attachment. A POM/CLI override of `MAVEN_OPTS`
must not silently collect with a different collector than the manifest names.
Deploying only one side does not establish this complete contract.

The HPI above depends on both sides of that pair. It was built from their task
working trees before the delivery commits and validated in real Docker Jenkins
on the existing reactor fixture, including negative cases. It is historical
validated-package provenance, not a claim that a newly built HPI has that hash.
No HPI is rebuilt or runtime campaign repeated by consolidation. See the
[MAVEN-ASM-09 report](https://github.com/SAP/smart-test-picker/blob/2cf74c0466049cbf80fe1bf38ed794e113f9fbef/verification/maven-asm-09/REPORT.md) for exact build and
packaging evidence. Later local PetClinic measurements do not extend that
Jenkins runtime qualification.

The delivery range `7382cff..4f73dc3` contains **12 commits**, not nine. Its base,
`7382cff`, already contains the collection-activation/selection separation.
The range establishes:

| Task | Commit | Established result |
| --- | --- | --- |
| MAVEN-ASM-04 | `e21189c` | Prepared ASM mapping rejects unsafe single-output fork configurations before execution. |
| MAVEN-ASM-05 | `4cd9b00` | Guarded automatic agent resolution/attachment, argLine preservation, explicit scope and collector activation. |
| MAVEN-ASM-06 | `b13cdba` | ASM fragment/diagnostic/evidence-v2 separation, outcome accounting and qualified reactor aggregation. |
| MAVEN-ASM-07 | `61be81b` | Project JaCoCo per-method report counters preserved on the tested coexistence fixture; actual agent ordering regression and inverted-order evidence. |
| MAVEN-ASM-08 | `772d51d` | Reactor-derived production instrumentation scope, installed-reactor-JAR coverage and zero-instrumentation rejection. |
| MAVEN-ASM-09 | `d9cb928` | Coupled Jenkins enablement above, with reactor-fixture positive and fail-closed Docker cases. |
| MAVEN-ASM-10 | `5fee1fb` | Complete local 69-test PetClinic mapping, cost/divergence characterization and project JaCoCo counter preservation. |
| MAVEN-ASM-11 | `ffdc753` | HTTP method hits retained as captured-container setup scopes, not missing instrumentation; selector consumes those scopes. |
| MAVEN-ASM-12 | `7c4e50f` | Cached shared Spring web context reproduces closed-container rejection; invoking the unbounded-shared-context hook alone is not a solution. |
| MAVEN-ASM-13 | `9faae9b` | Engineered sequential JaCoCo bleed and repeated destructive parallel loss with accepted maps; ASM parallel rejection is incidental. |
| MAVEN-ASM-14 | `e606140` | No leaf movers across three sequential method orders; finite differential, not proof of temporal isolation. |
| MAVEN-ASM-15 | `4f73dc3` | Detection-power analysis and class-order differential: no movers, 71/74 suspect edges covered by combined changed-successor observations, three blind edges remain. |

Source delivery is not a release: component versions remain `0.3.0-SNAPSHOT`.
Historical validation results remain scoped to their exact revisions/scenarios.

For the Jenkins-facing boundary, use the plugin repository's
[component ownership reference](https://github.com/ljubisap/stp-jenkins-plugin-poc/blob/main/docs/reference/component-ownership.md),
[support matrix](https://github.com/ljubisap/stp-jenkins-plugin-poc/blob/main/docs/reference/support-validation-matrix.md),
and [current runtime evidence](https://github.com/ljubisap/stp-jenkins-plugin-poc/blob/main/docs/validation/petclinic-docker-2026-09-23.md).

## Verification provenance

This overview was reviewed on the status date above against the current repository contracts and the
current Jenkins integration documentation. Detailed Jenkins builds and scenario results belong to
the linked evidence records. The coupled source/package checkpoint above is the explicit
cross-repository delivery exception, following EI-19; it does not replace those records.
