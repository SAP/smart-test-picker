# TASK67 — item 7 remote storage and credential contract

## Goal

TASK67 starts canonical backlog item **7 Storage + credentials** by fixing the production contract
between Jenkins STP and an authenticated remote artifact service. The product abstraction is a
vendor-neutral `CoverageMapStore`; its first remote implementation family will be `RAW_HTTP`, and
Nexus Repository OSS RAW will be controlled validation infrastructure only. This task changes no
production code, Docker topology, SCM/PR semantics, mapping/selection semantics, or item-8 policy.

Baselines were clean at main STP `d9a4df40d7c0c43d1a6115f8f215bd137d8e9351` and Jenkins plugin
`1c9fd40a59080c1b73fc973468c63e1424677db4`.

## Existing 5d storage model

The production implementation inspected for this contract is in the Jenkins plugin's bundled mapping
runtime. `CoverageMapStore` owns four operations: `putImmutableMap(project, branch, revision, bytes)`,
`getMap(project, branch, revision)`, `getLatestRevision(project, branch)`, and
`updateLatestRevision(project, branch, revision, RevisionOrder)`. The pointer results are `UPDATED`,
`ALREADY_CURRENT`, and `STALE_CANDIDATE`.

`FileCoverageMapStore` is the **LOCAL / REFERENCE IMPLEMENTATION**. Its current key is
`<root>/<safe-project>/<safe-branch>/<safe-revision>/coverage-map-v2.json`; its pointer is the trimmed
revision in `<root>/<safe-project>/<safe-branch>/latest-revision`. `MappingTool.publishStore` validates
a complete schema-v2 `PUBLISHED` map, requires the argument revision to match the map revision, writes
the immutable bytes, reads them back byte-for-byte, and only then calls the guarded pointer update.
`MappingTool.lookupLatest` reads the pointer, resolves the immutable object, validates the payload, and
returns no output when no pointer exists. It never creates a synthetic empty map.

The current latest lookup does not explicitly compare the decoded map revision with the pointer revision
(its optional `requiredRevision` check compares the pointer value), and the pointer carries no checksum.
Those are remote-contract gaps addressed by the structured pointer and binding checks below, not reasons
to reopen 5d's publication orchestration.

Already enforced above or at the store boundary:

- join completeness and exact execution accounting; schema, lifecycle and semantic validation;
- map/revision equality; immutable equal-byte retry and conflicting-byte rejection;
- immutable read-back before pointer update; safe latest resolution through the immutable object;
- Git ancestry policy supplied as `RevisionOrder`, keeping ancestry outside storage; and
- old-candidate and divergent-candidate rejection without changing the current pointer.

Currently filesystem-dependent are UUID temporary files, read-after-write, atomic rename when
supported, `CREATE_NEW`, a local `FileLock`, replacement rename for the pointer, `Path` return values,
and local-process exceptions. The fallback non-atomic move is safe against selecting temporary names,
but its crash guarantees are filesystem-specific. The local lock coordinates only processes sharing
that filesystem lock; it is not a distributed lock. Symbolic-link containment is additionally checked
by the legacy `localPublish` path, while `FileCoverageMapStore` uses normalized hashed components and a
root-prefix check.

## Item-7 responsibility boundary

Item 7 is the layer `Jenkins STP storage client -> generic remote storage contract -> authenticated
remote artifact service`. It owns remote transport and addressing, read/write operations, Jenkins
credential lookup and execution-time binding, authentication, remote error classification,
cross-build/cross-agent availability, and remote read-back/integrity verification. It does not own map
completeness, selector semantics, revision eligibility or Git ancestry, SCM/PR resolution, centralized
fallback/fail-build decisions, test execution, retry scheduling, retention policy, or business-specific
cloud deployment. Item 5d's semantics and item 6's frozen SCM facts remain inputs; item 8 will decide
what action to take for item-7 outcomes.

## Storage abstraction

Retain and evolve `CoverageMapStore`; do not introduce `NexusCoverageMapStore` or a parallel product
API. Its conceptual operations remain immutable put/exact get/pointer get/guarded pointer update.
The minimum backward-compatible implementation work is:

1. replace the local `Path` success value with a vendor-neutral stored-object identity/result (the file
   implementation may still retain its path internally);
2. return/throw typed storage outcomes rather than relying on filesystem exception classes;
3. make the pointer a validated structured document carrying revision and map digest; and
4. give pointer update an explicit concurrency capability/result while retaining application-supplied
   `RevisionOrder`.

Existing callers can be adapted behind the same interface and the file backend remains supported.
No production model change is needed in TASK67 itself.

## RAW HTTP backend contract

`RAW_HTTP` addresses opaque files under one configured HTTPS base URL plus a repository/root path. A
compatible service must provide authenticated `GET` and create/replace upload (`PUT` or an exactly
equivalent upload operation), deterministic read-after-write, exact response bytes, stable object
paths, and distinguishable not-found/authentication/authorization/server/transport results. `HEAD` is
optional: it may optimize existence or metadata checks but never replaces `GET` read-back validation.

The client sends `Content-Type: application/json`, a known `Content-Length` where the HTTP library can
do so, and the configured authentication header. It accepts only documented successful 2xx responses;
redirects must be disabled or restricted to the same trusted origin without forwarding credentials.
Typical interpretation is 401 authentication failure, 403 authorization failure, 404 not found,
408/504 timeout, 429 or 5xx remote unavailable, and unexpected status/body/protocol as invalid response.
No vendor-specific status is assumed; configurable server quirks belong in a validation adapter only.

Server checksum headers may be compared when their algorithm and meaning are trustworthy, but client
SHA-256 plus exact `GET` read-back is authoritative. Chunked transfer is allowed if the library cannot
send a length, provided read-back verifies the complete bytes. Replace support is required only for
the mutable pointer; immutable objects are protected at application level even when the server allows
overwrites. Conditional requests (`ETag` with `If-Match`/`If-None-Match`, or an equivalent atomic CAS)
are optional in the first single-controller topology and required for safe uncoordinated multi-controller
writers. A server without these operations is not universally compatible.

## Remote path model

The canonical layout is:

```text
<root>/projects/<safe-project>/branches/<safe-branch>/
  revisions/<safe-revision>/coverage-map-v2.json
  pointers/latest.json
```

`safe-project`, `safe-branch`, and `safe-revision` reuse `MappingTool.safeComponent`: trim only for the
readable prefix; replace every run outside `[A-Za-z0-9._-]` with `-`; strip edge hyphens; use `value`
if empty; truncate the prefix to 48 characters; then append `--` and the first 16 lowercase hex digits
of SHA-256 over the original, untrimmed UTF-8 input. Null/blank values are rejected. Thus slash,
backslash, `..`, query, fragment, percent-escape, and Unicode separator input cannot become URL
structure, while the digest distinguishes normalized collisions.

The fixed segments and encoded components are joined as path segments, never by resolving raw caller
text. The client normalizes exactly one separator between configured fixed segments, rejects base URLs
with user-info/query/fragment, rejects repository roots containing `.`/`..` or encoded separators, and
percent-encodes each already-safe segment when building the URI. Redirects cannot escape the configured
origin. No lower server path limit is assumed beyond support for this bounded component scheme; future
implementations must fail as `CONFIGURATION_ERROR` if a configured service limit cannot accommodate it.

## Immutable revision objects

Revision objects are immutable at the application boundary regardless of repository write policy.
Publication uses create-if-absent when available. Otherwise it performs `GET`: absence permits upload;
presence with exact bytes/digest is idempotent `ALREADY_EXISTS_IDENTICAL`; presence with different bytes
is `IMMUTABILITY_CONFLICT`. After upload it always reads back and compares exact bytes, SHA-256, decoded
schema/lifecycle/completeness, and revision/path identity.

Two writers racing through GET-then-PUT cannot be made safe by read-back alone when the service permits
blind overwrite: both might temporarily report success and the final bytes could change later. Therefore
same-key concurrent publication requires one of (a) atomic create-if-absent/conditional PUT, or (b) one
application writer authority that serializes the full check/write/read-back operation for that key.
Nexus repository write policy is defense in depth, never the sole protection.

