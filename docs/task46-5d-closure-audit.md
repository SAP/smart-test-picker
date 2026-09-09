# TASK 46 — 5d closure audit

## Decision

Canonical backlog item **5d Map orchestration — Jenkins repo is DONE**. The audit found no unresolved
correctness issue that can cause an incorrect expectation, lost tests, unsafe completeness, incorrect
revision binding, unsafe publication, stale-pointer regression, transport loss, or silent disagreement
between the orchestrator and collector.

This closes the base orchestration contract. It does not claim a production remote-storage backend or a
particular organization's Jenkins deployment policy. The existing file-backed store remains the
**LOCAL / REFERENCE IMPLEMENTATION**.

## Reconstructed 5d contract and requirement matrix

The contract below is reconstructed from the canonical architecture and backlog, TASK43–45 records, and
the Jenkins implementation and tests. No new requirement is introduced by this audit.

| Requirement | Owner | Implementation | Evidence | Status |
| --- | --- | --- | --- | --- |
| Revision ownership | Orchestrator | `StpPrepareCoverageMappingStep` freezes Git HEAD before discovery and rejects movement; map and publish steps verify the frozen revision | `CoverageMappingStepTest`; TASK44 PetClinic and TASK45 Spring Core accepted runs | DONE |
| Authoritative target inventory | Orchestrator/build-tool discovery | Prepare invokes Gradle/Maven authoritative inventory discovery before constructing plan v2; collector output is not inventory | `StpPrepareCoverageMappingStep`; `CoverageMappingManifestTest`; TASK44/45 plan artifacts | DONE |
| Shard planning and assignments | Orchestrator | `MappingTool split` deterministically hashes lifecycle containers; plan v2 freezes exact assignments and validates complete topology | `MappingToolTest.splitIsDeterministicAndComplete`, `splitKeepsOneLifecycleContainerOnOneShard`; `CoverageMappingManifestTest` | DONE |
| Collector orchestration | Jenkins public step | `stpCoverageMap` applies the plan-bound tool, target, collector, shard input, revision, and output paths around the caller's build body | `StpCoverageMapStep`; `CoverageMappingStepTest`; TASK44/45 public-flow runs | DONE |
| Collector/runtime ownership | Collector/runtime | Fragment and evidence listeners own observed execution, coverage, executable-unmapped identities, setup scopes, diagnostics, completion, and runtime integrity | `MappingListener`; `ExecutionEvidenceListener`; listener and mapping-runtime tests; TASK44/45 artifacts | DONE |
| Execution evidence | Collector/runtime, validated by orchestrator | Versioned evidence is bound to revision, shard, target, tool, and assignment; physical outcomes aggregate to exact logical identities | `ExecutionEvidence`, `ExecutionEvidenceListener`, `MappingTool.validateEvidence`; `ExecutionEvidenceListenerTest`; TASK44 failure matrix; TASK45 accounting | DONE |
| Positive non-execution accounting | Collector/runtime supplies; orchestrator requires | Only positive structured evidence supplies `NON_EXECUTED`; mixed physical invocations remain executed; subtraction is never an evidence source | `ExecutionEvidenceListenerTest`; `MappingToolTest.positiveNonExecutionCompletesInventoryWithoutPublishingSkippedUnmapped`; TASK44/45 | DONE |
| Fragment/evidence/diagnostic transport | Jenkins public steps | Plan v2 owns workspace-relative fragment, diagnostic, evidence, and stash identities; map stashes validated bounded outputs and publish unstashes every expected shard | `CoverageMappingManifest`; `StpCoverageMapStep.Callback`; `StpPublishCoverageMapStep`; TASK44 missing-stash case and physical run | DONE |
| Join | Orchestrator/shared model | `MappingTool.join` validates revision/shards/evidence, rejects duplicate identities and merges fragments/setup scopes through schema-v2 model types | `MappingToolTest.completeJoinUsesIndependentExpectedInventoryAndShards`, rejection and setup-merge tests | DONE |
| Global completeness | Orchestrator | Join requires exact disjoint identity equality `expected = mapped union executable-unmapped union positive-non-executed`, with zero missing, unexpected, and duplicates | `MappingTool.join`; `MappingToolTest` failure cases; TASK44 failure matrix; TASK44/45 exact accounting | DONE |
| Publication lifecycle | Orchestrator/storage boundary | Only a complete, valid, schema-v2 `PUBLISHED` map whose revision matches the plan can enter the store | `MappingTool.validatedPublished` and `publishStore`; `MappingToolTest.localPublishIsAtomicPathSafeImmutableAndIdempotent`; TASK45 publication summary | DONE |
| Immutability | Storage boundary | Revision-keyed equal bytes are idempotent; conflicting same-revision bytes are rejected; writes are temporary-file then move | `CoverageMapStore`; `FileCoverageMapStore`; `FileCoverageMapStoreTest.immutableWriteIsIdempotentAndConflictsAreRejected` | DONE |
| Lookup | Jenkins/storage abstraction | `stpLookupCoverageMap(project, branch)` resolves the latest revision, requires the pointed immutable map, validates it, and returns no map when no safe pointer exists | `StpLookupCoverageMapStep`; `MappingTool.lookupLatest`; registered-step test; TASK43 contract | DONE |
| Latest pointer | Storage boundary | Read-back byte equality precedes a locked atomic branch-pointer update | `MappingTool.publishStore`; `FileCoverageMapStore.updateLatestRevision`; store tests; TASK44/45 publication summaries | DONE |
| Concurrency/stale publication | Storage boundary | An ancestor candidate cannot move latest backward; divergent candidates fail without pointer change; current retries are harmless | `FileCoverageMapStore`; `FileCoverageMapStoreTest.olderCompletionCannotMovePointerBackwards`, `divergentCandidateFailsWithoutChangingPointer` | DONE |
| Failure recovery/safety | Orchestrator/storage boundary | Failed collection, transport, evidence validation, join, or publication cannot replace the previous pointer; failed joined output is removed | `StpPublishCoverageMapStep`; TASK44 failure matrix including `previousMapPreserved`; store pointer tests | DONE |
| Storage abstraction | Storage boundary | `CoverageMapStore` defines immutable put/read, latest lookup, and guarded pointer update; `FileCoverageMapStore` is the reference implementation | Interface and file-store tests | DONE |
| Remote storage implementation | Future backend integrations | One common storage contract will have configuration-selected remote implementations; none is required for base orchestration correctness | Deliberate TASK46 scope decision and extension-point note below | DEFERRED |
| Jenkins public API | Jenkins plugin | Prepare, map, and publish steps provide the supported mapping lifecycle without exposing internal fragment/evidence/join handling | Step descriptors and `CoverageMappingStepTest.mappingStepsAreRegistered`; TASK44/45 pipelines | DONE |
| Real-project validation | Jenkins orchestration | PetClinic proves physical three-agent isolation and equivalence; Spring Core proves the current productionized plan-v2 public lifecycle at scale | `verification/task44/`, `verification/task45/`, and TASK44/45 reports | DONE |
| Retention operations | Deployment/storage policy | Immutable maps may coexist and the pointed revision must be protected; pruning policy is not required for correctness of base publication | TASK43 retention invariant; no base behavior relies on pruning | DEFERRED |
| Post-merge scheduling | Deployment CI configuration | The architectural trigger is a merged branch update; configuring a specific Jenkins job is adoption work | TASK43 trigger statement; lifecycle is directly callable via public steps | DEFERRED |
| Organization publication cadence | Deployment CI configuration | Core validation, immutability, revision, and stale-build rules are implemented; cadence is organization-specific | Publication implementation/tests above | DEFERRED |
| Operational retries | Deployment CI configuration | State safety on failure is implemented; automatic retry scheduling is operational policy | Failure-safety implementation/evidence above | DEFERRED |
| CI-wide configuration | Product adoption | Installation, credentials, backend choice, and organization-wide rollout do not define base orchestration correctness | Public lifecycle is independently usable and validated | DEFERRED |
| Selector fail-open policy | 5b | Lookup returns no map and never synthesizes an empty one; choosing `FULL_SUITE` remains selector policy | `StpLookupCoverageMapStep`; `MappingTool.lookupLatest`; TASK43 boundary | OUT OF SCOPE |
| Arbitrary base/head comparison | 5e | Independent backlog item; unchanged | Canonical backlog | OUT OF SCOPE |

