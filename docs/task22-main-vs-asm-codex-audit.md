# Task 22: latest main versus ASM Codex integration audit

## Repository state

Audit date: 2026-09-06. `git fetch origin` completed before resolving the references.

```text
origin/main:        c88c2ca380a2bfa380d7504887f5caa2ee4134b7
research/asm-codex: 79482880cdfde1de4b7d5d76fb729606f5dd860d
merge base:         b7ab3b85ec69cef89c02b0b0af58f757c8f65ca7
```

The three-dot branch delta contains 660 paths, 587,982 insertions and 2 deletions. It is dominated by retained research evidence. No rebase, merge, history rewrite, module deletion, package move, or production cleanup was performed.

## Main since divergence

`merge-base..origin/main` contains four commits: two substantive commits and their merge commits.

| Commit | Title | Purpose/type | Files/modules | ASM relevance | Overlap | Potential conflict |
|---|---|---|---|---|---|---|
| `0039cd7` | feat: define coverage map schema v1 and fragment contract | Feature: versioned fragment/map models, lifecycle/completeness, codecs, validation, docs and tests | common, CLI, Maven, docs | YES | YES, semantic | YES, manual schema integration |
| `731c8eb` | Merge PR #29 | Merge carrying `0039cd7`; no additional logical patch | same | YES | YES | YES |
| `dacbc9b` | fix: support legal JVM method names in TestIdentity | Bug fix: correct JVM method-name validation | common, docs, tests | YES | YES, semantic | YES, identity translation review |
| `c88c2ca` | Merge PR #31 | Merge carrying `dacbc9b`; no additional logical patch | same | YES | YES | YES |

There are not three or four distinct bug-fix patches in the fetched range: there are four commits but only two substantive changes, one feature and one bug fix. Both matter. The schema commit directly affects serialization/schema, shared code, CLI/Maven dependencies and tests; it indirectly defines what agent/runtime output must become. The method-name fix is essential because ASM observes legal JVM names and descriptors. There were no main-range edits to agent/runtime (those modules do not exist on main), Gradle plugin source, or selector source.

Detailed commit evidence is in `stp-spring-core-spike/task22/evidence/main-since-branch-point.json`.

## Overlap analysis

The exact file intersection between `merge-base..origin/main` and `merge-base..research/asm-codex` is empty. This means a textual merge may look easy, but it does not remove the architectural conflict:

- Main's schema v1 is a public `CoverageFragment`/`CoverageMap` contract with validation and lifecycle/completeness semantics.
- The branch writes an experimental `agent-shell-1` envelope containing method catalog/hits and nested runtime JSON. Its test identity is JUnit Platform unique-ID based; main's contract identity is class name plus method name.
- The two identity changes are compatible at the JVM-name level, but there is no implemented, reviewed mapping for parameterized/dynamic/overloaded cases.

Classification: schema integration is `CONFLICT_REQUIRES_MANUAL_REVIEW`; method-name handling is `BOTH_CHANGED_COMPATIBLY`. There is no `MAIN_FIX_ONLY` code path to transplant into the branch because the eventual integration must start from main and retain both main patches as-is.

## Complete file and module inventory

The ASM-side delta is entirely additions except `.gitignore`, `README.md`, root `build.gradle`, `settings.gradle`, and one production Java file. No branch-side file deletion exists in the merge-base comparison.

