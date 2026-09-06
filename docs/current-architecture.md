# Current architecture

This is the authoritative architecture snapshot after TASK 24 and TASK 25 and before build-tool ASM
integration.

## Modules

- `smart-test-picker-common`: schema v2 authority, semantic coverage/test/method model, map and
  fragment codecs, validation, and retained legacy JaCoCo/common support.
- `smart-test-picker-core`: existing JaCoCo per-test collection and selector-support path.
- `stp-agent`: ASM javaagent with method-entry instrumentation, deterministic method catalog,
  context propagation, integrity metrics, and schema-v2 fragment emission.
- `stp-runtime`: runtime ownership/context, event aggregation, ASM-to-schema projection, and typed
  setup/integrity state.
- `stp-junit-adapter`: internal listener-only JUnit Platform lifecycle bridge, bundled with the agent.
- `smart-test-picker`: the Gradle plugin, still using the existing JaCoCo production integration.
- `smart-test-picker-maven`: the Maven plugin, still using the existing JaCoCo production integration.
- `smart-test-picker-cli`: the existing user-facing CLI and legacy paths; schema-v2 CLI integration
  has not started.

ASM collection infrastructure exists, but Gradle and Maven production mapping have not been migrated.
The current JaCoCo paths remain deliberately intact.

## Intended release-artifact surface

User-facing artifacts are the Gradle plugin, Maven plugin, STP CLI, and STP agent. `stp-runtime`, the
JUnit listener, and relocated ASM are internal/bundled. Common/core schema and selector libraries are
shared internal dependencies. Publishing/configuration work needed to realize this target remains a
follow-up; this snapshot does not change publication.
