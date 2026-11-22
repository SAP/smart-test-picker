<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP ASM mapping agent

`stp-agent` is the production foundation for collecting JVM method-entry observations with ASM. It provides deterministic descriptor-preserving method identities, collision detection, production filtering, supported asynchronous context propagation, integrity metrics, and a self-contained agent JAR.

Build it with:

```bash
./gradlew :stp-agent:agentJar
```

The resulting artifact is `stp-agent/build/libs/stp-agent.jar`. The agent bundles the internal runtime and JUnit lifecycle listener and relocates ASM.

Arguments are semicolon-separated:

```text
-javaagent:/path/stp-agent.jar=output=stp-agent-output.json;includes=com.example.;excludes=com.example.generated.;runId=run-1;debug=false
```

Method-entry instrumentation is enabled by default. `includes` is optional, but an empty set instruments nothing. `instrumentation=off` remains available only for bounded diagnostics and tests.

The agent is wired into the Gradle mapping backend and the prepared Maven ASM mapping path. It projects runtime observations into logical schema-2 or target-qualified executable schema-3 fragments according to the invocation contract. The shaded artifact includes `stp-runtime` and the JUnit adapter.

Maven preparation and Gradle schema-v3 mapping supply `productionSourcesFile` (one absolute production
directory/main-JAR path per line) and `requireInstrumentation=true`. The allowlist
is an additional code-source restriction, not a replacement for package filters.
Unknown code sources and test artifacts cannot pass it. The guard refuses to write
a fragment when `classesTransformed` is zero, retaining scope/count diagnostics.
Raw-agent callers must provide these options themselves; standalone Gradle schema 2 also enables
the zero-instrumentation guard but does not derive v3 production scope. See
[Maven scope validation](https://github.com/SAP/smart-test-picker/blob/2cf74c0466049cbf80fe1bf38ed794e113f9fbef/verification/maven-asm-08/REPORT.md).

Method-entry probes carry JVM-local dense ordinals, each resolving to exactly one
method identity. Diagnostic method IDs remain `fnv1a64-v1` hashes, including collision
reporting; they are not array indices. Valid repeat hits are deduplicated per resolved
test/container owner before event allocation and synchronized aggregation. Closed or
unsupported owners still produce the existing diagnostics rather than using that cache.

`rawMethodHits` remains an occurrence count, not a first-hit count. The diagnostic
`injectedProbeId` distinguishes the ordinal carried by bytecode from the reported hash.
`runtimeRecordingNanos` now measures first-hit and diagnostic slow paths only, as
labelled by `runtimeRecordingTiming`; it is not total method-hook CPU time and is not
directly comparable with the older all-hit timer. See the
[local performance and correctness record](https://github.com/SAP/smart-test-picker/blob/2cf74c0466049cbf80fe1bf38ed794e113f9fbef/verification/stp-perf-01/IMPLEMENTATION.md).

Collector errors carry structured severity and ownership, not severity inferred
from a diagnostic prefix. A proven affected executable identity becomes
`unmapped/COLLECTION_FAILED` and is mandatory for selection; passing siblings can
retain coverage in a complete fragment.
Executable ownership must match the configured target and observed identity.
Unowned errors, initialization/transform failures and collision counters are diagnostic information;
they no longer clear completion or invalidate passing siblings. They also do not prove that the
recorded coverage is complete. A site or class name alone does not identify the tests it affects.
The build-plugin zero-instrumentation, binding and assignment checks are independent of that policy.
ERROR setup diagnostics still make collection incomplete. All diagnostic messages
remain visible in the agent output; no coverage wire schema is changed. See the
[current collector policy](../docs/asm-coverage-mapping-current-state.md).
