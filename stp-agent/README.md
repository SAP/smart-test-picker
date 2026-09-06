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

Method-entry instrumentation is enabled by default. `includes` is optional and empty means any class not matched by the mandatory platform/framework/STP exclusions is eligible. `instrumentation=off` remains available only for bounded diagnostics and tests.

The agent output is an internal collection format. Projection into coverage-map schema v1 and Gradle/Maven/CLI attachment are follow-up migration work; the current JaCoCo product path remains active meanwhile.
