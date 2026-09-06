# ASM research closure audit

Audit baseline: branch `research/asm-codex`, starting HEAD `2a61dd25f8c08517052508da054e0a57240149f4`. The review covered all 653 tracked paths, the full status including ignored files, and the significant families below. Categories use the Round 18 vocabulary.

| Path/family | Category | Keep/remove | Canonical/historical | Reason |
| --- | --- | --- | --- | --- |
| `stp-agent/` core agent, method transformer/catalog/output | PRODUCTION_CANDIDATE_CODE | KEEP | CANONICAL | Implements direct method-entry collection and deterministic identity/output. |
| `stp-runtime/` logical context, aggregation, wrappers/hooks | PRODUCTION_CANDIDATE_CODE | KEEP | CANONICAL | Owns lifecycle, propagation, late quarantine, and runtime facts. Round-specific sink interfaces are inert research support. |
| `stp-junit-adapter/` listener and ordinary tests | PRODUCTION_CANDIDATE_CODE | KEEP | CANONICAL | Establishes logical JUnit leaf ownership. |
| Round 10–15 recorders, transformers, orderer/state/timeline code in those modules | RESEARCH_DIAGNOSTIC_CODE | KEEP | HISTORICAL | Property-gated evidence reproduction; not production behavior. Exact disposition is in `asm-baseline-status.md`. |
| `docs/asm-coverage-mapping-current-state.md`, summary, comparison, index, audit, policy, provenance, baseline status | CANONICAL_DOCUMENTATION | KEEP | CANONICAL | One entry point plus focused durable references. |
| Round reports in `docs/research/`, `docs/round*`, and ASM-related `docs/spikes/` | HISTORICAL_RESEARCH_DOCUMENTATION | KEEP | HISTORICAL | Preserve original questions, negatives, and evidence evolution; index marks superseded versus still canonical conclusions. |
| `docs/spikes/` Spring Data reports and `docs/spikes/archive/` JSON | HISTORICAL_RESEARCH_DOCUMENTATION / COMPACT_EVIDENCE | KEEP | HISTORICAL | Earlier adapter/observability research has unique history and compact evidence; it is explicitly outside the final ASM contract. |
| `stp-petclinic-spike/round2`, `round5`, `round8` scripts/configuration | REPRODUCTION_TOOLING | KEEP | HISTORICAL | Reproduces staged PetClinic claims. |
| `stp-petclinic-spike/**/evidence/*.json` and selected XML/reference data | COMPACT_EVIDENCE | KEEP | HISTORICAL | Structured comparison, classification, manifests, and bounded evidence. |
| 21 tracked PetClinic `.log` files | GENERATED_OR_DISPOSABLE | REMOVE | HISTORICAL | Console output is duplicated by structured artifacts and reproduction tooling; no unique claim depends on it. |
| Round 8 copied Surefire report trees (28 files) | GENERATED_OR_DISPOSABLE | REMOVE | HISTORICAL | Generated test reports; pass/failure facts remain in manifest and report. |
| `stp-spring-core-spike/round9`–`round17` analyzers/init scripts/config | REPRODUCTION_TOOLING | KEEP | HISTORICAL | Reproduces published Spring classifications without changing Spring. |
| `stp-spring-core-spike/**/evidence/*.json` and bounded target traces | COMPACT_EVIDENCE | KEEP | HISTORICAL | Required to audit fixed populations, negative results, and direct causal chains. The individually reviewed 1–5 MiB traces have unique causal value under policy. |
| `research/spring-reflection-order-reproducer/` | REPRODUCTION_TOOLING | KEEP | HISTORICAL | Standalone reproduction of the proved runtime mechanism. |
| `scripts/check-large-files.sh` | REPRODUCTION_TOOLING | KEEP | CANONICAL | Enforces the evidence size policy. |
| `docs/asm-baseline-commit-plan.md`, `docs/asm-git-artifact-audit.md` | GENERATED_OR_DISPOSABLE | REMOVE | HISTORICAL | One-time Round 16B planning/snapshot documents fully superseded by status, policy, provenance, and this closure audit. |
| ignored root/module/reproducer `build/` and `.gradle/` trees | GENERATED_OR_DISPOSABLE | REMOVE FROM WORKTREE | N/A | Reproducible build/cache output and never intended for Git. |
| ignored root `.DS_Store` | GENERATED_OR_DISPOSABLE | REMOVE FROM WORKTREE | N/A | OS metadata. |
| ignored `stp-petclinic-spike/round2/raw/*.log` | GENERATED_OR_DISPOSABLE | REMOVE FROM WORKTREE | N/A | Regenerable raw console output already intentionally ignored. |

No tracked JFR binary, temporary Spring checkout, editor file, build directory, or file over 5 MiB remained at audit time. The existing branch-wide non-ASM product and historical Spring Data files were inspected for generated material but are not reclassified as products of the ASM research. Cleanup deliberately avoids a production-hardening redesign and does not move historical files merely to create a new directory layout.
