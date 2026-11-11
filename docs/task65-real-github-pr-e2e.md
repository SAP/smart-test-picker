# TASK65 — real GitHub/GHE PR E2E

## Goal

TASK65 validates the automatic Jenkins PR-selection chain against a real private GitHub pull
request indexed by GitHub Branch Source. The proof uses only the public `stpPrSelect` Pipeline step;
the Jenkinsfile supplies no integration, PR-base, PR-head, or workspace revision.

## Environment

The disposable local controller ran Jenkins 2.516.2 with GitHub Branch Source
1917.v9ee8a_39b_3d0d and Git plugin 5.7.0. The provider was GitHub at
`https://api.github.com`. The Multibranch project was `task65-real-github-pr`.

The private repository required authentication for indexing. The already-authorized host GitHub
credential was installed temporarily in the disposable Jenkins global credential store as
`task65-github-scm`, referenced only by this SCMSource, and removed immediately after evidence
capture. The SCMSource reference was cleared first. Final verification reported that the credential
ID was absent and the SCMSource credential ID was empty. No secret value was recorded.

## Repository / PR

The controlled sandbox was `ljubisap/stp-gradle-poc`, PR 1, targeting `main` from
`task65-real-pr`. The frozen provider base was
`6c7df1bf95bfd543dc9067e9fe5e2b9fe9c3704e`. The final proof head was
`ea639ba1486158eef9bbdb12075d30b291598ef0`.

The original PR production change modifies `com.example.modulea.Foo`, which is mapped to
`com.example.modulea.FooTest#foo`. Three fixture-only commits were added while completing the proof:
the Jenkinsfile aggregates the two module inventories, the map remains bound to the frozen
integration commit, and the wrapper download timeout tolerates the observed network speed. These are
changes to the external sandbox only, not STP production changes. The PR and branch remain open for
the item-6 closure audit; no production repository was touched.

## Multibranch configuration

The existing `GitHubSCMSource` used `repoOwner=ljubisap`, `repository=stp-gradle-poc`, and
`apiUri=https://api.github.com`. `OriginPullRequestDiscoveryTrait` used strategy ID 2, which is PR
HEAD discovery. The Pipeline factory loaded `Jenkinsfile.task65`.

Actual Multibranch indexing discovered `main` and `PR-1` and naturally scheduled the PR build. The
successful evidence build was `task65-real-github-pr/PR-1 #4`. No `SCMRevisionAction`,
`PullRequestSCMHead`, or `PullRequestSCMRevision` was manually created or attached.

## Real provider metadata

Runtime inspection of build 4 produced:

| Field | Value |
| --- | --- |
| SCM head class | `org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead` |
| SCM revision class | `org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision` |
| checkout strategy | `HEAD` |
| PR ID | `1` |
| target | `main` |
| source | `ljubisap/stp-gradle-poc:task65-real-pr` |
| baseHash | `6c7df1bf95bfd543dc9067e9fe5e2b9fe9c3704e` |
| pullHash | `ea639ba1486158eef9bbdb12075d30b291598ef0` |
| mergeHash | `null` |
| workspace HEAD | `ea639ba1486158eef9bbdb12075d30b291598ef0` |

The real environment values were `CHANGE_ID=1`, `CHANGE_TARGET=main`,
`CHANGE_BRANCH=task65-real-pr`, `BRANCH_NAME=PR-1`, and `GIT_COMMIT=null`. They corroborate identity
and branch names but are not revision authority. In particular, resolution completed while
`GIT_COMMIT` was null.

## TASK61 assumption comparison

| TASK61 assumption | Result | Real evidence |
| --- | --- | --- |
| `PullRequestSCMHead` is the PR head type | CONFIRMED | actual `SCMRevisionAction` head class |
| `PullRequestSCMRevision` is the PR revision type | CONFIRMED | actual action revision class |
| `getPullHash()` is true source-head SHA | CONFIRMED | equals indexed ref checkout and workspace HEAD |
| `getBaseHash()` is frozen provider target/base SHA | CONFIRMED | equals the PR base/integration commit |
| `SCMRevisionAction` carries the indexed revision | CONFIRMED | build action supplied the typed revision used by the resolver |
| `GIT_COMMIT` is unnecessary as PR-head authority | CONFIRMED | it was null while typed resolution succeeded |