## Mutable branch pointers

`pointers/latest.json` is intentionally mutable and never contains map bytes, credentials, or an
environment-specific URL. Its canonical document is conceptually:

```json
{
  "schemaVersion": 1,
  "project": "original logical project",
  "branch": "original logical branch",
  "revision": "original revision",
  "mapSha256": "64 lowercase hex digits",
  "updatedAt": "UTC RFC-3339 instant",
  "producer": { "buildId": "non-secret diagnostic identity" }
}
```

`updatedAt` and `producer` are diagnostic, not ordering authorities. Git ancestry supplied by the
application decides eligibility. The checksum binds the pointer to the immutable bytes; the fixed path
and encoded-key recomputation bind logical project/branch/revision to addressing.

## Concurrency and stale publication

Item 5d continues to own stale-publication prevention: under the pointer-update critical section the
application reads current, returns already-current for equality, returns stale when candidate is an
ancestor of current, rejects divergence, and updates only when current is an ancestor of candidate.
Storage supplies coordination; it does not perform Git ancestry or infer time order.

The first Docker proof has one Jenkins controller as the sole writer authority. A controller-wide lock
keyed by backend/root/project/branch may serialize pointer check/ancestry/update/read-back across its
builds; agent location is irrelevant because storage I/O is controller-mediated or uses that shared
coordinator. Under this explicitly configured boundary, remote CAS is not required. Multiple controllers,
external writers, or a lock lost across failover require atomic remote CAS (conditional pointer PUT using
the version/ETag just read) and a typed `POINTER_CONFLICT`; a RAW server lacking CAS is unsupported
for that topology. A mere JVM lock, agent-local lock, or GET-then-unconditional-PUT is insufficient there.

| Race/failure | Required invariant | Minimum capability / application duty | Safe result |
| --- | --- | --- | --- |
| Two builds, same revision | One immutable byte identity | create-if-absent/CAS or sole-writer keyed lock; compare bytes | identical success or `IMMUTABILITY_CONFLICT` |
| Different revisions, same branch | Latest advances only by ancestry | serialized check/update or remote CAS; app supplies ancestry | one update; loser re-evaluates or `POINTER_CONFLICT` |
| Old build finishes after new | R2 stays latest | current pointer read inside critical section | `STALE_POINTER_UPDATE`/current unchanged |
| Simultaneous pointer updates | no lost update | controller-wide lock in first topology; CAS otherwise | verified winner or typed conflict |
| Crash during immutable upload | corrupt/partial bytes never activated | upload then exact GET/schema validation before pointer | `INTEGRITY_FAILURE`/`REMOTE_UNAVAILABLE`; old pointer remains |
| Crash during pointer upload | old or valid complete new pointer only | server object-level replacement; read-back; CAS where required | invalid pointer rejected; lookup returns no map |

The generic contract does not require atomic rename. It does require that one HTTP object replacement is
not exposed as a successful truncated response; any partial/corrupt pointer or map is invalid. If a server
cannot provide object-level complete PUT replacement, it is incompatible. Temporary upload plus server
move may be used by a backend that provides it but is not part of generic RAW HTTP.

## Publication transaction

A successful remote publication is:

1. validate local complete schema-v2 `PUBLISHED` map and exact revision binding;
2. create/idempotently confirm the immutable revision object;
3. `GET` the immutable object;
4. verify exact bytes, SHA-256, schema, lifecycle, completeness, revision, and path binding;
5. enter the branch coordination boundary and evaluate current-pointer ancestry eligibility;
6. write the structured pointer only if eligible (conditionally when CAS is required);
7. `GET` and validate the pointer;
8. resolve its immutable target and verify target bytes/digest/bindings before reporting pointer success.

Steps 1–5 match current 5d semantics; structured pointer verification and remote coordination make the
contract explicit. Immutable storage success may coexist with pointer stale/conflict failure. Overall
"published as latest" succeeds only after step 8. A map is never latest before its immutable object is
available and verified. A failed pointer update leaves the prior pointer or an invalid pointer that safe
lookup refuses; it never licenses an unverified map.

## Exact and latest lookup