| Area | Change | Audit outcome |
|---|---|---|
| `stp-agent` | New module: transformer, catalog/hash, filtering, output/metrics, executor propagation, diagnostics, tests | Keep production core; exclude round-specific diagnostics |
| `stp-runtime` | New module: contexts, registry, hooks/wrappers, aggregation, serializer, generic and Spring-oriented events | Keep minimal ASM context/runtime; Spring and round-specific portions research-only |
| `stp-junit-adapter` | New module: lifecycle listener plus Round 11/12 diagnostics | Listener required; separate adapter artifact unnecessary; diagnostics research-only |
| `stp-spring-data-adapter` | New narrow Spring Data advisor/metadata adapter | Research-only |
| Spring Data E2E/observability modules | New disposable fixtures | Research-only |
| PetClinic spike | New rounds, scripts, raw/normalized evidence | Research-only |
| Spring Core spike | New rounds 9-17, tasks 19-21, analyzers, oracle and evidence | Research-only |
| `research/spring-reflection-order-reproducer` | New standalone fixture | Research-only/archive |
| root `settings.gradle` | Adds all candidate and experimental modules; changes root project name | REVIEW; reconstruct manually with production modules only |
| root `build.gradle` | Adds Spring Data experiment aggregate task | Research-only |
| `.gitignore` | Adds research output rules | Research-only repository hygiene |
| `README.md` | Adds experimental Spring Data section | Research-only |
| `smart-test-picker-core/JacocoPerTestListener` | Adds `stp.jacoco.dump.events` trace | Obsolete research leak; do not port |
| existing Gradle plugin | No ASM-side code/build change | Missing integration work, not a branch delta |
| existing Maven plugin | No ASM-side change | Missing integration work; inherit main schema dependency changes |
| existing CLI | No ASM-side change | Missing integration work; inherit main schema dependency changes |
| existing common/schema/selector | No ASM-side change | Main wins as baseline |
| publishing/release | New modules explicitly have no publishing; agent is experimental shadow jar | Needs deliberate promotion; runtime/listener should stay bundled |
| documentation | 60 top-level `docs` additions plus research corpora | Keep a compact canonical summary; research history stays off production integration |

The machine-readable classification groups all 660 paths by coherent path/component family in `asm-delta-classification.json`; each group has exactly one primary classification.

## ASM delta classification

### KEEP_PRODUCTION

- Agent method-entry transformer, deterministic descriptor-preserving method keys/FNV IDs, collision handling, filtering, output/metrics, and ordinary tests.
- Supported thread, executor, scheduled-executor, ForkJoinPool and selected CompletableFuture context propagation and tests.
- Minimal framework-neutral runtime context, registry, aggregation, late-event quarantine, hooks/wrappers and method/test/unattributed facts.
- `StpRuntimeTestExecutionListener`, service metadata and ordinary lifecycle tests. Keep the behavior, not necessarily the module boundary.

### DELETE_OBSOLETE

- The ASM branch's `stp.jacoco.dump.events` instrumentation in `JacocoPerTestListener`. It exists only to prove task19 dump/reset ordering and main already correctly lacks it.

No deletion was executed because this audit is intended to precede cleanup.

### RESEARCH_ONLY

- Round 10-15 recorders/transformers/order/state/timeline diagnostics and matching runtime sink APIs.
- Spring-specific runtime event types and the complete Spring Data adapter/fixtures.
- PetClinic and Spring Core spike trees, task19-21 oracle/JaCoCo tooling, standalone reproducer, analyzers and evidence.
- Root experiment task, experimental README section and research-output ignore rules.

### MAIN_ALREADY_REPLACES

None of the ASM-side file deltas has an equivalent main-side replacement. Main does, however, remain authoritative for schema v1 and the fixed `TestIdentity`; those are baseline changes, not replacements for an ASM delta.

### REVIEW

- The mixed `settings.gradle` hunk.
- The exact ASM-observation-to-schema-v1 projection and test identity normalization.
- Eventual `smart-test-picker-common` versus `smart-test-picker-core` consolidation.
- Duration of JaCoCo compatibility and removal plan.
- Whether any bounded tracing/timeline concept deserves a newly designed production diagnostic API.
- Whether `asm-tree` is used after research diagnostics are removed.

## Old adapters

Four adapter/legacy integration families were evaluated.

