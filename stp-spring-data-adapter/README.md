<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# stp-spring-data-adapter (experimental)

This unpublished, disabled-by-default module observes synchronous caller-level
invocations of a narrowly supported set of real Spring Data repository beans.
The complete user setup and compatibility contract is in
[`docs/spring-data-adapter.md`](../docs/spring-data-adapter.md).

Activation requires the adapter JAR on the test runtime classpath and exactly:

```text
-Dstp.spring-data.enabled=true
```

Missing and `false` mean disabled; malformed values fail clearly. Enabled
startup requires visible Spring Data public APIs and an agent-installed service
in `RuntimeContextRegistry`. Missing prerequisites disable safely and never
create a fallback runtime.

## Lifecycle and boundaries

When active, public `RepositoryFactoryBeanSupport` customization captures
immutable `RepositoryInformation`: canonical bean name, aliases, authoritative
repository interface, domain type, provenance, and context identity. A late
BeanPostProcessor classifies final products without changing them. Matching
factory metadata naturally excludes mocks.

At `SmartInitializingSingleton.afterSingletonsInstantiated()`, eligible JDK
repository proxies are resolved again from the context, fingerprinted, and
receive exactly one context-owned advisor through `Advised.addAdvisor(0, ...)`.
The immediate structural audit proves bean/target identity, exposed interfaces,
proxy depth, original advisor identity/order, and placement before every
`CacheInterceptor`. Recording is enabled only after `AUDIT_PASSED`.

No additional proxy is created. Failure removes only the exact STP advisor and
proves restoration; an unprovable restoration aborts explicitly enabled startup.
Repositories first created after this lifecycle phase remain untouched and get
the bounded `REPOSITORY_CREATED_AFTER_INSERTION_PHASE` diagnostic.

## Event semantics

Each synchronous caller invocation produces one terminal
`RepositoryInvocationEvent` (`SUCCEEDED` or `FAILED`). Equal events aggregate by
count. Method descriptors come from the reflective method using JVM erasure;
for example `save(Ljava/lang/Object;)Ljava/lang/Object;`. Domain specialization
belongs only in `domainType`.

The advice calls `proceed()` exactly once, returns the exact result, and rethrows
the exact repository throwable. Runtime lookup/event/recording failures are
isolated and counted. No argument, result, entity state, SQL, stack trace, or
generated proxy identity is inspected or retained.

The advice lazily resolves the same agent-owned `RuntimeContextService` as ASM
hooks and the JUnit listener. There is no second aggregator. Attribution is
same-thread only; async and reactive propagation are unsupported.

## Supported status

Validated scope is Java 17+, Boot 4.1.x, Framework 7.0.x, Spring Data
Commons/JPA 4.1.x, JUnit Platform, real synchronous repositories, and existing
public `Advised` JDK products with unambiguous metadata. Mock, class-based,
non-`Advised`, late-created, reactive, async, Boot 3.x, other stores, SQL/table,
and repository-aware selection support are absent.

All implementation, metadata, eligibility, audit, metrics and diagnostic types
are package-private. The only public adapter types are the Spring discovery
initializer and the one-property constants holder. The JAR embeds no Spring,
Spring Data, or `stp-runtime` classes and has no publishing configuration.
