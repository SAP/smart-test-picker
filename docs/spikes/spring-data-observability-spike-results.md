# Spring Data Observability Spike: experiments A–G

> Archived research. The disposable comparison fixture and obsolete normalizer
> were removed during stabilization. Reviewed evidence now lives in
> `docs/spikes/archive/spring-data-observability/`; this is not the supported
> adapter contract.

## Scope

This report contains the first half of the Spring Data Observability Spike. It
uses a disposable fixture and public APIs only. It does not contain PetClinic,
Smart Test Picker runtime/agent/JUnit integration, startup/shutdown experiments,
SQL/Hibernate/JDBC observation, or an adapter design.

Tested versions:

```text
Spring Boot 4.1.0
Spring Data Commons/JPA 4.1.0
Spring Framework 7.0.8
Mockito 5.23.0
H2 managed by Spring Boot 4.1.0
```

## Fixture structure

The isolated, non-published `stp-spring-data-observability-spike` module has no
dependency on any STP module. Its fixture contains:

```text
FixtureApplication                 Spring Boot/JPA/cache configuration
SampleEntity                       minimal JPA entity
SampleRepository                   JpaRepository plus query, overload and default method
SampleRepositoryFragment           custom, failing and cacheable methods
SampleRepositoryImpl               fragment implementation and execution counter
MockRepositoryConsumer             application-side mock caller
RepositoryDiagnosticsConfiguration public-hook registration and diagnostics
DiagnosticRecorder                 restricted raw record writer
```

Four independent real-repository contexts measured listener-only,
repository-proxy-advice-only, external-wrapper-only, and combined listener/advice
ordering. Two `@MockitoBean` contexts measured public Mockito APIs and attempted
external bean wrapping.

## Commands

```bash
GRADLE_USER_HOME=/tmp/stp-gradle-home ./gradlew :stp-spring-data-observability-spike:test --rerun-tasks
Historical command (removed after evidence was archived):
`./gradlew :stp-spring-data-observability-spike:normalizeDiagnostics`.
```

The normalized evidence is under
`stp-spring-data-observability-spike/normalized-diagnostics`. Records contain
only the fields allowed by the spike contract. They contain no arguments,
returns, entity values, SQL, properties, or stack traces.

## Completed capability matrix

| Hook/API | Invocation hook? | Real repository methods observed | Default method | Before/after | Interface | Exact `Method`/descriptor | Domain type | Outcome | Cache call 1/2 | `@MockitoBean` | Behavior finding |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `RepositoryMethodInvocationListener` | Yes | CRUD, derived query and fragment methods | No | After only | Yes | Yes | Not supplied by invocation callback | `SUCCESS`/`ERROR` | Both | No | No changed result; callback position varies with transaction advice |
| AOP advice added by `RepositoryProxyPostProcessor` | Yes | All exercised categories | Yes | Before and after | From `RepositoryInformation` | Yes | From `RepositoryInformation` | Derived around `proceed()` | Both | No; repository factory is replaced | No changed result; adds first advisor to existing proxy |
| `RepositoryProxyPostProcessor` itself | No | None; it is registration-time metadata | N/A | Registration only | Yes | No invocation | Yes | No | N/A | No | Exposed repository metadata and existing `ProxyFactory` |
| `RepositoryFactoryCustomizer` itself | No | None; it configures the factory | N/A | Registration only | Factory object type only in this experiment | No invocation | Not supplied at this registration point | No | N/A | No | Public registration route worked for the real factory bean |
| External `BeanPostProcessor`/`ProxyFactory` wrapper | Yes for real repository product | All exercised categories, plus an unwanted infrastructure `FactoryBean.getObjectType()` call | Yes | Before and after | Supplied by wrapper configuration | Yes | Had to be supplied separately | Derived around `proceed()` | Both | No callbacks; Spring's singleton mock override bypassed this product post-processor | Functional calls passed, but exposed proxy identity/advisor view changed and infrastructure noise appeared |
| Mockito `MockSettings.invocationListeners` | Yes, only when configured during mock creation | Calls on the explicitly created mock | N/A | After only | From mock creation metadata | Yes through public invocation object | Resolvable separately from repository metadata | Return/throw report | N/A | Cannot be retrofitted through public settings onto the existing `@MockitoBean` | Reported stubbing, application and verification calls alike |
| Mockito `MockCreationListener` | Creation only | No invocations | N/A | Creation only | Mock type | No method | No repository domain metadata | No | N/A | Listener installed after context creation cannot observe the already-created bean | Custom mock creation was observed once |
| Mockito `MockingDetails.getInvocations()` | Snapshot, not callback | Actual stored invocations | N/A | After-the-fact | Mock settings | Exact methods are available | Not supplied | No return/failure outcome | N/A | Yes | Stubbing declaration was absent; verification did not add another invocation; reset cleared the snapshot |
| ASM on interfaces/proxies | Not tested by design | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | Explicitly outside this spike |

