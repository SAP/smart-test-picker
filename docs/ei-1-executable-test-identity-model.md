<!--
SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->
# EI-1: Executable test identity model

## Purpose

EI-1 implements the semantic foundation of `MODEL_C_EXECUTABLE_IDENTITY`. A declared test and the build location that executes it are separate concepts, allowing the same declaration to occur safely in multiple modules or tasks.

## Logical vs executable identity

`TestIdentity` remains the module-neutral logical identity: fully qualified JVM binary class name, declared method name, and declared parameter types. Module/task is part of `ExecutableTestIdentity`, not `TestIdentity`.

`ExecutableTestIdentity` is the pair `(ExecutionTarget, TestIdentity)`. Equality and ordering include both values, and `test()` explicitly projects back to the logical identity.

## ExecutionTarget

`ExecutionTarget` is a build-tool-neutral pair of `BuildTool` (`MAVEN` or `GRADLE`) and a canonical `targetId`. It identifies a deterministic execution location within one exact revision. It contains no Maven, Gradle, Jenkins, or adapter API types.

### Maven target contract

The target ID is the reactor-relative module path. The root is `.`, a child is `java-checks`, and a nested child is `java-checks-test-sources/default`. `/` is the separator. Leading or trailing `/`, `./`, `.`, `..`, empty segments, backslashes, absolute paths, controls, and ambiguous colon syntax are rejected rather than normalized.

### Gradle target contract

The target ID is the fully qualified Gradle `Test` task path, such as `:test`, `:spring-core:test`, or `:module-a:integrationTest`. It must start with `:`, end in a nonblank task segment, and contain no blank segments, controls, slashes, backslashes, or adjacent colons.

## Canonical forms

An execution target is `<build-tool>:<target-id>`:

```text
maven:.
maven:java-checks
gradle::spring-core:test
```

An executable identity is `<execution-target>::<logical-test-identity>`:

```text
maven:java-checks-common::org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage
maven:java-checks::org.sonar.java.checks.helpers.ReassignmentFinderTest#parameter_with_usage
gradle::module-a:test::com.example.Test#works
```

Both forms round-trip through `parse(toString())`. Natural ordering is build tool then target ID for targets, and target then logical identity for executable identities.

## Non-goals

EI-1 does not change schema v2, coverage maps or fragments, completeness, head inventory, selection, collectors, Maven or Gradle routing, Jenkins, or MM-2. The new types are intentionally not integrated into production flows yet.

## Next dependency

EI-2 introduces versioned artifact contracts that can carry executable identities.
