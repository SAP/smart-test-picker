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

The agent is wired into the Gradle production mapping backend. It projects runtime observations into logical schema-2 or target-qualified executable schema-3 fragments according to the invocation contract. The shaded artifact includes `stp-runtime` and the JUnit adapter; Jenkins/Gradle supply revision, shard, assignment and execution target. Maven mapping currently uses JaCoCo and deliberately rejects ASM.
