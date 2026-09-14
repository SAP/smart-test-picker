# EI-16 verification report

## Continuity

- STP initial `988114175f8446846a7e8985cdf34afc93059faf`, final production commit `147c7484086a61043169ff02e86fddda2c64d17f`, branch/worktree `research/asm-codex` / `/Users/d061177/work/asm-poc/codex-worktree`.
- Jenkins initial `ef91832f39a8553099c951bfb48961121608addf`, production/package commit `d0b9c8d`, branch/worktree `ei13/maven-distributed-selection` / `EI-13/worktrees/stp-jenkins-plugin`.
- No reset, push, release/version exercise, or unrelated checkout change occurred.

## Finding 1

Gradle ASM, Maven JaCoCo, and the shared Gradle JaCoCo conversion represented a failed test as mapped `FAIL` coverage. A test calling A and failing before B therefore exposed only A. This violated the existing `unmapped/FAILED` contract.

`FAILED` is now failure-dominant and emitted without a mapped entry. It remains a complete executed observation, so completeness reports it. ABORTED-only/disabled identities remain non-executed; an aborted sibling does not erase successful coverage. Runnable Gradle ASM and Maven JaCoCo fixtures retain failure reports, prove B is unreached/absent, and inspect the production fragment. `ExplicitPrSelectorFlow` selects the failed identity when B changes.

## Finding 2

Schema-v3 qualified test identities but copied logical setup scopes unchanged, and joins keyed scopes only by ID. Maven also captured no bounded class-container setup. Executable scopes now carry `ExecutionTarget owner`; qualification assigns it, codecs require it, validation rejects its absence, and joins key owner+ID. Selector projection uses owner-qualified logical scope IDs.

Ownerless schema-v3 payloads containing setup scopes are rejected rather than reinterpreted; v3 payloads without setup scopes remain compatible. Two-Gradle-task and two-Maven-module fixtures use the same `SharedTest` and setup-only ProductionA/ProductionB. Both owners/dependencies survive collection, round trip, and join. Selection is conservatively broadened across exact owners.

## Finding 3

Production chain: `StpPrSelectStep` → `ExplicitPrSelectionRunner` → packaged `SelectorBridge` → CLI `SelectTestsCommand` → `ExplicitPrSelectorFlow` → logical analyzer/selector → expansion against `ExecutableHeadTestInventory` → target-qualified plan → `SmartTestPickerStep` → Maven adapter.

The executable inventory remains separate from the logical coverage union. The CLI expands a selected logical identity to every exact inventory match; the bridge writes canonical targets; the adapter routes them and normalizes descriptors only at Maven's documented enforcement boundary.

Observed contract: **CONSERVATIVE**. The maintained workflow maps different coverage for the same logical identity in two modules, changes only ProductionA, and computes its oracle first. Expected, selected, planned, and executed are the same module-A and module-B `SharedTest#same` identities; both `notSelected` methods are excluded.

## Verification and evidence applicability

- Full Java-17 STP suite: PASS in 2m44s (common, CLI, core, runtime/JUnit, agent, Gradle and Maven functional tests).
- Maintained Java-21 Docker Jenkins verifier: PASS, including HPI provenance/checksums, production mapping/publication/lookup/automatic selection/execution, adapters, NONE/RUN_ALL/Failsafe, negative checks, caches, and restarts.
- EI-14 remains evidence for large ABORTED accounting and unchanged isolation; EI-16 replaces failed semantics and adds Maven bounded setup attribution.
- EI-13 remains evidence for unchanged Maven sharding/distributed/NONE behavior. EI-15 remains evidence for unchanged packaging/cache/policy; its workflow is strengthened here.
- Commons Statistics and Spring Framework were not rerun.
- Docker used: YES. External Jenkins used: NO (local Docker only). Push performed: NO. Task containers/volumes/network were removed.

EI-16: **PASS**. Remaining gaps within these findings: **NONE**.

Complete logs:

- `EI-16/evidence/complete-execution-logs/01-stp-full-test.log`
- Jenkins `verification/ei16/complete-execution-logs/01-packaged-docker-smoke.log` (failed oracle retained)
- Jenkins `verification/ei16/complete-execution-logs/02-packaged-docker-smoke.log` (final PASS)
