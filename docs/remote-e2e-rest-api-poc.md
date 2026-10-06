# Remote REST API E2E test selection POC

## Purpose

This document is the hand-off for continuing Smart Test Picker (STP) work on test suites that call a separately deployed REST API. It records the validated baseline, what the existing HexagonalSpring POC did and did not prove, the expected architecture for a genuine remote E2E validation, risks, acceptance criteria, and a reproducible next-session plan.

The development branch reserved for this work is:

```text
research/asm-codex-remote
```

It was created from `research/asm-codex` after rebasing that branch onto `origin/research/asm-codex` at:

```text
132a215a3e48d0d5bc6eaf8aa33a87b13ecd2568
```

## Terminology and scope

“Remote E2E” in this document means that the selected JUnit test runs in the test-runner process while the system under test runs in a different process, container, VM, or host and is reached over HTTP. It does not mean remote coverage-map storage. The `RAW_HTTP`/Nexus work documented elsewhere concerns storage and is independent of this validation.

There are two materially different coverage goals:

1. **Client/test-side selection:** determine which REST API tests should run and record coverage of code executing inside the test JVM. This is compatible with the current in-process JaCoCo/ASM collection model.
2. **Server-side production coverage:** associate a remote service’s executed production classes/methods with the identity of the test that caused the HTTP request. This requires cross-process test-context propagation and server-side collection. The current POC has not proven this.

The next session must state which goal it is validating. A result that proves only client-side selection must not be reported as server-side remote coverage.

## Current branch baseline

At branch creation, `research/asm-codex` had been rebased successfully onto its remote tracking branch. The remote update added Maven execution and cross-module fixes, including:

- Surefire inventory alignment;
- managed selective Maven reactor execution;
- managed execution-evidence runtime injection;
- implicit Surefire evidence capture;
- JUnit Vintage `ClassSource` evidence;
- Maven cross-module coverage mapping.

These changes are relevant because a remote REST suite is usually executed through Maven Surefire or Failsafe and may live in a dedicated E2E module. The existing executable identity model distinguishes Maven module, provider (`surefire` or `failsafe`), execution id, and optional profile. Preserve that identity; do not collapse E2E tests into an unqualified class/method name.

## Validated precursor: MM-1 HexagonalSpring

The existing precursor is MM-1, “Validate Maven multimodule STP mapping on HexagonalSpring.” Its complete session is stored outside this repository in the ASM POC results area at:

```text
<asm-poc-results>/MM-1.log
```

Its contract is:

```text
<asm-poc-contract>/MM-1.md
```

The tested third-party project was:

```text
repository: https://github.com/LuisBoto/HexagonalSpring.git
checkout:   <hexagonal-spring-stp-checkout>
branch:     main
commit:     8e81b021b5b0f145be8a70d5d9d26d8e89685af7
```

STP was taken from:

```text
branch: research/asm-codex
commit: 92a1c613458a2dffc7beabed28de4c5203e49ee5
```

MM-1 used a real Maven reactor with these modules:

```text
core
inbound
outbound
hexagonalApplication
```

`hexagonalApplication` contained `TariffControllerE2ETest`, a Spring Boot/JUnit Jupiter/RestAssured test. The test configured:

```java
RestAssured.port = port;
RestAssured.baseURI = "http://localhost:" + port;
```

It then issued real HTTP requests with RestAssured. One logical parameterized E2E method produced five physical invocations.

MM-1 results were:

```text
ordinary physical tests: 14
STP physical tests:      14
logical inventory:       10
mapped:                  10
executed:                10
missing/unexpected:      0/0
classification:          PASS
```

The result proved that the Maven reactor inventory, conservative Surefire class widening, test execution, module-local fragments, evidence, aggregation, and exact completeness handled a project containing RestAssured E2E tests.

## What MM-1 did not prove

MM-1 is not a remote-host validation. Spring Boot started inside the same Maven test process and RestAssured called `localhost` on a dynamic port. It did not prove:

