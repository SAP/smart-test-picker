# EI-4 runtime execution-target propagation

## Purpose

EI-4 carries a trusted build execution location into mapping collection and combines it with each logical test identity. Schema-v3 fragments are therefore keyed by `ExecutableTestIdentity`; schema-v2 collection remains logical-test keyed.

## Runtime-path audit

The audit was performed before implementation against baseline `35fdf854cffe9e8b75708ad3a099d081d0002f8c`.

### ASM

The Gradle `AsmCoverageCollectorBackend` configures the dedicated `StpCoverageTest`. It knows the collector type and supplies revision, shard ID, diagnostic output, and fragment output through `AsmAgentArgumentProvider`. That provider encodes the values in the existing semicolon-delimited `-javaagent` argument channel. `AgentConfiguration.parse` validates that channel before `StpAgent.premain` installs collection. The shaded `StpRuntimeTestExecutionListener` creates runtime logical `TestIdentity` values from JUnit `MethodSource` data. `RuntimeContextService` and `RuntimeEventAggregator` retain lifecycle and ASM method-hit facts. At shutdown, `AgentRuntime` asks `AsmCoverageFragmentProjector` to convert physical observations to coverage-model logical `TestIdentity` values and schema-v2 coverage facts, then writes them with `CoverageFragmentCodec`.

### JaCoCo

The Gradle `JacocoCoverageCollectorBackend` configures the same dedicated test task with collector type, revision, shard ID, the JaCoCo agent destination, `stp.exec.dir`, and the legacy listener runtime. `JacocoPerTestListener` derives only class, method, parameter types, outcome, and lifecycle facts from JUnit, writing logical identity sidecars beside per-test JaCoCo data. Gradle's report/map tasks consume those files through the legacy map engine. Maven Surefire likewise supplies `stp.exec.dir`; `GenerateCoverageFragmentMojo` knows revision, shard ID, output paths, and collector artifacts, converts the sidecars and reports into schema-v2 `CoverageFragment` facts, and writes them with `CoverageFragmentCodec`.

Assignment/filter inputs are adapter/orchestration concerns in the current design. Neither runtime listener reads an executable shard assignment. EI-4 therefore does not derive a target from an assignment or change routing.

The common flow is:

```text
build adapter
-> trusted runtime/collector configuration
-> JUnit listener and collector
-> logical TestIdentity
-> coverage or unmapped fact
-> fragment projection/writer
```

For schema v3, `ExecutionTarget` enters the existing trusted configuration boundary and is parsed before collection. A shared runtime qualification boundary combines it with logical facts immediately before executable fragment serialization.

## Trust boundary

The build adapter/orchestrator supplies the canonical target string. The runtime never infers Maven module or Gradle task ownership. It does not use working directories, classpaths, packages, class locations, or assignments as ownership evidence.

A schema-v3 collection context has exactly one trusted `ExecutionTarget`. The parsed value is immutable for that context, and every executable mapped or unmapped identity must have that exact target.

## Runtime configuration

The runtime configuration carries an explicit schema version, revision, shard ID, and—only for schema v3—a required parsed `ExecutionTarget`. Agent-backed ASM collection transports the schema version and canonical target through the existing agent-argument channel. Adapter-side JaCoCo projection uses the equivalent explicit collector configuration. Presence of a target never selects a schema implicitly.

## Why target is adapter-owned

The adapter knows the actual build invocation location. Runtime observations know test lifecycle and code coverage, but cannot reliably establish reactor module or Gradle task ownership. Keeping these responsibilities separate prevents identical logical tests in different execution locations from collapsing.

## Logical identity vs executable identity

JUnit listeners continue to produce logical identities. The shared qualification boundary constructs `new ExecutableTestIdentity(executionTarget, logicalIdentity)` for schema-v3 mapped and unmapped facts. Coverage data itself is unchanged.

## One-runtime-one-target invariant

One runtime collection process or task invocation emits identities for exactly one target. Missing or invalid configuration is rejected before schema-v3 collection begins, and qualification rejects an identity whose target differs from the configured context.

## ASM path

ASM preserves its descriptor-aware test discovery and method-hit attribution. Schema v2 uses the existing logical projector and codec. Schema v3 qualifies the resulting logical facts at the shared boundary and uses `ExecutableCoverageFragmentCodec`.

## JaCoCo path

The JaCoCo listener remains logical-only. Schema-v3 JaCoCo fragment production qualifies the logical fragment at the same shared boundary used by ASM. JaCoCo collection and report generation are otherwise unchanged.

## JUnit listener responsibility

The listeners own logical class, method, parameter types, lifecycle, outcome, and skip discovery. They are not Maven- or Gradle-aware. Existing nested, overloaded, parameterized, Kotlin/backtick, failure, and setup behavior is preserved.

## Setup scopes

Setup scopes retain the schema-v2 `TestContainer` model. EI-4 makes test ownership target-aware without inventing target-qualified setup scopes.

## Schema v2 compatibility

Schema version is explicit. Version 2 continues to emit `CoverageFragment` with logical `TestIdentity` keys and does not require an execution target. Version 3 requires a target and emits `ExecutableCoverageFragment`. There is no implicit v2-to-v3 reinterpretation.

## Failure behavior

Unsupported schema versions, missing targets, malformed targets, and target mismatches have distinct diagnostics. Schema-v3 startup fails closed before test collection when its target is absent or invalid; it never invents a default or writes a logical fragment as a substitute.

## SonarJava example

The logical identity `org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage` remains equal in two independent contexts, while `maven:java-checks-common::org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage` and `maven:java-checks::org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage` remain distinct. Their coverage sets are not unioned by the runtime.

## Non-goals

EI-4 does not derive Maven targets, inventory or route a reactor; derive Gradle task targets, inventory or route tasks; alter selectors or storage; touch Jenkins; resume MM-2; or change setup-scope ownership.

## Next dependency

EI-5 makes Maven reactor inventory and mapping routing use executable identities.
