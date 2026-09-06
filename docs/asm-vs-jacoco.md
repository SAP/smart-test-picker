# ASM and JaCoCo: coexistence and comparison

## Technical model

JaCoCo uses probes and execution-data reset/dump boundaries. It remains the existing project coverage agent and reporting mechanism used by the historical STP mapping POCs.

The ASM STP prototype instruments direct production method entry and attributes observations to an explicit logical `TestIdentity`. Supported task submissions propagate that logical context; observations after the identity closes are quarantined as `LATE_EVENT` instead of entering ordinary per-test coverage.

## Coexistence

The retained tests prove specific coexistence configurations in which existing JaCoCo project coverage and the STP ASM runtime/instrumentation both operate. This is technical coexistence, not proof that every agent order, build plugin, JVM, or arbitrary simultaneous configuration is supported.

“Existing JaCoCo project coverage + STP runtime” must not be confused with running two competing STP per-test collectors and merging or comparing their ownership as though they implemented one contract. The research did not establish such a dual-collector production mode.

## Comparison semantics

`JaCoCo map != ASM map` is not automatically an error. Differences can arise from:

- setup and lifecycle interval boundaries;
- JaCoCo execution accumulated before a reset/dump versus ASM logical leaf ownership;
- late-event quarantine;
- execution-order variation between runs;
- JVM/runtime control-flow variation, including reflection result order;
- descriptor-preserving ASM identity versus name-only views where an older JaCoCo/STP representation collapses overloads.

Round 2 showed bootstrap work present in JaCoCo test intervals but globally observed and deliberately unattributed by ASM. Round 8 explained six reference-only constructor edges through interval carryover versus late quarantine. Round 9 showed that large Spring differences primarily redistributed edges among tests while global unions stayed stable. These are attribution and execution observations, not a license to ignore genuine transformer errors; collisions, transformation failures, unsafe unknown-context events, global union loss, and reproducible missing method entries remain useful failure signals.

## Recommended future usage

- **ASM:** STP production-candidate collector for explicit runtime method-entry attribution.
- **JaCoCo:** optional validation, reference, project-coverage, and diagnostic source.

Selector correctness must be defined against selector semantics and the documented ASM observation contract. It must not depend on bit equality between ASM and JaCoCo per-test maps.