Exact lookup is `getMap(project, branch, revision)`: fetch the canonical immutable path, enforce bounded
response size, verify successful status and complete transport, exact SHA-256 when expected, decode and
validate complete schema-v2 `PUBLISHED`, and require payload revision plus encoded path identity to match
the request. A found but invalid payload is never returned as a usable map.

Latest lookup reads and strictly validates `latest.json`, including schema, logical key fields, full
revision and checksum; recomputes the immutable path; performs exact lookup; and requires the returned
map revision and SHA-256 to match the pointer. Missing pointer/target is `NOT_FOUND`; malformed pointer is
`INVALID_RESPONSE`; digest/content disagreement is `INTEGRITY_FAILURE`. In all invalid-target cases the
usable-map value is absent. No empty map is synthesized and no unverified payload is written to the
selection workspace.

## Typed storage outcomes

The item-7 result/error vocabulary is:

- `FOUND`, `STORED`, `ALREADY_EXISTS_IDENTICAL`, `POINTER_UPDATED`, `POINTER_ALREADY_CURRENT`;
- `NOT_FOUND`, `AUTHENTICATION_FAILED`, `AUTHORIZATION_FAILED`, `REMOTE_UNAVAILABLE`, `TIMEOUT`;
- `INVALID_RESPONSE`, `INTEGRITY_FAILURE`, `IMMUTABILITY_CONFLICT`, `POINTER_CONFLICT`,
  `STALE_POINTER_UPDATE`;
- `CONFIGURATION_ERROR`, `CREDENTIAL_REQUIRED`, `CREDENTIAL_NOT_FOUND`, and
  `CREDENTIAL_TYPE_UNSUPPORTED`.

These are facts, not item-8 actions. They do not imply fail build, full suite, or retry. Transport errors
retain a safe, redacted cause/status category but no authorization header, credential object, secret URL,
or unbounded response body.

## Credential model

Public persistent configuration stores only `credentialId`. The first supported Jenkins credential type
is Username/Password (`StandardUsernamePasswordCredentials` at implementation time); a repository token
may be placed in the password field when the server supports basic authentication, without a second STP
secret model. Cloud service accounts, access-key pairs, managed identity, OAuth flows, and secret-text
Bearer credentials are deferred until a backend actually requires them.

For an authenticated backend: absent ID is `CREDENTIAL_REQUIRED`; unresolved ID is
`CREDENTIAL_NOT_FOUND`; incompatible type is `CREDENTIAL_TYPE_UNSUPPORTED`; HTTP 401 is
`AUTHENTICATION_FAILED`; HTTP 403 is `AUTHORIZATION_FAILED`. These remain storage/credential outcomes.

## Credential resolution and secret safety

The Pipeline/global configuration provides `credentialId`; the Jenkins plugin resolves it through the
Jenkins Credentials API in the correct item/run/domain context immediately before execution; the backend
receives only a short-lived authentication abstraction/request-header supplier (or scoped secret values)
for the request. Resolved credentials are neither part of serializable Pipeline step state nor passed as
command arguments/environment variables to the mapping helper. They are not serialized across Pipeline
suspension and are discarded after the operation.

Secret values must never appear in Pipeline results, logs, exception messages, provenance, storage keys,
URLs, map/pointer artifacts, evidence JSON, or configuration exports. Jenkins masking is only defense in
depth: code must not print secrets. URLs containing user-info are rejected. Authorization headers and
credential objects are never logged. Remote diagnostic bodies are untrusted and potentially sensitive;
default diagnostics record only status, request ID from an allowlist, and a bounded media-type/length
summary. If an opt-in body excerpt is ever added it must strip control characters, cap at 512 characters,
and redact configured secret values, authorization/token/password/cookie patterns, and URLs with user-info.

## Configuration model

The current global configuration has no storage model; mapping steps accept a local `storageRoot`, and
the selector-side `CoverageMapSourceType` currently has only `WORKSPACE`. Item 7 should add one centralized,
snapshotted storage configuration, preserving the current file default:

