# TASK 25 — method identity and setup attribution contract

## Current method identity mismatch

TASK 24 runtime observations identified a method by `binaryClassName + methodName + jvmDescriptor`,
while schema v1 stored `binaryClassName#methodName`. Thus `Service#doIt()V` and
`Service#doIt(Ljava/lang/String;)V` both became `Service#doIt`. TASK 24 rejected this with
`schema-method-overload-conflict`; it prevented false merging but made valid overloads unmappable.

Descriptor loss is unsafe because selectors, graph edges, checksums, and map comparisons would treat
two different bytecode methods as one dependency. A descriptor cannot be reconstructed from a Java
name or old JaCoCo string. The affected surfaces are `TestCoverage`, fragment and map codecs,
`methodIndex`, validation, canonical ordering/checksums, projectors, and method-edge consumers.

## Schema version and compatibility decision

Schema v1 is frozen, documents exact-version compatibility, and assigns descriptor-less semantics to
the same string field. Extending that field would make old and new readers disagree. TASK 25 therefore
introduces schema v2. Its semantic `coverage.model.MethodIdentity` contains binary class name, exact
JVM method name, and exact JVM descriptor. Canonical text is
`binary.Class#methodName(JvmDescriptor)`, for example `Type#<init>(Ljava/lang/String;)V` and
`Type#<clinit>()V`. It validates, parses, sorts, hashes, and compares as a value. Source-language
signatures and descriptor normalization are forbidden.

Schema-v2 codecs require exact version 2. No automatic v1-to-v2 conversion is provided. Existing
legacy and production JaCoCo paths are unchanged; any bounded legacy conversion must retain class
coverage and omit method edges whose descriptors are unavailable. It must never guess a descriptor.

## Test identity

JUnit Jupiter permits overloaded test methods and exposes declared parameter types in `MethodSource`.
They are distinct runnable logical methods, so schema v2 `TestIdentity` includes the normalized JUnit
parameter-type text. Empty parameter lists retain the historical `Class#method` text; a non-empty
signature is `Class#method(java.lang.String)`. Parameterized/repeated invocations of the same declared
signature still collapse, while overloads do not. Display names are never parsed.

## Authoritative setup support matrix

| Setup type | Observable | Attributable | Schema representation | Production behavior | Fallback |
|---|---:|---:|---|---|---|
| `BeforeAll` | yes | yes, bounded class interval | `CONTAINER` | container coverage | none |
| `AfterAll` | yes | yes, bounded class interval | `CONTAINER` | container coverage; never last leaf | none |
| `BeforeEach` | yes | yes | test edge | active leaf coverage | none |
| `AfterEach` | yes | yes | test edge | active leaf coverage | none |
| static initializer in leaf | yes | yes | test edge | active leaf coverage | none |
| static initializer in container lifecycle | yes | yes | container scope | bounded container coverage | none |
| static initializer outside ownership | yes | no | no edge | unattributed runtime event | never guess |
| nested container lifecycle | yes | yes | `NESTED_CONTAINER` | binary nested container scope | none |
| inherited setup | possibly | no reliable declaring/affected set | none | unsupported | explicit error diagnostic; incomplete fragment |
| shared Spring/JUnit context | possibly | no reliable affected set | none | unsupported | explicit error diagnostic; incomplete fragment |
| framework setup | possibly | no reliable affected set | none | unsupported | explicit error diagnostic; incomplete fragment |
| parallel test containers | yes | yes for thread-bounded JUnit callbacks | independent container/test scopes | supported; no global active container | unowned events remain unattributed |
| async setup without propagated ownership | yes | no | none | unsupported | explicit error diagnostic; incomplete fragment |

`AfterAll` is owned only while the actual JUnit class container remains active. Finishing a leaf clears
the last-test quarantine when such a container exists, so subsequent synchronous lifecycle work goes
to the container. Once the container closes, events are `NO_ACTIVE_TEST`; they are not assigned to the
last test or container.

## Fail-open and integrity contract

If setup cannot be attributed to a bounded container, the collector creates no test or setup edge.
Typed `SetupDiagnostic` values distinguish `UNATTRIBUTABLE_SETUP`, parallel-container uncertainty,
shared context, inherited setup, and async setup. `ERROR` means known potential false-negative
coverage and forces `collection.completed=false`; `WARNING` and `INFO` are observable but do not do
so. Agent transformation failure, ID collision, initialization failure, unfinished/missing test
result, unsupported logical identity, and error-severity setup uncertainty are critical. Ordinary
startup/shutdown/no-active-test noise is non-critical unless a framework adapter identifies it as
setup.

This is local shard completion only. Expected tests/shards, map publication, and conservative
selection remain 5d/5b responsibilities. The collector does not emit `RUN_ALL`.

## SetupScope type audit

| Type | Classification |
|---|---|
| `CONTAINER` | `PRODUCIBLE_NOW` |
| `NESTED_CONTAINER` | `PRODUCIBLE_NOW` |
| `INHERITED_SETUP` | `SUPPORTED_BUT_NOT_YET_IMPLEMENTED` |
| `SHARED_CONTEXT` | `RESERVED` |
| `FRAMEWORK_SETUP` | `RESERVED` |

Reserved types are not emitted merely because they exist. None is classified safe for fabrication.

## Shards and parallelism

Setup IDs are derived from the actual binary container and each scope affects only that container.
The repository fixture compares one collector containing two containers with two collectors containing
one container each; normalized scopes are identical. There is no run-wide container set or Cartesian
attribution. Parallel leaf ownership is thread-local and container scopes are thread-local stacks, so
parallel containers do not share ownership. Events outside those intervals remain unattributed.

## Remaining boundaries

Framework adapters must explicitly identify inherited/shared/async setup before typed diagnostics can
be emitted; generic no-active-test bytecode alone cannot infer intent. Setup scopes remain class-level
in schema v2. Global completeness, selector fallback, merging, publication, and build-tool attachment
remain outside TASK 25.
