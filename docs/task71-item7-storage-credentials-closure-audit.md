# TASK71 — item 7 storage + credentials closure audit

## Goal

Decide whether canonical item **7 Storage + credentials** is complete for one Jenkins controller,
multiple builds and agents/workspaces, authenticated vendor-neutral RAW HTTP storage, Jenkins
Username/Password credentials, Nexus RAW validation infrastructure, and FILE as the local/reference
backend. The answer is **yes**. No production correctness blocker was found and no production code was
changed.

## Canonical scope

Item 7 owns storage addressing and transport, immutable publication, checked pointers, safe lookup,
configuration, execution-time credential resolution, authentication, typed storage facts, and persistence
across builds/workspaces. Map semantics and Git ordering policy remain item 5d responsibilities; SCM/PR
resolution remains item 6; choosing fail, retry, or full-suite actions remains item 8.

Supported writers are builds coordinated by one Jenkins controller. Multiple controllers, independent
external writers, and crash-safe distributed locking are not supported. They require future remote CAS.

## Evidence reviewed

The audit reviewed the TASK67 contract and summary, TASK68 implementation/real-Nexus evidence and
summary, TASK69 Jenkins credential/authentication evidence and summary, TASK70 cross-build/concurrency
evidence and summary, `docs/current-architecture.md`, current Jenkins and mapping-runtime production
code, Docker Compose, verification helpers, and the current automated tests. Baselines were main STP
`8cb0bf80c4c42c20623a5ed2f700d1bb8bc9972e` and Jenkins plugin
`2ea7cffb94eb2787d4296e60c8f5aba028faeb95`, both clean.

TASK67–70 each pass this audit. TASK69's 403 proof is a focused HTTP test rather than a separate Nexus
denial run; that proves the generic mapping required here, not every repository-manager authorization
configuration. TASK69/70 marker scans prove absence of their generated storage-secret markers in the
captured controller/agent logs and repository evidence, not absence of every conceivable secret from all
systems.

## Reconstructed requirements

| Group | Requirement | Classification |
| --- | --- | --- |
| storage abstraction | One vendor-neutral `CoverageMapStore`; FILE and RAW_HTTP share its semantics; results expose logical/backend keys, not `Path` | IMPLEMENTED + VALIDATED |
| remote transport | Canonical bounded GET and JSON PUT/read-back contract, no redirects or vendor API | IMPLEMENTED + VALIDATED |
| immutability | Equal bytes are idempotent; conflicting bytes cannot replace the winner in supported writer topology | IMPLEMENTED + VALIDATED |
| pointer semantics | Strict schema-v1 `latest.json` binds project, branch, revision, and SHA-256 | IMPLEMENTED + VALIDATED |
| integrity | Exact byte read-back, SHA-256, decoded schema/lifecycle/completeness, identity and revision checks before use | IMPLEMENTED + VALIDATED |
| lookup | Exact canonical lookup and latest pointer-to-target lookup return only validated usable maps; no synthetic empty map | IMPLEMENTED + VALIDATED |
| credentials | Persist credential ID only; resolve `StandardUsernamePasswordCredentials` in owning Item context | IMPLEMENTED + VALIDATED |
| authentication | Short-lived Basic header, no redirects, redacted auth object; 401/403 typed distinctly | IMPLEMENTED + VALIDATED |
| configuration | FILE/RAW_HTTP, FILE default, complete type-aware URL/path/timeout validation and immutable invocation snapshot | IMPLEMENTED + VALIDATED |
| security | TLS default, explicit insecure opt-in, bounded bodies, no secret URL/Pipeline parameter/artifact/evidence fields | IMPLEMENTED + VALIDATED |
| concurrency | Controller lock covers backend/base, repository, project, and branch and serializes supported same-key operations | IMPLEMENTED + VALIDATED |
| cross-build/cross-agent behavior | Remote map survives producer workspace deletion and is consumed by a different build/physical agent/workspace | VALIDATED |
| failure typing | Storage and credential categories propagate through runtime and public Pipeline failure without action policy | IMPLEMENTED + VALIDATED |
| backward compatibility | FILE default, legacy `storageRoot`, legacy pointer read and structured new writes remain supported | IMPLEMENTED + VALIDATED |
| supported topology | One controller coordinating multiple builds and physical agents/workspaces against authenticated RAW_HTTP | IMPLEMENTED + VALIDATED |
| explicitly deferred capabilities | Multi-controller/external-writer CAS, cloud/vendor backends, more credential types, retention, retry and rollout | DEFERRED |

