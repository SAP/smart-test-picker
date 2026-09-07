# TASK 40 — logical versus executable inventory contract

## Inventories

The **logical declared inventory** is every exact `TestIdentity` in the mapping target's authoritative
JUnit Platform discovery plan. It includes disabled and conditional declarations and is independent
of fragments, execution reports, collector observations, and successful-test counts.

The **execution target inventory** is the subset that the mapping run intends to execute. The
**reported runtime inventory** is the mapped and unsafe-unmapped identities actually reported by
collectors. Collectors own only these runtime facts and coverage; they do not manufacture expectation.

An **unmapped executable test** was an execution target but has no safe dependency mapping. It remains
`UnmappedTest` for every existing reason (`FAILED`, `SKIPPED`, `TIMEOUT`, or `COLLECTION_FAILED`) and is
always selected when still at head.

An **intentionally non-executable declaration** is in authoritative discovery but, based on an
orchestration-owned pre-execution/execution-condition signal, was intentionally outside this mapping
run's executable universe. It is encoded without a schema change as an identity in complete
`completeness.expectedTests`/`reportedTests` that appears in neither `tests` nor `unmapped`. Thus it has
no fabricated direct, method, or setup coverage and does not acquire `ALWAYS_SELECT` semantics.
`PublishedTestInventory` exposes this exact partition.

## Safety and completeness

`CollectionExpectation` accepts the explicit intentionally-non-executable set and requires it to be a
subset of independently discovered expectation. `Completeness.from` accounts for that set in addition
to collector-reported identities. A merely absent identity remains missing. Publication alignment also
requires the signal to equal exactly `expected - executable`; this prevents silent infrastructure loss
from being relabelled as intentional.

Global completeness remains exact set equality with zero missing, unexpected, duplicate-test,
missing-shard, and duplicate-shard entries. `Completeness.isComplete()` is unchanged.

The **selector head universe** remains the complete logical JUnit discovery inventory (`HeadTestInventory`;
its `runnableTests` accessor is a terminology mismatch retained for compatibility). Map-side new/deleted
comparison uses the published logical inventory. Selection policy still operates only on mapped executable
tests and unsafe executable `unmapped`; intentionally non-executable declarations neither look new nor
become mandatory. A changed declaration can still be selected by the unchanged changed-test rule.

## PetClinic reconciliation

The previous expectation came from Jenkins build 10 JUnit XML after excluding skipped cases: 73 XML
declarations, 69 runnable, expectation 69. Completeness was valid relative to that execution-only set.
TASK 40 aligns the preserved published map with the 73-entry TASK 39 discovery inventory. Its four
intentionally non-executable declarations are:

- `org.springframework.samples.petclinic.MySqlIntegrationTests#findAll`
- `org.springframework.samples.petclinic.MySqlIntegrationTests#ownerDetails`
- `org.springframework.samples.petclinic.PostgresIntegrationTests#findAll`
- `org.springframework.samples.petclinic.PostgresIntegrationTests#ownerDetails`

The aligned map preserves 69 mappings, 418 class edges, 1,343 method edges, and 10 setup scopes; it has
zero unsafe executable unmapped tests and four intentionally non-executable declarations. Exact pinned
head discovery equality yields zero new tests and `NONE`.

JUnit discovery itself does not evaluate all execution conditions reliably. The minimum safe status
signal is an orchestration-owned exact identity set obtained from JUnit Platform execution skip/disable
events or equivalent structured JUnit reports for the planned mapping run. Annotation source parsing is
not authoritative. An unexplained missing declaration cannot use this representation.

## Maven closure evidence

The real `schema-v2` Maven fixture produced six discovery identities without running Surefire: ordinary,
parameterized declared method, two overloads, nested binary class, and zero-coverage test. A later
`verify` run produced TASK 33 identity sidecars with exact equality for all six. The Maven plugin carries
the Jupiter engine and parameterized-test provider needed for discovery in its plugin realm.

The `schema-v2-reactor` fixture proves root aggregation across two modules whose tests share a simple
class name but have distinct FQNs. It merges both exact identities. Its optional collision module repeats
one canonical identity; generation removes the stale inventory and selector output is `FULL_SUITE`.
