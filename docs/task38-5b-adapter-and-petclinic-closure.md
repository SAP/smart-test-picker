# TASK 38 — 5b adapter and PetClinic closure

Schema-v2 selection now has one adapter-facing flow: candidate map plus an exact structured head
inventory enter `SchemaV2SelectionAnalyzer`, then `SchemaV2TestSelector` produces `SelectionOutput`.
Gradle, Maven, and CLI use that flow. The legacy engine remains only for legacy reporting compatibility.

The inventory file is a JSON array of canonical `TestIdentity` strings (binary FQN, declared method,
and declared parameter types when present). It is intended to be produced from JUnit `MethodSource`,
the structured source already used by runtime adapters. No selector adapter scans class filenames,
parses display names, or matches simple names. Missing or malformed inventory produces `FULL_SUITE`.

Execution consumes `selectedTests` only. `unmappedTests` remains diagnostic. Gradle and Surefire
conservatively widen selected identities to their complete binary test classes. `NONE` installs an
explicit no-test sentinel. `FULL_SUITE`, null, and unknown states remove or avoid restrictive filters;
Maven also deletes stale includes. CLI text writes the literal `FULL_SUITE` marker; Ant rejects
`FULL_SUITE` with a nonzero exit because an unfiltered Ant selector cannot be represented safely.

The real-project regression used a disposable detached PetClinic worktree at the pinned revision and
reused the checksum-valid published map with 69 runnable tests, 418 class edges, 1,343 method edges, and
10 setup scopes. Each scenario ran `selectTests` and `smartTest` separately. Gradle XML identities were
compared with selector output; compact accounting is in
`stp-petclinic-validation/task38/selector-regression.json`.

Direct selection ran 49/49 identities. A `CacheConfiguration` setup change ran 51/51; its three exact
affected containers were included and no container outside the union of direct edges and bounded scopes
leaked in. The pinned map has no production class occurring in exactly one setup scope, so the requested
single-scope premise is false for this revision; the test uses its exact three scopes. Controlled
unmapped and new-test cases each selected and executed one identity. `NONE` produced zero test XML.
Missing-map, trigger, and nonancestor cases each ran all 69 runnable tests (plus four disabled cases).
Method edges were not consulted.

This closes 5b without adding 5d storage/orchestration, 5e base/head controls, mapping changes, or
method-level selector precision.