No requirement is classified BLOCKER.

## Storage abstraction

`CoverageMapStore` remains the single product storage abstraction. `FileCoverageMapStore` and
`RawHttpCoverageMapStore` implement the same immutable-map, exact-get, pointer-get, and guarded-pointer
contract. `StoredObject` and `MapLookupResult` contain vendor-neutral logical/backend-relative identities;
no local `Path` leaks through the interface. Typed outcomes are retained. Production storage code contains
no Nexus, Sonatype, Artifactory, repository-manager, cloud, or unused vendor abstraction.

FILE remains the **LOCAL / REFERENCE IMPLEMENTATION**. RAW_HTTP servers are compatible only when they
provide the documented stable-path GET, complete PUT replacement, deterministic read-after-write,
bounded exact bytes, and distinguishable HTTP outcomes; universal RAW-server compatibility is not claimed.

## Immutable map contract

Publication validates a complete schema-v2 `PUBLISHED` map and its revision before storage, checks an
existing object, treats identical content as `ALREADY_EXISTS_IDENTICAL`, rejects different content as
`IMMUTABILITY_CONFLICT`, and verifies uploaded bytes and SHA-256 by GET. It then decodes and validates the
read-back map and its revision. The controller lock surrounds the full public RAW_HTTP operation.

TASK70's truly overlapping identical and conflicting same-revision builds produced one canonical object;
the conflicting loser reported `IMMUTABILITY_CONFLICT` and winning bytes remained unchanged.

**IMMUTABILITY CONTRACT = CLOSED.**

## Pointer contract

`pointers/latest.json` strictly contains exactly `schemaVersion`, `project`, `branch`, `revision`, and
`mapSha256`. Field type/schema and SHA-256 syntax are validated. Latest lookup checks logical identity,
resolves the canonical revision object, checks checksum and decoded map revision, and writes usable bytes
only after full map validation. Invalid pointer, missing target, or corrupt target yields no usable map;
no synthetic empty map exists. Pointer update GETs the pointer back and resolves its verified target, so a
successful latest pointer cannot designate unverified/corrupt content.

Ordering is equality → `POINTER_ALREADY_CURRENT`; candidate ancestor of current →
`STALE_POINTER_UPDATE`; current ancestor of candidate → `POINTER_UPDATED`; divergence →
`POINTER_CONFLICT`. Storage accepts a `RevisionOrder` callback and does not own Git ancestry.

## Lookup contract

Exact storage lookup computes the canonical key, performs a bounded GET, and returns bytes plus their
SHA-256. Publication and lookup orchestration decode those bytes, enforce schema-v2, `PUBLISHED`, complete,
and exact revision binding before use. Latest lookup additionally strictly decodes the pointer, verifies
project/branch identity, resolves the exact target, verifies SHA-256 and revision equality, and only then
materializes it. A missing target is `NOT_FOUND`; malformed content is rejected; checksum disagreement is
`INTEGRITY_FAILURE`.

## RAW HTTP contract

The implementation uses only Java HTTP GET/PUT against canonical opaque paths. Redirect following is
disabled. GET bodies are capped at 64 MiB; connect/read timeouts are positive and at most ten minutes.
Client SHA-256 and exact GET read-back—not server checksum metadata—provide integrity. A checksum is not
authenticity; transport authenticity relies on correctly configured HTTPS and server authentication.
Nexus Repository OSS RAW is validation infrastructure, while RAW_HTTP is the product abstraction.

## Configuration

Public Jenkins configuration exposes `FILE` and `RAW_HTTP`; `FILE` is the default. RAW_HTTP snapshots
base URL, repository path, credential ID, insecure-HTTP opt-in, and connect/read timeouts into an immutable
serializable non-secret object at step start, preventing later global edits from changing a running
invocation. Validation rejects missing type-specific fields, URL userinfo/query/fragment, non-HTTPS unless
explicitly opted in, absolute/trailing/blank/dot/backslash/encoded-separator repository paths, and out-of-
range timeouts. The legacy `storageRoot` override remains FILE-only.

