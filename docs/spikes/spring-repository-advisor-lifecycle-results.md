# Spring repository advisor lifecycle feasibility results

## Decision

**move insertion to a proven stable public lifecycle point**

For the two pinned PetClinic contexts, `SmartInitializingSingleton.afterSingletonsInstantiated()` is the earliest public lifecycle point at which both `ownerRepository` and the final exposed `vetRepository` proxy are present, their advisor chains are stable, and an index-zero advisor can be inserted and audited before the first relevant repository invocation.

This was a feasibility result. Its recommendation has since been implemented
and acceptance-validated; this document remains the research record.

## Environment and scope

| Item | Value |
|---|---|
| PetClinic commit | `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272` |
| Spring Boot | 4.1.0 |
| Spring Framework | 7.0.8 |
| Spring Data | 4.1.0 |
| JVM | SapMachine 21.0.12, aarch64 |
| PetClinic source level | Java 17 |
| Maven Wrapper | 3.9.16 |
| OS | macOS 26.5.1, aarch64 |

The tests were:

```text
org.springframework.samples.petclinic.service.ClinicServiceTests#shouldInsertOwner
org.springframework.samples.petclinic.PetClinicIntegrationTests#findAll
```

The PetClinic source and POM remained unchanged. The diagnostic JAR used public Spring APIs only and emitted no STP runtime repository events.

## Commands

The confirmation JAR was built with:

```bash
GRADLE_USER_HOME=/tmp/stp-gradle-home \
  ./gradlew :stp-spring-data-observability-spike:petclinicConfirmationJar --rerun-tasks
```

The complete matrix command was:

```bash
MAVEN_USER_HOME=/tmp/stp-petclinic-maven-home \
MAVEN_REPO_LOCAL=/tmp/stp-petclinic-m2 \
ruby stp-petclinic-spike/spring-data-lifecycle/run_lifecycle_spike.rb
```

Each isolated Maven invocation had this form:

```bash
./mvnw -Dmaven.repo.local=/tmp/stp-petclinic-m2 -o \
  -Dtest='<one selected test>' \
  -Dmaven.test.additionalClasspath='<spring-data-petclinic-confirmation.jar>' \
  -Dstp.confirmation.mode='<lifecycle mode>' \
  -Dstp.lifecycle.output='<raw output>' \
  surefire:test
```

Normalization and validation:

```bash
ruby stp-petclinic-spike/spring-data-lifecycle/normalize_lifecycle.rb
ruby stp-petclinic-spike/spring-data-lifecycle/validate_lifecycle.rb
```

## Diagnostic lifecycle hooks

The spike observed these public points:

1. `RepositoryFactoryBeanSupport` before initialization and factory customization.
2. `RepositoryProxyPostProcessor` receiving `ProxyFactory` and `RepositoryInformation`.
3. early and terminal ordered `BeanPostProcessor.postProcessAfterInitialization` observations.
4. diagnostic index-zero insertion from the terminal BPP mode.
5. `SmartInitializingSingleton.afterSingletonsInstantiated` before and after insertion.
6. `ContextRefreshedEvent` before and after insertion.
7. public Spring TestContext `TestExecutionListener.prepareTestInstance`.
8. diagnostic caller advisor immediately before and after repository invocation.
9. public Spring Data `RepositoryMethodInvocationListener` after underlying execution.
10. `ContextClosedEvent` and `DisposableBean.destroy`.

Spring does not expose a public callback that wraps every other `BeanPostProcessor` invocation. The spike therefore used ordered before/final observers and identifies a mutating component only where its public advisor/component contract makes attribution explicit. It does not claim an unknown component based only on timing.

## Phase-by-phase findings

### Repository factory and Spring Data proxy construction

At `RepositoryProxyPostProcessor`, both repositories had the initial Spring Data chain:

```text
CrudMethodMetadataPopulatingMethodInterceptor
PersistenceExceptionTranslationInterceptor (Spring Data advisor)
TransactionInterceptor
```