`RepositoryProxyPostProcessor` and `RepositoryFactoryCustomizer` were tested as
public registration/metadata APIs, not misreported as invocation hooks.

## Experiment A: method coverage

The fixture performed these logical calls once unless noted:

| Method category | Exact reflective declaration and descriptor | Listener callbacks | Repository advice callbacks | External wrapper callbacks |
| --- | --- | ---: | ---: | ---: |
| inherited `save` | `CrudRepository#save(Ljava/lang/Object;)Ljava/lang/Object;` | 1 after | 1 before + 1 after | 1 before + 1 after |
| inherited `findById` outside transaction | `CrudRepository#findById(Ljava/lang/Object;)Ljava/util/Optional;` | 1 after | 1 + 1 | 1 + 1 |
| inherited `findAll` | `ListCrudRepository#findAll()Ljava/util/List;` | 1 after | 1 + 1 | 1 + 1 |
| redeclared overload | `SampleRepository#findAll(Lorg/springframework/data/domain/Pageable;)Lorg/springframework/data/domain/Page;` | 1 after | 1 + 1 | 1 + 1 |
| derived query | `SampleRepository#findByName(Ljava/lang/String;)Ljava/util/List;` | 1 after | 1 + 1 | 1 + 1 |
| default interface method | `SampleRepository#defaultMethod()Ljava/lang/String;` | **0** | 1 + 1 | 1 + 1 |
| custom fragment | `SampleRepositoryFragment#customMethod()Ljava/lang/String;` | 1 after | 1 + 1 | 1 + 1 |
| cached fragment, called twice | `SampleRepositoryFragment#cachedMethod()Ljava/lang/String;` | 2 after | 2 + 2 | 2 + 2 |
| failing fragment | `SampleRepositoryFragment#failingCustomMethod()Ljava/lang/String;` | 1 after | 1 + 1 | 1 + 1 |
| inherited `delete` | `CrudRepository#delete(Ljava/lang/Object;)V` | 1 after | 1 + 1 | 1 + 1 |

`findById` was also called once inside an explicit transaction, producing one
additional listener callback and a before/after pair for each around hook.

The listener covered every tested database/query/fragment category except the
default interface method. The repository-boundary advice covered all tested
categories.

## Experiment B: success and failure

For `failingCustomMethod`, the combined callback order was:

```text
repository-proxy-advice BEFORE  RUNNING  transactionActive=false
RepositoryMethodInvocationListener AFTER ERROR transactionActive=true
repository-proxy-advice AFTER   ERROR    transactionActive=false
```

All hooks reported `FixtureFailure`. The caller received the exact same
exception object thrown by the fragment implementation. The transaction was
active at the listener and rolled back normally. Adding diagnostics changed no
test result or exception type/identity.

Successful methods similarly reported `SUCCESS`. The listener has no before
callback; the around hooks emitted one `RUNNING` before and one terminal record.

