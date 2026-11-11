# TASK 53 — Gradle/Maven explicit PR and PetClinic evidence

## Adapter contract

The existing Gradle `selectTests` task and Maven `select-tests`, `root-select-tests`, and `smart-test`
goals accept `integrationRevision`, `prBaseRevision`, and `prHeadRevision` (Maven properties use the
`smartTestPicker.` prefix). Supplying any one activates explicit mode and requires all three.
`ExplicitPrSelectorFlow` is the sole revision-policy authority. The map and inventory retain ownership
of their respective revisions. Local selection still uses `SchemaV2SelectorFlow` with committed,
staged, unstaged, and untracked changes.

Gradle `generateHeadTestInventory` and Maven `generate-head-test-inventory` bind a newly generated
inventory only after `WorkspaceRevisionVerifier` proves repository HEAD is the supplied full SHA. A
mismatch fails explicit generation; no Git mutation occurs. Explicit reports preserve the outer status,
normal selection output when present, and bounded changed-path/class evidence.

## Pinned dual-build validation

Spring PetClinic was used at immutable base
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`. It contains `gradlew`, `build.gradle`, `mvnw`, and
`pom.xml`. No Docker, Jenkins, or Spring Core command was run.

- P0 `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`: canonical TASK40 published map.
- P1 `58f546a4826d2b984514ae9de80dc37715e71c28`: integration change in `WelcomeController.java`.
- P2 `12a6215965dada4a1be27236841f9d489a575a62`: PR change in `VetController.java`.
- P3 `dbe2db23ef0f147ff211a50e6415870ed2bb1cb2`: later stale-PR head change.

Bounded inventory commands were Gradle `generateHeadTestInventory` (after `testClasses`) and Maven
`test-compile` plus `generate-head-test-inventory`. Both P2 artifacts are bound to P2 and contain
exactly 73 `TestIdentity` values. Both directional set differences are empty. Maven initially exposed a
real missing test-scope dependency-resolution defect (26 identities); `ResolutionScope.TEST` on all
inventory-generating goals corrected it without changing PetClinic's target universe.

## Explicit decision evidence

| Scenario | Gradle | Maven | Equality |
|---|---|---|---|
| fresh map P1, integration/base P1, head P2 | `SELECTION_RESULT / SELECTED` (6) | same | exact selected/unmapped sets |
| stale map P0, integration/base P1, head P2 | `SELECTION_RESULT / SELECTED` (9) | same | exact selected/unmapped sets |
| stale PR: map P0, integration P2, base P1, head P3 | `BASE_OUT_OF_DATE` | same | no selection output/file |
| too old: map P0, integration/base P1, head P3, distance 2 | `SELECTION_RESULT / FULL_SUITE` | same | equal status/reason |
| inventory P1 with head P2 | `ERROR / HEAD_INVENTORY_REVISION_MISMATCH` | same | focused adapter proof |
| revisionless inventory with head P2 | `ERROR / HEAD_INVENTORY_REVISION_MISMATCH` | same | focused adapter proof |
| invalid head ancestry | `ERROR` | same | focused adapter proof |

The stale-map reports contain both `WelcomeController.java`/`WelcomeController` from P0..P1 and
`VetController.java`/`VetController` from P1..P2. The effective observed interval is therefore P0..P2,
not P1..P2. Normal scenarios had no unmapped diagnostics. Attempts at workspace P3 to stamp P2 were
rejected by both adapters.

Task 5e remains **IN PROGRESS** pending its closure audit.