- connectivity to an independently deployed service;
- remote-service readiness and lifecycle management;
- authentication, TLS, proxy, DNS, or network-failure behavior;
- propagation of a logical STP test identity over HTTP;
- server-side association of coverage with the calling test;
- remote service coverage download or merge;
- safe handling of parallel tests against the same remote service;
- environment isolation or test-data cleanup;
- selection correctness after server-side production changes when the test repository contains no matching local production classes.

The current STP branch contains the Maven/selection/coverage machinery used by MM-1, but it does not contain the RestAssured test code. That code belongs to the HexagonalSpring repository.

## Fundamental selection question

STP normally selects tests by comparing changed production code with a previously generated coverage map. In a true remote API setup, the code under test and the API tests may be in different repositories. A useful POC must define the revision and change model explicitly.

Candidate models are:

### Same repository

The service and REST tests are versioned together, but the service is deployed separately for the test. Normal Git change detection can identify changed production classes. This is the simplest first remote-host validation.

### Separate service and test repositories

The service revision and test-suite revision are different Git identities. The coverage map must be keyed to the service revision while the executable inventory belongs to the test-suite revision. Current single-repository revision assumptions may not be sufficient. Do not hide this by copying SHAs or fabricating a common revision.

For the first genuine remote POC, prefer the same-repository model unless the actual product requirement mandates separate repositories.

## Proposed first genuine remote POC

Use two independently managed runtime roles:

```text
service runtime
  - starts from a recorded service commit
  - exposes a readiness endpoint
  - listens on a non-localhost network endpoint from the test runner's perspective

test runner
  - checks out the recorded test/service repository revision
  - discovers the authoritative JUnit inventory
  - receives an STP assignment
  - runs only assigned RestAssured tests
  - records exact execution evidence
```

Docker Compose is acceptable for isolation if the test runner calls the service by container DNS name, not `localhost`. Separate processes without Docker are also acceptable if lifecycle and network separation are proven. Jenkins should be added only after the local two-process contract passes.

Suggested topology:

```text
remote-e2e-service        service container/process
remote-e2e-test-runner    Maven test container/process
```

Record service container/process identity, test-runner identity, endpoint, repository commit, start/readiness/finish timestamps, and the exact test assignment.

## Configuration contract

The remote endpoint must be supplied by configuration rather than hard-coded. Prefer one project-owned property such as:

```text
-DremoteE2e.baseUrl=http://remote-e2e-service:8080
```

or an environment variable owned by the test project. STP should not own application credentials or endpoint semantics. It must preserve existing Maven/Surefire/Failsafe system properties and environment configuration while applying its includes file and execution instrumentation.

Secrets must use the CI credential mechanism and must not appear in Git, command logs, coverage maps, evidence, or reports.

For Failsafe-based suites, use the executable target describing the actual Failsafe execution. Do not run a Surefire inventory and later claim it represents Failsafe execution.

## Test identity and parameterization

The authoritative inventory must include every selectable REST test using canonical JUnit identities. Parameterized/template tests require special care:

- inventory is normally method-level;
- physical invocations may be greater than logical identities;
- an assignment must not silently omit a signature-bearing parameterized method;
- conservative class widening is acceptable only when recorded explicitly;
- execution evidence must prove that every assigned logical identity ran;
- physical invocation count is supporting evidence, not a substitute for logical completeness.

MM-1 demonstrated this exact issue: direct identity-to-Surefire patterns omitted a parameterized E2E method, while the production `SmartTestFilter` class-widening path correctly executed it.

## Coverage semantics

### Client-side only

If the JaCoCo agent/listener is attached only to the test JVM, coverage represents test/client/helper code. This can prove selective execution mechanics but cannot create a meaningful service-production coverage map when the production code executes remotely.

### Server-side coverage

To map remote service code per test, the architecture needs at least:

1. a stable logical test identity in the test runner;
2. propagation of that identity in every relevant HTTP request, for example a bounded internal correlation header;
3. server-side request interception that binds the propagated identity to execution context;
4. context propagation across server threads, executors, futures, reactive chains, and callbacks as applicable;
5. server-side coverage collection segmented by propagated identity;
6. an authenticated export/finalization boundary;
7. deterministic merge with test-runner execution evidence;
8. cleanup between tests and protection against cross-test contamination.

