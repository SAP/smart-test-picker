# EI-14 validation report

## Result

EI-14 passes. The independent R0 reconciliation and all four missing Docker Jenkins runtime negatives are complete. Historical EI-13 evidence remains unchanged.

## Verified continuity

- Initial STP checkpoint: `4ff0ed9c24c3ca8291530cca3b2a1b8f55d48233`; verified EI-14 STP: `8166cdd3b8ee0d5a6f0c67e7264f5658d5d3c969`.
- Initial Jenkins plugin checkpoint: `38c0414b2a8847e62faadd3e8c214ec91d38fd8f`; verified deployed EI-14 plugin: `955b12d0c395d305f73172706d35c3e3ce97343d`.
- Commons Statistics remained at R1 `04e9e5d9d66da5dcbaa9a635674926ae71b4ea77`; all R0 jobs checked out detached R0 `2937eb2e711483d8ea9dc216af45c16fd0066b77`.
- Loaded plugin version: `1.0-SNAPSHOT (private-955b12d0-d061177)`.
- Deployed EI-14 HPI SHA-256: `3646518c92e8e1fd96e74eb89ed91fe4983b9717d97e28ac52ad324290500ba7`.
- Pre-existing untracked `EI-9/` was preserved.

## Independent R0 reconciliation

The reproducible analyzer is `reconcile_r0_execution.py`. It reads baseline and worker Surefire XML before consulting STP inventory, assignments, evidence, fragments, or map data. Raw testcase display names, status, module, target, report, ordinal, shard, and canonical candidates are retained in `evidence/r0-reconciliation-final.json` (SHA-256 `cd60f54e47c09b75893c1ddef20564fe76c351fb8f8462e24807f9e8a88e271c`).

Provenance was identical: R0, the complete 11-project reactor, Maven `test`, profile `examples`, and target `surefire@default-test@examples`. EI-13 baseline #1 supplied 127 XML suites. Retained EI-13 worker workspaces supplied the missing raw XML and exposed the initial defect. Corrected `EI-14-R0-Mapping #2` archived 43/42/42 worker suites; it passed fragment/evidence validation and production join, then failed only because Nexus was still starting. `EI-14-R0-Publication #1` reused those exact artifacts and passed the normal production join/publication once Nexus was healthy. No second full mapping run was performed.

Final results:

- Baseline: 55,879 invocations = 55,831 executed + 48 skipped.
- Distributed workers: the identical 55,879 invocation multiset, including status and full parameter/repetition display names.
- Missing, unexpected, and cross-shard duplicate invocations: zero.
- Canonical identity ambiguities: zero. The Fisher overload is resolved by invocation arity while raw signatures remain present.
- Inventory: 1,993 identities = 1,905 executed/map entries + 88 evidence-backed non-executed identities.
- Mapping XML without collector evidence, collector evidence without XML, collector evidence missing from map, and map entries without collector evidence: zero.
- All 88 non-executed identities are explained: 83 parameterized templates with no baseline invocation and five wholly skipped identities.

The original EI-13 set of 85 is separately present as `originalNonExecutedAccounting`: 83 no-invocation templates and two genuine skips. Three sampling identities were incorrectly in EI-13 `EXECUTED`; `correctedFalseExecutedAccounting` records their baseline and mapping skipped XML. The corrected result therefore adds those three genuine skips and changes 1,908/85 to 1,905/88.

## Defect and verification

The JUnit Platform listener treated `ABORTED` test results as failed executions and wrote both identity and coverage data. This made assumption-aborted tests look executed. STP commit `8166cdd3b8ee0d5a6f0c67e7264f5658d5d3c969` records aborted tests only as non-executed and discards their snapshot after resetting JaCoCo. The focused `JacocoPerTestListenerTest` passed, the core JAR was rebuilt (`fcda8a06702740965536c071e31be928214c3a38c720a26fb1957371d62bc7ea`), and the corrected production mapping joined 1,905 identities.

The unknown-target contract also exposed that Jenkins deferred authoritative-inventory membership validation until the Maven body. Plugin commit `955b12d0c395d305f73172706d35c3e3ce97343d` moves that check before body entry. The focused six-test `ExecutableAssignmentOrchestratorTest` passed, the HPI was rebuilt and deployed, and the runtime scenario proved the body was not entered.

## Docker Jenkins runtime negatives

`EI-14-Runtime-Negatives #2` ran on the real retained controller and join agent using the deployed HPI and production `stpCoverageMap`/`stpPublishCoverageMap` steps. Every scenario began with the valid production artifacts from mapping #2; their checksums are archived per scenario. Manifest branch/stash names were rebound only to isolate storage.

- Unknown target: one assignment identity was changed to syntactically valid `maven:commons-statistics-descriptive@surefire@ei14-unknown@examples`. The production mapping entrypoint rejected it as absent from the authoritative inventory before body entry; no Surefire report or full-suite fallback occurred.
- Duplicate fragment: fragment 2's serialized shard ID was changed from `2` to `1`, producing transported shard IDs `[0, 1, 1]`. All three stashes arrived and evidence validation passed; the production join rejected the duplicate/mismatched ownership with `JOIN_INVALID` / `MAPPING_INCOMPLETE`.
- Missing expected fragment: expected shard IDs remained `[0, 1, 2]`, while the shard-2 transport stash was omitted. Production retrieval rejected the missing stash.
- Incomplete publication: fragment 2 alone changed `collection.completed` from `true` to `false`. All stashes arrived and evidence validation passed; production join rejected it with `JOIN_INVALID` / `MAPPING_INCOMPLETE`.

Each isolated namespace returned HTTP 404 for both the immutable R0 map object and `pointers/latest.json` before and after its attempt. Thus no invalid attempt created or changed a map or pointer.

## Evidence

- `evidence/r0-reconciliation.json`: preserved initial failing analysis.
- `evidence/r0-reconciliation-final.json`: final machine-readable analysis and complete raw/accounting records.
- `evidence/r0-mapping-2/`: corrected fragments, evidence, assignments, inventory, and worker XML archives.
- `evidence/r0-publication-1/`: corrected published map (SHA-256 `4e8e2aee3f91add145d5fc6afa401ff4983c69d02c4a76d876dbd57e9732c650`).
- `evidence/runtime-negatives-2/`: mutations, starting checksums, rejections, execution proof, and storage snapshots.
- `evidence/complete-execution-logs/`: complete mapping, publication, and negative-job consoles, including failed diagnostic runs.
- `evidence/artifacts/`: original/fixed core JARs and deployed HPI.

EI-13 full-suite, three-worker selection, R1 control, full R1 coverage, and previously successful negative evidence remain applicable because neither fix changes discovery, sharding, selection, map serialization, storage, or project tests. Only the affected R0 mapping reconciliation and unknown-target boundary were rerun.
