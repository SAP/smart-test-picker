# TASK69 — Jenkins credential integration and authenticated storage

## Goal and starting state

TASK69 connects the TASK67 credential contract to TASK68's vendor-neutral RAW HTTP store through the public Jenkins mapping steps. Clean baselines were main STP `448db5a` and Jenkins plugin `d35941c`. Item 7 remains in progress; item 8 is unchanged.

## Storage configuration model and effective snapshot

`StorageType` contains only `FILE` and `RAW_HTTP`; `FILE` is the backward-compatible default. Global configuration persists the FILE root and RAW HTTP base URL, repository path, credential ID, insecure-HTTP opt-in, and bounded connect/read timeouts. Type-aware validation retains TASK68 URL/path rules. A legacy Pipeline `storageRoot` remains a FILE override and is rejected for RAW_HTTP.

Each public step snapshots an immutable serializable `StorageConfiguration` at `start`. It contains only backend settings and `credentialId`, so an invocation cannot observe later global changes and no resolved credential crosses Pipeline suspension.

## Credential resolution, type, and secret lifetime

`JenkinsStorageCredentialResolver` calls `CredentialsProvider.lookupCredentials(Credentials.class, run.getParent())` and `CredentialsMatchers.withId`, scoped to the owning Job/Item and its visible folder/domain credentials. The sole supported type is `StandardUsernamePasswordCredentials`. Missing ID, absent credential, and incompatible type produce `CREDENTIAL_REQUIRED`, `CREDENTIAL_NOT_FOUND`, and `CREDENTIAL_TYPE_UNSUPPORTED`.

The Jenkins `Secret` is unwrapped immediately before controller-side I/O. Mutable password/header buffers are cleared in `finally`. No Pipeline parameter, effective snapshot, URL, map, pointer, STP configuration, or evidence contains the password.

## Authentication abstraction and RAW HTTP authentication

The Jenkins-neutral runtime defines non-serializable `HttpRequestAuthenticator` and `RawHttpAuthentication`. Basic authentication encodes UTF-8 `username:password` only while building each request. Its `toString` is redacted. `RawHttpCoverageMapStore` accepts this optional generic authenticator while retaining its unauthenticated constructor and imports no Jenkins or Nexus class. Local-server tests prove the header plus TASK68's 401/403 mappings.

## Public publish and lookup integration

Both steps use `CoverageMapStorageResolver`. FILE retains the agent helper. RAW_HTTP copies the validated joined map to a controller temporary file, resolves the credential, and invokes the bundled neutral store in-process. A public Docker Pipeline published a complete fixture map to authenticated Nexus, performed immutable read-back, and updated the validated pointer. A separate public lookup build resolved the credential, validated pointer/map identity and checksum, and materialized bytes in the agent workspace. Neither Pipeline supplied backend or secret arguments.

## Coordination process boundary

The old public helper launches a separate agent JVM, so TASK68's static locks alone cannot span builds. TASK69 executes RAW_HTTP in the controller plugin process and wraps the complete public remote operation in a controller-owned lock keyed by base, repository, project, and branch. This spans concurrent builds on one controller. Multiple controllers and external writers remain unsupported without remote CAS.

## Jenkins UI and persistence

Global UI exposes storage type plus all FILE/RAW fields. Credential ID is currently a text field, a UX limitation rather than a security limitation. Restart testing covers every field and audits only STP XML: credential ID is present; username, password, token, and Authorization are absent.

## Nexus authenticated topology and validation

The disposable topology used one controller, one agent, and Nexus repository `stp-coverage-maps`. Runtime provisioning created user `stp-publisher` and Jenkins credential ID `task69-nexus-storage`; its generated password was never printed or committed. Anonymous access was disabled and unauthenticated PUT returned 401. Public publish build A and lookup build B passed.

Wrong password produced public `AUTHENTICATION_FAILED`; missing credential produced public `CREDENTIAL_NOT_FOUND` before transport. Wrong type produced `CREDENTIAL_TYPE_UNSUPPORTED` in a real Jenkins harness. Nexus authorization denial was not separately run; an explicit backend test retains `403 -> AUTHORIZATION_FAILED`.

## Secret safety, FILE regression, and boundaries

A generated-marker scan across captured Pipeline output and controller logs recorded `secretLeakDetected=false`. Existing public FILE publish/lookup, default root, and `storageRoot` compatibility tests pass.

No status was mapped to a CI action and no fallback policy was added. TASK70 owns wider cross-build/cross-agent/concurrency remote correctness E2E; the item-7 closure audit follows it.