## Ownership and exact completeness

The implementation preserves the required split:

- The orchestrator owns revision, authoritative target inventory, deterministic shards and assignments,
  the boundary at which positive non-execution evidence is accepted, and the publication decision.
- The collector/runtime owns observed execution, coverage, executable-unmapped results, setup scopes,
  diagnostics, completion, and runtime integrity.

No expectation is reconstructed from collector output. In particular, non-execution is not calculated as
`expected - reported`. Each evidence artifact is checked against its exact frozen shard assignment, while
the final join independently requires:

```text
expected
= mapped
  union executable-unmapped
  union positive-non-executed
```

The three sets must be disjoint and the resulting accounting must have zero missing, unexpected, and
duplicate identities. TASK44's focused failure matrix proves the fail-closed cases; TASK45 confirms the
same contract at Spring Core scale. Repeating either large-project run was unnecessary.

## Transport, publication, and lookup boundaries

Plan v2 contains each shard's fragment path, diagnostic path, execution-evidence path, stash name, and
unstash expectation. `stpCoverageMap` validates and stashes the bounded outputs; `stpPublishCoverageMap`
unstashes all expected inputs and rejects missing or extra artifact sets. Shard workspaces therefore need
not share a filesystem with each other or with the join agent.

Publication semantics are complete independently of backend choice. The joined map must be complete,
valid, schema v2, `PUBLISHED`, and revision-bound. Immutable bytes are read back before latest is updated.
Equal same-revision publication is idempotent, conflicting bytes fail, an older revision cannot move latest
backward, and a divergent revision cannot replace it. Failures leave the last known-good pointer intact.

