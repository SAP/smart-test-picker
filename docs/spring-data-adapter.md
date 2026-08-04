# Experimental Spring Data repository observation

The optional `stp-spring-data-adapter` enriches the experimental `spike-2`
runtime output with caller-level Spring Data repository facts. It observes the
repository interface, canonical bean name, invoked method with its erased JVM
descriptor, domain type, terminal outcome, and repeated-call count. It records
neither arguments nor return values.

```text
JUnit test
→ ASM methods
→ Spring Data caller advisor
→ shared runtime
→ deterministic JSON
```

This feature is experimental, unpublished, and not connected to Smart Test
Picker's regression-test selection.

## Supported contract

The first validated scope is exactly:

- Java 17 or later;
- Spring Boot 4.1.x;
- Spring Framework 7.0.x;
- Spring Data Commons/JPA 4.1.x;
- JUnit Platform leaf tests;
- synchronous, same-thread calls;
- real Spring Data repositories whose final product is already a public Spring
  `Advised` JDK proxy and whose public repository metadata is unambiguous.

The adapter does not claim support for Spring Boot 3.x, class-based or
non-`Advised` repository products, repositories created after
`afterSingletonsInstantiated`, mocked repositories, reactive repositories,
async completion, MongoDB, Redis or other stores, SQL/table observation, or
selection based on repository events. Similar APIs in those configurations are
not evidence of compatibility.

## Setup

The test JVM needs these separate artifacts:

- `stp-agent-experimental.jar` (it contains the experimental runtime and JUnit
  listener used by the agent);
- `stp-spring-data-adapter.jar` explicitly on the test runtime classpath.

The adapter is deliberately not bundled into the agent JAR. Spring discovers
its initializer through `META-INF/spring.factories`; no application source
change is required.

Gradle test setup (using local project artifacts) can follow this shape:

```groovy
dependencies {
    testRuntimeOnly files("$rootDir/stp-spring-data-adapter/build/libs/stp-spring-data-adapter.jar")
}

tasks.withType(Test).configureEach {
    dependsOn ':stp-agent:agentJar', ':stp-spring-data-adapter:jar'
    jvmArgs "-javaagent:${rootDir}/stp-agent/build/libs/stp-agent-experimental.jar=" +
            "output=${buildDir}/stp-runtime.json;" +
            "includes=com.example.;runId=local;debug=false;instrumentation=on"
    systemProperty 'stp.spring-data.enabled', 'true'
}
```

For Maven Surefire, build the local adapter JAR first, add that exact file as an
additional test classpath element, and pass the agent/property explicitly:

```xml
<configuration>
  <additionalClasspathElements>
    <additionalClasspathElement>/absolute/path/stp-spring-data-adapter.jar</additionalClasspathElement>
  </additionalClasspathElements>
  <argLine>-javaagent:/absolute/path/stp-agent-experimental.jar=output=target/stp-runtime.json;includes=com.example.;runId=local;debug=false;instrumentation=on</argLine>
  <systemPropertyVariables>
    <stp.spring-data.enabled>true</stp.spring-data.enabled>
  </systemPropertyVariables>
</configuration>
```

The adapter is not published. Do not add it to a production runtime classpath.

## Event and cache semantics

One terminal event is attempted per caller invocation. Equal successful calls
become one deterministic fact with an incremented `count`; failures remain a
separate fact. Inherited CRUD descriptors stay erased.

```json
{
  "repositoryKind": "SPRING_DATA_PROXY",
  "repositoryInterface": "org.example.OwnerRepository",
  "beanName": "ownerRepository",
  "methodName": "save",
  "jvmDescriptor": "(Ljava/lang/Object;)Ljava/lang/Object;",
  "domainType": "org.example.Owner",
  "outcome": "SUCCEEDED",
  "evidence": { "source": "SPRING_DATA", "certainty": "OBSERVED" },
  "count": 1
}
```

The advisor is inserted before `CacheInterceptor`, so `count` means caller
invocations. Two calls served by one cached repository execution are therefore
reported as `count=2`; this is intentional and is not a database-execution
count.

## Lifecycle, attribution and diagnostics

The agent installs the only `RuntimeContextService`. The JUnit listener, ASM
hook, and Spring Data advice resolve that shared service; the adapter never
creates a fallback. Same-thread calls inside an active leaf test are attributed
to it. Executor, reactive, server-thread, and other async work is not
propagated.

Activation is disabled by default. The only setting is
`-Dstp.spring-data.enabled=true`; remove it or set it to `false` to disable the
adapter. Any other value fails clearly. Missing runtime or missing Spring Data
disables safely and does not construct substitutes.

Actionable bounded diagnostics cover missing/ambiguous factory metadata,
non-`Advised` or unsupported proxies, interface/context mismatch, existing STP
markers, duplicate identities, repositories created after the insertion phase,
audit failures, and recording failures. Samples are capped and deterministic;
they retain no arguments, results, bean graphs, stack traces, or generated proxy
class as canonical identity.

For a one-command maintained example run:

```bash
./gradlew :stp-spring-data-e2e-fixture:validateE2eFixture
```
