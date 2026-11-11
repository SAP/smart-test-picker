<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->
# EI-7b: Schema-v3 executable assignment production orchestration

EI-7b adds the shared production boundary between schema-v3 selection and the existing Maven and Gradle
adapter routers. `ExecutableSelectionResult` retains the exact revision and selected
`ExecutableTestIdentity` values. `ExecutableAssignmentOrchestrator` validates the requested revision and
explicit adapter, constructs the existing `ExecutableShardAssignment`, serializes it with the EI-3 codec,
and transports those bytes without projecting executable identities to logical identities.

Adapter selection is based only on `ExecutionTarget.buildTool`. A non-empty selection containing a target
for another adapter fails before transport. Empty selection remains a valid assignment and therefore uses
the caller's explicit adapter; both existing routers produce empty partitions and their adapters configure
no test execution. No runtime target is inferred from a class, module name, directory, or observed test.

The Maven preparation Mojo now delegates validation and partitioning to
`MavenExecutableAssignmentRouter`, symmetric with EI-6's `GradleExecutableAssignmentRouter`. Both validate
the exact revision, shard, build tool, and known target before returning strict logical-test filters scoped
under the retained executable target. Codec parsing continues to reject malformed and unknown build-tool
targets. Gradle's existing included-build rejection and `maxParallelForks=1` ASM guardrail are unchanged.

Positive non-execution evidence is unchanged: adapters and fragment validation retain explicit evidence;
EI-7b does not derive non-execution as `assignment - executed`. Schema-v2 selection and execution paths are
untouched.

The local JDK 17 validation runs the complete common, Maven-adapter, and Gradle-plugin test suites. This
includes existing EI-7a Maven reactor tests, EI-6 Gradle functional tests, schema-v2 regressions, strict
subset and zero-assignment fixtures, wrong adapter/revision/shard/target cases, and unsupported Gradle
included builds. No Docker, Jenkins runtime, or real-project rerun is necessary because the production
codec and both production adapter routing boundaries execute in this test chain.
