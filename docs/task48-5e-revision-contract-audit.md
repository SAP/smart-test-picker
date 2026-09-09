# TASK 48 — 5e revision contract audit

## TASK49 production refinement

TASK48 recorded the initial contract below as
`prBaseRevision == mapRevision == integrationRevision`. TASK49 does not erase that historical
decision; it refines it after accounting for the production reality that a complete coverage map may
legitimately lag the integration branch. The canonical contract from TASK49 onward is:

```text
prBaseRevision == integrationRevision
mapRevision ancestor-or-equal integrationRevision
integrationRevision ancestor-or-equal prHeadRevision
commitCount(mapRevision..prHeadRevision) <= maxCommitDistance
```

The map mismatch section below is therefore superseded only where it treats every older map as
unusable. A compatible older map remains safe inside the existing 5b distance window because selection
uses `Map(mapRevision)`, `diff(mapRevision, prHeadRevision)`, and the authoritative head inventory.
There is no second staleness threshold. A distance beyond `maxCommitDistance`, or a map not ancestral
to integration, produces `FULL_SUITE`. A stale PR still produces `BASE_OUT_OF_DATE`, and a head not
descended from integration is an explicit preflight `ERROR`.

For example, `Map(R0)`, integration/base `R1`, and head `R2` is eligible when ancestry holds and
`commitCount(R0..R2)` is within the window. Its effective interval is `R0..R2`, never `R1..R2`.
TASK49 implements this build-tool-neutral core preflight but does not wire adapters, so 5e is now
**IN PROGRESS**.

## Initial TASK48 decision (refined by TASK49 above)

TASK48 initially governed canonical backlog item **5e Arbitrary base/head revisions — STP core** by one mandatory
eligibility invariant:

```text
prBaseRevision == mapRevision == integrationRevision
```

All three values denote resolved Git commits, not branch names, moving references, trees, workspace
state, or caller labels. Selection may begin only after the invariant and the base/head relationship
have been validated. There is no override. This audit defines the contract only; 5e implementation
remains not started.

## Revision terminology

- `mapRevision` is the exact Git commit recorded in, and cryptographically bound by the integrity of,
  the validated published schema-v2 coverage map. For `Map(R5)`, it is `R5`.
- `integrationRevision` is the exact current commit of the PR target branch that the integration
  process has resolved for this evaluation. The target is commonly `develop`, `main`, or a
  `release/*` branch. A branch name alone is not this value; for `develop HEAD = R5`, it is `R5`.
- `prBaseRevision` is the exact target-branch commit on which the evaluated PR head is based. It is
  the intended integration baseline supplied for the PR, not an ambiguously named `baseRevision` and
  not a merge base silently recomputed against a moving branch. For a PR forked at `R3`, it is `R3`.
- `prHeadRevision` is the exact immutable commit containing the PR changes to evaluate. It is not
  required to equal the workspace's current `HEAD`. For the PR commit `P1`, it is `P1`.

Core APIs for 5e should use these explicit names. In particular, `baseRevision` must not be used when
it could mean the map revision, current target tip, merge base, or PR base.

## Eligibility and happy path

Given:

```text
mapRevision         = R5
integrationRevision = R5
prBaseRevision      = R5
prHeadRevision      = P2
```

all revisions must resolve locally as commits, and `R5` must be an ancestor of `P2`. Selection is
then allowed over the committed interval `R5 -> P2`. The selector consumes `Map(R5)`,
`diff(R5, P2)`, and the authoritative test inventory for `P2`.

The four commit values must be frozen for an evaluation. A moving integration reference must be
resolved before validation; implementations must not resolve it again later and accidentally compare
different tips. Object-resolution, equality, ancestry, diff, or head-inventory ambiguity fails closed.

## Stale PR precondition

For history `R3 --- R4 --- R5`, with `integrationRevision = R5`, `Map(R5)`, and PR history
`R3 --- P1`, the PR is **not eligible for STP selection**. Its `prBaseRevision` is `R3`, which differs
from the required integration and map baseline `R5`. STP must not produce `SELECTED`, `NONE`, or
`FULL_SUITE`, because selection did not start. The integration process must require the PR author or
automation outside STP to rebase the PR onto, or merge, the current target branch. Selection becomes
eligible only when the resulting PR base is `R5` and remains the validated current target revision.

No safety guarantee can be made for the stale PR. `Map(R5)` describes dependency knowledge at `R5`,
while the `R3`-based PR world may omit tests, production classes, test-to-code relationships,
setup/lifecycle relationships, and build changes introduced or removed in `R4` or `R5`. Consequently,
none of `Map(R5) + diff(R3, P1)`, `Map(R5) + diff(R5, P1)`, or an unvalidated
`Map(R3) + diff(R3, P1)` is allowed by this contract.