## Experiment C: cache boundary

Observed facts for two `cachedMethod()` caller invocations:

```text
caller invocations:                  2
fragment implementation executions: 1
listener callbacks:                  2 AFTER
repository advice callbacks:         2 BEFORE + 2 AFTER
external wrapper callbacks:          2 BEFORE + 2 AFTER
```

The combined trace was identical for invocation numbers 1 and 2:

```text
repository-proxy-advice BEFORE
RepositoryMethodInvocationListener AFTER
repository-proxy-advice AFTER
```

Therefore, in this fixture both public repository hooks observed the second
interface call even though the cache prevented a second fragment execution.
This result is for the tested placement of `@Cacheable` on the fragment method;
PetClinic's repository-interface cache placement remains untested because the
PetClinic confirmation is explicitly outside this half-spike.

## Experiment D: transaction boundary

The measured positions were:

| Invocation | Repository advice BEFORE/AFTER | Listener AFTER |
| --- | --- | --- |
| inherited CRUD outside explicit transaction (`save`, `findById`, `findAll`, `delete`) | false / false | true |
| derived `findByName` | false / false | false |
| non-transactional custom/cached fragment | false / false | false |
| `findById` inside explicit `TransactionTemplate` | true / true | true |
| failing fragment annotated `@Transactional` | false / false | true |

The repository advice was the first advisor and therefore outside Spring's
transaction interceptor. The listener ran within the transaction for methods
to which transaction advice applied. No transaction behavior was changed.

## Experiment E: method and domain metadata

The exact descriptors are listed in Experiment A. Inherited generic CRUD
methods remained erased; the fixture did not invent entity-specialized
descriptors. The overloads were distinct:

```text
ListCrudRepository#findAll()Ljava/util/List;
SampleRepository#findAll(Lorg/springframework/data/domain/Pageable;)Lorg/springframework/data/domain/Page;
```

The listener callback supplied `SampleRepository` as repository interface and
the exact reflective method, but did not supply domain type. The public
`RepositoryInformation` passed to `RepositoryProxyPostProcessor` supplied both:

```text
repositoryInterface = com.sap.oss.smarttestpicker.spike.springdata.SampleRepository
domainType = com.sap.oss.smarttestpicker.spike.springdata.SampleEntity
beanName = sampleRepository
```

Combining these metadata sources was not implemented as an adapter; this is
only a capability observation.

## Experiment F: proxy identity and advisor order

The unwrapped real repository was a JDK dynamic proxy. Its observed advisor
order began with:

```text
CrudMethodMetadataPopulatingMethodInterceptor
PersistenceExceptionTranslationInterceptor
TransactionInterceptor
DefaultMethodInvokingMethodInterceptor
QueryExecutorMethodInterceptor
ImplementationMethodExecutionInterceptor
PersistenceExceptionTranslationAdvisor
```

The `RepositoryProxyPostProcessor` diagnostic inserted its advisor at index
zero; the remaining order stayed the same. The exposed object remained the same
single JDK repository proxy shape.

The external bean wrapper changed the exposed object to a second JDK proxy with
one diagnostic advisor and retained the original repository proxy as its target.
All intended calls still passed, but the wrapper also observed an infrastructure
call to:

```text
FactoryBean#getObjectType()Ljava/lang/Class;
```

That extra call occurred while the failing invocation passed through exception
translation. Thus external wrapping changed proxy identity/advisor visibility
and introduced non-repository noise even though functional assertions passed.

## Experiment G: `@MockitoBean`

The actual Spring-created mock had:

```text
isMock = true
existing invocation listeners = 1
listener type = org.springframework.test.context.bean.override.mockito.MockReset$ResetInvocationListener
```

This is a public `MockCreationSettings` observation. The existing listener list
cannot be mutated through public Mockito settings after mock creation.

`MockingDetails.getInvocations()` produced:

```text
after stubbing declaration: 0
after application save:     1
after verification:         1
after reset:                0
```

