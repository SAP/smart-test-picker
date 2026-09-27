# ISSTA corrected-JaCoCo port audit

## Repository state before implementation

Audit date: 2026-09-27.

```text
main:                       c88c2ca380a2bfa380d7504887f5caa2ee4134b7
origin/research/asm-codex: 132a215a3e48d0d5bc6eaf8aa33a87b13ecd2568
merge base:                 c88c2ca380a2bfa380d7504887f5caa2ee4134b7
initial worktree status:    clean
initial checkout:           detached 70b3984626ebed16db015c7c261eda132e999f10
```

There was no local `refs/heads/research/asm-codex`; the authoritative fetched source inspected by this audit is `refs/remotes/origin/research/asm-codex`. The local `main` and `origin/main` both resolved to the SHA above. The ISSTA branch is created from `main`, never from the remote research branch.

## Classification method

The complete `main...origin/research/asm-codex` history and path delta were inspected, then commits touching the legacy JaCoCo listener, lifecycle extension, exec-to-XML conversion, XML mapper, Maven report generation, identity and selector paths were reviewed individually. The branch contains a large ASM/schema-v2/v3/executable-inventory architecture; a commit is not considered independently portable merely because it also touches a legacy JaCoCo file.

## Relevant change inventory

| Source commit/change | Classification | Files/concepts | Finding and decision |
|---|---|---|---|
| `b7ab3b8` on `main` (`fix/unique-session-id`) | `JACOCO_CORRECTNESS_FIX` | `JacocoPerTestListener`, `SessionFileNames`, mapper/tests | FQN-derived session suffix and reversible filename escaping prevent same-simple-class/case-insensitive filename collisions. Already present in the starting `main`; do not duplicate. The FineRTS collection evidence found no collisions. |
| RAD1/main append behavior (`70b3984`, inherited by `main`) | `JACOCO_CORRECTNESS_FIX` | listener and Jupiter extension session writes | Multiple invocations of a logical method append JaCoCo blocks and `ExecFileLoader` unions probes. Already present; Phase 5A confirmed invocation-to-method aggregation. Do not duplicate. |
| `92d8ad8` | `JACOCO_ROBUSTNESS_FIX` / research diagnostic | listener dump/reset tracing and method-entry-gap evidence | Confirms early-exception/probe limitations but its production hunk is diagnostic tracing, not a correction. Do not port. |
| `baa3600` | `MIXED_JACOCO_ASM` | agent/runtime preparation plus listener diagnostics | Primarily ASM productionization; legacy JaCoCo change is research tracing. Not independently useful. Reject wholesale and all hunks. |
| `8b1130f` | `MIXED_JACOCO_ASM` | `ExecToXmlEngine` `.status` files, descriptor-aware mapper, Maven schema-v2 fragments | Adds useful integrity status (`COVERED`/`EMPTY`/`FAILED`) but only as input to a new schema-v2 inventory/fragment pipeline. It does not preserve an empty legacy-map entry and imports new descriptor/schema models. Not independently portable under the frozen legacy schema. Reject; JZC-01 solves the demonstrated loss directly. |
| `9f29f0b` | `MIXED_JACOCO_ASM` | `JacocoExecutionDataSource`, listener/extension, Maven executable mapping | Captures JaCoCo bytes directly and independently of a project's `destfile`. It replaces the dump/file boundary and participates in executable assignment/evidence semantics. This is an architectural runtime/ownership change, not a small correction demonstrated by the FineRTS subjects. Reject. |
| `fb0692f`, `8166cdd`, `d9834b0` | `MIXED_JACOCO_ASM` | aborted/non-executed accounting and executable fragments | Correctness for the new inventory/evidence model, but requires schema-v2/v3 execution status. The current map contains executed logical tests only and the reported zero-coverage blocker concerns successfully executed sessions. Reject as architectural inventory changes. |
| `147c748` | `MIXED_JACOCO_ASM` | failed coverage/setup ownership | Introduces setup-container and execution-outcome ownership contracts from the new runtime model. Proper lifecycle attribution is architectural, not a small listener patch. Reject. |
| `39249f1` | `MIXED_JACOCO_ASM` | Vintage `ClassSource` execution evidence and identity sidecars | Supports custom Vintage runners through the new authoritative inventory/evidence path. Phase 3/5 evidence did not demonstrate a missing `ClassSource` test in the surviving subjects. Requires sidecar/inventory concepts, so reject for this closed correction set. |
| `48355cb`, `112d801`, `b080035`, `343fe6e` | `JACOCO_FEATURE` / `MIXED_JACOCO_ASM` | descriptor-aware schema-v2 and selector | Public schema/selector redesign, not a correction to the existing RAD1 map. Explicitly forbidden for this task. |
| `c0d5120`, `2741da4`, `edaf7ca` and later executable-inventory commits | `JACOCO_FEATURE` / `MIXED_JACOCO_ASM` | test inventory, revision binding, executable identities | These could represent zero-coverage tests but are a separate inventory/schema architecture. They are not needed because the existing schema accepts empty entries. Reject. |
| `2828a15` and collector-backend commits | `ASM_ONLY` / `JACOCO_FEATURE` | collector switching/backends | Introduces JaCoCo/ASM backend selection and hybrid product behavior. Forbidden. |
| `b55fd3a` through ASM agent/runtime/context work | `ASM_ONLY` | `stp-agent`, `stp-runtime`, bytecode transformers, propagation | ASM dependency collector and runtime. Forbidden in full. |
| Jenkins, storage, orchestration, sharding and productization commits | `UNRELATED` | CI/storage/multi-agent modules and evidence | Not collection correctness for the legacy JaCoCo path. Reject. |
| Documentation, research evidence and large fixture commits | `UNRELATED` | research/validation trees | No implementation port. |

