# Task 32 — runtime safety and Spring Core Jenkins regression

## Runtime ownership contract

The collector now applies the mandatory rule: exact bounded attribution or explicit safe degradation.
Inherited JUnit Jupiter `@BeforeAll` and `@AfterAll` methods are observed inside the concrete executing
`ClassSource` container, even when the method is declared by a superclass. The focused real-launcher
fixture therefore retains a truthful `CONTAINER` scope for the subclass; it does not invent a new setup
kind or attribute the lifecycle method to the declaring superclass as an affected test container.

Supported asynchronous wrappers now capture an active setup container as well as an active leaf. Work
executed while that concrete container remains open is setup coverage for exactly that container. If the
captured work executes after its container closes, its method hit is quarantined, an
`ASYNC_SETUP_UNSUPPORTED` error is recorded, and projection is incomplete. The ASM call-site transformer
also recognizes unsupported `ForkJoinTask`/task-shaped `ForkJoinPool` submission boundaries: it records
the error only when a test or setup container is active and never assigns the later hit to a guessed
owner.

Shared-fixture integrations have a narrow explicit boundary for an initializer whose complete consumer
set is unavailable. Hits inside that boundary create no setup edge and record
`SHARED_CONTEXT_SETUP_UNSUPPORTED` as an error. This is safe degradation, not generalized Spring
TestContext modeling. Generic `NO_ACTIVE_TEST` and `LATE_EVENT` observations remain diagnostic-only;
only stronger lifecycle/submission evidence produces an ownership error.

## Fragment publication

Gradle deletes the prior fragment and diagnostic at mapping-task start, and agent startup independently
invalidates the configured fragment target. The shutdown publisher projects and serializes schema v2,
decodes the exact serialized bytes for validation, writes a sibling temporary file, and moves it to the
final target with `ATOMIC_MOVE` plus replacement. Filesystems without atomic-move support use a
same-directory replacement fallback; temporary files are removed on every path.

After the test JVM exits, `generateSmartTestCoverage` requires the fresh target to exist, decodes it with
`CoverageFragmentCodec`, and checks revision, shard, and `collection.completed=true`. Tests cover missing,
malformed, wrong-revision, wrong-shard, incomplete, and failed-final-replacement cases. Thus a failed
attempt cannot expose an older completed fragment as current output.

## PetClinic regression

Spring PetClinic revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` passed the production Gradle
ASM path against the established 69-test non-database environment baseline. The fresh schema-v2 fragment
maps 69 tests with zero unmapped, 418 class edges, 1,343 descriptor-aware method edges, and ten bounded
single-container setup scopes. Collection is complete; agent errors, transformation failures, method-ID
collisions, fragment failures, and ownership errors are zero. The historical `CacheConfiguration`,
`CrashController`, and `WelcomeController` relations to `ValidatorTests` remain absent.

## Scope and backlog

## Spring Core Jenkins regression

The accepted run is Jenkins job `stp-task32-spring-core` build 7 on physical node `stp-agent`, with
Spring Framework `99a366baf6640b275d08dde60f05da719139bb6a`, Gradle 8.14.2, and Eclipse Temurin
21.0.12.1. Independent `:spring-core:test` inventory and the full mapping execution each ran 4,705
physical cases with 29 skipped and no failures. XML normalization found 3,647 logical identities,
3,643 with at least one runnable execution, and four only skipped. The collector's authoritative
JUnit `MethodSource` normalization produced 3,632 mapped logical tests and six explicitly unmapped
skipped tests.

`stpCoverageMap` invoked `:spring-core:generateSmartTestCoverage` with `collector=ASM`, validated the
fresh output with the shared mapping tool, and completed successfully. The schema-v2 fragment has the
exact revision, shard `task32-full`, `collection.completed=true`, 45,648 class edges, 171,649 method
edges, and 112 setup scopes. Agent errors, transformations failures, method-ID collisions, runtime
initialization failures, fragment failures, unsupported identities, setup errors, and ownership-sensitive
async errors are all zero. There are 11,212 benign `NO_ACTIVE_TEST` observations and zero `LATE_EVENT`s.
Kotlin/backtick display identities remain present, and no `$jacocoInit` method or historical JaCoCo
listener path appears.

The hostile run exposed and fixed two Jenkins/Gradle adapter assumptions needed for this regression:
the Jenkins init script now activates the plugin only after the Java plugin is present and derives the
fragment directory from the complete Gradle task path; the dedicated Gradle mapping task inherits the
standard test task's JVM arguments, heap bounds, assertions flag, and system properties. This preserved
Spring's required BlockHound JVM flag without changing test selection.

Schema v2, selector behavior, Maven integration, and Task 5d production scope are unchanged. Task 5c
remains in progress: the Gradle/runtime closure is complete, and Task 33 still owns the remaining Maven
schema-v2 adapter.