## Credential model

Persistent STP state and Pipeline step state contain only `credentialId`, never username, password, token,
Authorization, or a resolved credential. `JenkinsStorageCredentialResolver` queries credentials visible to
the owning Run's parent Job/Item and matches the configured ID; it does not enumerate unrestricted global
secrets. The first supported type is `StandardUsernamePasswordCredentials`.

Resolution occurs inside each controller operation. The password is unwrapped immediately before I/O,
never serialized into Pipeline state and never supplied as an environment variable or command-line
argument. The resolver, bridge, and Basic authenticator clear mutable password/header buffers in `finally`.
TASK70 password rotation caused the stale credential to fail with `AUTHENTICATION_FAILED` and the updated
credential to succeed, proving resolved secrets are not cached across builds.

## Authentication

`RawHttpAuthentication` constructs Basic authentication for the active operation and applies Authorization
only while building requests. Its `toString()` is redacted. Credentials never enter the URL and redirects
are disabled, so a credential-bearing redirect is not followed. `401` maps to `AUTHENTICATION_FAILED` and
`403` to `AUTHORIZATION_FAILED`. The runtime authentication interface remains Jenkins-neutral.

## Security

Maps and pointers contain no credentials. Public Pipeline arguments and storage URLs contain no secret.
The configured production posture is HTTPS; HTTP requires explicit local/test opt-in. Responses are bounded
and Authorization is neither logged nor included in exceptions. TASK69/70 generated-marker scans found no
leak in captured Pipeline/controller/agent logs and repository evidence. These scans plus code inspection
establish the supported paths tested; they are not a proof about unrelated Jenkins plugins, host logs, or
every possible administrator-supplied username/URL label.

## Cross-build/cross-agent proof

TASK70 producer `stp-task70-publish-a1#3` used agent 1 and its workspace, published R1, and deleted the
workspace. Consumer `stp-task70-lookup-a2#1` used a distinct build, agent 2, and distinct workspace and
retrieved byte-identical R1. Logs recorded `storageType=RAW_HTTP` and `localFileStoreUsed=false`. R2 was
then published and retrieved after credential rotation and restart, while R1 remained immutable/readable.
This closes persistent cross-build, cross-agent, and producer-workspace independence.

## Concurrency

TASK70 used overlapping Jenkins builds, not sequential simulation:

| Scenario | Required invariant | Observed result |
| --- | --- | --- |
| late-old | R2 remains latest after delayed R1 | R2 updated; R1 returned `STALE_POINTER_UPDATE` |
| same revision, identical | one byte identity; retry idempotent | `STORED`, then `ALREADY_EXISTS_IDENTICAL` / already current |
| same revision, conflicting | loser cannot replace winner | loser `IMMUTABILITY_CONFLICT`; winner unchanged |
| ancestor-related revisions | final latest is descendant R2 | five overlapping pairs all ended at R2 |
| divergent candidate | current remains unchanged | `POINTER_CONFLICT`; D1 remained latest |
| consumer during publish | no partial/dangling bytes | consumer waited and received verified R8 |
| failure before pointer | prior latest remains usable | R9 object remained; pointer/consumer remained on R8 |

Lock traces show a waiter entered only after the holder released; no same-key critical sections overlapped.

## TASK70 ancestry fix audit

The old defect ran `git merge-base` from the controller working directory. Current production code creates
the ancestry callback from the publishing build's `FilePath` and `Launcher`; its command is run with
`.pwd(workspace)`. The callback is invoked by the neutral runtime while `CoverageMapStorageResolver` still
holds the controller lock around the complete publish. The backend still accepts only abstract
`RevisionOrder`/`BiPredicate` policy and imports no Jenkins or Git policy.

The fix did not move ancestry into `RawHttpCoverageMapStore`, duplicate item-5d ordering, mutate the
workspace, or fetch SCM data. Storage semantics are unchanged. The focused test and TASK70 R1→R2 and race
evidence independently validate the repair. The controller-working-directory defect no longer exists.

