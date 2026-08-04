# Spring Data stabilization inventory

This inventory was completed before stabilization deletions. Classifications
describe the state entering cleanup; the action column records the reviewed
disposition.

## Module disposition

| Module/path | Classification | Reason and action | Released artifact? |
|---|---|---|---:|
| `stp-runtime` | `PRODUCTION_CANDIDATE` | Shared immutable event model, registry, attribution and deterministic JSON. Keep experimental and unpublished. | Future, not currently |
| `stp-agent` | `PRODUCTION_CANDIDATE` | Agent, ASM method entry, shared-runtime owner and output. Keep; reduce public surface to `StpAgent`. | Experimental agent JAR only |
| `stp-junit-adapter` | `PRODUCTION_CANDIDATE` | JUnit Platform identity/lifecycle bridge. Keep; remove obsolete registry facade. | Bundled in experimental agent |
| `stp-spring-data-adapter` | `PRODUCTION_CANDIDATE` | Narrow validated caller-boundary adapter. Keep unpublished; no spike dependencies. | No |
| `stp-spring-data-e2e-fixture` | `EXPERIMENTAL_TEST_FIXTURE` | One-command integrated proof. Keep unpublished. | No |
| `stp-petclinic-spike` | `REPRODUCTION_SCRIPT` | External pinned-project contract, scripts and normalized evidence. Keep outside Gradle publication. | No |
| `stp-spring-data-observability-spike` | Mixed fixture/research/obsolete | Keep only PetClinic acceptance/lifecycle diagnostics; delete rejected alternatives; archive normalized research evidence. | No |
| `docs/spikes/**` | `RESEARCH_DOCUMENT` | Historical decisions and evidence. Keep, mark rejected/superseded architecture where needed. | Documentation only |

## File inventory: production candidates

Every path below is retained unless an explicit action says otherwise.

