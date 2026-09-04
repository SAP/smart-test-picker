<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP ASM coverage POC — round 2

## Decision

**ASM NEEDS MORE COVERAGE WORK**

The generic transformer is complete and deterministic for the synchronous PetClinic scope tested here. All methods reported by JaCoCo but absent from an ASM test bucket were independently observed by ASM before the JUnit leaf became active; none was an instrumentation false negative. The approach remains promising, but worker-thread attribution and application classes already loaded when a transformer is installed remain generic, RTS-relevant gaps outside this scope. Those gaps need focused work before a broader mapping experiment.

This round did not change STP selection, the JaCoCo collector, or any framework adapter.

## Completeness audit

The classification question is whether omitting the executable body can remove an RTS dependency. “MUST COVER” describes the required collector behavior, not a claim that attribution is already solved in every environment.

| Category | Classification | Round-2 result and boundary |
|---|---|---|
| Ordinary concrete application methods | MUST COVER | Covered at entry. Abstract and native declarations have no instrumentable application body and may be ignored. |
| Constructors `<init>` | MUST COVER | Newly covered. A hook before the first bytecode instruction is verifier-safe in tested Java 17 classes and observes entry without using uninitialized `this`. |
| Static initializers `<clinit>` | MUST COVER | Newly covered. Initialization can contain the only executed changed code. |
| Interface default methods | MUST COVER | Newly covered. |
| Private interface methods | MUST COVER | Newly covered. |
| Static concrete interface methods | MUST COVER | Covered by the same interface-body handling. |
| Bridge methods | SHOULD COVER | Retained as deterministic raw facts. A bridge can be an executed dispatch body, though usually the bridged target is the useful source dependency. No normalization is performed in the collector. |
| Synthetic methods | SHOULD COVER | Retained as raw facts because blanket filtering can hide lambda/compiler bodies. Consumers may later classify noise, but the collector must not silently discard it. |
| Lambda bodies in application classes | MUST COVER | Named synthetic `lambda$...` bodies are covered and retain exact descriptors. Hidden/runtime lambda implementation classes are not treated as stable application identities. |
| Nested classes | MUST COVER | Covered when the binary name is included; `$` names remain stable within a revision. |
| Anonymous classes | MUST COVER | Covered when application-compiled. Ordinal binary names are stable for the pinned revision, not across source edits. |
| Records | MUST COVER | Explicit and compiler-generated record bodies are covered, including constructor/accessor/`equals`/`hashCode`/`toString`. |
| Other compiler-generated application methods | SHOULD COVER | Covered if they have code and reside in an included application class. Raw symbolic identity is retained. |
| Reflection-invoked application methods | MUST COVER | Covered because entry instrumentation is independent of call mechanism. Field-only reflective access has no method body to observe. |
| Generated application classes | SHOULD COVER | Stable application build output is covered. Classes matching known runtime proxy/enhancer patterns remain intentionally ignored because their names/bodies are framework implementation details. |
| Framework-generated proxy/enhancer methods | MAY IGNORE | Ignored by existing `$$`, Mockito, Byte Buddy, and Hibernate name filters. They are not stable application change identities. |
| Application methods loaded before transformer installation | MUST COVER | `premain` installs early enough in the tested launch. Dynamic attach/retransformation is unsupported and must be declared incomplete rather than guessed. |
| Custom-classloader application methods | MUST COVER | Transformation and a defining-loader fixture pass. Duplicate binary definitions in different loaders currently collapse to one symbolic identity; acceptable only when they denote the same application revision. |
| Worker-thread application execution | MUST COVER | Execution is observed globally, but the current thread-local JUnit scope records it as `NO_ACTIVE_TEST`. Per-test attribution is unsupported and remains the principal generic gap. |
| Annotation types | MAY IGNORE | Annotation methods have no Code attribute. |
| STP agent/runtime/hooks and excluded framework/test classes | MUST NOT COVER | Existing include/exclude and test-output filters remain in force, preventing recursive instrumentation. |

No safe attempt was made to instrument runtime-hidden lambda classes, retransformation targets, or framework proxies. Their identity and lifecycle require evidence beyond a name heuristic.

## Implementation

The minimum transformer change was to stop rejecting interfaces wholesale and stop skipping `<init>`/`<clinit>`. Abstract/native methods and JaCoCo's helper remain skipped. The idempotence marker uses the JVM-required `public static final` shape on interfaces and remains private on classes. Straight-line entry injection still uses `COMPUTE_MAXS`; no control-flow or exception behavior was changed.

The hook records a new cumulative `runtimeRecordingNanos` metric around catalog resolution, hit aggregation, and test attribution. Collector failures remain contained by `RuntimeHooks`; transformation failures still return the original bytes.

## Canonical identity

The persisted comparison truth is exactly:

```text
binaryClassName#methodName(JVM descriptor)
```

Examples in the retained output include constructors and overloaded descriptors. Nested, anonymous, record, bridge, synthetic, and lambda names are persisted verbatim. FNV-1a 64 remains an internal lookup key only: every catalog and event entry contains the full symbolic key, and collisions retain all keys and fail closed for attribution.

## PetClinic experiment

Revision: `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`. Toolchain: OpenJDK 17.0.19, Maven Wrapper from the pinned checkout, JaCoCo 0.8.13, one Surefire JVM per fresh command. The tests were the three already selected by the ASM/Spring Data spike:

