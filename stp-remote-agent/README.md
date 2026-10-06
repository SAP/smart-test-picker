<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP remote servlet request correlation agent POC

This independent Java agent records selected application method entries against an HTTP test execution ID received by a Servlet server. It has no dependency on `stp-agent`, `stp-runtime`, or the JUnit adapter and does not use JUnit test lifecycle callbacks.

Build the shaded standalone agent with:

```bash
./gradlew :stp-remote-agent:remoteAgentJar
```

Run the server JVM with an explicit output file and application package allow-list:

```text
-javaagent:/path/stp-remote-agent.jar=output=/path/remote-observations.json;includes=com.example.application.;header=X-STP-Test-Execution-Id
```

The default header is `X-STP-Test-Execution-Id`. Comma-separated package prefixes are accepted. `excludes` can narrow an included prefix. The agent intercepts concrete `javax.servlet.FilterChain.doFilter` and `jakarta.servlet.FilterChain.doFilter` implementations. No controller changes are needed.

Each request temporarily installs its header value on the request thread. Missing, blank, overlong, or control-character IDs suppress attribution for that request. Nested filter-chain scopes restore their parent, and request exit restores or clears the prior thread state. Application methods are instrumented only under configured prefixes, and repeated hits are deduplicated by method identity.

At server JVM shutdown, the agent atomically writes a small JSON observation file. This POC has no live export, storage, async/background propagation, authentication contract, or cross-service propagation. The correlation header is trusted test metadata and must only be enabled in an isolated/test deployment protected from arbitrary callers.

The automated integration fixture starts an embedded Servlet server in a child JVM with this agent attached, then makes real HTTP calls with two IDs, no ID, and concurrent IDs. Jetty 11 exercises the `jakarta.servlet` path; bytecode verification covers both Servlet namespaces.

## PetClinic two-JVM proof

To validate against the standalone Spring PetClinic checkout without changing its application code, run:

```bash
PETCLINIC_DIR=/path/to/spring-petclinic-asm ./stp-remote-agent/petclinic-poc/run-two-jvm-poc.sh
```

`PETCLINIC_DIR` is required. The script builds PetClinic's executable jar with tests skipped, starts it as JVM A with this agent attached, then compiles and starts a small JDK `HttpClient` harness as JVM B. The default readiness probe is `GET /actuator/health`; `PROBE_PATH` can select another explicit path and the harness logs it. The probe must return HTTP 200 and never falls back to an application route. JVM B sends `GET /vets` with `vets-A`, `GET /owners/1` with `owner-B`, and a headerless `GET /vets`. It stops JVM A to flush observations and verifies the captured Vet and Owner controller paths, absence of cross-attribution, and absence of a headerless observation. Verification also prints the full method count for each execution ID without applying count thresholds. `PORT`, `OUTPUT_DIR`, and `OBSERVATIONS_NAME` can override defaults; the output defaults under this module's ignored `build/` directory and includes a provenance text file.

The harness is plain Java and does not start or depend on a PetClinic Spring test context. This POC verifies the JSON Vet route and the HTML Owner details route over real HTTP between separate JVMs.
