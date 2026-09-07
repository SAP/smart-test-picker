# TASK 43 — 5d map orchestration productization

TASK 43 starts canonical backlog item 5d. It substantially replaces the local Jenkins proof with a
versioned plan and an immutable storage boundary, but 5d remains **IN PROGRESS** pending physical Jenkins
validation and production positive non-execution evidence.

## Current POC audit

| Capability | Current POC behavior | Production gap | TASK 43 action |
| --- | --- | --- | --- |
| Prepare | Caller supplied revision and inventory; runtime split it into numbered text files | Plan omitted project, branch, tool, target, collector, assignments and artifact identities | Plan v2 freezes all of these values and validates exact assignment equality |
| Collect | Gradle ASM default or explicit JACOCO; fragment validated after the body | Gradle-only API and caller-created transport names | Added Maven/JACOCO mode and plan-owned fragment, diagnostic and stash identities |
| Publish | Shared schema-v2 runtime joined fragments | Local `~/.gradle` path, build-number key, no lookup or pointer | Added immutable revision store, validated lookup and branch pointer |
| Transport | Pipelines stashed fragment plus diagnostic | Names manually duplicated in Jenkinsfiles | Plan v2 derives stable names; a future wrapper still needs to invoke Pipeline stash/unstash |
| Storage | Atomic local file write | No durable backend, pointer, concurrency or retention contract | Added a storage interface and reference file backend with conflict rejection and ancestry guard |

## Lifecycle and ownership

The intended lifecycle is merged revision, authoritative build-tool inventory, deterministic plan,
sequential collector contexts, schema-v2 fragments, stash/unstash, shared-model join, exact accounting,
immutable publication, pointer update, and later lookup. Mapping remains separate from selection.

The orchestrator owns revision, authoritative inventory, assignments, target, positive non-execution
evidence and publication. A collector owns reported tests, coverage, executable unmapped tests, outcomes,
setup scopes, runtime integrity, completion and diagnostics. It never manufactures global expectation.

## Plan, transport and join

`mapping-manifest.json` version 2 is an orchestration plan, not a coverage map. It contains project, branch,
revision, build tool, test target, collector, inventory, expected tests/shards, exact assignments, shard input,
fragment, diagnostic and stash identities. Revision resolves at most once. IDs are `0..N-1`; sorted exact
identities are assigned deterministically and validation requires every expected identity exactly once.

Gradle authoritative discovery is `generateHeadTestInventory`; Maven discovery is
`generate-head-test-inventory`. Runtime output is never inventory. ASM is the Gradle default, JACOCO is an
explicit fallback, and Maven supports JACOCO only. Each collector context remains sequential and transports
only a validated fragment, compact diagnostic and optional positive evidence through stash/unstash.

Shared Java model code performs fragment decode, join, expectation/completeness construction, validation and
encoding. Missing, unexpected or duplicate shards/tests, corrupt/incomplete fragments, revision disagreement
and unexplained identities prevent publication. Full raw diagnostics are debug-only.

Positive non-execution evidence remains the principal gap. It must come from exact structured events, never
`expected - reported`. A parameterized logical test is non-executed only if every invocation is non-executed;
a successful sibling makes it executable and mapped. The Task 42 common model enforces exact equality, but
the Jenkins adapter does not yet emit and transport this production evidence artifact.

## Publication, concurrency and retention

`CoverageMapStore` defines immutable put/read, latest-revision read and guarded pointer update. The reference
backend key is `<root>/<safe-project>/<safe-branch>/<safe-revision>/coverage-map-v2.json`; components combine
a sanitized prefix and SHA-256 suffix. Equal retries are idempotent and conflicting bytes are rejected.
Complete PUBLISHED schema-v2 bytes are written and read back before the locked branch pointer update.

An older candidate that is an ancestor of current latest remains stored but cannot move the pointer backward;
divergent candidates are rejected. The file backend is explicitly local/reference, not remote production
storage. Immutable maps may coexist and retention must never delete the pointed revision. If lookup finds no
safe map, it returns none; 5b remains responsible for FULL_SUITE and no empty map is synthesized. Checksums
are integrity, not authenticity.

The trigger is a merged branch update, not PR selection workflow. Branch is explicit; `main` and `develop`
are conventions only. Failures produce no new latest map, and pointer failure preserves the prior pointer.

## Evidence and remaining gaps

Prior evidence remains PetClinic `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` (73 authoritative; 69/4
or 73/0 depending on Docker) and Spring Core `99a366baf6640b275d08dde60f05da719139bb6a`
(3,643 authoritative; recorded 3,638/5). Those prove collectors and the POC, not plan-v2 publication.

Remaining gaps: exact production non-execution artifact generation/transport; automatic Pipeline
stash/unstash wrappers; prepare-owned invocation of both authoritative discovery goals; a durable remote
backend; the full failure matrix; and fresh physical PetClinic three-agent plus Spring Core publication runs.
Accordingly canonical 5d is **IN PROGRESS**.
