# TASK 41 — build-tool execution-universe audit

## Baseline

The repository guard was run on branch `research/asm-codex`. The starting HEAD was
`2741da42712da611a5324319d076ec7ee4bd0135` (`Align published logical inventory with JUnit
discovery`), exactly the expected TASK 40 commit, and the working tree was clean. No unexpected
TASK 40 changes existed.

A new disposable local clone at `/private/tmp/task41-petclinic.idkfIm/repo` was detached at Spring
PetClinic revision `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`. The clone was clean before the
audit. The first test invocation was exactly:

```text
./gradlew test
```

It used no STP plugin, agent, init script, coverage instrumentation, extra JVM flag, extra environment
variable, user-supplied Spring profile, database setup, or Docker setup. The build succeeded. Gradle's
JUnit XML contained 20 test suites, 73 test cases, 69 passed cases, four skipped cases, zero failures,
and zero errors.

## Project test configuration

PetClinic has one Java test source set. Its only standard-test customization is
`tasks.named('test') { useJUnitPlatform() }`: there is no `TestFilter`, file include/exclude, JUnit tag
filter, engine filter, separate integration-test source set, or database-specific Gradle `Test` task.
`./gradlew tasks --all` exposes `test` as the normal JVM test target and `nativeTest` as a separate
native-image target. It exposes no `integrationTest`, `mysqlTest`, `postgresTest`, `testIntegration`,
or equivalent task. `bootTestRun` runs the application with the test runtime classpath; it is not a
test-suite target.

Both database test classes are compiled from `src/test/java` and match the normal Gradle `test`
candidate set. Each class activates its own database profile with `@ActiveProfiles`, so the vanilla
command did not need a caller-supplied profile:

- `MySqlIntegrationTests` activates `mysql`, declares a Testcontainers MySQL container, and uses
  `@Testcontainers(disabledWithoutDocker = true)`. With Docker unavailable, the Testcontainers JUnit
  execution condition disables the class and Gradle reports both methods as skipped.
- `PostgresIntegrationTests` activates `postgres` and configures Spring Boot Docker Compose startup.
  Its `@BeforeAll` calls `assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not
  available")`. With Docker unavailable, the failed assumption aborts the container and Gradle reports
  both methods as skipped.

The profiles and external databases are needed for successful execution, but their configuration is
owned by the tests themselves. Their absence changes execution outcome, not membership in `test`.

## Four disputed identities

| Identity | Raw JUnit discovery | Normal `test` member | Method body executed | Skipped | Condition-disabled | Reason | Special profile required | External DB required |
|---|---:|---:|---:|---:|---:|---|---:|---:|
| `org.springframework.samples.petclinic.MySqlIntegrationTests#findAll` | YES | YES | NO | YES | YES | Testcontainers disables the class when Docker is unavailable | YES, automatically activated by the class | YES, automatically provisioned when Docker is available |
| `org.springframework.samples.petclinic.MySqlIntegrationTests#ownerDetails` | YES | YES | NO | YES | YES | Testcontainers disables the class when Docker is unavailable | YES, automatically activated by the class | YES, automatically provisioned when Docker is available |
| `org.springframework.samples.petclinic.PostgresIntegrationTests#findAll` | YES | YES | NO | YES | NO | The class-level `@BeforeAll` Docker-availability assumption is false | YES, automatically activated by the class | YES, configured through Docker Compose by the class |
| `org.springframework.samples.petclinic.PostgresIntegrationTests#ownerDetails` | YES | YES | NO | YES | NO | The class-level `@BeforeAll` Docker-availability assumption is false | YES, automatically activated by the class | YES, configured through Docker Compose by the class |

None is unconditionally `@Disabled`. “Condition-disabled” above distinguishes the MySQL JUnit
execution condition from the PostgreSQL failed assumption. Gradle represents both outcomes as skipped
test cases in XML.

## Exact population reconciliation

The retained TASK 40 head fixture is the output of raw JUnit Platform discovery against this pinned
revision. A fresh vanilla Gradle run independently supplied the configured-target/runtime population.
Canonical `TestIdentity` reconciliation, removing only the XML method-name `()` presentation suffix,
produced:

| Population | Count |
|---|---:|
| A. Raw JUnit discovery | 73 |
| B. Normal Gradle `test` target | 73 |
| C. Runtime reported by Gradle XML | 73 |
| C1. Passed/executed test methods | 69 |
| C2. Skipped test methods | 4 |
| Failures/errors | 0 |

The exact set differences are:

```text
raw discovery - Gradle test target = empty
Gradle test target - raw discovery = empty
Gradle test target - runtime reported = empty
runtime reported - Gradle test target = empty
Gradle test target - successfully executed = the four DB identities above
```

Thus `VANILLA_GRADLE_TEST_EXECUTION_UNIVERSE` contains all 73 exact identities reported by the normal
target, including its four conditionally skipped members. It is not the 69-case successful-execution
subset.

## Membership boundary and Gradle/JUnit boundary

For this contract:

- **discovered** means present in a JUnit `TestPlan` for the configured target inputs;
- **included in target** means retained after the Gradle `Test` task's candidate-class selection,
  `TestFilter`, include/exclude patterns, test-framework configuration, engines, tags, and other JUnit
  Platform discovery filters are applied;
- **executed** means JUnit started and completed the test method rather than skipping or disabling it;
- **skipped** means the target reported the identity but did not execute its method body because an
  execution condition, assumption, or equivalent runtime decision prevented it;
- **disabled** is a particular JUnit skip decision, whether annotation- or condition-driven; it does
  not by itself remove target membership;
- **excluded** means Gradle or the configured test framework removed the identity before execution;
  an excluded identity does not belong to that target's inventory.

The architectural authority is the configured build-tool execution target. The narrow Gradle boundary
starts with `Test.getTestClassesDirs()` and `Test.getClasspath()`, then must preserve the task's
candidate include/exclude patterns, `TestFilter`, selected test framework, JUnit Platform engines and
tags, and framework configuration. The resulting JUnit `TestPlan`, together with execution skip events,
represents target membership without relying on successful XML results. Merely running generic
classpath-root discovery is not sufficient for a project whose `Test` task configures additional
filters, although it is exactly equivalent for this pinned PetClinic `test` task because PetClinic
configures none.

Skipped/disabled events classify target-member outcomes; they are not exclusion filters. No annotation,
PetClinic class name, database type, or infrastructure-availability heuristic may define inventory.
If project-side `test` configuration later makes another identity a target member, a target-driven
inventory must include it automatically.

## Contract decision and TASK 40 verdict

The chosen authority is **BUILD-TOOL EXECUTION TARGET**. For pinned PetClinic, raw JUnit discovery and
that target happen to be identical at 73 identities. The four database methods belong to normal
`./gradlew test`; they are not members of another task and do not require caller-side activation.
Their environment-conditioned skip leaves 69 method bodies executed.

Therefore TASK 40's 73-logical/69-executable distinction is architecturally necessary for this
baseline and is accepted. `HeadTestInventory` for `test` must contain the 73-member configured target
plan. The published map must retain the same 73-member logical inventory, partitioned into 69 mapped
executions and four intentionally non-executable outcomes for this Docker-unavailable mapping run.
That produces zero false new tests and permits `NONE` when no relevant change exists.

No production, selector, schema, fixture, Maven, 5d, or 5e change was required. In particular, this
audit introduces no `@Disabled` handling, database handling, or PetClinic-name special case. Existing
exact `TestIdentity`, new-test safety, unmapped `ALWAYS_SELECT`, setup-scope behavior, and `FULL_SUITE`
fail-open behavior are unchanged.