Before the repository product was exposed, Spring Data added its public proxy interceptors, including default-method, query-executor and implementation-method interceptors. These additions are part of repository proxy construction, not the mutation which failed the STP audit.

### Owner context (`ClinicServiceTests`)

The final `ownerRepository` product was one JDK proxy layer with six advisors:

```text
CrudMethodMetadataPopulatingMethodInterceptor
PersistenceExceptionTranslationInterceptor (Spring Data advisor)
TransactionInterceptor
DefaultMethodInvokingMethodInterceptor
QueryExecutorMethodInterceptor
ImplementationMethodExecutionInterceptor
```

There was no cache advisor and no external `PersistenceExceptionTranslationAdvisor`. The same chain and advisor object identities remained unchanged through:

```text
product final BPP
→ SmartInitializingSingleton
→ ContextRefreshedEvent
→ prepareTestInstance
→ first OwnerRepository invocation
→ context shutdown
```

### Vet/full web context (`PetClinicIntegrationTests`)

The final `ownerRepository` product had the six advisors above plus:

```text
org.springframework.dao.annotation.PersistenceExceptionTranslationAdvisor
→ org.springframework.dao.support.PersistenceExceptionTranslationInterceptor
```

The responsible public component is:

```text
org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor
```

This is not inferred only from timing: the added public advisor type is the advisor installed by that public post-processor contract. It was already present by the final product observation, before `SmartInitializingSingleton` and before `ContextRefreshedEvent`.

The exposed `vetRepository` was materially different:

```text
outer JDK cache proxy, depth=2
advisor[0] = BeanFactoryCacheOperationSourceAdvisor / CacheInterceptor
target = inner Spring Data repository proxy
```

The outer cache proxy and its advisor existed at repository-product observation. They remained identical through `SmartInitializingSingleton`, refresh, TestContext preparation, both caller invocations and shutdown.

## Exact cause of `EXISTING_ADVISORS_CHANGED`

The production insertion captured `ownerRepository` before the full web context had completed persistence-exception translation processing. Its original fingerprint contained the six-advisor Spring Data chain. Before the production audit ran, `PersistenceExceptionTranslationPostProcessor` added this seventh advisor:

```text
org.springframework.dao.annotation.PersistenceExceptionTranslationAdvisor
```

Consequently the audited chain was:

```text
STP advisor
six original advisors
new PersistenceExceptionTranslationAdvisor
```

Removing only the STP advisor correctly left seven advisors rather than the original six. Restoration therefore could not prove the original fingerprint and failed closed with `EXISTING_ADVISORS_CHANGED`.

No advisor was removed, moved or replaced. The bean and target identities did not change for `ownerRepository`; the mutation was one appended advisor.

## Why earlier acceptance results differed

`vetRepository` reached `AUDIT_PASSED` because the bean seen by insertion was already the final, exposed two-layer cache proxy. Index-zero insertion placed STP outside `CacheInterceptor`, and no later advisor mutation occurred.

`ownerRepository` did not receive an audited advisor in the Owner acceptance context because it was not eligible/installed before the production registry closed its audit window. The acceptance diagnostic itself eagerly instantiated `vetRepository`, explaining why Vet appeared in the installation registry while Owner calls later had no repository facts. This is lifecycle timing, not mock behavior.

In the full Vet context, application wiring instantiated `ownerRepository` early enough for production insertion, but persistence-exception translation subsequently appended its advisor before the audit. That produced the restoration failure.

## Normalized structural diffs

### Full-context Owner mutation

```text
before production STP insertion
  0 CrudMethodMetadata
  1 Spring Data PersistenceExceptionTranslation
  2 Transaction
  3 DefaultMethod
  4 QueryExecutor
  5 ImplementationMethodExecution

after remaining BPP processing
  0 STP
  1..6 original advisors, identical and ordered
  7 PersistenceExceptionTranslationAdvisor       ← added
```

Phase: after the production adapter's product callback, but before `SmartInitializingSingleton`.

Responsible component: `PersistenceExceptionTranslationPostProcessor`.