## Restart and transaction semantics

TASK69 restart testing persisted all storage fields and credential ID. TASK70 clean Jenkins restart then
resolved the credential and retrieved remote R2; TASK68 separately proved Nexus object persistence. The
controller's in-memory lock does not survive restart. Clean shutdown had no active critical section, and
crash-safe distributed coordination is not claimed. This does not block the explicitly supported
single-controller, controller-coordinated live-writer topology.

Publication order is: validate map; store immutable object; GET/read-back and validate; evaluate pointer
under coordination; PUT pointer; GET/validate pointer and target. TASK70's controlled failure before pointer
left an immutable candidate, preserved the previous pointer, and allowed the consumer to keep receiving the
previous latest. **PUBLICATION TRANSACTION SAFETY = CLOSED** for the supported topology.

## Typed outcomes and item-8 boundary

Runtime outcomes are `FOUND`, `STORED`, `ALREADY_EXISTS_IDENTICAL`, `POINTER_UPDATED`,
`POINTER_ALREADY_CURRENT`, `NOT_FOUND`, `INVALID_RESPONSE`, `INTEGRITY_FAILURE`,
`IMMUTABILITY_CONFLICT`, `POINTER_CONFLICT`, `STALE_POINTER_UPDATE`, `REMOTE_UNAVAILABLE`, `TIMEOUT`,
`CONFIGURATION_ERROR`, `CREDENTIAL_REQUIRED`, `CREDENTIAL_NOT_FOUND`,
`CREDENTIAL_TYPE_UNSUPPORTED`, `AUTHENTICATION_FAILED`, and `AUTHORIZATION_FAILED`.

The Jenkins boundary preserves applicable failure names in `StorageOutcome` and public exceptions.
Successful store statuses remain runtime results/log facts; `FOUND`/`NOT_FOUND` remain lookup value facts.
No typed outcome is silently converted to an unrelated category when exposed by the runtime. Unknown
untyped reflection failures conservatively become `REMOTE_UNAVAILABLE`; this is not conversion of a known
typed storage error. A generic Pipeline failure containing the correct category is sufficient for item 7.
No item-7 code maps unavailability/authentication/not-found/timeout to full suite, build failure, run-all,
retry scheduling, or any other central action. Item 8 remains NOT STARTED.

## Supported topology

One Jenkins controller coordinating multiple builds on multiple agents/workspaces is **SUPPORTED** and
validated. Multiple controllers and independent external writers are **UNSUPPORTED**. Remote CAS is not
implemented. These limits are explicit and sufficient for current product scope, so they are nonblocking.

## Deferred capabilities

| Capability | Result | Why nonblocking |
| --- | --- | --- |
| multi-controller remote CAS | DEFERRED-NONBLOCKING | Current writer topology has one controller authority |
| external-writer coordination | DEFERRED-NONBLOCKING | External writers are outside the supported authority boundary |
| GCS backend | DEFERRED-NONBLOCKING | RAW_HTTP and FILE satisfy current scope |
| S3 backend | DEFERRED-NONBLOCKING | Optional backend extension |
| Azure Blob backend | DEFERRED-NONBLOCKING | Optional backend extension |
| Artifactory-specific backend | DEFERRED-NONBLOCKING | Compatible generic service may use RAW_HTTP; no vendor API required |
| Bearer-token credential type | DEFERRED-NONBLOCKING | Username/Password Basic is the declared first credential contract |
| cloud service-account credentials | DEFERRED-NONBLOCKING | No cloud backend is in current scope |
| credential selector UX enhancement | DEFERRED-NONBLOCKING | Text credential ID is functionally and securely resolved |
| retention/lifecycle management | DEFERRED-NONBLOCKING | Publication/lookup correctness does not require deletion policy |
| storage retry scheduling | DEFERRED-NONBLOCKING | Typed facts exist; action/retry policy belongs to item 8 |
| CI-wide adoption/rollout | DEFERRED-NONBLOCKING | Deployment adoption is not product storage correctness |

## Requirement matrix

