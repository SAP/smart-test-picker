# TASK 44 — 5d execution accounting and transport

TASK 44 continues canonical item 5d in the companion Jenkins repository. It introduces a separate version-1 execution-evidence artifact bound to revision, shard, build tool, and test target. Exact `TestIdentity` values come from structured JUnit `MethodSource` events and the existing Maven TASK 33 identity sidecars; display names and test-result XML are not identity authorities.

Physical invocations aggregate to a logical identity: PASS or FAIL makes it executable; skipped, disabled, or aborted-only makes it positively non-executed; a mixed parameterized group is executable. The join requires the disjoint identity equality `expected = mapped ∪ executable-unmapped ∪ positive-non-executed`. It never infers skip evidence by subtraction and does not publish intentionally non-executed tests as synthetic `UnmappedTest` coverage.

Preparation freezes revision before build-tool-owned discovery and rejects HEAD movement. Plan v2 owns inventory, shard inputs, fragment, compact diagnostic, evidence, and stash identities. Shard steps validate and automatically stash successful bounded outputs; publication automatically restores every expected stash and fails closed on missing or invalid evidence before immutable storage and pointer update.

The existing Docker Jenkins 2.516.2 topology validates pinned PetClinic revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` with an authoritative inventory of 73 and three physical agents. Accepted Jenkins build 27 assigned 34/12/27 identities and accounted for 69 mapped, zero executable-unmapped, and four positively non-executed identities. It published 418 class edges, 1,343 method edges, and 10 setup scopes; immutable storage, the latest pointer, and the single-versus-three semantic comparison all passed. Compact results are committed in the Jenkins repository under `verification/task44/`.

Canonical state remains 5a/5b/5c DONE, 5d IN PROGRESS, and 5e NOT STARTED / independent. Remaining 5d work is a durable remote backend with failure injection, a full Spring Core productionized Jenkins publication, and the final closure audit.