1. `stp-junit-adapter` solves a real production problem: JUnit leaf lifecycle owns ASM hits. Deleting its listener without replacement makes hits unattributed. The listener is still required; a separately published adapter is not. Bundle or co-locate it with the agent/runtime integration and remove Round 11/12 research classes.
2. `stp-spring-data-adapter` solves optional, synchronous repository semantic enrichment for a narrow Spring 7/Data 4.1 topology. ASM method coverage does not require it. Removing it breaks only the optional Spring Data events/experiments. Keep it research-only.
3. Existing common mapper/JAXB/JaCoCo packages solve the current main JaCoCo XML/exec pipeline. They are not ASM deltas. They become architecturally obsolete only after Gradle, Maven and CLI ASM migration is complete; deleting them now would break main.
4. Existing core JaCoCo listener/extension solves current per-test collection. ASM replaces that collection role eventually, but the build tools are not wired to ASM yet. Keep main unchanged for integration, omit the branch's debug hunk, and migrate/remove legacy collection separately with end-to-end coverage.

Therefore old adapters can be removed only **partially** now: Spring Data and research diagnostic code should not enter production; the JUnit listener behavior must survive; main's JaCoCo paths require a later migration.

## Target production architecture

The six requested capabilities are valid, with one current-repository naming caveat:

| Capability | Responsibility | Release | Runtime | Build-tool-specific | Status |
|---|---|---|---|---|---|
| core/shared | Schema v1, codecs/validation, map resolution and selector/shared logic | Published internal dependency | No | No | Production; currently split across common/core |
| agent | ASM transforms, catalog/filtering, propagation and fragment output | User-facing javaagent | Yes | No | Production candidate |
| runtime | Minimal hooks/context/aggregation/late quarantine | Bundled, not separately published | Yes | No | Production internal |
| Gradle plugin | Attach/configure agent, collect fragments, select tests | User-facing | No | Yes | Existing, integration missing |
| Maven plugin | Attach/configure agent, collect fragments, select tests | User-facing | No | Yes | Existing, integration missing |
| CLI | Validate/merge/select/inspect | User-facing | No | No | Existing, integration missing |

The JUnit listener is also required but should be bundled inside the agent, not treated as a seventh user-visible artifact. The repository proves no requirement for Spring Data or a mapping-adapter chain in the final architecture.

## Release artifacts

- User-facing: Gradle plugin, Maven plugin, CLI, and one self-contained STP javaagent.
- Published internal dependencies: current shared/core libraries while migration is in progress.
- Bundled: `stp-runtime`, JUnit listener/service metadata, and relocated ASM inside the agent.
- Not released: Spring Data adapter, E2E/observability fixtures and all research modules.

The current shadow configuration already embeds runtime and selected listener output. That is the recommended simplification. Publishing runtime separately would introduce classpath/version alignment risk and has no demonstrated external consumer. Bootstrap instrumentation could change this conclusion, but the current architecture explicitly excludes bootstrap/framework classes.

## Research artifact disposition

- `KEEP_IN_RESEARCH_BRANCH`: PetClinic rounds, Spring Core rounds 9-17, tasks 19-21, oracle tooling, raw-artifact manifests, Spring Data experiments and the reproducer.
- `KEEP_AS_DOCUMENTATION`: compact summary, current-state, JaCoCo comparison, baseline status and closure audit.
- `ARCHIVE`: chronological round/spike/task reports and compact evidence when the research branch is retired.
- `REMOVE_BEFORE_PRODUCTION_INTEGRATION`: all bulk evidence and reproduction trees from the production diff. This means omit them from the integration branch, not delete them from research history.

## Accidental and experimental production changes

The concrete leaks are the JaCoCo dump-boundary hook, direct Round 13-15 registration in agent startup/runtime, Round 11/12 JUnit classes, PetClinic/no-op defaults, experimental artifact naming, generic runtime pollution by Spring event models, and root build/settings/README exposure of Spring experiments. Diagnostics are disabled by default, but disabled experimental code is still not a production requirement.

No ASM-delta hard-coded developer path was found. The existing CLI `/tmp/jacoco-exec/` default predates the branch. `asm-tree` is a possible unused dependency after diagnostic removal and remains `REVIEW`, not a deletion recommendation.

## Main fix inheritance plan

