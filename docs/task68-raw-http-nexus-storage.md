# TASK68 — RAW HTTP storage and Nexus validation

## Goal and starting contract

TASK68 implements item 7b's first production remote family, vendor-neutral RAW_HTTP. TASK67 retained
CoverageMapStore, named FileCoverageMapStore the local/reference backend, and required typed outcomes,
checksummed pointers, application immutability, and an explicit writer boundary. Item 5d and item 8 are
unchanged; item 7 remains in progress for TASK69 credentials.

## CoverageMapStore evolution and typed storage model

The interface no longer returns Path. StoredObject, MapLookupResult, and PointerUpdateResult carry
logical/backend-relative keys, SHA-256, bytes where applicable, and StorageStatus. StorageException
classifies all failures. The vocabulary includes FOUND, STORED, ALREADY_EXISTS_IDENTICAL,
POINTER_UPDATED, POINTER_ALREADY_CURRENT, NOT_FOUND, INVALID_RESPONSE, INTEGRITY_FAILURE,
IMMUTABILITY_CONFLICT, POINTER_CONFLICT, STALE_POINTER_UPDATE, REMOTE_UNAVAILABLE, TIMEOUT,
CONFIGURATION_ERROR, and the TASK67 credential classifications. No status implies an item-8 action.

## Structured pointer and file migration

Deterministic schema version 1 JSON contains exactly schemaVersion, project, branch, revision, and
mapSha256. Strict decoding rejects missing/extra fields, unsupported versions, blank identities, and
invalid digests. The file backend writes the canonical TASK67 layout and latest.json. It reads a legacy
latest-revision and legacy map path, deriving the digest from the target; all later writes use the new
format. Immutable retry/conflict, read-back, file locking, and safety remain intact.

## RAW HTTP backend and transport

RawHttpCoverageMapStore is production code and imports no Nexus API. Configuration is baseUrl,
repositoryPath, allowInsecureHttp, connect timeout, and request/read timeout. The JDK HTTP client has
redirects disabled and bounded bodies. GET accepts 200 and maps 404 to NOT_FOUND. PUT accepts
200/201/204. 401/403 classify authentication/authorization, 408/504 timeout, 429/5xx unavailable, and
other statuses invalid response. Client SHA-256 plus exact GET read-back is authoritative.

## Layout and path safety

Revision objects use projects/safe-project/branches/safe-branch/revisions/safe-revision/coverage-map-v2.json;
the pointer uses projects/safe-project/branches/safe-branch/pointers/latest.json. Logical values reuse
safeComponent. Base URLs reject userinfo, query, fragment, and insecure HTTP by default. Repository
paths reject absolute paths, dot segments, backslashes, and encoded separators.

## Immutability, concurrency, publication, and lookup

Application check/write/GET verification prevents overwrite even if the server permits redeploy. Shared
mapping validation requires complete schema-v2 PUBLISHED bytes and revision binding before the pointer.
Exact/latest lookup validates maps, the pointer digest, logical identity, and map/pointer revision equality;
it never synthesizes an empty map.

File publication retains its shared-filesystem lock. RAW HTTP uses JVM-wide keyed locks covering the
complete immutable and pointer transactions. This supports one Jenkins controller/process as sole writer.
Multiple controllers or external writers are unsupported until remote CAS exists.

## Docker Nexus topology and provisioning

Compose adds nexus / stp-plugin-nexus with pinned image sonatype/nexus3:3.96.0 and named volume
nexus_data. The helper accepts the Community Edition EULA through REST, creates hosted RAW repository
stp-coverage-maps, and enables a disposable repository-scoped anonymous role. It reads the bootstrap
password transiently and never prints or stores it. docker compose down -v removes disposable data.

## Runtime and failure validation

Real Nexus tests passed publish/read-back, schema/revision binding, exact/latest lookup, idempotence,
immutable conflict with original bytes preserved, R1-to-R2 stale rejection, divergent rejection, and
corrupt-pointer rejection. Unreachable transport classification passed. A Jenkins controller loaded the
packaged plugin runtime and used its production RAW backend to resolve the real Nexus latest map. Public
backend configuration selection is pending credential-aware integration.

## Security, credential, and item-8 boundaries

No Nexus production class, URL credential, Jenkins Credentials API, credential field, password/token
Pipeline input, secret persistence, SCM/PR change, or fallback decision was added. TASK69 owns credential
resolution and authenticated public selection. Item 8 remains not started.
