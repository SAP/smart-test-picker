# PetClinic existing STP identity propagation POC

This validation uses JUnit Jupiter as the concrete PetClinic test runner because
the existing STP agent supports it. Remote STP receives only an opaque execution
ID over HTTP; the server-side agent has no JUnit dependency. The value propagated
in this experiment is the existing STP `TestIdentity.platformUniqueId`. This does
not require other REST test frameworks to use JUnit.

## Revisions and runtime

- STP: branch `research/asm-codex-remote`, commit
  `6d65ad9983cd0393e1f106b248ee361cd989031e`, clean at both runs.
- PetClinic: `https://github.com/spring-projects/spring-petclinic.git`, commit
  `88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`, clean.
- Java: SapMachine OpenJDK 21.0.12.1 LTS.
- Server readiness: `GET /actuator/health`.
- JVM B: Gradle task `:stp-remote-agent:petclinicRemoteTest`, running JUnit Jupiter
  in a dedicated Gradle Test worker JVM.

The existing STP runtime was visible from the test code before propagation was
implemented. `RuntimeContextRegistry.current()` and
`RuntimeContextService.currentTest()` were present during an ordinary test. Both
`RuntimeContextRegistry` and `RuntimeContextService` were loaded by
`jdk.internal.loader.ClassLoaders$AppClassLoader` in the worker JVM. Each test
logged its active `platformUniqueId`; those exact values were used as HTTP header
values.

## Reproduction

With `PETCLINIC_DIR` pointing to the clean PetClinic checkout, run from the STP
repository root:

```sh
PETCLINIC_DIR="$PETCLINIC_DIR" \
  stp-remote-agent/petclinic-poc/run-existing-stp-identity-poc.sh sequential

PETCLINIC_DIR="$PETCLINIC_DIR" \
  stp-remote-agent/petclinic-poc/run-existing-stp-identity-poc.sh parallel
```

Both runs started PetClinic as JVM A with `stp-remote-agent`, waited on
`/actuator/health`, and launched the dedicated JVM B test worker. The worker's
actual STP agent arguments were:

```text
output=<run-output>/client-stp-<mode>.json;runId=petclinic-<mode>;instrumentation=off;debug=false
```

No `fragmentOutput`, `revision`, `shardId`, or `executionTarget` argument was
configured. The worker command attached the built `stp-agent.jar` with those
options and set `-Dpetclinic.baseUrl=http://127.0.0.1:18080`. The parallel run
also set:

```text
-Djunit.jupiter.execution.parallel.enabled=true
-Djunit.jupiter.execution.parallel.mode.default=concurrent
```

The standalone headerless control `GET /vets` ran only after the test worker
exited and returned HTTP 200.

## Results

Both sequential and parallel runs passed. Each client STP output recorded six
successful physical test executions: `vetsRequest`, `ownerRequest`, three
`ownersParameterized(int)` invocations, and `noHttpTest`. The three parameterized
invocations had distinct native platform IDs:

```text
[engine:junit-jupiter]/[class:com.sap.oss.smarttestpicker.remote.petclinic.PetClinicRemoteApiTest]/[test-template:ownersParameterized(int)]/[test-template-invocation:#1]
[engine:junit-jupiter]/[class:com.sap.oss.smarttestpicker.remote.petclinic.PetClinicRemoteApiTest]/[test-template:ownersParameterized(int)]/[test-template-invocation:#2]
[engine:junit-jupiter]/[class:com.sap.oss.smarttestpicker.remote.petclinic.PetClinicRemoteApiTest]/[test-template:ownersParameterized(int)]/[test-template-invocation:#3]
```

Both client outputs reported `instrumentation=off` and `bytecodeModified=false`.
The verifier joined all five HTTP-producing test executions to exactly five
remote observation IDs using equality of `platformUniqueId` and
`testExecutionId`. It found no unknown remote IDs or cross-attribution:

- `vetsRequest` contained
  `org.springframework.samples.petclinic.vet.VetController#showResourcesVetList`
  and no Owner controller method.
- `ownerRequest` and each parameterized owner invocation contained
  `org.springframework.samples.petclinic.owner.OwnerController#showOwner` and no
  Vet controller method.
- `noHttpTest` was present in client STP output and had no remote observation.
- The post-run headerless control returned 200 and created no observation ID.

Remote method counts were 16 for `vetsRequest`, 21 for `ownerRequest`, and 21
for each of the three parameterized owner invocations, in both runs. Counts are
reported as evidence only, with no completeness threshold.

The parallel run proved overlap from the request helper's monotonic START/END
timestamps. The `vetsRequest` and `ownerRequest` HTTP intervals overlapped for
`157,006,500` ns. The verifier repeated the exact identity join and path-isolation
checks successfully for that run.

## Evidence and limits

Raw generated evidence is kept outside the repository under
`/tmp/stp-remote-identity-evidence/`:

- `sequential-clean/client-stp-sequential.json`,
  `remote-observations-sequential.json`, `jvm-b-test-output.log`,
  `petclinic-server-sequential.log`, `provenance.txt`,
  `headerless-control-status.txt`, and `join-verification.log`.
- `parallel/client-stp-parallel.json`,
  `remote-observations-parallel.json`, `jvm-b-test-output.log`,
  `petclinic-server-parallel.log`, `provenance.txt`,
  `headerless-control-status.txt`, and `join-verification.log`.

The server observations demonstrate request-thread method entries correlated to
the existing test identity. They do not establish coverage of work continuing on
server-side asynchronous/background threads.