This real indexing proof confirms, and does not rewrite, the provider API/runtime conclusions in
TASK61.

## Checkout strategy

The tested trait configuration produced `HEAD`, resolved by STP as `SOURCE_HEAD`. The checkout fetched
`refs/pull/1/head` and checked out the exact pull hash. The repository reported
`--is-shallow-repository=false`; no resolver fetch, unshallow, checkout, reset, switch, merge, or
rebase behavior was added.

## Automatic stpPrSelect execution

The Jenkinsfile invoked `stpPrSelect` without revision parameters. The public chain was:

```text
GitHub PR -> GitHub Branch Source indexing -> PR-1 Multibranch job
-> real SCMRevisionAction -> ScmPrContextResolver
-> GitHubBranchSourcePrAdapter -> ResolvedPrContext -> toFiveERevisions()
-> revision-bound head inventory -> ExplicitPrSelectionRunner
-> common-core explicit-select -> AutomaticPrSelectionResult
```

The successful result was `resolutionStatus=RESOLVED`,
`automaticStatus=SELECTION_COMPLETE`, `checkoutMode=SOURCE_HEAD`,
`selectionOuterStatus=SELECTION_RESULT`, and `selectorStatus=SELECTED`. The resolved integration and
PR-base revisions were the frozen base commit; PR-head and workspace revisions were the final pull
hash.

## Inventory provenance

The real build generated and archived a version-1 inventory with revision
`ea639ba1486158eef9bbdb12075d30b291598ef0` and 10 discovered logical tests. Its revision equals both
the provider pull hash and workspace HEAD. The Jenkinsfile merged revision-bound module inventories
without rewriting their revision.

## Selection result

The complete schema-v2 `PUBLISHED` map was checksum-valid and bound to
`6c7df1bf95bfd543dc9067e9fe5e2b9fe9c3704e`, an ancestor-or-equal of integration. Common-core
`explicit-select` ran with the resolved revisions and returned `SELECTED`: 10 selected tests, zero
unmapped tests, and changed class `com.example.modulea.Foo`. No full-suite result was used as the
happy-path proof.

## Stale-PR validation

**REAL STALE PR VALIDATION DEFERRED.** Making the controlled external PR stale would require advancing
the target branch and would disturb the minimal frozen graph. TASK62 and local provider-native tests
remain the stale-PR evidence; this result does not label synthetic provider state as real indexing.

## Synthetic-merge observation

No real MERGE-strategy build was run. The minimum real SOURCE_HEAD proof is complete. No synthetic
merge inventory feature or secondary checkout was added.

## Production code changes

None in the main STP or Jenkins plugin repository. The first successful build attempt exposed only a
sandbox Jenkinsfile aggregation mismatch; a later attempt exposed a Gradle distribution read timeout.
Both were corrected in the controlled external fixture. No provider semantic contradiction or STP
product defect was established, so no new unit test was added.

The full Jenkins suite passed all 58 tests. HPI packaging, deterministic adapter build verification,
generated manifest integrity, and dependency/enforcer checks passed. The first sandboxed Maven test
attempt hit the already-known `target/antrun/build-main.xml (Operation not permitted)` environment
failure; the unchanged escalated rerun passed.

## Safety boundaries

No credential secret appears in repository content or evidence. Only credential ID
`task65-github-scm` and its verified removal are recorded. No fetch/recovery feature, synthetic-merge
support, item-7 storage/credential abstraction, item-8 failure policy, Spring Core run, or full MCS
run was introduced.

## Remaining item-6 work

Real GitHub Branch Source SOURCE_HEAD resolution and automatic selection are now proven. Canonical
item 6 remains **IN PROGRESS** only for its final closure audit. Items 7 and 8 remain **NOT STARTED**.

