<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Spring Data end-to-end fixture results

## Scope and fixture

This spike used no PetClinic code. The disposable
`stp-spring-data-e2e-fixture` module combines Spring Boot 4.1.0, Spring Data JPA,
H2, the real STP agent, JUnit adapter, runtime, and explicit Spring Data adapter
JAR. The fixture contains `SampleService`, `SampleRepository`, `SampleEntity`, a
custom repository fragment, cache configuration, and four ordered JUnit tests.

Application code is under `example.springdatae2e.app`; tests are outside the
agent include. The exact agent configuration was:

```text
instrumentation=on
includes=example.springdatae2e.app.
runId=e2e-run
-Dstp.spring-data.enabled=true
```

The adapter is an ordinary test-runtime dependency. It is not bundled into the
agent JAR.

## Commands

```text
GRADLE_USER_HOME=/tmp/stp-gradle-home ./gradlew \
  :stp-spring-data-e2e-fixture:validateE2eFixture --rerun-tasks

GRADLE_USER_HOME=/tmp/stp-gradle-home ./gradlew test --rerun-tasks
git diff --check
```

`validateE2eFixture` runs five fresh test JVMs:

1. STP only;
2. repeated STP only;
3. adapter disabled;
4. JaCoCo 0.8.15 before STP;
5. STP before JaCoCo 0.8.15.

Gradle's automatically attached JaCoCo agent is disabled only for these fixture
tasks so the requested explicit agent orders contain exactly one JaCoCo agent.

## Integration finding and shared-runtime proof

The first enabled run exposed a real integration defect: eligibility processed
the `RepositoryFactoryBeanSupport` object after factory metadata had become
available, classified that factory object as non-`Advised`, and then rejected
the actual product proxy as a duplicate. Eligibility now explicitly leaves the
factory object to the metadata collector and classifies only the final product.

After that narrow correction, one agent-installed `RuntimeContextService` is
resolved by the service-loaded JUnit listener, ASM hooks, and the audited Spring
Data caller advice. No fixture aggregator or fallback runtime exists. The same
Platform unique-ID bucket contains both:

```text
SpringDataRuntimeE2eTest#cachedSuccessfulGraph
→ SampleService.cachedLookupTwice()                         [ASM_METHOD_ENTRY]
→ SampleRepository.findCachedByName(String) count=2        [SPRING_DATA]
→ domainType SampleEntity
```

The runtime output remains schema `spike-2`. Method IDs in attributed method
facts resolve through the agent method catalog, and the agent reported no
errors or double instrumentation.

## Cache and terminal outcomes

`cachedSuccessfulGraph` calls the exposed repository twice. The outer STP
advisor reports one deduplicated fact with `count=2`; the custom fragment's
underlying execution counter is exactly `1`, proving that the second call was
served by Spring Cache.

`failedRepositoryPreservesThrowable` produces one terminal `FAILED` event for
`SampleRepository.failExactly()V`. The test receives the exact fixture throwable
object. No `STARTED` event is present. Successful and failed facts remain
separate.

The context seed invokes `save(Object)` before any leaf test. Its repository
event and the seed ASM method remain globally unattributed with
`NO_ACTIVE_TEST`; neither enters the first test.

## Attribution and contamination

The two sequential sentinels are disjoint:

```text
sequentialAlpha → SampleService.alpha → SampleRepository.findAlpha
sequentialBeta  → SampleService.beta  → SampleRepository.findBeta
```

No `findBeta` fact occurs under Alpha, no `findAlpha` fact occurs under Beta,
and no test contains a `LATE_EVENT`. All four enabled JVM modes produce the same
normalized per-test graph. Adapter-disabled mode preserves the identical ASM
method graph and contains no repository facts.

## Determinism and golden output

The normalized STP-only and repeated-STP outputs are identical. Both JaCoCo
orders normalize to the same graph as well. The reviewed fixture is:

```text
stp-spring-data-e2e-fixture/golden/normalized-runtime.json
```

The validator checks schema version, exact method/repository facts, cache count,
startup attribution, late events, sequential contamination, disabled behavior,
agent errors, duplicate instrumentation, and JaCoCo files.

## JaCoCo coexistence

Both explicit orders passed all four tests:

```text
-javaagent:jacoco-0.8.15 ... -javaagent:stp-agent ...
-javaagent:stp-agent ...     -javaagent:jacoco-0.8.15 ...
```

The exec files were approximately 418 KiB and 416 KiB. STP outputs were also
created, expected method and repository facts remained visible, and there was
no linkage, verification, Mockito, Byte Buddy, or duplicate-hook failure.

## Exploratory measurements

| Mode | JUnit suite duration | Classes transformed | Methods instrumented | Raw method hits | Repository callbacks | Recording time | JSON size |
|---|---:|---:|---:|---:|---:|---:|---:|
| STP only | 0.533 s | 5 | 12 | 9 | 6 | 2,196,083 ns | 10,758 B |
| STP repeat | 0.543 s | 5 | 12 | 9 | 6 | 2,347,791 ns | 10,760 B |
| JaCoCo before STP | 0.682 s | 5 | 12 | 9 | 6 | 2,536,500 ns | 10,766 B |
| STP before JaCoCo | 0.662 s | 5 | 12 | 9 | 6 | 2,918,750 ns | 10,767 B |
| Adapter disabled | 0.566 s | 5 | 12 | 9 | 0 | n/a | 9,102 B |

The six enabled callbacks are five attributed test invocations plus the global
startup `save`. The complete five-JVM build and normalization task took 19 s on
this environment. These numbers are fixture evidence only, not a production
performance claim.

## Normalized excerpt

```json
{
  "method": "example.springdatae2e.app.SampleService#cachedLookupTwice()V",
  "evidenceSource": "ASM_METHOD_ENTRY",
  "count": 1
}
{
  "repositoryInterface": "example.springdatae2e.app.SampleRepository",
  "methodName": "findCachedByName",
  "jvmDescriptor": "(Ljava/lang/String;)Ljava/util/List;",
  "domainType": "example.springdatae2e.app.SampleEntity",
  "outcome": "SUCCEEDED",
  "evidenceSource": "SPRING_DATA",
  "count": 2
}
```

## Recommendation

**Proceed to the pinned PetClinic acceptance task.** The internal fixture proves
one shared runtime, caller-level cache semantics, terminal failure behavior,
same-thread attribution, deterministic output, disabled-mode isolation, and
both JaCoCo orders. PetClinic remains necessary to confirm the same eligibility
and advisor structure on its real Owner and Vet repositories.