Lookup safely implements `project + branch -> latest revision -> immutable map`. A missing pointer returns
no map; a dangling, invalid, incomplete, non-published, or wrong-required-revision result fails rather than
creating an empty map. Selector fail-open policy remains owned by 5b.

## Existing real-project evidence

TASK44 remains sufficient physical multi-agent evidence. PetClinic revision
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` used three physical Jenkins agents and an authoritative
inventory of 73. It accounted for 69 mapped, zero executable-unmapped, and four positively non-executed
identities. Its published map has 418 class edges, 1,343 method edges, and ten setup scopes, and the
single-versus-three-agent semantic comparison passed.

TASK45 exercised `stpPrepareCoverageMapping`, `stpCoverageMap`, and `stpPublishCoverageMap` through the
current plan-v2 implementation for Spring Core revision
`99a366baf6640b275d08dde60f05da719139bb6a`. Its 3,643 identities account exactly as 3,638 mapped, zero
executable-unmapped, and five positively non-executed. All 4,705 physical cases completed with no failures
or errors, 112 setup scopes were preserved, and immutable read-back-verified publication succeeded.

## Historical gaps

- Remote storage: **DEFERRED EXTENSION POINT**. It is backend integration, not missing base semantics.
- Retention: **DEFERRED operational policy**. Immutable coexistence and protection of current latest are
  already correctness invariants.
- Lookup: **DONE** at the abstraction and reference-backend level.
- Scheduling/post-merge trigger: **DEFERRED deployment-specific CI configuration**.
- Publication policy: core safety is **DONE**; organization cadence/configuration is **DEFERRED**.
- Failure recovery: state safety is **DONE**; retry scheduling is **DEFERRED**.
- CI-wide configuration: **DEFERRED product adoption/deployment concern**.

## Remote extension point

Future remote map storage implementations share one storage contract.

Planned backend families:

- RAW
- GCS
- S3
- AZURE_BLOB

RAW means a generic artifact/raw HTTP repository abstraction and is not Nexus-specific. Concrete
implementations are intentionally deferred and do not block base 5d orchestration closure.

## Public API sanity check

**PASS.** A Jenkins pipeline can use `stpPrepareCoverageMapping`, `stpCoverageMap`, and
`stpPublishCoverageMap` for the supported mapping lifecycle without knowing internal fragment, evidence,
or join details. The pipeline still supplies normal lifecycle inputs and executes its build body; those are
public orchestration inputs, not leaked internal transport details.

## Canonical backlog after audit

```text
5a DONE
5b DONE
5c DONE
5d DONE
5e NOT STARTED / independent
```
