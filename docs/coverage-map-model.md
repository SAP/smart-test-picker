# Coverage map semantic model

The immutable records in `coverage.model` are separate from codecs in `coverage.serialization`.
Schema 2 uses logical test identities. Schema 3 uses `ExecutableTestIdentity`: a canonical execution
target plus logical identity, so the same test in two build targets remains two executable occurrences.

## Identity

`TestIdentity` retains a qualified binary class name, exact method/feature name and declared parameter
types. The first `#` separates class and method; subsequent `#` belongs to the name. Method names
are nonempty free text except ISO control characters; punctuation such as '.', ';', '[' and '/' is valid.

A nonempty trailing parenthesized suffix is parsed as parameter types only when every trimmed element
matches the supported primitive, qualified binary-name/array or JVM array-descriptor grammar. Original
spelling is retained; `()` is part of a name. This wire format has an accepted ambiguity: a feature
literally named `foo(java.lang.String)`, `foo([I)` or `foo(int)` with no parameters has the same text as a
parameter-bearing identity. A simple default-package type name such as `Foo` is read as name text.
There is no escape format. Production `MethodIdentity` is a distinct class/name/JVM-descriptor triple.

Build filters cannot select one overload by its parameter types. Assignments must include all effective
overloads of each assigned class/method name. Inventory identity remains descriptor-distinct.

## Coverage, setup and missing observations

`TestCoverage` stores sets of covered production classes and methods, an outcome and collection status.
`COLLECTED_EMPTY` is an observed successful empty set; `COLLECTION_FAILED` belongs in unmapped.
The model can represent a failed outcome separately from collection, but the ASM projector deliberately
puts failed tests into unmapped `FAILED` rather than retaining their partial coverage.

Setup scopes carry class coverage and affected containers; executable scopes also have an execution
target owner. Selection conservatively includes tests in those containers for relevant changes.
Not every modeled setup kind is emitted by every collector.

Completed, otherwise valid ASM target fragments absorb assigned identities absent from both mapped and
unmapped as `COLLECTION_FAILED`. Maven and Gradle use the same audit/merge rule. The audit remains on
the rewritten target fragment (and Maven evidence), not the generic joined map. It records
`ASSIGNED_TEST_NOT_REPORTED`, identities, count and `executionObserved=false`.
Paired-directory CLI joining has a separate `ASSIGNED_NOT_REPORTED` reason for its list complement.

Unmapped tests still present at head are mandatory in selection until a later map covers them.
`SKIPPED` observations provide positive non-execution evidence; an absent observation cannot do so.

## Completeness and publication

Expected identities and shard assignments are authoritative inputs, not inferred from successful
test counts. Accounted identities include mapped, non-SKIPPED unmapped and explicitly declared
non-execution. Completeness requires empty missing/unexpected/duplicate-test sets and empty
missing/duplicate-shard sets. The joiner also rejects unexpected shards and invalid partitions.
There is no degraded-map publication mode.

Only validated published maps are selection inputs. Missing whole shards, incomplete collection,
bad bindings or contradictory ownership do not become accepted through absorption.
Complete accounting is not a guarantee of causal attribution or unobserved code-path coverage.

See [wire schemas](coverage-map-schema.md), [v3 encoding](v3-map-format.md) and
[collector boundaries](asm-coverage-mapping-current-state.md).
The [original schema design](history/coverage-map-model.md) is retained as history.