Both substantive main changes are `TAKE_MAIN_AS_IS`, including their merge commits, tests and documentation. The integration branch should start at `origin/main`, so no cherry-pick is necessary. Neither change is `ALREADY_PRESENT_EQUIVALENTLY` in the branch. The work to project ASM events into schema v1 is new integration work, not a merge resolution.

## Proposed final integration delta

### KEEP / ADD TO MAIN

- Minimal production portions of `stp-agent` and `stp-runtime` listed above.
- Bundled JUnit lifecycle listener and service declaration.
- Clean module includes for agent/runtime/listener source location.
- Gradle and Maven agent attachment, configuration, fragment collection/merge and schema-v1 ingestion.
- CLI support appropriate to ASM fragments while retaining main contract compatibility.
- Main's schema v1, legal JVM-name fix and all associated tests unchanged.

### DELETE / OMIT BEFORE INTEGRATION

- JaCoCo dump diagnostic hunk; Round 10-15 diagnostic classes/wiring/runtime sinks; Round 11/12 experiment classes.
- PetClinic-specific defaults, no-op-by-default release behavior, experimental artifact names.
- Spring Data root build task and README section.

### RESEARCH ONLY

- All Spring Data modules, PetClinic/Spring Core research trees, task19-21, reproducer, detailed research docs and bulk evidence.

### MANUAL REVIEW

- Schema/identity projection, common/core module boundary, temporary JaCoCo coexistence, listener source location, optional diagnostic API and `asm-tree` necessity.

## Dependency graph sanity check

Current candidate graph:

```text
Gradle plugin -> common + core
Maven plugin  -> common
CLI           -> common
agent         -> runtime + ASM + compileOnly junit-adapter
junit-adapter -> runtime + JUnit Platform
Spring adapter -> runtime
Spring fixture -> Spring adapter -> runtime (+ agent + junit-adapter)
```

There is no dependency cycle. There is one unnecessary indirection: the agent compiles against `stp-junit-adapter` and then manually copies its classes/resources while runtime is independently shaded. Target graph:

```text
Gradle plugin -> shared schema/selector; configures self-contained agent
Maven plugin  -> shared schema/selector; configures self-contained agent
CLI           -> shared schema/selector
agent         -> bundled minimal runtime + bundled JUnit listener + relocated ASM
runtime       -> JDK only
```

## Readiness and answers

1. Main added schema v1/fragment lifecycle and a legal JVM method-name fix (four commits including merges).
2. Both substantive changes matter and should be inherited unchanged.
3. Production-worthy ASM work is the core agent, minimal logical runtime, propagation and JUnit lifecycle listener/tests.
4. Research-only work is explicitly enumerated above and in evidence JSON.
5. The only unambiguous modified-production obsolete hunk is JaCoCo dump tracing; round-specific code is obsolete for production but retained as research.
6. Spring Data and research diagnostic adapters can be omitted; legacy main JaCoCo adapters cannot yet be deleted.
7. JUnit lifecycle adaptation has a real purpose, though it need not remain a separate artifact.
8. The target architecture is clear at capability level; common/core consolidation remains a naming/module review.
9. Intended releases are Gradle, Maven, CLI and agent, with shared/core internal dependencies and bundled runtime/listener.
10. The eventual integration branch should contain only the proposed production delta on top of `c88c2ca` or its then-current successor.
11. Manual-review items remain, chiefly schema/identity projection and migration coexistence.
12. The branch is ready to begin a selective integration/hardening implementation, but not ready for broad deletion or direct merge.

## Final decision

```text
latest main can be used as integration base: YES
old adapters can be removed: PARTIAL
target architecture is clear: YES (capabilities), with module-boundary reviews
cleanup can start: NO for broad cleanup; YES only for omitting explicitly classified research/obsolete code from a new integration branch
integration-ready after cleanup: NO; schema/identity projection and build-tool wiring must first be implemented and tested
```

Machine-readable evidence is under `stp-spring-core-spike/task22/evidence/`.
