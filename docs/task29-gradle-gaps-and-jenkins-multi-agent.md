# TASK 29 — Gradle gaps and Jenkins multi-agent validation

## Baseline

Work started on `research/asm-codex` at
`58f84a7bb1f93161282240d268a1080beccb6bda`. The existing uncommitted TASK 27/28
work was preserved.

## Gradle integration gaps

The plugin now owns two internal resolvable configurations. `stpAgent` resolves exactly one shaded
agent, using `:stp-agent`/`stpAgentElements` in the source multi-project build and
`com.sap.oss.smart-test-picker:stp-agent:<plugin-version>` for a published consumer.
`stpJacocoCollector` similarly places the existing core listener runtime on only the dedicated legacy
mapping test classpath. Consumers do not assemble runtime, listener, or ASM dependencies.

The shaded publication contains `stp-runtime`, `stp-junit-adapter`, and relocated ASM. An external
TestKit consumer resolves the plugin marker and agent from an isolated Maven repository and produces a
schema-v2 fragment without declaring `stpAgent`. Plugin version lookup now uses a Gradle-generated
resource and fails explicitly if missing or empty; it no longer silently assumes `0.1.0`.

The JACOCO failure had three integration causes: the core listener was absent from the dedicated test
runtime, Jupiter extension auto-detection was not enabled, and Gradle gave the custom task a destination
other than the legacy listener's `build/jacoco/test.exec` contract. The backend now restores those
legacy conditions only for `generateSmartTestCoverage`. The ordered mapping lifecycle is test, per-test
exec, XML, then legacy JSON. The functional fixture verifies every artifact and verifies that no ASM
fragment is created. No descriptor synthesis was added.

## PetClinic single-agent smoke

Spring PetClinic was checked out at the required
`88e37c15cf6fc8490b01bc3e8e2c800cec1ac272`. The published plugin and agent were used with no local
agent override. `generateSmartTestCoverage` passed and emitted a completed schema-v2 fragment with zero
transformation failures and zero method-ID collisions.

This host had Docker available during the run, so four database tests that TASK 28 recorded as
environment-disabled executed successfully. The observed population was therefore 73 rather than 69,
with 428 class edges, 1,353 method edges, and 106 setup edges. This is an environment/scheduling
difference, not an accepted replacement for the locked TASK 28 oracle. The three historical false
ValidatorTests setup relations remain absent. A strict TASK 28 semantic regression result is therefore
not claimed from this run.

## Existing Jenkins environment and compatibility boundary

The existing topology was reused for inspection: controller `stp-plugin-controller`, Jenkins 2.516.2,
and four online physical inbound agents (`stp-agent`, `stp-map-agent-1`, `stp-map-agent-2`, and
`stp-map-agent-3`). Labels `stp-map-0` through `stp-map-3` are available; workspaces are independently
rooted in each container at `/home/jenkins/agent`. The controller runs Temurin 21.0.8. Agent Java is at
`/opt/java/openjdk/bin/java`.

The installed POC cannot validate schema-v2 fragments without a Jenkins-plugin change:

- `stpCoverageMap` injects the old JaCoCo mapping init script and its callback rejects every
  `schemaVersion` other than 1.
- `stpPublishCoverageMap` invokes the bundled schema-v1 join/publish runtime.
- Consequently no truthful schema-v2 stash/join/completeness or negative-test evidence was generated.

`stpPrepareCoverageMapping` was inspected but not executed for TASK 29. `stpCoverageMap` and
`stpPublishCoverageMap` were not exercised against ASM. This is the exact compatibility boundary; it
must be adapted in the Jenkins POC before the requested three-physical-agent validation can proceed.
No production 5d storage or publication implementation was added.

## Status

Gradle automatic agent resolution and the JACOCO fallback gaps are closed and repository tests pass.
TASK 29 as a whole is incomplete because the installed Jenkins POC remains schema-v1-only and the
environment-enabled PetClinic population did not reproduce the locked 69-test oracle. Task 5c remains
**IN PROGRESS**. Task 5d remains **NOT STARTED — POC proven**; 5b and Maven status are unchanged.
