<!--
SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->
# EI-1: Executable test identity model

> **Historical implementation stage.** EI-1 was introduced at revision `d83852001887d270e7ea10f6584fe5a37a75477b` on 2026-09-12. Later EI work integrated these identities into executable schema 3; versions, status, and non-integration statements below describe EI-1 only. The identity contract remains relevant; use the [current development state](current-development-state.md) and [compatibility table](compatibility.md) for current integration status.

## Purpose

EI-1 implements the semantic foundation of `MODEL_C_EXECUTABLE_IDENTITY`. A declared test and the build location that executes it are separate concepts, allowing the same declaration to occur safely in multiple modules or tasks.

## Logical vs executable identity

`TestIdentity` remains the module-neutral logical identity: fully qualified JVM binary class name, method or free-text feature name, and declared parameter types. The nonempty name accepts any character except ISO control characters, including `.`, `;`, `[` and `/`. The first `#` separates class and method; later `#` characters belong to the name. Module/task is part of `ExecutableTestIdentity`, not `TestIdentity`.

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

A trailing parenthesized suffix is a declared parameter list only when every
comma-separated element, trimmed for validation, matches one of these forms:

- A primitive: `boolean`, `byte`, `char`, `short`, `int`, `long`, `float`, `double`.
- A fully qualified binary class name containing at least one `.`, with `$`
  allowed in identifiers. Primitives and qualified names may have repeated `[]`
  suffixes, including the existing `com.example.Outer$Inner[][], long` form.
- A JVM array descriptor: one or more `[` followed by exactly one primitive
  descriptor letter (`Z B C S I J F D`) or `L<binary class name>;`. An object-array
  descriptor is unambiguous even for a default-package component (`[LFoo;`).

An empty `()` is never a parameter list: serialization emits a parameter suffix
only for nonempty parameters. Empty elements, including a trailing comma, do not
form a parameter list. All other suffixes remain part of the method name, subject
to its existing validation. This preserves `should work (happy path)`,
`returns empty list()`, `is #expected for valueOf(code)`,
`handles #input (edge case)`, and `()` itself. A suffix containing `#` cannot
match the type grammar, preserving Spock placeholders such as `valueOf(#code)`.

JUnit's `MethodSource` formats reflected parameter types using `Class::getName`
(JUnit r5.9.3: `MethodSource.java:137` delegates to `ClassUtils.nullSafeToString`,
`ClassUtils.java:67-68`). It emits `, ` separators and array descriptors, for
example `java.lang.String, [I, [[Ljava.lang.String;`. Per-element trimming is
therefore necessary, but the original parameter string, including spacing and
array spelling, is preserved exactly. There is no normalization or wire-format
change. Qualified names and descriptors contain `.`, `[` or `;`, which are now
also allowed in free-text names. Consequently these forms can collide with
feature names, as described below. This applies to logical and executable
identities, including unmapped entries.

Accepted residual ambiguities (no escape format is introduced):

- A default-package parameter type has a simple `Class.getName()` result such as
  `Foo`. `works(Foo)` is read as the method name with empty parameters, not as
  method `works` with parameter `Foo`.
- A method name ending in a valid primitive list, such as `foo(int)` with empty
  parameters, is read as method `foo` with parameter `int`.
- A feature literally named `foo(java.lang.String)` or `foo([I)` with empty
  parameters has the same wire text as a parameter-bearing identity. Parsing
  still interprets that suffix as parameters; no escaping is introduced.

## Consumer boundaries and existing limitations

Gradle executable assignments can split methods of one class between shards,
even though the default sharder groups classes. Dotted feature names therefore
use Gradle's leading-wildcard matcher syntax, bounded by an exact assigned-class
file filter; they never widen execution to every method of a class. Ambiguous
assigned class suffixes are rejected instead of risking cross-class matching.

Maven method globs allow `/` while file-path globs retain path boundaries.
Surefire and Failsafe includes use exact regex patterns for free-text names,
with code-point escapes shielding their commas, `#` and other pattern delimiters.
Ordinary Java method names retain their previous include spelling. No class-wide
fallback is needed. JaCoCo session filenames encode unsafe path characters at
the filename boundary; identity sidecars remain authoritative.

Two pre-existing restrictions are not changed: ANT output has no escaping for
`+` and `,`, and `ExecutionPlanCodec` rejects blank or untrimmed fields. A name
accepted by this model is not automatically representable in every consumer's
output syntax.

## Non-goals

EI-1 does not change schema v2, coverage maps or fragments, completeness, head inventory, selection, collectors, Maven or Gradle routing, Jenkins, or MM-2. The new types are intentionally not integrated into production flows yet.

## Next dependency

EI-2 introduces versioned artifact contracts that can carry executable identities.
