<!-- SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors -->
<!-- SPDX-License-Identifier: Apache-2.0 -->

# TASK 19 execution-oracle design

## Decision

TASK 19 uses a native JVMTI `MethodEntry` agent. It observes entry events without modifying Spring bytecode, using JaCoCo probes, or calling the ASM runtime. Each event contains a sequence, native monotonic and wall-clock timestamps, native thread id, Java thread name, declaring-class signature, method name, and JVM descriptor. Filtering accepts `Lorg/springframework/` for Spring runs and the fixture prefix for oracle acceptance.

The independent JUnit side has two components. A Platform `TestExecutionListener` records the selected leaf ownership interval. A Jupiter `InvocationInterceptor` records exact invocations of user `beforeAll`, `beforeEach`, test, `afterEach`, and `afterAll` methods. Neither reads ASM bucket state. The common wall clock joins native entries to Java boundaries; the platform and native monotonic clocks have different epochs on macOS and are deliberately not compared.

## Candidates

| Candidate | Strength | Limitation | Decision |
| --- | --- | --- | --- |
| JVMTI `MethodEntry` | Exact entry event; descriptors and threads; no bytecode modification | Native build, high event overhead, ownership still needs lifecycle evidence | Chosen |
| Independent bytecode tracer | Exact targeted entry and portable Java runtime | Conceptually shares bytecode instrumentation and can interact with the two coverage transformers | Fallback, not used |
| Spring AOP | Useful proxy-boundary sanity check | Misses non-beans, private/static methods, constructors, self-invocation, non-proxied calls, and Spring internals | Rejected as primary |
| JFR sampling | Low implementation effort | Absence from a sample cannot prove non-execution | Rejected as primary |

## Acceptance

`oracle-fixture-acceptance.json` proves 18 expected descriptor-exact entries without missing or duplicate logical entries. The fixtures cover public/private/static/self calls, overloads, bridge dispatch, lambda target, constructor, static initializer, exception flow, reflection, raw thread, executor, `CompletableFuture`, and virtual thread; thread-name checks pass.

`lifecycle-fixture-acceptance.json` proves ordered phase boundaries and the policy: `beforeEach`, test, and `afterEach` are test-owned; `beforeAll` and `afterAll` are outside; delayed work on a different thread after leaf end is `LATE`.

## Known blind spots

- Cross-thread work inside the leaf interval has execution truth but no independent submission context. It is `AMBIGUOUS`, not scored as owned.
- Wall-clock joining has millisecond lifecycle resolution. No scored observation in this experiment depended on choosing between two events inside a boundary millisecond, but this is weaker than a shared native clock bridge.
- The checked-in native build script targets macOS. Porting requires the platform JVMTI include directory and shared-library flags.
- JVMTI materially perturbed one of four observer-effect controls by changing a two-method cache path. Results therefore describe each same-run execution; historical or no-oracle paths are never substituted as truth.
- Dynamic hidden-class names contain VM addresses. Global joining filters to class names present in Spring production output, avoiding unstable hidden-class identity.

The acceptance gate passed before Spring metrics were computed.
