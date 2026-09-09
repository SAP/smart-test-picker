# TASK66 — item 6 SCM/PR resolution closure audit

## Decision

**6 DONE.** Current production code, TASK58–TASK65 evidence, and the final verification show no
item-6 correctness blocker. The remaining capabilities listed below are safe, explicit boundaries,
not incomplete implementations.

Baselines were clean at `45457d2e0ba233d3b3f75d626305450476e324f3` (main STP) and
`1c9fd40a59080c1b73fc973468c63e1424677db4` (Jenkins plugin). TASK66 changes documentation and
machine-readable closure evidence only; production code is unchanged.

## Final responsibility boundary

Item 6 converts real CI/provider state into provider-specific authoritative evidence, resolves one
unambiguous typed PR context through a provider-neutral resolver, freezes and locally verifies the
integration/base/head/workspace commits, classifies checkout mode, retains provider and workspace
provenance, returns typed outcomes, and hands `PrSelectionRevisions` to the existing 5e explicit
selector flow through Jenkins' automatic API.

The boundary is:

```text
real CI/provider state
    -> provider-specific authoritative evidence
    -> generic SCM/PR resolver
    -> frozen typed ResolvedPrContext
    -> PrSelectionRevisions
    -> existing 5e explicit selector flow
```

Item 6 owns PR detection, provider metadata interpretation, frozen integration/base/head resolution,
workspace revision and checkout-mode classification, provider/workspace provenance, safe local
object/history verification, typed resolver outcomes, and automatic Jenkins handoff. It does not own
coverage-map or remote storage, credential lifecycle/productization, selection fallback policy, 5e
eligibility or inventory policy, selector union/mapping semantics, or a secondary immutable
source-head workspace for synthetic merges.

## Revision semantics

- `integrationRevision`: frozen current target/integration commit associated with the provider PR
  snapshot. Common-core 5e uses it for eligibility.
- `prBaseRevision`: frozen, locally validated provider target/base provenance retained for API
  compatibility. It is not a historical fork point and does not independently decide eligibility.
- `prHeadRevision`: frozen true PR source-head commit.
- `workspaceRevision`: actual Jenkins workspace `HEAD`, read by the generic resolver. It is Jenkins
  diagnostic/execution context and is never substituted for source head in merge mode.

TASK62 is authoritative over the historical TASK54/TASK56 contract. The canonical stale-PR rule is:

```text
integrationRevision ancestor-or-equal prHeadRevision
```

When Git proves that predicate false, common-core `RevisionPreflight` returns `BASE_OUT_OF_DATE`.
`prBaseRevision == integrationRevision` is no longer an eligibility rule. Missing objects and shallow
history stop in the resolver as typed failures, so an unprovable predicate is not mislabeled stale.
TASK54 retains an explicit TASK62 clarification; historical evidence is preserved rather than rewritten.

## Provider architecture

`ScmPrContextResolver` reads `SCMRevisionAction` and `ChangeRequestSCMHead`, rejects multiple actions,
classifies ordinary heads as `NOT_PR_BUILD`, and delegates concrete semantics through
`ScmPrProviderAdapter`. Zero claiming adapters yields `UNSUPPORTED_SCM_CONTEXT`; multiple claiming
adapters yields `AMBIGUOUS_SCM_CONTEXT`. The generic resolver contains no GitHub types or field mapping.

It reads workspace `HEAD`, accepts only full SHA-1/SHA-256 IDs, verifies each required commit with
`git cat-file`, rejects shallow repositories, preserves typed status, and creates `ResolvedPrContext`
only after all required data and provenance are complete. It does not infer PRs from branch names,
use `GIT_COMMIT` as head authority, inspect merge parents, fetch, unshallow, check out, switch, reset,
merge, or rebase.

## GitHub provider

`GitHubBranchSourcePrAdapter` is the only implemented provider adapter. Provider API inspection,
provider-class harness tests, Docker runtime evidence, and TASK65 real indexing agree on:

| Meaning | GitHub Branch Source API |
| --- | --- |
| PR identity | `PullRequestSCMHead.getId()` |
| target name | `getTarget().getName()` |
| optional source name | `getSourceBranch()` |
| checkout mode | `getCheckoutStrategy()` |
| frozen target/integration | `PullRequestSCMRevision.getBaseHash()` |
| frozen provider base provenance | `getBaseHash()` |
| true source head | `getPullHash()` |
| optional provider merge revision | `getMergeHash()`; not needed by the adapter |

The mapping is `integrationRevision=baseHash`, `prBaseRevision=baseHash`, and
`prHeadRevision=pullHash`. `HEAD` maps to `SOURCE_HEAD`; `MERGE` maps to `SYNTHETIC_MERGE`.
This mapping is correct under TASK62 because base hash is frozen target/base provenance, not a fork
point. Other SCM providers are future adapters; item 6 promised an extensible generic seam plus a
GitHub Branch Source implementation, not GitLab, Bitbucket, or generic change-request support.

TASK65 used a real private GitHub PR, actual Multibranch indexing and `GitHubSCMSource`, a naturally
created PR job/action/head/revision, `HEAD` checkout, and workspace `HEAD == pullHash`. No provider
object was manually injected. `GIT_COMMIT` was null. Public `stpPrSelect` generated the real inventory,
invoked bundled common-core `explicit-select`, and returned `SELECTION_RESULT/SELECTED`. No provider
assumption was contradicted.

## Resolver safety

`REQUIRED_COMMIT_MISSING` and `SHALLOW_HISTORY` remain first-class resolver outcomes. There is no
hidden recovery. Normal Jenkins SCM checkout may fetch as part of Jenkins' own checkout, but item-6
production code only verifies the resulting local repository. Resolver-level recovery was deliberately
deferred because safe credential-aware recovery is not required for correctness: unavailable or
incomplete history is refused, never guessed.

The automatic path also preserves `NOT_PR_BUILD`, `PR_METADATA_MISSING`,
`TARGET_BRANCH_UNKNOWN`, `SOURCE_BRANCH_UNKNOWN`, `PR_HEAD_UNRESOLVED`,
`INTEGRATION_REVISION_UNRESOLVED`, `PR_BASE_UNRESOLVED`,
`WORKSPACE_REVISION_UNRESOLVED`, `AMBIGUOUS_SCM_CONTEXT`, `UNSUPPORTED_SCM_CONTEXT`, and
`SYNTHETIC_MERGE_UNCLASSIFIED`. A resolver failure returns `RESOLUTION_FAILED`; the Pipeline body and
selector are not invoked and no execution plan is created.

## Automatic Jenkins flow

`stpPrSelect(coverageMap: ...) { ... }` is the automatic public API. The caller supplies no integration,
PR-base, PR-head, or workspace revision. It calls `ScmPrContextResolver`, obtains the complete
`ResolvedPrContext`, calls `toFiveERevisions()`, and passes that value unchanged to the shared
`ExplicitPrSelectionRunner`. It does not recompute SHAs from environment variables or local heuristics.

The typed automatic result categories are `RESOLUTION_FAILED`, `PR_HEAD_INVENTORY_UNAVAILABLE`, and
`SELECTION_COMPLETE`; resolution status and the common-core outer/selector statuses remain separate.
No resolver status is converted to a full-suite/fail/run-all policy. That central fallback decision is
item 8.

## Manual compatibility

`stpExplicitPrSelect` remains the explicit/manual API for callers that already possess frozen
integration, base, and head revisions and a revision-bound head inventory. Automatic mode was additive.
TASK63 focused tests, Jenkins harness/Docker flow, the full 58-test plugin suite, and TASK65 packaging
prove the shared runner and manual step remained intact.

## SOURCE_HEAD support