## Area conclusions

### Test identity

`main` already distinguishes fully qualified declaring classes in its hash and reversibly sanitizes method-name case. Nested classes are included in `fullClassName`. No demonstrated legacy collision remains. Descriptor-aware executable identity on the research branch belongs to schema v2/v3 and is rejected.

### Parameterized, repeated and template tests

`main` intentionally uses one logical method selection unit. Per-invocation files are appended and JaCoCo OR-merges probes. No research-branch correction can be separated from the later executable-evidence model. Existing behavior is retained.

### Coverage-session attribution

The branch's direct in-memory snapshot source and identity/status sidecars are entangled with executable inventory, assignment and schema-v2/v3 integrity. They are not ported. The established `main` listener/dump/session architecture remains authoritative.

### Lifecycle attribution

Setup containers, class construction and before/after lifecycle ownership are `ARCHITECTURAL_CHANGE`. No `SMALL_CORRECTNESS_FIX` was found that is both independently portable and demonstrated by the FineRTS evidence. They remain explicitly out of scope.

### Zero-production-coverage preservation

This is the one demonstrated correctness defect not fixed on either legacy `main` path. The existing map can carry empty `classes` and `methods`, so it requires no inventory or schema redesign.

# Approved ISSTA JaCoCo correction set

## JZC-01 — Preserve and conservatively select zero-production-coverage tests

```text
ID: JZC-01
Problem: Executed logical sessions with no covered production line are dropped before map generation.
Source branch/commit: New correction derived from Phase 5C evidence; not copied from ASM code.
Files/concepts: ExecToXmlEngine empty XML output; existing CoverageMapperJaxb empty collections;
                TestSelector explicit NO_COVERAGE selection and result reason metadata; tests/reporting.
Reason for inclusion: Demonstrated on 22 Codec and 2 Asterisk logical units.
Expected behavioral change: Every executed logical session reaches the map. Empty-coverage entries are always
                            included in reduced execution with reason NO_COVERAGE.
Expected effect on existing RAD1 results: Regenerated maps gain previously absent tests and reduced selections
                                         may grow. Historical RAD1 artifacts remain historical and unchanged.
```

The implementation must preserve the legacy map shape. A zero-coverage session is represented as an ordinary test mapping with empty `classes` and `methods`. A new-test/unmapped test remains a separate concept.

# Explicitly rejected changes

- Every ASM transformer, agent, runtime hook, context propagation path and ASM-derived dependency edge.
- Collector switching, hybrid JaCoCo+ASM operation and ASM fallback.
- Schema-v2/v3, descriptor/executable identity, fragment, inventory, sharding and join systems.
- Direct JaCoCo byte-source/identity-sidecar rewrite (`9f29f0b`) because it is coupled to executable ownership and was not required by the observed defect.
- Lifecycle/setup attribution redesign (`147c748` and related commits).
- Vintage `ClassSource` inventory/evidence support (`39249f1`) because it depends on the rejected inventory architecture and was not demonstrated as missing in the candidate corpus.
- Aborted/skipped/non-executed inventory accounting.
- Jenkins, storage, CI orchestration, Maven reactor productization and unrelated features.
- Performance optimizations and refactorings not required by JZC-01.
- All research evidence, fixtures and documentation from the ASM branch.

This correction set is closed before implementation. Any later issue requires a separate explicit review and must not be folded into this branch in response to benchmark results.
