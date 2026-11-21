# ASM coverage mapping: current state

ASM observes production method entry and attributes it to test or setup owners.
A complete map proves structural accounting, not causal completeness of every
execution. Unsupported asynchronous handoffs, escaped work and shared application
state can still limit attribution. See [collector comparison](asm-vs-jacoco.md),
[context propagation](asm-context-propagation.md) and [compatibility](compatibility.md).

## Scope and execution

Gradle derives includes from the production classes directories and project JARs
on the mapped Test task's runtime classpath. Maven uses reactor production outputs
and installed JARs of omitted reactor dependencies. Explicit includes override
prefix derivation, not the production-source boundary. Both prepared paths require
at least one transformed class. A single test with no production edges is valid.

Inventories follow the effective build selection. Assignments may only narrow it;
partial method-overload assignments are rejected because provider filters match
method names, not parameter types. Mapping requires a single test JVM. Gradle
overrides parallel forks to one, rejects `forkEvery`, and rechecks effective values
after execution. Maven guards its supported fork configuration. These checks are
not an in-JVM parallel-test isolation guarantee.

## Per-test outcomes and absorption

`AsmCoverageFragmentProjector` retains passing siblings when a known test fails
or is unfinished. Failed tests become `FAILED`; unfinished or result-less tests
become `COLLECTION_FAILED`; wholly aborted tests become `SKIPPED`. Passing tests
with no hits are `PASS / COLLECTED_EMPTY`.

Collector health counters and unowned error strings remain diagnostic information,
not a shard-wide invalidation switch. Explicitly owned errors affect their named
identities. ERROR setup diagnostics independently make collection incomplete.
This policy preserves observations; it does not prove that missing instrumentation
or an unowned propagation error left every passing test's coverage complete.

For a completed, otherwise valid schema-v3 ASM fragment, Maven and Gradle use
`ExecutableAssignmentAbsorption` to add each unreported assigned identity as unmapped
`COLLECTION_FAILED`. The rewritten target fragment carries `assignmentAbsorption`
with reason `ASSIGNED_TEST_NOT_REPORTED`, count, identities and
`executionObserved=false`. Existing audit data is validated and merged. Writes
are atomic; healthy fragments are not rewritten unnecessarily.

Maven also includes the audit in its module execution evidence. Gradle does not
produce a separate execution-evidence file. The generic fragment/map codecs do
not retain this audit extension; retain finalized target artifacts for provenance.
Maven evidence's `EXECUTED` coverage-owner bucket includes non-SKIPPED unmapped
identities and is not, by itself, proof that an absorbed test physically ran.

Unmapped identities still present at head are mandatory in selection until a later
map covers them. Absorption does not repair invalid diagnostics, wrong bindings,
unexpected identities, invalid setup owners or incomplete fragments. Allowed
unmapped reasons at ASM finalization are `SKIPPED`, `FAILED` and
`COLLECTION_FAILED`. Missing whole shards still prevent joining.

## Setup ownership and limitations

Container-owned work can form a class-level setup scope rather than a requesting
leaf's edge. Selection conservatively includes head tests in an affected container
when a changed class intersects that scope. This is not request-specific ownership.

Workers captured by a container that later closes can emit
`ASYNC_SETUP_UNSUPPORTED / ERROR` and `LATE_EVENT`; shared cached web contexts with
that topology reject collection. ForkJoin execution without observed submission
can also reject. Such rejection is not a general detector for parallel tests.

The JaCoCo collector instead resets process-global probes at session boundaries;
parallel or escaped work can cross those boundaries. Single-JVM enforcement does
not make that attribution thread-isolated. Neither collector waits for arbitrary
asynchronous descendants.

Enum values for shared/inherited setup and parallel-container diagnostics are not
proof of runtime support. Unbounded shared setup has no known consumer set and
reports an ERROR when invoked. ClassSource-only Vintage identities and per-fork
merging remain unsupported. The [historical attribution report](history/asm-coverage-mapping-current-state.md)
retains the experiments and their limits; it is not a current support matrix.
