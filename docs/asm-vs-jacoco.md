# ASM and JaCoCo collectors

ASM observes production method entry. JaCoCo derives covered methods from execution
probes collected at test and setup boundaries. The observations need not be identical:
generated-code filtering, exceptional control flow and lifecycle boundaries can change
which edges each collector records. Do not use equality between collectors as a
correctness requirement.

Both tools default to ASM for schema-v3; Maven schema-v1/v2 defaults to JACOCO. Select one collector per mapping
invocation. Both use the production sources admitted by their build integration,
including supported cross-module dependencies. See [compatibility](compatibility.md)
and the [configuration reference](configuration-reference.md).

## Project coverage alongside STP

With ASM, a project's JaCoCo agent and reports remain active. Integration tests for
both build tools compare STP fragments with and without project JaCoCo, and project
coverage with and without STP. This proves the tested attachment configurations,
not arbitrary agent orderings or third-party transformations.

With the JACOCO collector, STP shares the project's active agent and resets its data
at session boundaries. In schema-v3 mapping, build-tool-managed agent attachment
writes to an STP-owned destination, leaving the project's execution-data file intact.
Project report, verification, merge and dump consumers are skipped and logged; run
project coverage in a separate invocation. Maven removes its invocation-owned agent
destination at session end, after STP has collected the per-test data.

A literal project-owned `-javaagent` argument is not rewritten. Its destination may
receive partial data during mapping. Prefer managed attachment or a separate project
coverage invocation. A missing active JaCoCo agent is an error; Maven offline
instrumentation is unsupported for this collector.

See the [Gradle guide](../smart-test-picker/README.md),
[Maven guide](../smart-test-picker-maven/README.md), and
[historical comparison](history/asm-vs-jacoco.md) for older research, not the current contract.