- `OwnerControllerTests#processCreationFormSuccess`
- `ClinicServiceTests#shouldInsertOwner`
- `PetClinicIntegrationTests#findAll`

For comparison, each test ran alone with JaCoCo before ASM in the same execution. JaCoCo XML was read directly so descriptors were retained even though the production STP map currently collapses them. For determinism, the three tests ran together in three fresh alphabetical runs and once in reverse-alphabetical order. No Spring Data adapter was installed.

Reproduction:

```bash
./gradlew :stp-agent:test :stp-runtime:test :stp-junit-adapter:test
PETCLINIC_DIR=/path/to/pinned/petclinic \
STP_AGENT_JAR=$PWD/stp-agent/build/libs/stp-agent-experimental.jar \
JACOCO_AGENT_JAR=/path/to/org.jacoco.agent-runtime.jar \
ruby stp-petclinic-spike/round2/run_round2.rb
ruby stp-petclinic-spike/round2/compare_round2.rb
```

The normalized, descriptor-preserving result is `stp-petclinic-spike/round2/normalized-round2.json`. Raw logs are intentionally ignored and regenerated by the runner.

## ASM versus JaCoCo

| Test | Both | ASM-only | JaCoCo-only | Jaccard |
|---|---:|---:|---:|---:|
| Owner controller | 33 | 0 | 6 | 0.846154 |
| Clinic service | 9 | 0 | 7 | 0.562500 |
| Integration `findAll` | 5 | 0 | 18 | 0.217391 |

Global union: 35 common methods, 0 ASM-only methods, and 14 distinct JaCoCo-only methods. All method-edge and class lists are retained without elision in the normalized JSON.

The lower Jaccard values do not represent ASM instrumentation loss. For every JaCoCo-only method, its exact descriptor also appears in the ASM run's global `methodHits`, but not in its active-test bucket. The sets are exact: controller 33 attributed plus 6 global-only; service 9 plus 7; integration 5 plus 18. JaCoCo's process-wide probes were enabled during application-context bootstrap and dumped after the leaf, so the current interval assigns bootstrap work to that test. ASM begins attribution at the JUnit Platform leaf and keeps earlier work explicitly unattributed.

The 14 distinct differences comprise constructors for the application/configuration/controllers/entities and these bootstrap methods:

- `WebConfiguration#addInterceptors(InterceptorRegistry)V`
- `WebConfiguration#localeChangeInterceptor()LocaleChangeInterceptor`
- `WebConfiguration#localeResolver()LocaleResolver`
- `CacheConfiguration#petclinicCacheConfigurationCustomizer()JCacheManagerCustomizer`

Answers for every JaCoCo-only edge are therefore the same and are machine-checkable in the output:

1. It really executed: both collectors observed the exact symbolic identity.
2. It is an application/bootstrap dependency, but not a dependency of the JUnit leaf under ASM's explicit lifecycle boundary. Conservatively assigning it to the JaCoCo interval is baseline behavior, not evidence that the leaf invoked it.
3. ASM did not miss it; ASM quarantined it outside the active test.
4. Generic ASM already safely covers it; changing attribution to mimic JaCoCo would be a lifecycle-policy change and was out of scope.
5. No transformer fix is appropriate.

There were no ASM-only methods and no true ASM false negatives in this tested scope. There was consequently no overloaded-identity loss in the descriptor-preserving XML comparison; the existing persisted JaCoCo STP map would collapse overloads because it stores only `class#name`.

## Determinism

All three fixed-order fresh-JVM maps had identical exact per-test sets. The reverse-order run was also identical for every test. There were zero missing/extra edges and zero global-union differences. This answers only the narrow synchronous behavior tested; it does not establish worker-thread or parallel-test determinism.

## Safety validation

The focused suite passes with JVM verification enabled and covers ordinary/static/private methods, overloads, constructors, static initialization, default/private interface methods, lambda bodies, compiler-generated record methods, synchronized methods, try/catch, unchanged throwable propagation, custom definition, marker idempotence, filtering, hook failure containment, and deterministic full-key output. The existing isolated process test exercises the shaded agent with `-Xverify:all`.

Existing coexistence runs cover JaCoCo-before-STP in this matrix. The retained pre-Round-2 suite continues to cover STP-before-JaCoCo; the transformer edit does not inspect or rewrite JaCoCo semantics. Metrics show zero transformation errors, zero duplicate-marker skips, and zero method-ID collisions.

## Performance sanity check

For one representative fixed run: 16,987 classes considered, 29 included, 22 transformed, 118 methods considered, 113 instrumented, 5 skipped, 0 transformation failures, 141 raw hits, and 49 unique method facts. Transformer time was 26.335 ms and runtime recording time was 3.499 ms. The ASM JSON was 50,943 bytes; normalized comparison output was 29,423 bytes.

Three fresh fixed-order ASM runs took 4.258, 4.301, and 4.258 seconds (median 4.259). Three no-agent runs took 4.226, 4.179, and 4.259 seconds (median 4.226), an approximate median overhead of 0.78%. This is a sanity check, not a benchmark; JVM and Spring startup dominate.

## Remaining work before broader mapping

The next narrow coverage work should prove a generic, bounded test-context propagation mechanism for executor worker threads and explicitly detect/declare application classes loaded before installation. Until then, async suites must be treated as incomplete. Broader single-agent and later multi-agent mapping experiments should wait for that result; this document makes no recommendation to replace JaCoCo globally.