There is no `allowOutdatedBase`, `requireUpToDateBase`, `forceHistoricalSelection`, or equivalent
override. Safety is not configurable at this boundary.

## Map mismatch and existing 5b fallback

When `mapRevision != integrationRevision`, there is no coverage map for the required baseline. For
example, `Map(R4)` cannot be used when the current integration revision and PR base are `R5`.
This is an unusable/stale-map condition already owned by 5b and returns its semantic RUN_ALL fallback,
publicly spelled `FULL_SUITE`. A missing map at the required baseline has the same existing 5b result.
This audit does not introduce a redundant `NO_SAFE_MAP` status.

This differs from an out-of-date PR: map absence or mismatch means selection was requested at an
eligible integration baseline but safe dependency data was unavailable, so running all tests is safe.
A stale PR means the integration precondition itself failed, so the selector must not be invoked.

## Git and working-tree contract

Both `prBaseRevision` and `prHeadRevision` must exist and resolve locally as commits.
`prBaseRevision` must be an ancestor of `prHeadRevision`; equality is permitted by the revision
relationship contract and represents an empty committed interval, with 5b deciding the resulting
selection. Missing, tree-only, non-commit, unresolved, or non-descendant input is invalid and fails
closed before selection. The integration process must make the objects available; STP does not fetch,
check out, merge, rebase, reset, or otherwise repair repository state.

PR CI is strictly commit-to-commit. Staged, unstaged, and untracked changes are not part of
`diff(prBaseRevision, prHeadRevision)` and are not included in the authoritative head inventory.
Existing 5b worktree-aware behavior may remain available to local workflows, but it is a separate mode
and must not be mixed into the 5e PR contract. No requirement that `prHeadRevision == workspace HEAD`
is imposed; an implementation may inspect commits without checkout mutation.

## Ownership and status model

The integration process determines and freezes the target branch's `integrationRevision`, the PR's
`prBaseRevision`, and its `prHeadRevision`, makes those commits resolvable, and enforces the up-to-date
policy. STP independently validates the supplied commits, equality invariant, and ancestry and refuses
selection if validation fails. STP never silently repairs a stale branch.

The smallest clean public contract leaves the selector result states unchanged: `SELECTED`, `NONE`,
and `FULL_SUITE` (with operational errors handled by existing boundaries). A 5e preflight eligibility
result is separate from `SelectionOutput`:

- eligible: proceed to the existing 5b selector;
- `NOT_ELIGIBLE / BASE_OUT_OF_DATE`: `prBaseRevision != integrationRevision`; do not invoke selection;
- invalid revision relationship/input: explicit preflight error; do not invoke selection.

`BASE_OUT_OF_DATE` is the canonical reason code for the not-eligible outcome. It is an integration/core
preflight status, not a new selector-output state. An implementation may prevent selector invocation
upstream, but STP core must still validate rather than trust that upstream enforcement occurred.

## Decision table

| Map | Integration | PR base | PR head relation | Expected result |
| --- | --- | --- | --- | --- |
| R5 | R5 | R5 | descendant of R5 | Eligible; run 5b selection with `Map(R5)` and `diff(R5, head)` |
| R5 | R5 | R3 | descendant of R3 | `NOT_ELIGIBLE / BASE_OUT_OF_DATE`; reject stale PR before selection |
| R4 | R5 | R5 | descendant of R5 | No map for required baseline; existing 5b `FULL_SUITE` fallback |
| missing | R5 | R5 | descendant of R5 | Missing safe map; existing 5b `FULL_SUITE` fallback |
| R5 | R5 | R5 | non-descendant | Invalid revision relationship; explicit preflight error, no selection |
| R5 | R5 | missing | n/a | Invalid/missing base; explicit preflight error, no selection |
| R5 | R5 | R5 | missing head | Invalid/missing head; explicit preflight error, no selection |

## Relationship to 5b and excluded historical maps

5e owns explicit PR base/head inputs, integration-base freshness, commit resolution, and revision
relationship validation. It does not replace or redefine 5b. After 5e admits an evaluation, 5b still
owns schema-v2 ingress and map validation, direct class selection, setup-scope expansion, unmapped
`ALWAYS_SELECT`, new/changed-test union, `NONE`, and the `FULL_SUITE` safety fallback.

Using `Map(R5)` for a PR based on `R3` is unsupported. Automatically finding or using a real
`Map(R3)` is also outside TASK48 and base 5e. Historical-map selection would require a separate backlog
item and safety contract; the presence of such a map does not make a stale PR eligible under this one.

## Scope and backlog

TASK48 changes documentation only. It changes no selector/core production code, CLI, Gradle, Maven,
Jenkins, orchestration, storage, mapping, configuration, or 5d behavior. Canonical state after the audit
is 5a/5b/5c/5d **DONE** and 5e **NOT STARTED / CONTRACT DEFINED**. Implementation and tests for 5e
remain future work.