For `SOURCE_HEAD`, resolver success requires `workspaceRevision == prHeadRevision`. Before selector
invocation, `stpPrSelect` verifies workspace `HEAD` still equals the frozen head and verifies the
generated inventory's version, tests array, and `revision == prHeadRevision`. The inventory generator
receives the frozen SHA directly; the automatic step neither rewrites nor re-stamps the artifact.
TASK63 Docker and TASK65 real GitHub Multibranch E2E both passed the full path.

## SYNTHETIC_MERGE boundary

GitHub merge metadata resolves as `RESOLVED/SYNTHETIC_MERGE`, including distinct source-head and
workspace revisions. Automatic selection returns `PR_HEAD_INVENTORY_UNAVAILABLE` because the merge
workspace cannot authoritatively generate a source-head inventory. The body and selector are not
invoked, the workspace is not called the source head, and the primary workspace is not mutated.

Lack of automatic synthetic-merge selection is a **supported capability boundary / deferred
extension**, not an item-6 blocker. The implemented behavior fully preserves the semantic distinction
and fails safely until a separate immutable source-head workspace exists.

## Failure/status model

Item 6 reports typed facts; it does not decide centralized execution policy. There is no hardcoded
mapping such as `NOT_PR_BUILD -> FULL_SUITE`, `SHALLOW_HISTORY -> FAIL`, or
`UNSUPPORTED_SCM_CONTEXT -> RUN_ALL`. `AutomaticPrSelectionResult` exposes no credentials, tokens,
remotes, or mutable Jenkins runtime objects.

`ResolvedPrContext` retains PR ID, target branch, optional source branch, all four frozen/current
revisions, checkout mode, provider name, and field-level authority. Workspace provenance is added only
by the resolver as `WORKSPACE_GIT_HEAD`. SHA validation accepts full 40-hex SHA-1 and 64-hex SHA-256
object IDs; abbreviated and symbolic frozen inputs are rejected. Jenkins and common core remain aligned.

## 5e boundary

Jenkins passes only integration/base/head values and does not implement Git ancestry, map ancestry,
map-to-head distance, `BASE_OUT_OF_DATE`, inventory revision matching policy, mapping, or selector union.
Those remain in `RevisionPreflight`, `ExplicitPrSelectorFlow`, `SchemaV2SelectionAnalyzer`, and
`SchemaV2TestSelector`. Jenkins performs only workspace/head safety checks necessary to bind execution
to resolved SCM facts.

## Item-7 / Item-8 boundaries

TASK65 temporarily reused an already-authorized Jenkins SCM credential solely for external indexing,
then cleared the SCMSource reference and removed and verified absence of the credential. This did not
create an STP credential model, remote storage, or product configuration; item 7 remains not started.
Item 8 also remains not started because item 6 returns typed outcomes without choosing fallback behavior.

## Requirement matrix