The earlier ASM context-propagation research is relevant, but no remote HTTP propagation contract has yet been validated. Do not assume `ThreadLocal` alone is sufficient.

The correlation header must be treated as internal diagnostic metadata. A public service must not trust arbitrary callers to inject identities. Use network isolation, authentication, allow-listing, or a test-only deployment mode.

## Required acceptance sequence

### Phase 1: baseline

1. Record all repository branches, HEADs, remotes, and worktree status.
2. Record Java, Maven/Gradle, test framework, and service/runtime versions.
3. Run the complete remote REST suite without STP.
4. Record logical and physical counts, results, duration, and service logs.

### Phase 2: inventory and full assignment

1. Generate the authoritative revision-bound inventory.
2. Verify module/provider/execution ownership.
3. Assign the full inventory through the production STP path.
4. Run against the independently deployed service.
5. Prove exact assigned = executed completeness.

### Phase 3: strict subset

1. Create a deterministic service production change at a recorded commit.
2. Derive expected impacted tests independently from the baseline map.
3. Run STP selection through the production entry point.
4. Prove selected = assigned = executed.
5. Prove selection is a strict subset of the full authoritative inventory.
6. Run the full suite at the same changed revision and confirm selected tests are included and pass.

### Phase 4: negative cases

At minimum validate:

- service unavailable before test start;
- readiness timeout;
- HTTP authentication failure if authentication exists;
- selected test fails after reaching the service;
- selected test never reaches the service;
- missing or malformed propagated identity for server-side coverage;
- concurrent REST tests without identity leakage;
- stale service deployment revision;
- incomplete remote coverage export;
- test-runner cancellation and cleanup.

Failures must remain typed and must not be converted silently into successful selection evidence.

## Evidence requirements

Preserve machine-readable evidence for:

- service and test repository revisions;
- deployment image/artifact digest;
- endpoint identity without secrets;
- authoritative inventory;
- assignment and selection result;
- logical execution evidence;
- client and, if implemented, server coverage fragments;
- completeness result;
- service readiness/lifecycle timestamps;
- baseline/full-suite comparison;
- negative-case outcomes;
- exact commands and configuration names;
- final worktree status.

Do not commit credentials, access tokens, cookies, raw authorization headers, or production endpoint details.

## Known implementation boundaries

- The current branch can discover and route Maven Surefire/Failsafe executable targets and create exact execution evidence.
- MM-1 proved RestAssured compatibility only with a locally embedded Spring Boot server.
- Current coverage instrumentation naturally observes only the JVM to which it is attached.
- A remote service requires its own instrumentation if server production coverage is required.
- Cross-repository service/test revision modeling is not yet defined.
- Remote test endpoint and authentication are application/test-suite configuration, not STP storage configuration.
- `RAW_HTTP`, `RemoteStoreClient`, and coverage-map storage are unrelated to calling the system under test.

## Recommended next-session plan

1. Confirm whether the target use case needs only selective remote test execution or also per-test server-side production coverage.
2. Select and pin a real project whose REST suite can call an independently deployed service.
3. Prefer same-repository service/tests for the first validation.
4. Add only project-owned endpoint configuration; verify ordinary remote execution first.
5. Exercise the existing Maven executable inventory/assignment path unchanged.
6. Capture a full-assignment remote baseline.
7. If server coverage is required, design and review the HTTP identity-propagation/security contract before implementation.
8. Implement server interception and context propagation behind an explicit test-mode boundary.
9. Add deterministic strict-subset and concurrency validations.
10. Only after local two-process success, add Jenkins/Docker orchestration and publish final evidence.

## Start-of-session commands

```bash
git switch research/asm-codex-remote
git fetch origin
git rebase origin/research/asm-codex
git status --short --branch
git log -5 --oneline --decorate
```

Before rebasing an already-published feature branch, inspect divergence and coordinate if another contributor has pushed to it.

## Current conclusion

The repository has a solid Maven/JUnit executable-selection baseline and a successful precursor containing RestAssured tests. It does not yet contain proof of a genuinely remote REST service call with server-side per-test production coverage. The `research/asm-codex-remote` branch and this document establish an explicit, non-overclaiming continuation point for that work.
