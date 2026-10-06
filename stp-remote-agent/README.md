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

The integration fixture starts an embedded Servlet server in a child JVM with this agent attached, then makes real HTTP calls with two IDs, no ID, and concurrent IDs. Jetty 11 exercises the `jakarta.servlet` path; bytecode verification covers both Servlet namespaces.