| Area | Requirement | Implementation evidence | Runtime/E2E evidence | Result |
| ---- | ----------- | ----------------------- | -------------------- | ------ |
| storage abstraction | One neutral store API; no `Path` result | `CoverageMapStore` records logical/relative keys | FILE/RAW tests | PASS |
| FILE backend | Local/reference backend retained | `FileCoverageMapStore` | Full suites | PASS |
| RAW_HTTP backend | Neutral GET/PUT backend | `RawHttpCoverageMapStore` | TASK68–70 Nexus | PASS |
| remote layout | Canonical bounded keys | `StorageKeys` safe components | TASK68 object inspection | PASS |
| immutability | Equal retry/different conflict | immutable put/read-back | TASK68, TASK70 races | PASS |
| structured pointer | Strict schema-v1 fields | `CoverageMapPointer` | pointer tests/TASK68 | PASS |
| checksum binding | Pointer SHA-256 equals target | `getLatestMap` | corruption tests | PASS |
| revision binding | Argument/path/pointer/map agree | `MappingTool` validation | TASK68/TASK70 | PASS |
| exact lookup | Canonical bounded GET and validated use | store GET + orchestration validator | RAW tests/TASK68 | PASS |
| latest lookup | Strict pointer then verified target | `lookupFromStore` | TASK68–70 | PASS |
| typed errors | Preserve storage/credential category | status enums/exceptions/classifier | negative tests/E2E | PASS |
| configuration | Type-aware URL/path/timeouts | `StorageConfiguration` | tests/restart | PASS |
| credential ID persistence | Persist ID only | global config/snapshot | TASK69 XML audit | PASS |
| credential resolution | Owning Item-visible lookup | Jenkins resolver | TASK69 public builds | PASS |
| Basic auth | Request-scoped and redacted | authentication abstraction | header tests/Nexus | PASS |
| 401 | Authentication category | HTTP status mapping | TASK69/TASK70 | PASS |
| 403 | Authorization category | HTTP status mapping | focused server test | PASS |
| secret safety | No storage-secret persistence/logging | config/resolver/buffer clearing | TASK69/70 scans | PASS |
| cross-build | Independent producer/consumer builds | controller RAW bridge | TASK70 | PASS |
| cross-agent | Physical agent 1 → agent 2 | controller storage boundary | TASK70 | PASS |
| workspace independence | Producer workspace dispensable | remote object lookup | TASK70 deletion | PASS |
| R1 → R2 advancement | Descendant advances latest | ancestry callback | TASK70 | PASS |
| late-old protection | Ancestor cannot regress latest | guarded decision | TASK70 overlap | PASS |
| same-revision idempotence | Identical race succeeds safely | branch/object coordination | TASK70 overlap | PASS |
| same-revision conflict | Conflicting bytes rejected | immutable check | TASK70 overlap | PASS |
| divergent pointer | Divergence rejected | pointer decision | TASK70 overlap | PASS |
| consumer-during-publish | No partial/dangling result | controller coordination + validation | TASK70 overlap | PASS |
| failure-before-pointer | Previous pointer stays usable | transaction order | TASK70 controlled failure | PASS |
| controller coordination | Same logical key serializes | four-part controller lock key | TASK70 lock trace | PASS |
| credential rotation | Resolve anew per build | resolver inside operation | TASK70 rotation | PASS |
| controller restart | Config/ID/map remain usable | persisted config, remote data | TASK69/70 | PASS |
| single-controller scope | Multiple builds/agents supported | controller-owned lock | TASK70 | PASS |
| multi-controller limitation | Remote CAS required | explicitly absent | Not in supported topology | DEFERRED-NONBLOCKING |
| item-8 separation | No central action mapping | typed fact-only code | audit/search | PASS |

## Blocker assessment

Are there correctness blockers for item 7? **No.** Documentation and evidence are consistent with the
current production implementation. No cleanup-only concern warrants keeping the item open.

## Final item-7 decision

**Item 7 Storage + credentials = DONE.** All item-7 slices, including this closure audit, are DONE.
Canonical item **8 Central failure policy** remains NOT STARTED. The recommended next task is
**TASK72 — item-8 failure-policy taxonomy and decision contract**; it is not started here.