| Files | Classification | Action / API status |
|---|---|---|
| `stp-runtime/build.gradle`, `README.md` | `PRODUCTION_CANDIDATE` | Keep; experimental, unpublished module contract. |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/*.java` | `PRODUCTION_CANDIDATE` | Keep. Public registry/service/hooks/aggregator/serializer are cross-module APIs; `EventFacts` remains package-private. |
| `stp-runtime/src/main/java/com/sap/oss/smarttestpicker/runtime/model/*.java` | `PRODUCTION_CANDIDATE` | Keep public immutable schema types. Compatibility is explicitly experimental (`spike-2`). |
| `stp-runtime/src/test/**` | `EXPERIMENTAL_TEST_FIXTURE` | Keep as module regression/golden tests; not packaged. |
| `stp-agent/build.gradle`, `README.md` | `PRODUCTION_CANDIDATE` | Keep experimental shaded agent packaging. |
| `stp-agent/src/main/**/StpAgent.java` | `PRODUCTION_CANDIDATE` | Keep public because `Premain-Class` requires it. |
| all other `stp-agent/src/main/**/*.java` | `PRODUCTION_CANDIDATE` | Keep but make package-private; implementation is not user API. |
| `stp-agent/src/test/**` | `EXPERIMENTAL_TEST_FIXTURE` | Keep fixture JVM and verification tests; never packaged. |
| `stp-junit-adapter/build.gradle`, `README.md`, listener and service file | `PRODUCTION_CANDIDATE` | Keep public listener for SPI/programmatic use. |
| `stp-junit-adapter/src/main/**/RuntimeServiceRegistry.java` | `OBSOLETE_SPIKE_CODE` | Delete deprecated forwarding facade; `RuntimeContextRegistry` is authoritative and no released compatibility exists. |
| `stp-junit-adapter/src/test/**` | `EXPERIMENTAL_TEST_FIXTURE` | Keep and migrate facade assertion to shared registry. |
| every `stp-spring-data-adapter/src/main/**/*.java` except initializer/properties | `PRODUCTION_CANDIDATE` | Keep package-private. No diagnostic/helper public API. |
| `StpSpringDataApplicationContextInitializer.java` | `PRODUCTION_CANDIDATE` | Keep sole public activation entry point; experimental compatibility only. |
| `SpringDataAdapterProperties.java` | `PRODUCTION_CANDIDATE` | Keep public constant for the one supported property; experimental compatibility only. |
| adapter `spring.factories`, `build.gradle`, `README.md` | `PRODUCTION_CANDIDATE` | Keep; use non-embedding dependency boundary and no publishing. |
| `stp-spring-data-adapter/src/test/**` | `EXPERIMENTAL_TEST_FIXTURE` | Keep unit, factory, eligibility, audit and packaging tests. |

## File inventory: maintained fixtures and reproduction

| Files | Classification | Action |
|---|---|---|
| every file under `stp-spring-data-e2e-fixture/` | `EXPERIMENTAL_TEST_FIXTURE` | Keep. One command proves method/repository facts, cache `2→1`, failure, contamination, disabled mode and golden determinism. |
| `stp-petclinic-spike/experiment-manifest.yaml`, `baseline-results.yaml`, `metrics-template.yaml`, `README.md` | `RESEARCH_DOCUMENT` | Keep pinned experiment contract/evidence. |
| `stp-petclinic-spike/task6_experiment.rb`, `validate_task6_results.rb` | `REPRODUCTION_SCRIPT` | Keep historical ASM-only reproduction. |
| `stp-petclinic-spike/task-6-outputs/*.json` | `RESEARCH_DOCUMENT` | Keep normalized/small historical evidence; no build directories. |
| `stp-petclinic-spike/spring-data-acceptance/*.rb` | `REPRODUCTION_SCRIPT` | Keep current acceptance, normalization, performance and offline validator. |
| `normalized-acceptance.json`, `performance-summary.json` | `RESEARCH_DOCUMENT` | Keep reviewed deterministic acceptance evidence. |
| `stp-petclinic-spike/spring-data-lifecycle/*.rb` | `REPRODUCTION_SCRIPT` | Keep lifecycle reproduction while its diagnostic source remains. |
| `normalized-lifecycle.json` | `RESEARCH_DOCUMENT` | Keep reviewed lifecycle evidence. |

## Observability spike inventory

| Files | Classification | Action |
|---|---|---|
| confirmation `AcceptanceAuditRecorder`, `PetClinicAcceptanceAuditConfiguration`, `ConfirmationRecord`, `ConfirmationRecorder`, `Descriptors`, `PetClinicConfirmationConfiguration`, `PetClinicConfirmationInitializer`, `spring.factories` | `EXPERIMENTAL_TEST_FIXTURE` | Keep for current pinned PetClinic acceptance and cache/structural evidence. |
| confirmation `RepositoryLifecycleConfiguration`, `RepositoryLifecycleRecorder`, `RepositoryLifecycleTestExecutionListener` | `EXPERIMENTAL_TEST_FIXTURE` | Keep for reproducible lifecycle evidence; no production dependency. |
| confirmation `CallerBoundaryConfiguration`, `CallerBoundaryRecord`, `CallerBoundaryRecorder`, `ProxySnapshot` | `OBSOLETE_SPIKE_CODE` | Delete. Rejected wrapper/auto-proxy alternatives are superseded by smart-singleton insertion. |
| `src/main/**`, `src/test/**` | `OBSOLETE_SPIKE_CODE` | Delete executable listener/advice/wrapper/mock comparison fixture; conclusions remain archived research evidence. |
| `normalize_caller_boundary.rb`, `normalize_petclinic_confirmation.rb` | `OBSOLETE_SPIKE_CODE` | Delete duplicated historical normalizers; current acceptance/lifecycle normalizers are authoritative. |
| `normalized-diagnostics/**`, `petclinic-confirmation/**` | `RESEARCH_DOCUMENT` | Move unchanged under `docs/spikes/archive/spring-data-observability/`. Not executable architecture. |
| observability `README.md`, `build.gradle` | `EXPERIMENTAL_TEST_FIXTURE` | Retain and rewrite/build only maintained confirmation source set. No publication. |

## Diagnostic reason review

| Enum/reason family | Meaning and disposition |
|---|---|
| `MetadataDiagnosticReason` | Factory metadata validity, conflict, alias and customizer failures. All remain reachable and bounded. |
| `RepositoryEligibilityReason` | Final bean eligibility, duplicate/context/interface/proxy conditions, plus actionable `REPOSITORY_CREATED_AFTER_INSERTION_PHASE`. All remain deterministic; no generated proxy identity. |
| `AdvisorAuditReason` | Exact identity/count/index/order/cache/structure/restoration failures. Removed obsolete `AUDIT_WINDOW_CLOSED`; late creation now belongs to eligibility. |

`MetadataDiagnostics` stores counters and at most 20 sorted canonical bean-name
samples per reason. It stores no exception, argument, result, generated proxy
name or bean reference. Context registries retain only the minimum identity
bookkeeping during context lifetime and clear it on close.

## Public API review

| Public types | Decision |
|---|---|
| runtime service/registry/hooks/aggregator/serializer and all runtime model records/enums | Required cross-module experimental API; no stable compatibility commitment before schema graduation. |
| `StpRuntimeTestExecutionListener` | Required public JUnit SPI/programmatic entry; experimental. |
| `RuntimeServiceRegistry` and nested registration | Delete; obsolete forwarding API. |
| `StpAgent` | Required public JVM agent entry. |
| agent configuration/transformer/catalog/hash/metrics/helper types | Make package-private; no external API commitment. |
| `StpSpringDataApplicationContextInitializer` | Required public Spring discovery entry; experimental. |
| `SpringDataAdapterProperties` | Required public constant for `stp.spring-data.enabled`; experimental. |

No new annotation framework is introduced. “Experimental” is documented in
module/user documentation and artifact naming.

The exhaustive retained public-type list is:

- runtime service/API: `RuntimeContextRegistry`,
  `RuntimeContextRegistry.Registration`, `RuntimeContextService`,
  `RuntimeEventAggregator`, `RuntimeHooks`, and `RuntimeJsonSerializer`;
- runtime immutable schema: `Certainty`, `EndpointEvent`, `EntityEvent`,
  `Evidence`, `EvidenceSource`, `MethodHitEvent`, `MethodIdentity`,
  `RepositoryInvocationEvent`, `RepositoryKind`, `RepositoryOutcome`,
  `RuntimeEvent`, `SpringBeanEvent`, `TableAccess`, `TableEvent`,
  `TestExecutionStatus`, `TestIdentity`, `TestResult`, `UnattributedEvent`, and
  `UnattributedReason`;
- JUnit SPI: `StpRuntimeTestExecutionListener`;
- agent entry: `StpAgent`;
- Spring discovery/configuration: `StpSpringDataApplicationContextInitializer`
  and `SpringDataAdapterProperties`.

These types have only an experimental compatibility commitment. All other
agent and Spring Data adapter implementation types are package-private. The
runtime types are necessarily public because the agent, JUnit listener and
optional framework adapter are separate modules and share the same model and
service.

## Released-artifact boundary

No new module is configured for Maven publication. The Spring Data adapter JAR
contains only adapter classes and `spring.factories`; it embeds no Spring,
Spring Data or `stp-runtime` classes. Agent remains Spring-free. Production
candidates have no dependency on observability, E2E or PetClinic spike modules.

## Stabilization outcome

The reviewed actions above were applied. Obsolete executable alternatives and
the adapter-owned runtime facade were deleted; research JSON was moved without
rewriting it; maintained PetClinic diagnostics compile from their isolated
`confirmation` source set. No production-candidate module depends on a fixture
or reproduction module. The adapter remains unpublished and its Spring/runtime
dependencies are non-embedded implementation or compile-only boundaries.

The remaining deliberate technical debt is bounded: compatibility has only
been validated on PetClinic plus the internal fixture; class-based, late-created,
mocked, reactive and async repository products remain unsupported; adapter
metrics are context-local rather than part of runtime JSON; and repository facts
are not consumed by RTS selection.
