# TASK 54 — 5e closure audit

## Decision

**5e DONE.** No correctness blocker remains. This audit inspected current production code, focused
tests, and the pinned TASK53 PetClinic record; it did not repeat PetClinic, Docker, Jenkins, or Spring
Core validation.

## Implementation chain

`RevisionPreflight` → `ExplicitPrSelectorFlow` → `SchemaV2SelectionAnalyzer.analyzeExplicit` →
`SchemaV2TestSelector` → thin CLI (`SelectTestsCommand`), Gradle (`GradleExplicitPrSelection` /
`SelectTestsTask`), and Maven (`MavenExplicitPrSelection` / selector mojos) adapters.

`RevisionPreflightRequest.mapRevision()` reads the immutable published map artifact. Integration,
PR-base, and PR-head are caller inputs. `HeadTestInventory` owns inventory provenance. Eligible
analysis constructs `SelectionContext(map.revision(), prHeadRevision, ...)` from exactly one committed
`git diff --name-status --find-renames mapRevision..prHeadRevision`; local HEAD, index, working tree,
and untracked queries exist only in the separate local analyzer.

## Requirement matrix

| Requirement | Implementation | Test/evidence | Status |
|---|---|---|---|
| Frozen explicit revisions | `RevisionPreflight.resolveFrozen`; `GitRevisionAccess.resolveCommit` | `RevisionPreflightTest.errorsForEveryUnresolvedOrSymbolicRevision` | PASS |
| Base/integration equality | preflight equality before ancestry/selection | `rejectsStalePrAsBaseOutOfDate` | PASS |
| Map ancestry | `isAncestor(map, integration)` | divergent-map preflight/flow/adapter tests | PASS |
| Head ancestry | `isAncestor(integration, head)` | invalid-head preflight/flow/adapter tests | PASS |
| Stale-map bounded window | `commitDistance(map, head)` | `acceptsStaleMapAndCountsFromMapRatherThanIntegration` | PASS |
| `map..head` interval | `SelectionRevisionInterval(map, head, distance)` | explicit-flow stale-map and trigger tests | PASS |
| Inventory/head binding | resolvable full inventory SHA equals resolved PR head | explicit-flow mismatch tests | PASS |
| Revisionless rejection | explicit flow rejects missing inventory revision | common/CLI/Gradle/Maven tests | PASS |
| Stale PR | outer `BASE_OUT_OF_DATE`, no selector output | common/CLI/Gradle/Maven tests | PASS |
| Too-old map | normal `SELECTION_RESULT / FULL_SUITE` | preflight/flow/adapter tests | PASS |
| Divergent map | normal `SELECTION_RESULT / FULL_SUITE` | preflight, flow, CLI tests | PASS |
| Invalid head | outer `ERROR` | common/CLI/Gradle/Maven tests | PASS |
| CLI adapter | explicit args and JSON envelope delegate to common flow | `ExplicitPrSelectTestsCommandTest` | PASS |
| Gradle adapter | additive properties, common delegation, execution gate | `GradleExplicitPrSelectionTest`; `SmartTestExecutionFilterTest` | PASS |
| Maven adapter | additive properties and shared delegation in all selector goals | `MavenExplicitPrSelectionTest` | PASS |
| Local-mode compatibility | `SchemaV2SelectorFlow` remains separate | analyzer local-worktree tests; adapter mode-gate tests | PASS |
| Workspace-head stamping safety | `WorkspaceRevisionVerifier.requireHead` precedes binding | focused adapter tests; TASK53 mismatch proof | PASS |
| Deterministic exact inventory | sorted codec; exact `TestIdentity`; duplicate rejection | codec and JUnit generator tests | PASS |
| PetClinic inventory equality | Gradle 73, Maven 73, directional differences 0 | `task53-5e-gradle-maven-petclinic.md` | PASS |
| PetClinic fresh scenario | P1 map, P1 integration/base, P2 head: equal `SELECTED` (6) | TASK53 evidence | PASS |
| PetClinic stale-map scenario | P0 map to P2 head: equal `SELECTED` (9), observes P0..P1 and P1..P2 | TASK53 evidence | PASS |
| PetClinic stale-PR scenario | equal `BASE_OUT_OF_DATE`, no selection output/file | TASK53 evidence | PASS |
| PetClinic too-old scenario | equal normal `FULL_SUITE` | TASK53 evidence | PASS |
| PetClinic provenance failures | inventory mismatch and revisionless inventory are equal `ERROR` | TASK53/focused adapter evidence | PASS |
| PetClinic invalid ancestry | equal `ERROR` | TASK53/focused adapter evidence | PASS |
| Maven test-scope resolution | inventory-generating/selection goals use `ResolutionScope.TEST` | annotations; PetClinic 26→73 generic fix | PASS |
| 5b non-regression | `SchemaV2TestSelector` unchanged during 5e | Git history/diff; selector tests | PASS |
| No Git mutation | Git access uses only rev-parse, merge-base, rev-list, diff, ls-files | production inspection | PASS |
| No 5c/5d changes | no collector, fragment, join, publication, storage, or Jenkins policy in 5e | 5e diff and boundary inspection | PASS |

## Inventory and adapter safety

Test Inventory remains the authoritative complete logical test set for one concrete build/test target
at one concrete revision—not executed tests or arbitrary names. JUnit Platform discovery does not
execute bodies; exact identity and duplicate protections remain. Gradle and Maven verify workspace
HEAD before stamping a generated explicit inventory and perform no checkout/reset/merge/rebase.

Normal selector output is unchanged: `SELECTED`, `NONE`, or `FULL_SUITE`. Stale PR and invalid states
remain distinct outer results. Gradle and Maven prevent selective execution for `BASE_OUT_OF_DATE`,
fail visibly for `ERROR`, remove restrictive filtering for `FULL_SUITE`, execute none for `NONE`, and
retain existing mandatory selection for `SELECTED`. Maven test-scope dependency resolution is generic
build-tool correctness: discovery needs the configured target runtime; target-universe semantics did
not change.

## Boundaries and deferred work

5e is a revision eligibility/explicit-flow layer around the unchanged 5b union: direct class edges,
bounded setup scopes, unmapped `ALWAYS_SELECT`, new tests, changed tests, and deleted-test handling.
It consumes an immutable revision-bound published map and changes no 5c collection/fragment/mapping or
5d join/publication/storage/Jenkins semantics. It does not discover historical maps automatically.

Blockers: none.

Deferred extension/adoption work: automatic historical-map lookup; remote map registry/discovery;
CI-provider-specific revision extraction; optional production Jenkins selector wiring; deployment and
adoption configuration; `maxCommitDistance` policy tuning.

## Closure chain

- TASK49: revision preflight.
- TASK50: explicit committed selector flow.
- TASK51: inventory revision provenance.
- TASK52: CLI exposure.
- TASK53: Gradle/Maven exposure and pinned PetClinic equivalence.
- TASK54: final code/evidence audit, current documentation cleanup, and closure.

## Closure decision

**5e DONE**
