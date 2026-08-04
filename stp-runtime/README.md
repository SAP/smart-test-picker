<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# stp-runtime (experimental)

`stp-runtime` defines the framework-neutral event vocabulary and deterministic
in-memory result format for the PetClinic runtime-dependency spike. It provides
immutable test and method identities, semantic dependency events, explicit
evidence, aggregation, and canonical JSON output.

It does **not** attach a Java agent, transform bytecode, discover JUnit tests,
inspect Spring, observe Hibernate/JDBC, integrate PetClinic, or propagate context
between threads. Callers must explicitly begin, record events for, and finish a
test. `RuntimeContextService` provides a same-thread `beginTest`/`endTest`,
`currentTest`, and `record` lifecycle around an explicitly supplied aggregator.
It clears its `ThreadLocal` in a `finally` block and deliberately retains no
cross-thread carrier.

After `endTest`, the service intentionally keeps the last finished identity in
a same-thread marker. An immediate observation on that thread is routed to the
finished bucket and becomes `LATE_EVENT`. `beginTest` clears the marker before
activating the next test, so the previous test cannot receive the next test's
events. A different thread has no marker and records `NO_ACTIVE_TEST` globally.
This is narrow experimental late-event accounting, not async propagation or a
general lifecycle token.

`RuntimeContextRegistry` is the framework-neutral discovery bridge for the one
explicitly owned `RuntimeContextService`. It is empty by default, never creates
a fallback runtime, permits only one active registration, and uses a scoped
registration whose close cannot remove a newer service. The agent owns the
installed service; JUnit and future optional integrations only discover it.
There is still no static global collector or fallback aggregator.

Evidence carries both a source and a certainty. `OBSERVED` means a runtime
integration reported an occurrence; `INFERRED` means metadata or static mapping
supports a dependency without proving runtime access. A `MAPPED` table is kept
separate from `OBSERVED_READ` and `OBSERVED_WRITE`; mapping an entity to a table
must never be presented as an observed database operation.

Repository events also carry a required `RepositoryKind` provenance. The only
currently supported value is `SPRING_DATA_PROXY`: it means the observation came
from the Spring Data repository-proxy boundary. It does not identify JDK versus
class-based proxying, a generated proxy class, bean implementation type, or
mock status. Kind participates in repository dependency equality and sort order
so observations from future, genuinely different repository boundaries cannot
be merged into the same fact.

The module is an experimental spike API. Its schema version is `spike-2`, source
and binary compatibility are not promised, and it intentionally does not apply
`maven-publish` or participate in current Smart Test Picker production behavior.
