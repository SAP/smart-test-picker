# ASM Spring stability baseline status

## Baseline identity

- Branch: `research/asm-codex`
- Starting commit before consolidation: `64c2636fe4f9224e7f3af4c9677c38fa38594115`
- Baseline HEAD: the commit dereferenced by annotated tag `asm-spring-stability-baseline` (the exact hash is also printed in the ROUND 16 completion report)
- Annotated tag: `asm-spring-stability-baseline`
- Remote: `origin` (`https://github.com/SAP/smart-test-picker.git`)

## Consolidated commit areas

ROUND 16 converts the accumulated ROUND 6-15 work into separate commits for logical context propagation, thread/executor propagation, PetClinic validation, bounded causal diagnostics, Spring Core validation, stability analysis, and focused causal evidence. ROUND 16 results, the reproducer, and final baseline documentation are kept in subsequent commits. The final hashes and subjects are recorded below after commit creation.

## Production candidate versus diagnostics

Production-candidate code consists of the ASM method-entry collector, deterministic method catalog and output, logical test context, JUnit lifecycle attribution, late-event quarantine, supported call-site propagation wrappers, class/generated-code filtering, and associated tests.

The closure disposition is definitive for the current branch. “Remove before production release” means exclude or relocate it during a later production-hardening task, not delete it from this research baseline.

| Component | Production candidate? | Research only? | Keep for reproducibility? | Remove before production release? |
| --- | --- | --- | --- | --- |
| ASM method-entry transformer, catalog, collision handling, output | YES | NO | YES | NO |
| Logical `TestIdentity`, lifecycle aggregation, late-event quarantine | YES | NO | YES | NO |
| Supported thread/executor/`CompletableFuture` propagation and tests | YES | NO | YES | NO |
| JUnit Platform execution listener | YES | NO | YES | NO |
| `CausalTraceRecorder` (Round 10) | POSSIBLE UTILITY; API NOT ACCEPTED | YES | YES | YES, unless separately promoted |
| `Round11ClassOrderer` | NO | YES | YES | YES |
| `Round11StateDiagnostics` | NO | YES | YES | YES |
| `Round12TestTimeline` | POSSIBLE UTILITY; API NOT ACCEPTED | YES | YES | YES, unless separately promoted |
| Round 13 cache transformer/recorder | NO | YES | YES | YES |
| Round 14 candidate-order transformer/recorder | NO | YES | YES | YES |
| Round 15 reflection-order transformer/recorder | NO | YES | YES | YES |
| Round 9–17 Spring init scripts/analyzers/evidence | NO | YES | YES | YES |
| PetClinic experiment runners/analyzers/evidence | NO | YES | YES | YES |
| Standalone reflection-order reproducer | NO | YES | YES | YES |

Property-gated or experiment-specific code is disabled unless its named system properties are supplied. The detailed diagnostic rationale is:

| Diagnostic | Disposition | Reason |
| --- | --- | --- |
| `CausalTraceRecorder` (`stp.round10.trace.*`) | POTENTIAL_PRODUCTION_UTILITY | Bounded method-hit ownership correlation can support future troubleshooting, but its schema/API is experimental. |
| `Round11ClassOrderer` | REMOVE_BEFORE_PRODUCTION | JUnit order manipulation exists only for controlled experiments. |
| `Round11StateDiagnostics` | KEEP_FOR_REPRODUCIBILITY | Direct reflective cache snapshots/reset preserve ROUND 11 evidence; they are unsafe as normal runtime behavior. |
| `Round12TestTimeline` | POTENTIAL_PRODUCTION_UTILITY | Lifecycle timestamps may be generally useful, but current output and deprecated thread-id calls need hardening. |
| `Round13ClassFileTransformer` / `Round13TraceRecorder` | KEEP_FOR_REPRODUCIBILITY | Exact cache-owner/hit-miss tracing reproduces the ROUND 13 negative cache result. |
| `Round14ClassFileTransformer` / `Round14TraceRecorder` | KEEP_FOR_REPRODUCIBILITY | Exact candidate decision tracing reproduces the BridgeMethodResolver control-flow proof. |
| `Round15ClassFileTransformer` / `Round15TraceRecorder` | KEEP_FOR_REPRODUCIBILITY | Exact reflection pipeline tracing reproduces the raw-order proof. |
| ROUND 9-17 init scripts and analyzers | KEEP_FOR_REPRODUCIBILITY | Research harnesses, not shipped runtime behavior. |

`RuntimeHooks` contains small sink interfaces for ROUND 13-15 because transformed Spring bytecode must call a bootstrap-visible runtime boundary. Those interfaces are inert without the property-gated transformer and recorder.

## Cleanup

Canonical Git retains conclusions, compact derived evidence, and reproduction tooling. Bulk raw maps, copied full references, generated test results, JFR recordings, and temporary runtime logs are intentionally excluded. Their hashes and reproduction guidance are recorded in `docs/removed-research-artifacts.md`; the retention rules are in `docs/research-evidence-policy.md`.

## Future canonical branch promotion

The expected future strategy is to promote or rename `research/asm-codex` to the canonical `main` branch rather than merge the entire research history into the existing `main`. ROUND 16B does not perform that promotion.

## Verification

- Complete STP regression: PASS, JDK 21.0.11, `./gradlew test --rerun-tasks`, 57 actionable tasks.
- Pre-consolidation `git diff --check`: PASS.
- Five ROUND 16 Spring Core executions: PASS, zero test failures and zero transformation errors.
- Spring revision: `99a366baf6640b275d08dde60f05da719139bb6a`; source checkout clean after experiments.
- Final `git diff --check`: PASS.
- Final working tree before tag/push: clean.

## ROUND 16 commit list

Commits created from the prior `64c2636` cutoff, in chronological order:

1. `3d79599` Propagate logical test context through ASM runtime
2. `574c669` Support thread and executor context propagation
3. `6ca3231` Validate ASM attribution on PetClinic
4. `392d41d` Add bounded Spring causal diagnostics
5. `6440c88` Validate ASM attribution on Spring Core
6. `bdd8a87` Analyze Spring Core attribution stability
7. `d53dea1` Preserve focused Spring causal evidence
8. `9e6e0e8` Characterize fixed Spring Core unstable edges
9. `b3b69c5` Add Spring reflection order reproducer
10. `4c1c9b9` Document canonical ASM stability baseline
11. `9da11da` Record final ASM baseline metadata
12. `this commit` Establish lean canonical research evidence policy

The final entry identifies the commit containing this self-referential status record; its immutable hash is the annotated tag target and is reported by `git rev-parse asm-spring-stability-baseline^{commit}`.
