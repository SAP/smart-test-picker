# ASM baseline commit plan

ROUND 16 starts from `research/asm-codex` at `64c2636`, where ROUND 1-5 are already committed. The remaining worktree is intentionally divided into production-candidate runtime changes, bounded research diagnostics, validation harnesses/evidence, and final baseline documentation.

## Proposed commits

1. **Propagate logical test context through the ASM runtime**
   - Add the immutable logical `TestExecutionContext` and context-id-aware runtime ownership.
   - Preserve setup/lifecycle attribution and explicit late-event semantics.
   - Add runtime unit coverage and the logical-context design record.

2. **Support bounded thread and executor context propagation**
   - Cover supported `Thread`, executor, scheduled-executor, `ForkJoinTask`, and `CompletableFuture` submission shapes.
   - Retain explicit diagnostics for unsupported or unresolved boundaries.
   - Add transformation and end-to-end fixtures plus ROUND 6b documentation.

3. **Validate ASM attribution on PetClinic**
   - Add the reproducible ROUND 8 harness, compact evidence, and report.
   - Keep historical observations unchanged.

4. **Validate Spring Core stability and preserve ROUND 9-10 evidence**
   - Add the Spring Core run harnesses, fixed analyses, and evidence for topology and repeatability.
   - Preserve the ROUND 10 192-edge `UNKNOWN` inventory as the immutable input to ROUND 16.

5. **Add focused Spring causal diagnostics**
   - Add property-gated ROUND 11 lifecycle/cache state, ROUND 12 provenance/timeline, ROUND 13 cache tracing, ROUND 14 candidate control-flow tracing, and ROUND 15 reflection-order tracing.
   - Keep these diagnostics bounded and disabled by default.
   - Mark their production disposition explicitly in `docs/asm-baseline-status.md`.

6. **Preserve ROUND 11-15 causal evidence and findings**
   - Add analysis scripts, evidence bundles, and reports without rewriting negative or failed experiments.

7. **Characterize the fixed 192 edges in ROUND 16**
   - Add the fixed input, repeated full-run maps, stability vectors, exclusive analysis groups, causal classifications, summary, and reproducible analysis/run tooling.
   - Add only focused diagnostics required by direct evidence.

8. **Add the standalone Spring reflection-order reproducer**
   - Add a research-only Java/JUnit fixture, run instructions, observations, and a neutral upstream issue draft.

9. **Document and mark the canonical ASM baseline**
   - Add `docs/asm-baseline-status.md` and `docs/asm-coverage-mapping-current-state.md`.
   - Record verification, exact ROUND 16 commits, HEAD, and annotated tag after all preceding commits exist.

## Cleanup boundary

Keep all report-referenced source evidence and final reports. Remove only ignored OS/editor files, nested Spring build/cache output, the disposable temporary Spring checkout after its pristine revision is proven, and scratch artifacts that are demonstrably superseded and unreferenced. Update `.gitignore` only where a transient class of files is not already excluded.

## Commit construction notes

Some currently modified files contain both production runtime behavior and opt-in diagnostic registration. Their hunks will be staged separately so production propagation does not become an opaque diagnostics commit. No historical commit will be rewritten, no force-push will be used, and no existing tag will be replaced.