```text
storage.type = FILE | RAW_HTTP
storage.file.root
storage.rawHttp.baseUrl
storage.rawHttp.repositoryPath
storage.rawHttp.credentialId
storage.rawHttp.allowInsecureHttp = false
storage.rawHttp.connectTimeout / readTimeout (bounded transport settings, not retry policy)
```

Fields irrelevant to the selected type are rejected/not persisted. `baseUrl` owns scheme/authority only;
`repositoryPath` owns fixed path prefix. No cloud fields are added. Storage selection should remain global
or folder-admin configuration and be snapshotted for a build. Existing Pipeline steps should need no new
parameters: `project`, `branch`, revision, and outputs already express invocation identity. The existing
`storageRoot` override may remain a deprecated FILE-only compatibility input during migration. No Pipeline
secret is exposed; a credential-ID override is not justified for the first implementation.

This remote map registry is distinct from selector-side workspace input: lookup materializes a validated
map into the existing workspace path, after which current selection APIs remain unchanged.

## Security properties

- Credentials are never persisted in coverage maps or pointers and never logged or URL-embedded.
- Remote content is untrusted until size, transport, digest, structure, lifecycle, completeness, revision,
  and path bindings validate.
- SHA-256 supplies integrity/identity, **not authenticity or trust**. Repository authentication controls
  access but does not cryptographically authenticate artifact authorship.
- HTTPS with certificate and hostname verification is mandatory for production non-local endpoints.
- Plain HTTP is allowed only when `allowInsecureHttp=true` for the explicitly controlled local Docker
  validation network; it must be rejected for other production configuration.
- Response bodies, redirects, and proxy diagnostics cannot become a secret-exfiltration/logging channel.

## Docker Nexus validation strategy

TASK68 should extend the existing topology to `jenkins-controller + jenkins-agent + nexus`. Proposed
service name: `nexus`; repository name: `stp-coverage-maps`; type: hosted RAW. Jenkins reaches
`http://nexus:8081` only over the private Compose network. Disposable state is preferred for deterministic
CI; an explicitly named volume may be used for restart tests, with cleanup controlled by the validation
task rather than product retention logic.

No secret belongs in tracked Compose, Groovy, or evidence. A future bootstrap script should wait for Nexus,
obtain the one-time admin secret at runtime from the mounted data volume, rotate it, create a least-privilege
`stp-publisher` user and hosted RAW repository through Nexus provisioning APIs, store the generated runtime
credential directly in Jenkins Credentials, and delete any temporary bootstrap material. Fixed usernames
and repository names are non-secret; passwords are generated/injected by the harness. Provisioning APIs and
Nexus status quirks stay in `docker/` or verification helpers, never in `CoverageMapStore` or `RAW_HTTP`.

Nexus Repository OSS is the first controlled remote test environment. The STP production backend is generic
RAW HTTP. Compatibility claims extend only to services providing the required operations and concurrency
semantics; Artifactory Generic may later be validated through the same backend without an Artifactory API.

## Cross-build validation plan

Build A on agent/workspace A creates and publishes the immutable map plus latest pointer. Build B runs as a
separate Jenkins build on agent/workspace B, starts with no map and no access to A's workspace, downloads
latest and the exact revision from RAW HTTP, validates both, and uses the materialized map for selection.
Evidence must record distinct build IDs, nodes/workspaces, remote request paths and digests (no secrets), and
must prove the configured local file root is absent/unused. Separate agents are preferred; if only one agent
exists, isolated wiped workspaces and disabled local map reuse are mandatory.

Cross-revision E2E uses a real Git graph R1 -> R2: publish R1 and prove latest R1; publish R2 and prove latest
R2; exact-read R1 and compare its original digest/bytes; then execute a delayed R1 pointer attempt and prove
`STALE_POINTER_UPDATE` with latest and R2 unchanged. Concurrent same-revision identical/conflicting uploads,
simultaneous R1/R2 pointer writers, interrupted uploads, corrupt pointer/target, auth failures, and service
unavailability must also be exercised before item-7 closure.

## Deferred cloud backends