### Full-context Vet cache proxy

```text
repository proxy post-processor: inner Spring Data proxy, depth=1
product final BPP:              outer cache proxy, depth=2, CacheInterceptor at 0
Smart insertion:                STP at 0, CacheInterceptor shifted to 1
refresh/prepare/calls/shutdown: unchanged
```

The outer cache proxy is created by Spring's public auto-proxy infrastructure for the cache advisor. The spike did not create it.

## Candidate insertion-point matrix

| Candidate | Final cache present? | Before first tested call? | Audit before events? | Identity unchanged? | Both contexts? | Finding |
|---|---:|---:|---:|---:|---:|---|
| Current production late BPP | Not reliably | Yes | No: later BPP mutation possible | Yes | No | Rejected; ordering is not terminal in practice. |
| Diagnostic terminal BPP | Yes in measured registration order | Yes | Yes in experiment | Yes | Yes | Technically works, but public BPP ordering cannot guarantee it remains last relative to equal/late processors. |
| `SmartInitializingSingleton` | Yes | Yes | Yes, immediately after insertion | Yes | Yes | Earliest proven stable public point. |
| `ContextRefreshedEvent` | Yes | Yes in both tests | Yes | Yes | Yes | Stable but later than necessary. |
| TestContext `prepareTestInstance` | Yes | Yes for test calls | Yes | Yes | Yes | Test-specific and later; unnecessary. |
| Lazy first invocation | Yes | At the call itself | Difficult without special recursion rules | Yes | Potentially | Rejected because Smart singleton point already solves the measured lifecycle safely. |
| Auto-proxy advisor registration | Can create/wrap another proxy | Yes | Different proxy semantics | Not guaranteed | Not proven | Rejected by no-additional-proxy boundary. |

No relevant repository invocation occurred before `SmartInitializingSingleton` in either selected test. The first caller-boundary diagnostic snapshot occurred after refresh and TestContext preparation. Startup calls remain a general limitation: an application that invokes a repository while creating another singleton could run before this point and is outside the proposed first-version guarantee.

## Required answers

1. **Exact failing change:** one appended `PersistenceExceptionTranslationAdvisor` on `ownerRepository`.
2. **Responsible component:** public `PersistenceExceptionTranslationPostProcessor`.
3. **Relative to smart-singletons:** before `SmartInitializingSingleton`.
4. **Relative to refresh:** before `ContextRefreshedEvent`.
5. **Earliest stable public point:** `SmartInitializingSingleton.afterSingletonsInstantiated`.
6. **Audit before first call:** yes for both selected PetClinic tests; insertion and immediate structural audit can complete there.
7. **Lifecycle difference:** Owner is a one-layer Spring Data proxy; Vet in the full context is a two-layer exposed cache proxy. The full context also applies external persistence exception translation to Owner.
8. **One strategy:** yes, smart-singleton insertion into each final exposed `Advised` singleton supports both measured contexts.
9. **Caller-level feasibility:** yes for this pinned scope without an additional proxy or internal API.

## Evidence

Reviewed normalized output:

```text
stp-petclinic-spike/spring-data-lifecycle/normalized-lifecycle.json
```

It contains eight isolated runs (four modes × two tests), deterministic advisor identity tokens, proxy structure, phase ordering and invocation-boundary snapshots. Regenerating it twice produced the same SHA-256:

```text
69ea060339685b49b1c716e40dafe04f29b5f682843a89e8261cf940e68a9ff5
```

The validator checks the pinned commit, all eight runs, final Vet cache proxy, full-context Owner translation advisor, index-zero smart insertion, stable advisor/target/depth state through refresh and TestContext preparation, and the required recommendation.

## Recommendation

Move the experimental production insertion/audit from repository product processing to `SmartInitializingSingleton.afterSingletonsInstantiated()`. Retain the existing strict fingerprint and restoration checks. Explicitly document that repository calls made during singleton creation precede observation and remain unsupported.

**move insertion to a proven stable public lifecycle point**
