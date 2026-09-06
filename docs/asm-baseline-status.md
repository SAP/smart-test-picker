# ASM Spring stability baseline status

## Baseline identity

- Branch: `research/asm-codex`
- Starting commit before consolidation: `64c2636fe4f9224e7f3af4c9677c38fa38594115`
- Baseline HEAD: recorded after the final ROUND 16 documentation commit
- Annotated tag: `asm-spring-stability-baseline`
- Remote: `origin` (`https://github.com/SAP/smart-test-picker.git`)

## Consolidated commit areas

ROUND 16 converts the accumulated ROUND 6-15 work into separate commits for logical context propagation, thread/executor propagation, PetClinic validation, bounded causal diagnostics, Spring Core validation, stability analysis, and focused causal evidence. ROUND 16 results, the reproducer, and final baseline documentation are kept in subsequent commits. The final hashes and subjects are recorded below after commit creation.

## Production candidate versus diagnostics

Production-candidate code consists of the ASM method-entry collector, deterministic method catalog and output, logical test context, JUnit lifecycle attribution, late-event quarantine, supported call-site propagation wrappers, class/generated-code filtering, and associated tests.

Property-gated or experiment-specific code is disabled unless its named system properties are supplied:

| Diagnostic | Disposition | Reason |
| --- | --- | --- |
| `CausalTraceRecorder` (`stp.round10.trace.*`) | POTENTIAL_PRODUCTION_UTILITY | Bounded method-hit ownership correlation can support future troubleshooting, but its schema/API is experimental. |
| `Round11ClassOrderer` | REMOVE_BEFORE_PRODUCTION | JUnit order manipulation exists only for controlled experiments. |
| `Round11StateDiagnostics` | KEEP_FOR_REPRODUCIBILITY | Direct reflective cache snapshots/reset preserve ROUND 11 evidence; they are unsafe as normal runtime behavior. |
| `Round12TestTimeline` | POTENTIAL_PRODUCTION_UTILITY | Lifecycle timestamps may be generally useful, but current output and deprecated thread-id calls need hardening. |
| `Round13ClassFileTransformer` / `Round13TraceRecorder` | KEEP_FOR_REPRODUCIBILITY | Exact cache-owner/hit-miss tracing reproduces the ROUND 13 negative cache result. |
| `Round14ClassFileTransformer` / `Round14TraceRecorder` | KEEP_FOR_REPRODUCIBILITY | Exact candidate decision tracing reproduces the BridgeMethodResolver control-flow proof. |
| `Round15ClassFileTransformer` / `Round15TraceRecorder` | KEEP_FOR_REPRODUCIBILITY | Exact reflection pipeline tracing reproduces the raw-order proof. |
| ROUND 9-16 init scripts and analyzers | KEEP_FOR_REPRODUCIBILITY | Research harnesses, not shipped runtime behavior. |

`RuntimeHooks` contains small sink interfaces for ROUND 13-15 because transformed Spring bytecode must call a bootstrap-visible runtime boundary. Those interfaces are inert without the property-gated transformer and recorder.

## Cleanup

Historical reports and report-referenced evidence were preserved unchanged, including negative experiments and raw maps. Ignored build output remains untracked. The nested `spring-framework-round14` checkout is a disposable pristine subject checkout and is removed only after ROUND 16 runs and its clean status/revision are verified. `.DS_Store` files are already ignored by the repository and are disposable.

## Verification

- Complete STP regression: PASS, JDK 21.0.11, `./gradlew test --rerun-tasks`, 57 actionable tasks.
- Pre-consolidation `git diff --check`: PASS.
- Five ROUND 16 Spring Core executions: PASS, zero test failures and zero transformation errors.
- Spring revision: `99a366baf6640b275d08dde60f05da719139bb6a`; source checkout clean after experiments.
- Final `git diff --check`, working-tree state, tag, and push are recorded after finalization.

## ROUND 16 commit list

To be filled with exact hashes after the final commits are created.