It can provide an after-the-fact set of actual invocations, but it does not
provide the terminal return/failure outcome required by the proposed repository
event.

A separately created mock configured with the public Mockito invocation
listener emitted three callbacks for the same method shape:

```text
STUBBING     findByName(Ljava/lang/String;)Ljava/util/List;
APPLICATION  findByName(Ljava/lang/String;)Ljava/util/List;
VERIFICATION findByName(Ljava/lang/String;)Ljava/util/List;
```

The phase labels above came from the experiment driver; Mockito's invocation
report did not identify those semantic phases. Therefore the real-time public
listener cannot independently isolate only the application invocation.

The external repository `BeanPostProcessor` was enabled in a separate
`@MockitoBean` context. It emitted zero records because Spring's singleton bean
override did not pass the mock product through that post-processor. Ordinary
field injection, stubbing, application invocation, verification, and reset all
continued to pass, but no repository observation was obtained.

## Callback-order traces

### Successful implicit-transaction CRUD call

```text
repository advice BEFORE  transaction=false
listener AFTER SUCCESS    transaction=true
repository advice AFTER   transaction=false
```

### Successful derived query without repository transaction metadata

```text
repository advice BEFORE  transaction=false
listener AFTER SUCCESS    transaction=false
repository advice AFTER   transaction=false
```

### Explicit caller transaction

```text
repository advice BEFORE  transaction=true
listener AFTER SUCCESS    transaction=true
repository advice AFTER   transaction=true
```

### Failing transactional fragment

```text
repository advice BEFORE  transaction=false
listener AFTER ERROR      transaction=true
repository advice AFTER   transaction=false
```

### Cached calls 1 and 2

```text
repository advice BEFORE
listener AFTER SUCCESS
repository advice AFTER
```

The cached trace occurred twice; the implementation counter advanced once.

## Unsupported or unavailable information

- `RepositoryMethodInvocationListener` does not expose domain type or bean name
  in its invocation value.
- It does not report the tested default interface method.
- `RepositoryFactoryCustomizer` and `RepositoryProxyPostProcessor` do not
  observe calls by themselves.
- Public Mockito invocation reports do not label stubbing, application, or
  verification phases.
- Public settings do not retrofit a new invocation listener onto the existing
  `@MockitoBean`.
- `MockingDetails` snapshots do not provide terminal success/failure.
- The external bean post-processor did not receive the Spring singleton mock
  override and therefore observed no mock calls.
- PetClinic cache placement and its exact real repositories remain untested in
  this half-spike.
- Startup/shutdown attribution remains untested by scope.

## Behavior-change findings

- Listener and repository-proxy advice: all fixture results, cache counts,
  transaction results, and exception identity remained unchanged.
- Repository-proxy advice: added one advisor to the existing proxy but did not
  add another proxy layer.
- External wrapper: added a second proxy layer, changed the exposed advisor
  view, and recorded an unwanted infrastructure call. Functional assertions
  still passed.
- Mockito public listener on a custom mock: ordinary Mockito behavior passed,
  but the listener observed stubbing and verification as well as application
  use.
- External wrapping of `@MockitoBean`: did not occur; Mockito behavior remained
  unchanged but no evidence was collected.

## Recommendation

**Mock support is not reliable with the public mechanisms tested.** No tested
public mechanism provides real-time application-only invocation plus terminal
outcome for an existing `@MockitoBean` without controlling mock creation or
using internal APIs.

PetClinic confirmation is justified for real repositories only. The next spike
should run `RepositoryMethodInvocationListener` and the diagnostic
repository-proxy advice independently against
`ClinicServiceTests#shouldInsertOwner` and `PetClinicIntegrationTests#findAll`,
especially to measure PetClinic's actual cache placement and default-method
requirements. Do not implement an adapter yet, and do not claim mocked
`OwnerRepository.save` support. A separate mock feasibility decision is needed
only if mock coverage remains mandatory.
