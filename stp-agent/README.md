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

Maven preparation supplies optional `productionSourcesFile` (one absolute production
directory/main-JAR path per line) and `requireInstrumentation=true`. The allowlist
is an additional code-source restriction, not a replacement for package filters.
Unknown code sources and test artifacts cannot pass it. The guard refuses to write
a fragment when `classesTransformed` is zero, retaining scope/count diagnostics.
Absent options preserve the existing Gradle/raw-agent behavior. See
[Maven scope validation](../verification/maven-asm-08/REPORT.md).

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
[local performance and correctness record](../verification/stp-perf-01/IMPLEMENTATION.md).