| Requirement | Implementation | Test/evidence | Status |
| --- | --- | --- | --- |
| Normal branch | Typed non-change head becomes `NOT_PR_BUILD` | `ScmPrContextResolverTest.normalBranchAndPrLookingBranchAreNotPrBuilds` | PASS |
| Unsupported PR provider | Zero adapter claims becomes `UNSUPPORTED_SCM_CONTEXT` | resolver and GitHub adapter tests; TASK59/61 | PASS |
| Ambiguous provider context | Multiple actions/adapters or conflicts are ambiguous | resolver/adapter ambiguity tests | PASS |
| GitHub HEAD local fixture | base/pull mapping and `SOURCE_HEAD` equality | `GitHubBranchSourcePrAdapterTest`; TASK61 harness | PASS |
| GitHub MERGE local fixture | typed mode allows workspace/head inequality | adapter test; TASK61 Docker | PASS |
| Missing commit | local object verification returns typed failure | resolver and adapter tests | PASS |
| Shallow history | shallow repository returns typed failure | resolver and adapter tests; TASK58 probe | PASS |
| Ignored `GIT_COMMIT` | never used as revision authority | resolver/adapter tests; TASK65 null value | PASS |
| Full SHA-1 / SHA-256 | both native full object-ID formats accepted | TASK60 unit and local SHA-256 probe | PASS |
| Manual explicit mode | `stpExplicitPrSelect` and shared runner retained | TASK63 regression/full suite | PASS |
| Automatic SOURCE_HEAD Docker flow | public step, generated inventory, real selector | TASK63/TASK64 Docker evidence | PASS |
| Synthetic MERGE safe rejection | resolved metadata, unavailable inventory, no body/selector | automatic result test; TASK63 Docker | PASS |
| TASK62 stale PR | integration-to-head non-ancestry gives `BASE_OUT_OF_DATE` | deterministic common-core and automatic integration tests | PASS |
| Stale map | common-core distance policy retained | TASK62/TASK63 tests | PASS |
| Too-old map | common core returns full-suite selector result | TASK62/TASK63 tests | PASS |
| Inventory mismatch | common core rejects revision mismatch | `ExplicitPrSelectorFlowTest`; TASK62/TASK63 | PASS |
| Real GitHub Multibranch SOURCE_HEAD | natural provider objects and `SELECTED` result | TASK65 build `PR-1 #4` | PASS |
| Credential cleanup | SCM reference cleared and credential absent | TASK65 cleanup evidence | PASS |

## Closure criteria

| # | Criterion | Result |
| ---: | --- | --- |
| 1 | PR/provider detection implemented | PASS |
| 2 | GitHub provider implemented | PASS |
| 3 | Frozen revisions resolved | PASS |
| 4 | Source head distinct from workspace merge supported semantically | PASS |
| 5 | Workspace revision captured | PASS |
| 6 | Provenance retained | PASS |
| 7 | Typed failures preserved | PASS |
| 8 | Shallow/missing safety preserved | PASS |
| 9 | No unsafe guessing | PASS |
| 10 | No hidden fetch/mutation | PASS |
| 11 | 5e policy not duplicated | PASS |
| 12 | Automatic Jenkins step implemented | PASS |
| 13 | Manual mode preserved | PASS |
| 14 | SOURCE_HEAD full Docker E2E passed | PASS |
| 15 | Real external GitHub SOURCE_HEAD E2E passed | PASS |
| 16 | Synthetic merge safely refused | PASS |
| 17 | No item-7 implementation leakage | PASS |
| 18 | No item-8 policy leakage | PASS |
| 19 | Full Jenkins regression suite passed | PASS |
| 20 | Packaging/integrity passed | PASS |

## External-proof decisions

Real stale external PR validation is not required for closure. TASK62 deterministically proves the
common-core predicate, provider-native tests prove the revision mapping, TASK63 proves automatic
handoff, and TASK65 confirms the real provider semantics without contradiction. A real stale PR would
repeat graph behavior rather than answer an unresolved provider question.

Real external MERGE indexing is not required for closure. Actual provider classes/API inspection,
local harness and Docker evidence establish merge metadata semantics, while automatic execution stops
safely before inventory/selection. There is no open semantic question that justifies a new experiment.

## Deferred capabilities

- Separate immutable source-head workspace for automatic synthetic-merge selection.
- Resolver-level credential-aware fetch/unshallow recovery.
- GitLab, Bitbucket, and other provider adapters.
- Real external stale-PR demonstration.
- Real external MERGE observation.
- Item 7 remote storage and productized credentials.
- Item 8 centralized failure/fallback policy.

All are non-blocking because current unsupported or unverifiable states terminate with precise typed
results before inventory generation, selector invocation, plan creation, or workspace mutation.

## Closure decision

**CORRECTNESS BLOCKERS: NONE.**

**6 DONE.** Items 7 and 8 remain **NOT STARTED**.