GCS, S3, Azure Blob, and Artifactory-specific implementations/configuration are **DEFERRED
IMPLEMENTATIONS**. They must later implement the same immutable-object, structured-pointer, validation,
typed-outcome, credential-resolution, and concurrency contract without reopening item 5d publication
semantics. RAW HTTP plus complete validation is sufficient for item-7 closure; universal vendor support is
not claimed.

## Decision matrix

| Case | Required semantics | Owner | Typed outcome | Future validation |
| --- | --- | --- | --- | --- |
| Immutable revision object | create/confirm then exact verified GET | store + 5d validator | `STORED` | upload/read-back E2E |
| Mutable pointer | structured replace after map verification | store coordinator | `POINTER_UPDATED` | pointer decode/resolve E2E |
| Idempotent re-publish | preserve equal bytes | store | `ALREADY_EXISTS_IDENTICAL` | repeat publish |
| Conflicting re-publish | never overwrite different bytes | store/coordination | `IMMUTABILITY_CONFLICT` | conflict race |
| Exact lookup | validate path/revision/schema/digest | store + 5d validator | `FOUND` | cross-build exact read |
| Latest lookup | validate pointer and referenced object | store + 5d validator | `FOUND` | cross-build latest read |
| Missing object | no fabricated map | store | `NOT_FOUND` | 404 pointer/target |
| Corrupt object | never return usable map | validator/store | `INTEGRITY_FAILURE` | mutate bytes/digest |
| Partial upload | never activate; old pointer stays | store/service | `INTEGRITY_FAILURE` | interrupted/truncated upload |
| Remote unavailable | preserve category, no policy | transport | `REMOTE_UNAVAILABLE` | stop Nexus/5xx |
| Authentication failure | distinguish rejected identity | credential/transport | `AUTHENTICATION_FAILED` | wrong password/401 |
| Authorization failure | distinguish denied operation | transport | `AUTHORIZATION_FAILED` | read-only user/403 |
| Credential missing | fail before request | Jenkins resolver | `CREDENTIAL_REQUIRED` / `CREDENTIAL_NOT_FOUND` | absent/unknown ID |
| Credential wrong type | fail before request | Jenkins resolver | `CREDENTIAL_TYPE_UNSUPPORTED` | secret-text fixture |
| Concurrent same revision | one identity; equal idempotent | store/coordination | identical or `IMMUTABILITY_CONFLICT` | parallel equal/different bytes |
| Concurrent different revision | ancestry-ordered pointer | 5d order + coordinator | update/conflict/stale | parallel R1/R2 |
| Stale pointer update | never regress latest | 5d under coordination | `STALE_POINTER_UPDATE` | delayed R1 after R2 |
| Pointer CAS race | no lost update | remote CAS or sole writer | `POINTER_CONFLICT` | conditional PUT fixture |
| Invalid protocol/body | reject unexpected response | transport | `INVALID_RESPONSE` | malformed status/JSON |
| Timeout | distinguish bounded timeout | transport | `TIMEOUT` | delayed endpoint |
| Cross-build read | remote durability, no workspace reuse | RAW HTTP | `FOUND` | build A -> build B |
| Cross-agent read | node independence | RAW HTTP | `FOUND` | agent A -> agent B |
| Configuration invalid | no request/secret resolution | config resolver | `CONFIGURATION_ERROR` | URL/path/TLS cases |

## Item-7 implementation plan

- **TASK67 — 7a contract + credential model:** this document and evidence; 7 remains in progress.
- **TASK68 — generic RAW HTTP backend + Docker Nexus RAW:** minimally evolve `CoverageMapStore`, implement
  transport/layout/typed results and safe unauthenticated/local validation plumbing; add the controlled
  Nexus topology and runtime provisioning, without vendor leakage.
- **TASK69 — Jenkins credential integration:** add centralized storage configuration, execution-time
  Username/Password resolution, authenticated publish/exact/latest lookup, and secret-safety tests.
- **TASK70 — remote correctness E2E:** cross-build/cross-agent and R1/R2 proofs plus immutability,
  concurrency, interrupted-write, lookup-integrity, credential, and remote-failure validation.
- **TASK71 — item-7 closure audit:** verify contract/evidence and decide item 7 DONE. Item 8 remains separate.
